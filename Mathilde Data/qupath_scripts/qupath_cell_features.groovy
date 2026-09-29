/**
 * Per-cell measurements for classification.
 *
 * ALWAYS adds the notebook's 12 features, under the same names, so QuPath's measurement
 * table and the notebook's DataFrame stay comparable column by column:
 *   area_um2, equiv_diameter_um, eccentricity, solidity, extent,
 *   mean_r, mean_g, mean_b, std_intensity, mean_hue, mean_sat, nucleus_fraction
 *
 * FOR CELL OBJECTS THAT CARRY A REAL NUCLEUS (made by qupath_cells_with_nuclei.groovy)
 * it also adds the features that actually separate BAL cell types:
 *   nucleus_area_um2, nucleus_diameter_um, nc_ratio, nucleus_solidity,
 *   nucleus_circularity, nucleus_eccentricity        <- lobed nuclei score low on the last two
 *   cyto_area_um2, cyto_mean_r/g/b, cyto_r_minus_g, cyto_std_intensity
 *                                                    <- cyto_r_minus_g is the eosin/granule signal,
 *                                                       measured on cytoplasm only, so chromatin
 *                                                       colour no longer contaminates it
 * nc_ratio is a true measured nucleus:cell area ratio, unlike nucleus_fraction, which is only
 * a brightness proxy computed within one outline.
 *
 * WHICH OBJECTS ARE MEASURED
 *   - Select region objects and their descendant cells are measured.
 *   - Otherwise every "leaf" object below MAX_CELL_AREA_UM2 is measured, which picks up
 *     cells while skipping the regions containing them.
 *
 * COST: one small pixel read per cell, roughly 1-2 ms. Fine for thousands; for a whole-slide
 * run with 100k+ cells prefer computing features in the notebook.
 */

import qupath.lib.regions.RegionRequest
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathCellObject
import java.awt.Color

// Overridable by the GUI via BAL_* variables; defaults apply when run standalone.
double PIXEL_SIZE_UM     = binding.hasVariable('BAL_PIXEL_SIZE_UM')     ? BAL_PIXEL_SIZE_UM as double     : 0.137
double MAX_CELL_AREA_UM2 = binding.hasVariable('BAL_MAX_CELL_AREA_UM2') ? BAL_MAX_CELL_AREA_UM2 as double : 5000
// Objects bigger than this are classified "Clump/debris" so they can be left out of counts.
// The largest genuine cell measured on this slide was 263 um2. Set to 0 to switch the check off.
double CLUMP_AREA_UM2    = binding.hasVariable('BAL_CLUMP_AREA_UM2')    ? BAL_CLUMP_AREA_UM2 as double    : 0

// Which outline the 12 notebook features describe:
//   'CELL'    - the outer boundary (right when the nucleus came from InstanSeg pairing)
//   'NUCLEUS' - the inner boundary
//   'AUTO'    - nucleus for cell objects, outline for everything else. This suits
//               cellExpansion output, where the inner boundary IS the raw Cellpose mask.
def MEASURE_ROI = binding.hasVariable('BAL_MEASURE_ROI') ? BAL_MEASURE_ROI.toString().toUpperCase() : 'AUTO'

def imageData = getCurrentImageData()
def server = imageData.getServer()

// --- work out which objects are cells ---------------------------------------
def leaves = { PathObject root ->
    def out = []
    def stack = [root]
    while (!stack.isEmpty()) {
        def o = stack.pop()
        if (o.hasChildObjects()) stack.addAll(o.getChildObjects())
        else if (o.getROI() != null && o.getROI().isArea()) out << o
    }
    out
}

def cells = []
def selected = getSelectedObjects().findAll { it.hasChildObjects() }
if (!selected.isEmpty()) {
    selected.each { cells.addAll(leaves(it)) }
} else {
    def all = []
    all.addAll(getAnnotationObjects())
    all.addAll(getDetectionObjects())
    cells = all.findAll {
        !it.hasChildObjects() && it.getROI() != null && it.getROI().isArea() &&
                it.getROI().getArea() * PIXEL_SIZE_UM * PIXEL_SIZE_UM < MAX_CELL_AREA_UM2
    }
}

