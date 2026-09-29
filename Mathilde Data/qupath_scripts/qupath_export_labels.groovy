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
int CROP = binding.hasVariable('BAL_CROP_PX') ? BAL_CROP_PX as int : 128
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

// The masks must contain EVERY cell, so the filter excludes only the region rectangles.
// This is the whole point: a classified cell still counts as a cell.
def labelServer = new LabeledImageServer.Builder(imageData)
        .backgroundLabel(0, ColorTools.BLACK)
        .multichannelOutput(false)
        .useInstanceLabels()
        .useFilter({ PathObject o -> !(o.getPathClass()?.toString() in ["Training", "Validation"]) })
        .build()

double downsample = PIXEL_SIZE_UM / server.getPixelCalibration().getAveragedPixelSizeMicrons()
if (!(downsample > 0)) downsample = 1.0

int segWritten = 0, cropsWritten = 0
def classCounts = [:].withDefault { 0 }
def labelRows = []

regions.eachWithIndex { region, idx ->
    def roi = region.getROI()
    boolean isValidation = region.getPathClass().toString() == "Validation"
    def request = RegionRequest.createInstance(server.getPath(), downsample, roi)

    // Every annotation inside this rectangle, whatever its class.
    def cells = getAnnotationObjects().findAll { a ->
        a != region && !(a.getPathClass()?.toString() in ["Training", "Validation"]) &&
                roi.getGeometry().intersects(a.getROI().getGeometry())
    }

    // --- segmentation ground truth -------------------------------------------
    if (WHAT in ['BOTH', 'SEGMENTATION']) {
        def dir = new File(CORPUS, isValidation ? "test" : "train")
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
    if (WHAT in ['BOTH', 'CLASSIFIER']) {
        cells.findAll { it.getPathClass() != null && !(it.getPathClass().toString() in NON_CELL_CLASSES) }
             .each { cell ->
            def cls = cell.getPathClass().toString()
            def safeCls = cls.replaceAll(/[^A-Za-z0-9._-]/, "_")
            def outDir = new File(new File(CLASSIFY_DIR, "crops"), safeCls)
            outDir.mkdirs()
            def cx = cell.getROI().getCentroidX(), cy = cell.getROI().getCentroidY()
            def cropRoi = ROIs.createRectangleROI(cx - CROP / 2, cy - CROP / 2, CROP, CROP,
                    cell.getROI().getImagePlane())
            def cropReq = RegionRequest.createInstance(server.getPath(), 1.0, cropRoi)
            def name = "${tag}_${cell.getID().toString().take(8)}.png"
            try {
                ImageWriterTools.writeImageRegion(server, cropReq, new File(outDir, name).getAbsolutePath())
                cropsWritten++
                classCounts[cls]++
                labelRows << "\"${name}\",\"${cls}\",\"${imageName}\",${Math.round(cx)},${Math.round(cy)}"
            } catch (Exception e) {
                println "  (skipped a crop at ${(int) cx},${(int) cy}: ${e.getMessage()})"
            }
        }
    }
}

// --- classifier label index ------------------------------------------------------
if (WHAT in ['BOTH', 'CLASSIFIER'] && cropsWritten > 0) {
    def csv = new File(CLASSIFY_DIR, "labels.csv")
    // Append, so labels gathered from many slides accumulate. Same crop name overwrites.
    def existing = csv.exists() ? csv.readLines().drop(1).findAll { line ->
        !labelRows.any { row -> line.startsWith(row.split(",")[0]) }
    } : []
    csv.text = "crop,class,image,x,y\n" + (existing + labelRows).join("\n") + "\n"
    println ""
    println "labels.csv now holds ${(existing + labelRows).size()} labelled cell(s) across all slides"
}

println ""
println "=== export complete ==="
if (WHAT in ['BOTH', 'SEGMENTATION'])
    println "  segmentation: ${segWritten} region(s) written to ${CORPUS}"
if (WHAT in ['BOTH', 'CLASSIFIER']) {
    println "  classifier:   ${cropsWritten} crop(s) written to ${new File(CLASSIFY_DIR, 'crops')}"
    classCounts.sort { -it.value }.each { cls, n -> println "      ${n}  ${cls}" }
    if (classCounts.isEmpty())
        println "      none -- no cells carry a cell-type class yet"
}
println ""
println "The same cells now serve both jobs: their outlines train Cellpose, their classes"
println "train the classifier. Classified cells are NO LONGER dropped from the masks."
