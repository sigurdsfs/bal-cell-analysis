/**
 * Exports this project's corrected ground truth into a shared corpus folder, so training
 * data accumulates across slides and projects instead of living inside one project.
 *
 * WHY THIS EXISTS
 * The extension normally exports patches and trains in one go, wiping the export folder
 * first (Cellpose2D line ~998: saveTrainingImages() only runs when cleanTrainingDir is set,
 * and it calls cleanDirectory() immediately before). That is fine for a single project,
 * but it would delete a corpus you had built up over weeks.
 *
 * So the corpus workflow separates the two steps:
 *   1. THIS script exports the current project's Training/Validation rectangles into the
 *      corpus folder, adding to what is already there (never cleaning).
 *   2. qupath_train_cellpose.groovy trains FROM the corpus with cleaning switched off.
 *
 * Patches are named "<imageName>_region_<n>.tif" plus "..._masks.tif", so different slides
 * don't collide and re-exporting the same slide overwrites its own patches rather than
 * duplicating them.
 *
 * TWO THINGS TO WATCH with a shared corpus, both from that naming:
 *   - Images with the SAME NAME in different projects overwrite each other. Give slides
 *     distinct names, or keep one corpus per study.
 *   - The index <n> is positional. Delete a rectangle and re-export, and the leftover
 *     higher-numbered patch stays behind as orphaned ground truth. Clear that slide's
 *     files by hand if you remove regions.
 *
 * NOTE: the export covers EVERY image in the current project that has Training/Validation
 * rectangles, so you only need to run it once per project, not once per image.
 *
 * Save the project first -- the export reads saved data, not the screen. (This script saves
 * the current image for you; other images in the project must already be saved.)
 *
 * MUST BE RUN FROM THE QUPATH WINDOW: saveTrainingImages() calls QPEx.getQuPath().getProject()
 * internally, which is null on the command line. Same limitation as training itself.
 */

import qupath.ext.biop.cellpose.Cellpose2D
import qupath.ext.biop.cellpose.CellposeSetup

def CORPUS = binding.hasVariable('BAL_GT_DIR') ? new File(BAL_GT_DIR.toString())
        : new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/cellpose_ground_truth")
def MODEL = binding.hasVariable('BAL_MODEL') ? BAL_MODEL.toString() : 'cyto3'
double DIAM = binding.hasVariable('BAL_DIAMETER') ? BAL_DIAMETER as double : 58

// Same environment wiring as the other scripts.
def samPython = [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose_seg/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose_seg/python.exe',
].find { new File(it).exists() }
def legacyPython = [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose3/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose3/python.exe',
].find { new File(it).exists() }
if (samPython != null) CellposeSetup.getInstance().setCellposeSAMPythonPath(samPython)
if (legacyPython != null) CellposeSetup.getInstance().setCellposePythonPath(legacyPython)

// Save first: the export reads each image's saved data from the project.
def entry = getProjectEntry()
if (entry != null) {
    try {
        entry.saveImageData(getCurrentImageData())
    } catch (Exception e) {
        println "WARNING: couldn't save this image's data (${e.getMessage()}) -- unsaved corrections won't be exported"
    }
}

def before = countPairs(CORPUS)

def builder = Cellpose2D.builder(MODEL)
        .pixelSize(0.137)
        .groundTruthDirectory(CORPUS)      // where the patches land
// deliberately NO .cleanTrainingDir() -- that would erase the corpus
if (MODEL == 'cpsam' || (binding.hasVariable('BAL_MODEL_IS_SAM') && BAL_MODEL_IS_SAM))
    builder.useCellposeSAM()
else if (DIAM > 0)
    builder.diameter(DIAM)

def cellpose = builder.build()
println "Exporting ground truth to ${CORPUS}"
cellpose.saveTrainingImages()

def after = countPairs(CORPUS)
println ""
println "=== corpus now holds ==="
println "  train: ${after.train} image/mask pairs (was ${before.train})"
println "  test : ${after.test} image/mask pairs (was ${before.test})"
if (after.train == before.train && after.test == before.test)
    println "  NOTHING WAS ADDED -- did you save the project, and do the rectangles hold corrected outlines?"
println ""
println "Train on the whole corpus with qupath_train_cellpose.groovy, setting the same folder"
println "as the ground truth directory (the GUI's 'Training data folder' field)."

// Counts image/mask pairs, so the numbers mean patches rather than files.
def countPairs(File corpus) {
    def count = { File dir ->
        if (!dir.exists()) return 0
        dir.listFiles()?.count { it.name.toLowerCase().endsWith("_masks.tif") } ?: 0
    }
    [train: count(new File(corpus, "train")), test: count(new File(corpus, "test"))]
}
