/**
 * Cell-type classification with a pathology vision transformer (Phikon-v2).
 *
 * WHY NOT THE MEASUREMENTS: the 12 features describe size, shape and average colour. What
 * separates BAL cell types is texture -- a neutrophil's lobed nucleus, an eosinophil's coarse
 * granules -- which no average can see. Phikon-v2 is a ViT trained on histology; a small
 * classifier on its embeddings needs far fewer labels and sees those patterns.
 *
 * THREE MODES (BAL_CLASSIFY_MODE):
 *   'EXPLORE' - no labels yet. Clusters the embeddings and writes bal_classify/clusters.png
 *               so you can see which cell types are on the slide before labelling.
 *   'TRAIN'   - uses the classes you set by hand, reports cross-validated per-class scores,
 *               saves the classifier, and predicts the rest.
 *   'PREDICT' - applies a saved classifier to every cell.
 *
 * Predictions come back into QuPath as classifications, so you can correct them and retrain.
 *
 * LABEL FIRST (for TRAIN): select a cell, then in the Annotations tab click a class and
 * right-click > Set selected. Aim for 40-60 per type. qupath_setup_classes.groovy puts the
 * BAL classes in that list.
 */

import qupath.lib.objects.PathObject

def MODE = binding.hasVariable('BAL_CLASSIFY_MODE') ? BAL_CLASSIFY_MODE.toString().toUpperCase() : 'TRAIN'
int CLUSTERS = binding.hasVariable('BAL_CLUSTERS') ? BAL_CLUSTERS as int : 5
def CLASSIFIER = binding.hasVariable('BAL_CLASSIFIER') && BAL_CLASSIFIER?.toString()?.trim() ?
        BAL_CLASSIFIER.toString().trim() : null
// Embedding is the slow step (~1 s per 60 cells on the GPU). Reuse the parquet when the
// cells haven't changed since it was written.
boolean REUSE = binding.hasVariable('BAL_REUSE_EMBEDDINGS') ? BAL_REUSE_EMBEDDINGS as boolean : false

def BAL_DIR = new File(CFG.balClassifyDir ?: "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify")
def python = (CFG.pythonCellpose4 ?: [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose_seg/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose_seg/python.exe',
]).find { new File(it).exists() }
if (python == null) {
    Dialogs.showErrorMessage("Classify cells", "No cellpose_seg environment found -- it holds the transformer.")
    return
}
if (!new File(BAL_DIR, "embed_cells.py").exists()) {
    Dialogs.showErrorMessage("Classify cells", "Missing ${BAL_DIR}/embed_cells.py")
    return
}

def scriptDir = null
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file")
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
if (scriptDir == null || !new File(scriptDir, "qupath_export_cells.groovy").exists())
    scriptDir = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")

def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.regions.*
import qupath.lib.measurements.*

// Machine-specific paths come from bal_config.groovy (with bal_config.local.groovy
// overriding it); the literals below are only a fallback for running this file alone.
def CFG = [:]
try {
    def cfgFile = [new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.groovy"),
                   new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts", "bal_config.groovy")]
            .find { it.exists() }
    if (cfgFile != null) CFG = evaluate(cfgFile) ?: [:]
} catch (Exception ignored) { }

"""

// Runs a command, echoing its output into the QuPath log as it arrives.
def run = { List<String> cmd ->
    println "> " + cmd.join(" ")
    def pb = new ProcessBuilder(cmd)
    pb.directory(BAL_DIR)
    pb.redirectErrorStream(true)
    def proc = pb.start()
    proc.inputStream.eachLine { line ->
        // Hide progress-bar spam and library warnings; keep everything that matters.
        if (!(line =~ /it\/s|\r|UserWarning|warnings\.warn|^\s*$/)) println "  " + line
    }
    int code = proc.waitFor()
    if (code != 0) println "  (exited with code ${code})"
    code
}

// --- 1. export the cells QuPath currently holds ------------------------------
def cells = getCellObjects()
if (cells.isEmpty()) cells = getDetectionObjects()
if (cells.isEmpty()) {
    Dialogs.showErrorMessage("Classify cells", "No cells found -- run the segmentation first.")
    return
}
int labelled = cells.count { it.getPathClass() != null && it.getPathClass().toString() != "Clump/debris" }
println "=== ${MODE}: ${cells.size()} cells, ${labelled} carrying a hand-set class ==="
if (MODE == 'TRAIN' && labelled < 20) {
    Dialogs.showErrorMessage("Classify cells",
            "Only ${labelled} labelled cells.\n\nLabel ~40-60 per type first: select a cell, then in the\n" +
            "Annotations tab click a class and right-click > Set selected.")
    return
}

BAL_EXPORT_MODE = 'EXPORT'
evaluate(PRE + new File(scriptDir, "qupath_export_cells.groovy").text)

// --- 2. embed the crops with the transformer ---------------------------------
def parquet = new File(BAL_DIR, "cells.parquet")
if (REUSE && parquet.exists()) {
    println "Reusing existing embeddings (${parquet.getName()}) -- untick 'reuse' after re-segmenting."
} else {
    if (run([python, "embed_cells.py", "--geojson", "cells.geojson", "--out", "cells.parquet"]) != 0) {
        Dialogs.showErrorMessage("Classify cells", "Embedding failed -- see the log.")
        return
    }
}

// --- 3. explore / train / predict --------------------------------------------
def cmd = [python, "classify_cells.py"]
if (MODE == 'EXPLORE')      cmd += ["explore", "--cells", "cells.parquet", "--clusters", String.valueOf(CLUSTERS)]
else if (MODE == 'PREDICT') cmd += ["predict", "--cells", "cells.parquet"]
else                        cmd += ["train", "--cells", "cells.parquet"]
if (CLASSIFIER != null && MODE != 'EXPLORE') cmd += ["--model", CLASSIFIER]

if (run(cmd) != 0) {
    Dialogs.showErrorMessage("Classify cells", "The classifier step failed -- see the log.")
    return
}

if (MODE == 'EXPLORE') {
    println ""
    println "Open bal_classify/clusters.png: each row shows the most typical cells of a cluster."
    println "Name them, label examples of each in QuPath, then run this again in TRAIN mode."
    return
}

// --- 4. bring the predictions back into QuPath -------------------------------
BAL_EXPORT_MODE = 'IMPORT'
evaluate(PRE + new File(scriptDir, "qupath_export_cells.groovy").text)
println ""
println "Predicted classes are now on the cells. Correct any that are wrong, then run TRAIN"
println "again -- every correction makes the next round better."
