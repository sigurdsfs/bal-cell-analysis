/**
 * InstanSeg segmentation, as an alternative to Cellpose.
 *
 * Needs the QuPath InstanSeg extension (installed here, v0.1.7) and a downloaded model.
 * Unlike Cellpose this runs inside QuPath through Deep Java Library -- no Python involved.
 *
 * IMPORTANT DIFFERENCE: the brightfield model segments NUCLEI, whereas Cellpose-SAM
 * segments WHOLE CELLS. Counts are comparable, but the measured areas are not: a nucleus
 * is much smaller than its cell. Don't mix the two in one analysis.
 *
 * SPEED: QuPath has downloaded the CPU build of PyTorch, so this runs on the CPU while
 * Cellpose has the GPU. For GPU, install the CUDA build via
 * Edit > Preferences > Deep Java Library (or the Extensions > Deep Java Library menu).
 *
 * Run it from the GUI script, or standalone with an annotation selected.
 */

import qupath.ext.instanseg.core.InstanSeg
import qupath.ext.instanseg.core.InstanSegModel
import java.nio.file.Paths

// Overridable from the GUI.
def MODEL_DIR   = binding.hasVariable('BAL_INSTANSEG_MODEL') ? BAL_INSTANSEG_MODEL :
        System.getProperty("user.home") + "/QuPath/v0.7/instanseg/downloaded/brightfield_nuclei-0.1.1"
def OUTPUT_TYPE = binding.hasVariable('BAL_OUTPUT_TYPE')     ? BAL_OUTPUT_TYPE           : 'DETECTION'
def TARGETS     = binding.hasVariable('BAL_TARGETS')         ? BAL_TARGETS               : 'SELECTED'
int TILE_SIZE   = binding.hasVariable('BAL_TILE_SIZE')       ? BAL_TILE_SIZE as int      : 512
def DEVICE      = binding.hasVariable('BAL_DEVICE')          ? BAL_DEVICE                : 'cpu'
// Leave the pixel size at 0 to use whatever resolution the model was trained for.
double PIXEL_SIZE_UM = binding.hasVariable('BAL_INSTANSEG_PIXEL_SIZE') ? BAL_INSTANSEG_PIXEL_SIZE as double : 0

def modelPath = Paths.get(MODEL_DIR)
if (!modelPath.toFile().exists()) {
    Dialogs.showErrorMessage("InstanSeg", "Model not found:\n${MODEL_DIR}\n\n" +
            "Download one through Extensions > InstanSeg first.")
    return
}
def model = InstanSegModel.fromPath(modelPath)
if (!model.isValid()) {
    Dialogs.showErrorMessage("InstanSeg", "'${MODEL_DIR}' is not a usable InstanSeg model.")
    return
}

def imageData = getCurrentImageData()
def pathObjects = TARGETS == 'ALL_ANNOTATIONS' ? getAnnotationObjects() : getSelectedObjects()
if (pathObjects.isEmpty()) {
    Dialogs.showErrorMessage("InstanSeg", "Select an annotation first.")
    return
}

def cal = imageData.getServer().getPixelCalibration()
double downsample = PIXEL_SIZE_UM > 0 ? PIXEL_SIZE_UM / cal.getAveragedPixelSizeMicrons()
                                      : model.getPreferredDownsample(cal)

def builder = InstanSeg.builder()
        .model(model)
        .device(DEVICE)
        .tileDims(TILE_SIZE)
        .downsample(downsample)
        .allInputChannels()
        .makeMeasurements(true)

switch (OUTPUT_TYPE) {
    case 'ANNOTATION': builder.outputAnnotations(); break
    case 'CELL':       builder.outputCells(); break
    default:           builder.outputDetections(); break
}

println "InstanSeg: model '${model.getName()}', device ${DEVICE}, downsample ${String.format('%.2f', downsample)}" +
        " (${String.format('%.3f', downsample * cal.getAveragedPixelSizeMicrons())} um/px), tiles ${TILE_SIZE}"
if (DEVICE == 'cpu')
    println "  running on CPU -- expect this to be slower than Cellpose on the GPU"

long t0 = System.currentTimeMillis()
def results = builder.build().detectObjects(pathObjects)
double secs = (System.currentTimeMillis() - t0) / 1000.0

int produced = pathObjects.sum { it.getChildObjects().size() } ?: 0
println "InstanSeg done: ${produced} objects in ${secs}s"
println "  totals now -- annotations: ${getAnnotationObjects().size()}, detections: ${getDetectionObjects().size()}"
println "  NOTE: this model outlines NUCLEI, not whole cells -- areas are not comparable with Cellpose-SAM."
