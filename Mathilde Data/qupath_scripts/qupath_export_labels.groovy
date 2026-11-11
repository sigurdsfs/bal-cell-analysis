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
 * THE ONLY EXPORT. It replaced qupath_export_training_data.groovy, which drove the
 * extension's own exporter and therefore dropped every classified cell from the mask. Each
 * of the two outputs is switched on independently and has its own folder:
 *
 *   BAL_EXPORT_SEG    -> <BAL_GT_DIR>/train|test/<project>__<image>_region_N.tif + _masks.tif
 *                        Instance masks: every cell gets its own label, classified or not.
 *                        'test' for a Validation rectangle, 'train' for a Training one.
 *   BAL_EXPORT_CROPS  -> <BAL_CLASSIFY_DIR>/crops/<ClassName>/<project>__<image>_<id>.png
 *                        One crop per labelled cell, filed by class, plus labels.csv beside
 *                        the crops folder -- which is where embed_cells.py looks for it.
 *                        Crops come from QuPath, so this works for ANY slide -- no zarr.
 *
 * Both default to on, which is the point: one set of hand-labelled cells serves both jobs.
 * Switch one off when you only want that half -- re-exporting crops after changing
 * `cropStyle`, say, without rewriting masks that have not changed.
 *
 * Run it with the image open. It covers every Training/Validation rectangle in the image;
 * run it once per slide (or let qupath_batch_slides.groovy do that for you).
 *
 * NOT COVERED, and the one thing the old exporter did that this does not: it walked EVERY
 * image in the project in a single run. This one does the open image. Use
 * qupath_batch_slides.groovy for a whole project.
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

// The two halves are independent switches. BAL_EXPORT_WHAT is the older single-valued form
// and is still honoured, so anything that set it keeps working.
boolean SEG, CROPS
if (binding.hasVariable('BAL_EXPORT_WHAT')) {
    def w = BAL_EXPORT_WHAT.toString().toUpperCase()
    SEG = w in ['BOTH', 'SEGMENTATION']
    CROPS = w in ['BOTH', 'CLASSIFIER']
} else {
    SEG = binding.hasVariable('BAL_EXPORT_SEG') ? BAL_EXPORT_SEG as boolean : true
    CROPS = binding.hasVariable('BAL_EXPORT_CROPS') ? BAL_EXPORT_CROPS as boolean : true
}
if (!SEG && !CROPS) {
    Dialogs.showErrorMessage("Export labelled cells",
            "Nothing to export.\n\n" +
            "Tick 'Segmentation masks', 'Classifier crops', or both.")
    return
}

def CORPUS = new File(binding.hasVariable('BAL_GT_DIR') && BAL_GT_DIR?.toString()?.trim() ? BAL_GT_DIR.toString().trim()
        : (CFG.groundTruthDir ?: "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/cellpose_ground_truth"))
// Holds crops/ and labels.csv. Overridable so the two outputs can go to different places --
// and so the test harness can write somewhere other than the real corpus, which it used to
// add crops to every time it ran.
def CLASSIFY_DIR = new File(binding.hasVariable('BAL_CLASSIFY_DIR') && BAL_CLASSIFY_DIR?.toString()?.trim()
        ? BAL_CLASSIFY_DIR.toString().trim()
        : (CFG.balClassifyDir ?: "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify"))
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

// A nucleus carries no cell type, so there are no classifier crops to cut from one. Say so
// rather than reporting "0 crops", which reads like a failure.
if (CROPS && TARGET == 'NUCLEI') {
    println "Target is Nuclei, so no classifier crops will be written: a nucleus annotation"
    println "  carries no cell-type class. Switch the target to Soma for crops."
    if (!SEG) {
        Dialogs.showErrorMessage("Export labelled cells",
                "Nothing to export.\n\n" +
                "Only 'Classifier crops' is ticked, but the target is Nuclei and nuclei\n" +
                "carry no cell type. Tick 'Segmentation masks', or set the target to Soma.")
        return
    }
}

// Counts image/mask pairs, so the numbers mean patches rather than files. Carried over from
// qupath_export_training_data.groovy, which this script replaced: knowing the corpus grew
// (and by how much) is the only way to tell a working export from a silent no-op.
def countPairs = { File root ->
    def one = { File d -> d.exists() ? (d.listFiles()?.count { it.name.toLowerCase().endsWith("_masks.tif") } ?: 0) : 0 }
    [train: one(new File(root, "train")), test: one(new File(root, "test"))]
}
def before = SEG ? countPairs(CORPUS_ROOT) : null

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
if (CROPS && TARGET != 'NUCLEI') {
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
    if (SEG) {
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
    if (CROPS && TARGET != 'NUCLEI') {
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
if (CROPS && cropsWritten > 0) {
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
if (SEG) {
    println "  segmentation: ${segWritten} ${TARGET.toLowerCase()} region(s) written to ${CORPUS_ROOT}"
    def after = countPairs(CORPUS_ROOT)
    println "    corpus now holds ${after.train} train and ${after.test} test pair(s) " +
            "(was ${before.train} and ${before.test})"
    if (after.train == before.train && after.test == before.test) {
        println "    NOTHING WAS ADDED -- the rectangles may hold no outlines, or this slide's"
        println "    patches were already there and were overwritten with the same content."
    }
    // Per-slide coverage: with many slides it matters which ones are actually represented,
    // and a corpus is easy to train on while believing it covers more slides than it does.
    ["train", "test"].each { sub ->
        def bySlide = (new File(CORPUS_ROOT, sub).listFiles() ?: [])
                .findAll { it.name.endsWith("_masks.tif") }
                .groupBy { it.name.replaceAll(/_region_\d+_masks\.tif$/, "") }
        if (!bySlide.isEmpty()) {
            println "    ${sub} patches by slide:"
            bySlide.sort { it.key }.each { slide, files -> println "      ${files.size()}  ${slide}" }
        }
    }
}
if (CROPS) {
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
