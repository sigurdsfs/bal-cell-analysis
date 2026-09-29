/**
 * Cellpose-SAM detection script for the BAL slide, run from inside QuPath.
 *
 * Tested end-to-end on this machine on 2026-09-07: opens Image_BAL_#2.vsi (series 2,
 * "40x_BF_01") as a real 8-level pyramid, calls Cellpose-SAM through the cellpose_seg
 * conda env on the GPU, and imports the result as an editable annotation.
 *
 * PREREQUISITES (one-time setup):
 * 1. Create a QuPath project and add Image_BAL_#2.vsi to it. When prompted for a
 *    series, pick "Image_BAL_#2.vsi - 40x_BF_01" (NOT the "label" or "macro" series --
 *    those are the small overview/label images bundled in the same .vsi file).
 * 2. The qupath-extension-cellpose jar is already installed in your QuPath user
 *    extensions folder -- restart QuPath once if you haven't since it was added.
 * 3. Open the image, draw or select an annotation over the region you want to segment,
 *    then run this script (Extensions > Script Editor, or drag this file onto QuPath).
 *    Only the SELECTED annotation(s) are processed -- see the bottom of the script to
 *    switch to "all annotations" for batch/whole-slide runs.
 *
 * NOTE ON PARAMETERS: this ran successfully but only found 1 cell in a quick test tile
 * where more were expected. Cellpose-SAM's default overlap/normalization may not match
 * what your notebook pipeline does with the same tiles -- if detections look sparse,
 * try uncommenting .normalizePercentiles() below, or compare against the notebook's
 * model.eval(tile, diameter=None) result on the same region before trusting counts.
 */

import qupath.ext.biop.cellpose.Cellpose2D
import qupath.ext.biop.cellpose.CellposeSetup

