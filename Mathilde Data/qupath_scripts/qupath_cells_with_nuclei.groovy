/**
 * Whole cells from Cellpose + nuclei from InstanSeg, paired into QuPath cell objects.
 *
 * WHY: classification needs more than an outline. Pairing the two gives
 *   - a true nucleus:cytoplasm ratio (measured areas, not a brightness proxy)
 *     -> the main lymphocyte vs macrophage discriminator
 *   - nuclear shape on its own (circularity, solidity)
 *     -> lobed neutrophil and bilobed eosinophil nuclei score low
 *   - cytoplasm colour with the nucleus excluded
 *     -> granule/eosin signal no longer contaminated by chromatin colour
 *
 * HOW: run cyto3 for cell outlines, run InstanSeg for nuclei, then match each nucleus to
 * the cell that contains its centroid. Cells keep their outline either way; a cell with no
 * nucleus found stays a plain detection and is counted in the summary below.
 *
 * Takes roughly 15 s for a 1024 px region: ~8 s Cellpose (GPU) + ~2 s InstanSeg (CPU) + pairing.
 *
 * Run with a region selected, or through the GUI ("cyto3 cells + InstanSeg nuclei").
 */

import qupath.lib.objects.PathObjects
import qupath.lib.roi.RoiTools
import qupath.lib.objects.PathObject

// Which cell model to use for the outer boundary.
def CELL_MODEL   = binding.hasVariable('BAL_MODEL')    ? BAL_MODEL.toString()  : 'cyto3'
double DIAMETER  = binding.hasVariable('BAL_DIAMETER') ? BAL_DIAMETER as double : 58
// A nucleus is clipped to its cell, so nc_ratio can never exceed 1. Only a match that ends up
// smaller than this share of the cell is thrown away as degenerate.
// NOTE: do NOT reject nuclei for being large. BAL lymphocytes genuinely have nuclei filling
// 85-95% of the cell -- an earlier 95% cut-off was discarding exactly the cells we care about.
double MIN_NUC_FRACTION = binding.hasVariable('BAL_MIN_NUC_FRACTION') ? BAL_MIN_NUC_FRACTION as double : 0.02

def scriptDir = null
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file")
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
if (scriptDir == null || !new File(scriptDir, "qupath_cellpose_detection.groovy").exists())
    scriptDir = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")
if (!new File(scriptDir, "qupath_cellpose_detection.groovy").exists()) {
    Dialogs.showErrorMessage("Cells + nuclei", "Can't find the sibling BAL scripts.")
    return
}

def regions = getSelectedObjects().findAll { it.getROI() != null && it.getROI().isArea() }
if (regions.isEmpty()) {
    Dialogs.showErrorMessage("Cells + nuclei", "Select at least one region annotation first.")
    return
}