if (cells.isEmpty()) {
    Dialogs.showErrorMessage("Cell features", "No cell objects found. Run a detection script first.")
    return
}
println "Measuring ${cells.size()} cells..."

// --- Otsu threshold on a 256-bin histogram of within-cell gray values --------
def otsu = { int[] hist, int total ->
    double sum = 0
    for (int i = 0; i < 256; i++) sum += i * hist[i]
    double sumB = 0, wB = 0, best = -1
    int bestT = 0
    for (int t = 0; t < 256; t++) {
        wB += hist[t]
        if (wB == 0) continue
        double wF = total - wB
        if (wF == 0) break
        sumB += t * hist[t]
        double between = wB * wF * Math.pow(sumB / wB - (sum - sumB) / wF, 2)
        if (between > best) { best = between; bestT = t }
    }
    bestT
}

// Running totals for one region of pixels. Storing sums (not coordinate lists) keeps
// memory flat and still gives the second moments needed for eccentricity.
class Accum {
    int n = 0
    double r = 0, g = 0, b = 0, hue = 0, sat = 0, gray = 0, gray2 = 0
    double x = 0, y = 0, xx = 0, yy = 0, xy = 0
    void add(int red, int grn, int blu, double gr, float[] hsb, int px, int py) {
        n++
        r += red; g += grn; b += blu
        hue += hsb[0]; sat += hsb[1]
        gray += gr; gray2 += gr * gr
        x += px; y += py; xx += (double) px * px; yy += (double) py * py; xy += (double) px * py
    }
    // Eccentricity from the inertia tensor, as skimage's regionprops defines it.
    double eccentricity() {
        if (n == 0) return 0
        double cx = x / n, cy = y / n
        double mu20 = xx / n - cx * cx, mu02 = yy / n - cy * cy, mu11 = xy / n - cx * cy
        double common = Math.sqrt(Math.pow(mu20 - mu02, 2) + 4 * mu11 * mu11)
        double l1 = (mu20 + mu02 + common) / 2.0, l2 = (mu20 + mu02 - common) / 2.0
        l1 <= 0 ? 0.0 : Math.sqrt(Math.max(0.0, 1.0 - l2 / l1))
    }
    double stdGray() {
        if (n == 0) return 0
        double m = gray / n
        Math.sqrt(Math.max(0.0, gray2 / n - m * m))
    }
}

int done = 0, nClumps = 0, nWithNucleus = 0
long t0 = System.currentTimeMillis()