// Machine-specific paths come from bal_config.groovy (with bal_config.local.groovy
// overriding it); the literals below are only a fallback for running this file alone.
def CFG = [:]
try {
    def cfgFile = [new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.groovy"),
                   new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts", "bal_config.groovy")]
            .find { it.exists() }
    if (cfgFile != null) CFG = evaluate(cfgFile) ?: [:]
} catch (Exception ignored) { }


// --- one-time env setup -----------------------------------------------------
// Same as Edit > Preferences > Cellpose/Omnipose in the GUI -- setting it here too
// means this script is self-contained even on a fresh QuPath user profile.
// Each Windows account on this PC has its own cellpose_seg env -- use whichever this user can see.
def cellposePython = (CFG.pythonCellpose4 ?: [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose_seg/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose_seg/python.exe',
]).find { new File(it).exists() }
if (cellposePython == null) {
    Dialogs.showErrorMessage("Cellpose", "No cellpose_seg environment found for this Windows user.")
    return
}
CellposeSetup.getInstance().setCellposeSAMPythonPath(cellposePython)

// cyto2/cyto3 belong to cellpose 3 and will NOT load in the cellpose 4 environment, so they
// get their own env and their own path setting in the extension.
def legacyPython = (CFG.pythonCellpose3 ?: [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose3/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose3/python.exe',
]).find { new File(it).exists() }
if (legacyPython != null)
    CellposeSetup.getInstance().setCellposePythonPath(legacyPython)


// --- CHOOSE THE OUTPUT TYPE ------------------------------------------------
// 'ANNOTATION' - editable objects. Use while checking or correcting results by hand:
//               the brush, wand and split tools all work on them. Slow once there are
//               many thousands, so prefer DETECTION for whole-slide runs.
// 'DETECTION'  - lightweight, read-only objects. The right choice for big runs, for
//               QuPath's object classifier, and for measurement export.
// 'CELL'       - cell objects, each holding an inner (nucleus) and outer (cell) boundary.
//               NOTE: this fits nucleus-segmenting models. cpsam segments WHOLE cells here,
//               so the inner boundary is the whole cell and CELL_EXPANSION_UM just adds a
//               ring around it -- only useful if you actually want a peri-cellular zone.
// Each setting below can be overridden by qupath_bal_gui.groovy, which passes BAL_* variables
// before running this script. Run standalone and the defaults here apply.
def OUTPUT_TYPE          = binding.hasVariable('BAL_OUTPUT_TYPE')       ? BAL_OUTPUT_TYPE              : 'DETECTION'
double CELL_EXPANSION_UM = binding.hasVariable('BAL_CELL_EXPANSION_UM') ? BAL_CELL_EXPANSION_UM as double : 2.0
double PIXEL_SIZE_UM     = binding.hasVariable('BAL_PIXEL_SIZE_UM')     ? BAL_PIXEL_SIZE_UM as double  : 0.137
int TILE_SIZE            = binding.hasVariable('BAL_TILE_SIZE')         ? BAL_TILE_SIZE as int         : 1024
// 'SELECTED' = only what you have selected; 'ALL_ANNOTATIONS' = every annotation in the image
def TARGETS              = binding.hasVariable('BAL_TARGETS')           ? BAL_TARGETS                  : 'SELECTED'
// 'cpsam' is the only pretrained model in cellpose 4.x. Older names (cyto2, cyto3, nuclei)
// belong to cellpose 3 and will NOT load in this environment. A path to a model you
// fine-tuned yourself from cpsam works here too.
def MODEL                = binding.hasVariable('BAL_MODEL')             ? BAL_MODEL.toString()         : 'cpsam'
// cyto2/cyto3 are scale-sensitive and need the cell diameter in pixels (0 = let cellpose guess).
// Measured on this slide: median cell diameter is 58 px at 0.137 um/px.
double DIAMETER          = binding.hasVariable('BAL_DIAMETER')          ? BAL_DIAMETER as double       : 0

// --- build the detector -------------------------------------------------
// Which environment a model needs depends on what it was trained FROM, which cannot be read
// from the file. cpsam -> cellpose 4; cyto2/cyto3 and anything fine-tuned from them -> cellpose 3.
// Custom models default to cellpose 3, since cyto3 is this pipeline's base model.
// Set BAL_MODEL_IS_SAM = true for a model fine-tuned from cpsam.
boolean isSAM = MODEL == 'cpsam' ||
        (binding.hasVariable('BAL_MODEL_IS_SAM') ? BAL_MODEL_IS_SAM as boolean : false)
if (!isSAM && legacyPython == null) {
    Dialogs.showErrorMessage("Cellpose", "'${MODEL}' is a cellpose 3 model, but no cellpose3 environment was found.")
    return
}

def builder = Cellpose2D.builder(MODEL)
        .pixelSize(PIXEL_SIZE_UM)                      // um/px, from the VSI's own OME metadata (40x_BF_01)
        .tileSize(TILE_SIZE)                           // matches the notebook's tile size / measured throughput
//        .setOverlap(60)                               // default is 60px; raise if large cells are getting split at tile edges
//        .normalizePercentilesGlobal(1, 99, 10)        // try this if detections look sparse vs. the notebook's own run
        .measureShape()                                // adds shape measurements comparable to the notebook's regionprops features

if (isSAM)
    builder.useCellposeSAM()                           // cellpose 4.x model, matches the notebook pipeline
// cpsam is scale-free: it does no rescaling, and cellpose 4 warns that --diam_mean is
// "deprecated in v4.0.1+ and no longer used". Only cyto2/cyto3 (and models fine-tuned from
// them) rescale the image by diam_mean/diameter, so only they get the setting.
if (!isSAM && DIAMETER > 0)
    builder.diameter(DIAMETER)

switch (OUTPUT_TYPE) {
    case 'ANNOTATION':
        builder.createAnnotations()
        break
    case 'CELL':
        builder.cellExpansion(CELL_EXPANSION_UM)       // expand the mask outwards to form the cell boundary
               .cellConstrainScale(1.5)                // stop the expansion running away on large cells
        break
    case 'DETECTION':
        break                                          // the builder's default
    default:
        Dialogs.showErrorMessage("Cellpose", "OUTPUT_TYPE must be ANNOTATION, DETECTION or CELL -- got '${OUTPUT_TYPE}'")
        return
}
def cellpose = builder.build()

// --- run on the selected annotation(s) ----------------------------------
def imageData = getCurrentImageData()
def pathObjects = TARGETS == 'ALL_ANNOTATIONS' ? getAnnotationObjects() : getSelectedObjects()

if (pathObjects.isEmpty()) {
    Dialogs.showErrorMessage("Cellpose", TARGETS == 'ALL_ANNOTATIONS'
            ? "This image has no annotations to process."
            : "Please draw or select an annotation first, then run this script again.")
    return
}

cellpose.detectObjects(imageData, pathObjects)

// Count only what this run produced, i.e. objects inside the regions we processed.
int produced = pathObjects.sum { it.getChildObjects().size() } ?: 0
int nCells = getCellObjects().size()
println "Cellpose-SAM done (OUTPUT_TYPE=${OUTPUT_TYPE}): ${produced} objects created in the selected region(s)"
println "  totals now -- annotations: ${getAnnotationObjects().size()}, detections: ${getDetectionObjects().size()}, of which cell objects: ${nCells}"