def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.regions.*
import qupath.lib.measurements.*
"""

// --- 1. cells ----------------------------------------------------------------
println "Step 1/3: cell outlines with ${CELL_MODEL}..."
regions.each { it.setLocked(false); it.clearChildObjects() }
selectObjects(regions)
BAL_MODEL = CELL_MODEL
BAL_DIAMETER = CELL_MODEL == 'cpsam' ? 0 : DIAMETER
BAL_OUTPUT_TYPE = 'DETECTION'
BAL_TARGETS = 'SELECTED'
evaluate(PRE + new File(scriptDir, "qupath_cellpose_detection.groovy").text)
def cellRois = regions.collectMany { r -> r.getChildObjects().collect { it.getROI() } }
println "  ${cellRois.size()} cells"

// --- 2. nuclei ---------------------------------------------------------------
println "Step 2/3: nuclei with InstanSeg..."
regions.each { it.setLocked(false); it.clearChildObjects() }
selectObjects(regions)
// Say which model explicitly rather than inheriting whatever is in the binding -- the
// fluorescence model must never be picked up by accident for this brightfield slide.
BAL_INSTANSEG_MODEL = binding.hasVariable('BAL_NUCLEUS_MODEL') ? BAL_NUCLEUS_MODEL :
        System.getProperty("user.home") + "/QuPath/v0.7/instanseg/downloaded/brightfield_nuclei-0.1.1"
// Nucleus resolution. The model's own default is 0.5 um/px, but measured over 3 regions
// that makes the nucleus outline almost coincide with the cell outline here:
//   0.50 um/px -> 170 cells paired, nc_ratio median 0.86, IQR 0.18  (little to separate)
//   0.25 um/px -> 141 cells paired, nc_ratio median 0.67, IQR 0.48  (2.7x the spread)
// 0.25 costs ~17% of pairings but makes nc_ratio a usable feature, so it is the default.
// Set BAL_NUCLEUS_PIXEL_SIZE = 0 to go back to the model's own resolution.
BAL_INSTANSEG_PIXEL_SIZE = binding.hasVariable('BAL_NUCLEUS_PIXEL_SIZE') ? BAL_NUCLEUS_PIXEL_SIZE as double : 0.25
BAL_OUTPUT_TYPE = 'DETECTION'
BAL_TILE_SIZE = 512                          // InstanSeg works in smaller tiles than Cellpose
evaluate(PRE + new File(scriptDir, "qupath_instanseg_detection.groovy").text)
def nucRois = regions.collectMany { r -> r.getChildObjects().collect { it.getROI() } }
println "  ${nucRois.size()} nuclei"

// --- 3. pair them ------------------------------------------------------------
println "Step 3/3: pairing..."
regions.each { it.setLocked(false); it.clearChildObjects() }

// Index nuclei by centroid so each cell only tests plausible candidates.
def nucByCell = [:].withDefault { [] }
def unmatchedNuclei = 0
for (nuc in nucRois) {
    double cx = nuc.getCentroidX(), cy = nuc.getCentroidY()
    def owner = cellRois.find { it.contains(cx, cy) }
    if (owner == null) unmatchedNuclei++
    else nucByCell[owner] << nuc
}

int paired = 0, noNucleus = 0, multi = 0, rejected = 0
def toAdd = []
for (cellRoi in cellRois) {
    def candidates = nucByCell[cellRoi]
    def nuc = null
    if (candidates.size() == 1) nuc = candidates[0]
    else if (candidates.size() > 1) {
        multi++
        nuc = candidates.max { it.getArea() }      // binucleate or a split nucleus: keep the largest
    }
    // Clip the nucleus to its cell: the two models outline independently, so a nucleus can
    // stick out past the cell boundary. Clipping keeps nc_ratio meaningful (always <= 1).
    if (nuc != null) {
        try {
            def clipped = RoiTools.intersection([cellRoi, nuc])
            if (clipped == null || clipped.isEmpty() || clipped.getArea() / cellRoi.getArea() < MIN_NUC_FRACTION) {
                nuc = null
                rejected++
            } else {
                nuc = clipped
            }
        } catch (Exception e) {
            nuc = null
            rejected++
        }
    }
    if (nuc == null) {
        noNucleus++
        toAdd << PathObjects.createDetectionObject(cellRoi)
    } else {
        paired++
        toAdd << PathObjects.createCellObject(cellRoi, nuc, null, null)
    }
}
addObjects(toAdd)
fireHierarchyUpdate()

println "\n=== cells with nuclei ==="
println "  ${paired} cells paired with a nucleus"
println "  ${noNucleus} cells with no nucleus found (kept as plain detections)"
if (multi > 0)      println "  ${multi} cells held more than one nucleus -- the largest was used"
if (rejected > 0)   println "  ${rejected} nuclei rejected (overlap with their cell too small)"
if (unmatchedNuclei > 0) println "  ${unmatchedNuclei} nuclei fell outside every cell (ignored)"
println "\nNow run qupath_cell_features.groovy with BAL_MEASURE_ROI='CELL' to get nc_ratio,"
println "nuclear shape and cytoplasm-only colour (the GUI does this for you)."