for (cell in cells) {
    def outerRoi = cell.getROI()
    def nucRoi = (cell instanceof PathCellObject) ? cell.getNucleusROI() : null
    def primary = (MEASURE_ROI == 'NUCLEUS' && nucRoi != null) ? nucRoi
                : (MEASURE_ROI == 'AUTO' && nucRoi != null)    ? nucRoi
                : outerRoi

    // Read once over the outer bounding box; it contains the nucleus too.
    def request = RegionRequest.createInstance(server.getPath(), 1.0, outerRoi)
    def img = server.readRegion(request)
    int bx = request.getX(), by = request.getY()
    int w = img.getWidth(), h = img.getHeight()

    def accPrimary = new Accum(), accNuc = new Accum(), accCyto = new Accum()
    int[] hist = new int[256]
    float[] hsb = new float[3]

    for (int py = 0; py < h; py++) {
        for (int px = 0; px < w; px++) {
            double gx = bx + px + 0.5, gy = by + py + 0.5
            boolean inOuter = outerRoi.contains(gx, gy)
            boolean inNuc = nucRoi != null && nucRoi.contains(gx, gy)
            boolean inPrimary = (primary.is(outerRoi)) ? inOuter : inNuc
            if (!inOuter && !inNuc) continue

            int rgb = img.getRGB(px, py)
            int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff
            double gray = (0.2125 * r + 0.7154 * g + 0.0721 * b) / 255.0   // skimage rgb2gray
            Color.RGBtoHSB(r, g, b, hsb)

            if (inPrimary) {
                accPrimary.add(r, g, b, gray, hsb, px, py)
                hist[(int) Math.min(255, Math.round(gray * 255))]++
            }
            if (nucRoi != null) {
                if (inNuc) accNuc.add(r, g, b, gray, hsb, px, py)
                else if (inOuter) accCyto.add(r, g, b, gray, hsb, px, py)
            }
        }
    }
    if (accPrimary.n == 0) continue
    int n = accPrimary.n

    // Nucleus fraction: Otsu within this outline, then the dark (nucleus-like) share.
    int thresh = otsu(hist, n)
    int dark = 0
    for (int i = 0; i < thresh; i++) dark += hist[i]

    double areaUm2 = n * PIXEL_SIZE_UM * PIXEL_SIZE_UM
    def ml = cell.getMeasurementList()
    ml.put("area_um2", areaUm2)
    ml.put("equiv_diameter_um", Math.sqrt(4.0 * n / Math.PI) * PIXEL_SIZE_UM)
    ml.put("eccentricity", accPrimary.eccentricity())
    ml.put("solidity", primary.getSolidity())
    ml.put("extent", n / (double) (w * h))
    ml.put("mean_r", accPrimary.r / n)
    ml.put("mean_g", accPrimary.g / n)
    ml.put("mean_b", accPrimary.b / n)
    ml.put("std_intensity", accPrimary.stdGray())
    ml.put("mean_hue", accPrimary.hue / n)
    ml.put("mean_sat", accPrimary.sat / n)
    ml.put("nucleus_fraction", dark / (double) n)

    // --- features that need a real nucleus ----------------------------------
    if (nucRoi != null && accNuc.n > 0) {
        nWithNucleus++
        double nucArea = accNuc.n * PIXEL_SIZE_UM * PIXEL_SIZE_UM
        double cellArea = (accNuc.n + accCyto.n) * PIXEL_SIZE_UM * PIXEL_SIZE_UM
        double perim = nucRoi.getLength() * PIXEL_SIZE_UM        // ROI perimeter, in um
        ml.put("nucleus_area_um2", nucArea)
        ml.put("nucleus_diameter_um", Math.sqrt(4.0 * accNuc.n / Math.PI) * PIXEL_SIZE_UM)
        ml.put("nc_ratio", cellArea > 0 ? nucArea / cellArea : Double.NaN)
        ml.put("nucleus_solidity", nucRoi.getSolidity())
        // 4*pi*A/P^2: 1.0 is a circle, lobed neutrophil nuclei fall well below.
        ml.put("nucleus_circularity", perim > 0 ? 4 * Math.PI * nucArea / (perim * perim) : Double.NaN)
        ml.put("nucleus_eccentricity", accNuc.eccentricity())
        if (accCyto.n > 0) {
            ml.put("cyto_area_um2", accCyto.n * PIXEL_SIZE_UM * PIXEL_SIZE_UM)
            ml.put("cyto_mean_r", accCyto.r / accCyto.n)
            ml.put("cyto_mean_g", accCyto.g / accCyto.n)
            ml.put("cyto_mean_b", accCyto.b / accCyto.n)
            ml.put("cyto_r_minus_g", (accCyto.r - accCyto.g) / accCyto.n)   // eosin / granules
            ml.put("cyto_std_intensity", accCyto.stdGray())
        }
    }
    ml.close()

    // QC: mark oversized objects so they can be kept out of differential counts.
    if (CLUMP_AREA_UM2 > 0 && areaUm2 > CLUMP_AREA_UM2) {
        cell.setPathClass(getPathClass("Clump/debris"))
        nClumps++
    }

    if (++done % 250 == 0) println "  ${done}/${cells.size()}"
}

fireHierarchyUpdate()
println "Done: measured ${done} cells in ${(System.currentTimeMillis() - t0) / 1000.0}s"
if (nWithNucleus > 0)
    println "  ${nWithNucleus} had a paired nucleus -> nc_ratio, nucleus shape and cytoplasm colour added"
if (CLUMP_AREA_UM2 > 0)
    println "Flagged ${nClumps} object(s) as 'Clump/debris' (area > ${CLUMP_AREA_UM2} um2)"
println ""
println "NEXT: label ~40-60 cells per class, then"
println "  Classify > Object classification > Train object classifier"
