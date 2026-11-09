/**
 * Exports ONE set of hand-labelled cells for BOTH jobs: segmentation ground truth for
 * Cellpose, and cell-type labels for the vision-transformer classifier.
 *
 * WHY THIS EXISTS
 * The Cellpose extension's own exporter builds its label mask with
 *     useFilter(o -> o.getPathClass() == null)      // Cellpose2D ~line 1459
 * so any cell you classify "Macrophage" is dropped from the mask while its pixels stay in
 * the image -- teaching the model that those cells are background. That forced a choice:
 * label a cell for the classifier, or use it for segmentation, never both.
 *
 * This script writes the masks itself with the opposite filter: every annotation inside a
 * region counts as a cell, whatever its class, and only the Training/Validation rectangles
 * are excluded. So you can label each cell by type, class the rectangle Validation, and
 * still get complete segmentation ground truth -- plus the class labels for the classifier.
 *
 * WHAT IT WRITES (BAL_EXPORT_WHAT = 'BOTH' | 'SEGMENTATION' | 'CLASSIFIER'):
 *   SEGMENTATION -> <corpus>/train|test/<project>__<image>_region_N.tif + _masks.tif
 *                   Instance masks: every cell gets its own label, classified or not.
 *   CLASSIFIER   -> <balClassify>/crops/<ClassName>/<project>__<image>_<id>.png
 *                   One crop per labelled cell, filed by class, plus labels.csv.
 *                   Crops come from QuPath, so this works for ANY slide -- no zarr needed.
 *
 * Run it with the image open. It covers every Training/Validation rectangle in the image;
 * run it once per slide (or let qupath_batch_slides.groovy do that for you).
 */

import qupath.lib.images.servers.LabeledImageServer
import qupath.lib.images.writers.ImageWriterTools
import qupath.lib.regions.RegionRequest
import qupath.lib.common.ColorTools
import qupath.lib.common.GeneralTools
import qupath.lib.objects.PathObject
import qupath.lib.roi.ROIs

def CFG = [:]
try {
    def cfgFile = [new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.groovy"),
                   new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts", "bal_config.groovy")]
            .find { it.exists() }
    if (cfgFile != null) CFG = evaluate(cfgFile) ?: [:]
} catch (Exception ignored) { }

def WHAT = binding.hasVariable('BAL_EXPORT_WHAT') ? BAL_EXPORT_WHAT.toString().toUpperCase() : 'BOTH'
def CORPUS = new File(binding.hasVariable('BAL_GT_DIR') && BAL_GT_DIR?.toString()?.trim() ? BAL_GT_DIR.toString().trim()
        : (CFG.groundTruthDir ?: "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/cellpose_ground_truth"))
def CLASSIFY_DIR = new File(CFG.balClassifyDir ?: "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify")
double PIXEL_SIZE_UM = binding.hasVariable('BAL_PIXEL_SIZE_UM') ? BAL_PIXEL_SIZE_UM as double : (CFG.pixelSizeUm ?: 0.137)
// Crop size around each labelled cell, in pixels at full resolution. 128 px = 17.5 um here,
// enough context for the transformer to see the cell and its neighbours.
int CROP = binding.hasVariable('BAL_CROP_PX') ? BAL_CROP_PX as int : (CFG.cropPx ?: 128)
// How the crop is cleaned up. See bal_crops.groovy: a 128 px window in a crowded smear
// contains the neighbouring cells, and the transformer embeds the whole window.
def CROP_STYLE = binding.hasVariable('BAL_CROP_STYLE') ? BAL_CROP_STYLE.toString()
        : (CFG.cropStyle ?: 'NEIGHBOURS_OUT')

def scriptDir = null
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file")
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
if (scriptDir == null || !new File(scriptDir, "bal_crops.groovy").exists())
    scriptDir = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")
// Classes that are not cell types and must not become classifier training data.
def NON_CELL_CLASSES = ["Training", "Validation", "Clump/debris", "Uncertain"] as Set

def imageData = getCurrentImageData()
def server = imageData.getServer()
def imageName = GeneralTools.stripExtension(server.getMetadata().getName()).replaceAll(/[^A-Za-z0-9._#-]/, "_")
def project = getProject()
def prefix = project != null ?
        qupath.lib.projects.Projects.getBaseDirectory(project)?.getName()?.replaceAll(/[^A-Za-z0-9._-]/, "_") : ""
def tag = prefix ? prefix + "__" + imageName : imageName

def regions = getAnnotationObjects().findAll { it.getPathClass()?.toString() in ["Training", "Validation"] }
if (regions.isEmpty()) {
    Dialogs.showErrorMessage("Export labels",
            "No rectangles classed 'Training' or 'Validation' in this image.\n\n" +
            "Draw one, set its class, and put your labelled cells inside it.")
    return
}

// WHICH TARGET: soma outlines or nuclei. They are two training sets over the same pixels,
// so they get separate masks and separate corpus folders -- point one trainer at both and
// it learns an average of "cell" and "nucleus", which is worse than either.
// Soma ground truth stays at <corpus>/train|test so the hand-corrected data already there
// keeps working; nuclei go in <corpus>/nuclei/train|test.
def TARGET = binding.hasVariable('BAL_EXPORT_TARGET') ? BAL_EXPORT_TARGET.toString().toUpperCase() : 'SOMA'
def NUCLEUS_CLASS = "Nucleus"
def REGION_CLASSES = ["Training", "Validation"]
def CORPUS_ROOT = TARGET == 'NUCLEI' ? new File(CORPUS, "nuclei") : CORPUS

/** Is this annotation part of the target we are exporting? */
def isTargetObject = { PathObject o ->
    def c = o.getPathClass()?.toString()
    if (c in REGION_CLASSES) return false
    // A nucleus annotation sits INSIDE a soma annotation. Letting it into the soma mask
    // would add a second, smaller object on top of every cell and quietly poison the
    // corpus, so each target takes only its own objects.
    TARGET == 'NUCLEI' ? c == NUCLEUS_CLASS : c != NUCLEUS_CLASS
}

// The masks must contain EVERY object of the target, classified or not: a cell you called
// "Macrophage" is still a cell. Only the region rectangles and the other target are left out.
def labelServer = new LabeledImageServer.Builder(imageData)
        .backgroundLabel(0, ColorTools.BLACK)
        .multichannelOutput(false)
        .useInstanceLabels()
        .useFilter({ PathObject o -> isTargetObject(o) })
        .build()

double downsample = PIXEL_SIZE_UM / server.getPixelCalibration().getAveragedPixelSizeMicrons()
if (!(downsample > 0)) downsample = 1.0

int segWritten = 0, cropsWritten = 0, paintedTotal = 0
def classCounts = [:].withDefault { 0 }
def labelRows = []

// The crop maker, built once. Its neighbour list must be EVERY segmented cell, not just
// the labelled ones: a macrophage beside an unlabelled lymphocyte has the same problem.
def cropper = null
if (WHAT in ['BOTH', 'CLASSIFIER'] && TARGET != 'NUCLEI') {
    def cropsLib = evaluate(new File(scriptDir, "bal_crops.groovy"))
    def cropCandidates = getAnnotationObjects().findAll { isTargetObject(it) } + getDetectionObjects()
    cropper = cropsLib.newCropper(server, cropCandidates, CROP, CROP_STYLE)
}

regions.eachWithIndex { region, idx ->
    def roi = region.getROI()
    boolean isValidation = region.getPathClass().toString() == "Validation"
    def request = RegionRequest.createInstance(server.getPath(), downsample, roi)

    // Every annotation of this target inside the rectangle, whatever its cell-type class.
    def cells = getAnnotationObjects().findAll { a ->
        a != region && isTargetObject(a) && roi.getGeometry().intersects(a.getROI().getGeometry())
    }

    // --- segmentation ground truth -------------------------------------------
    if (WHAT in ['BOTH', 'SEGMENTATION']) {
        def dir = new File(CORPUS_ROOT, isValidation ? "test" : "train")
        dir.mkdirs()
        def base = "${tag}_region_${idx}"
        try {
            ImageWriterTools.writeImageRegion(server, request, new File(dir, base + ".tif").getAbsolutePath())
            ImageWriterTools.writeImageRegion(labelServer, request, new File(dir, base + "_masks.tif").getAbsolutePath())
            segWritten++
            println "  ${isValidation ? 'test ' : 'train'}: ${base}.tif  (${cells.size()} cells, " +
                    "${cells.count { it.getPathClass() != null }} of them classified)"
        } catch (Exception e) {
            println "  FAILED to write ${base}: ${e.getMessage()}"
        }
    }

    // --- classifier crops ------------------------------------------------------
    if (WHAT in ['BOTH', 'CLASSIFIER'] && TARGET != 'NUCLEI') {
        cells.findAll { it.getPathClass() != null && !(it.getPathClass().toString() in NON_CELL_CLASSES) }
             .each { cell ->
            def cls = cell.getPathClass().toString()
            def safeCls = cls.replaceAll(/[^A-Za-z0-9._-]/, "_")
            def outDir = new File(new File(CLASSIFY_DIR, "crops"), safeCls)
            outDir.mkdirs()
            def cx = cell.getROI().getCentroidX(), cy = cell.getROI().getCentroidY()
            def name = "${tag}_${cell.getID().toString().take(8)}.png"
            try {
                // Shared with qupath_export_cells.groovy, which cuts the crops the
                // classifier is later APPLIED to. Same code, same style, or the model is
                // trained on one kind of picture and used on another.
                def r = cropper.write(cell, new File(outDir, name))
                cropsWritten++
                paintedTotal += r.painted
                classCounts[cls]++
                labelRows << "\"${name}\",\"${cls}\",\"${imageName}\",\"${CROP_STYLE}\",${Math.round(cx)},${Math.round(cy)}"
            } catch (Exception e) {
                println "  (skipped a crop at ${(int) cx},${(int) cy}: ${e.getMessage()})"
            }
        }
    }
}

// --- classifier label index ------------------------------------------------------
if (WHAT in ['BOTH', 'CLASSIFIER'] && cropsWritten > 0) {
    def csv = new File(CLASSIFY_DIR, "labels.csv")
    def COLS = ["crop", "class", "image", "crop_style", "x", "y"]
    // Append, so labels gathered from many slides accumulate. Same crop name overwrites.
    def oldLines = csv.exists() ? csv.readLines() : []
    def oldCols = oldLines ? oldLines[0].split(",").collect { it.trim() } : COLS
    def existing = oldLines.drop(1).findAll { line ->
        line.trim() && !labelRows.any { row -> line.startsWith(row.split(",")[0]) }
    }.collect { line ->
        // A file written before crop_style existed has FEWER columns. Writing the new
        // header over those rows would shift every field one to the left -- the x
        // coordinate would be read as the crop style. So map each old row by name and
        // re-emit it in the new order.
        if (oldCols == COLS) return line
        def vals = line.split(",")
        def byName = [:]
        oldCols.eachWithIndex { c, i -> byName[c] = i < vals.length ? vals[i] : "" }
        COLS.collect { byName.containsKey(it) ? byName[it] : '"unknown"' }.join(",")
    }
    csv.text = COLS.join(",") + "\n" + (existing + labelRows).join("\n") + "\n"
    // The corpus is read from the FOLDER, not this CSV, so the style has to be recorded
    // there too -- otherwise a classifier can be trained on crops made two different ways
    // with nothing to notice it.
    new File(new File(CLASSIFY_DIR, "crops"), "crop_style.txt").text = CROP_STYLE + "\n"
    println ""
    println "labels.csv now holds ${(existing + labelRows).size()} labelled cell(s) across all slides"
}

println ""
println "=== export complete ==="
if (WHAT in ['BOTH', 'SEGMENTATION'])
    println "  segmentation: ${segWritten} ${TARGET.toLowerCase()} region(s) written to ${CORPUS_ROOT}"
if (WHAT in ['BOTH', 'CLASSIFIER']) {
    println "  classifier:   ${cropsWritten} crop(s) written to ${new File(CLASSIFY_DIR, 'crops')}"
    println "                crop style ${CROP_STYLE}" + (CROP_STYLE == 'NEIGHBOURS_OUT'
            ? ", ${paintedTotal} neighbouring cell(s) painted out" : "")
    classCounts.sort { -it.value }.each { cls, n -> println "      ${n}  ${cls}" }
    if (classCounts.isEmpty())
        println "      none -- no cells carry a cell-type class yet"
}
println ""
println "The same cells now serve both jobs: their outlines train Cellpose, their classes"
println "train the classifier. Classified cells are NO LONGER dropped from the masks."
