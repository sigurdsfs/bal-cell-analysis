/**
 * Cell-type classification with a pathology vision transformer (Phikon-v2).
 *
 * WHY NOT THE MEASUREMENTS: the 12 features describe size, shape and average colour. What
 * separates BAL cell types is texture -- a neutrophil's lobed nucleus, an eosinophil's coarse
 * granules -- which no average can see. Phikon-v2 is a ViT trained on histology; a small
 * classifier on its embeddings needs far fewer labels and sees those patterns.
 *
 * FOUR MODES (BAL_CLASSIFY_MODE):
 *   'EXPLORE'      no labels yet. Clusters the embeddings and writes bal_classify/clusters.png
 *                  so you can see which cell types are on the slide before labelling.
 *   'TRAIN'        uses the classes you set by hand IN THIS IMAGE, reports cross-validated
 *                  per-class scores, saves the classifier, and predicts the rest.
 *   'TRAIN_CORPUS' trains on the crops exported from EVERY slide instead -- the only mode
 *                  that produces a classifier worth using on an image it has not seen.
 *                  Needs no cells in the open image; it reads bal_classify/crops/.
 *   'PREDICT'      applies a saved classifier. Cells you classified by hand keep their
 *                  class unless BAL_PREDICT_OVERWRITE is set.
 *
 * Predictions come back into QuPath as classifications, so you can correct them and retrain.
 *
 * The transformer itself is never trained -- it is a fixed instrument. What is saved and
 * loaded is the small classifier on top of its embeddings, and TRAINING IS ALWAYS A FRESH
 * FIT: nothing carries over from the saved file. So "more training" means re-fitting over
 * all the labels, which is why they accumulate in crops/ rather than in the model.
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
// PREDICT only: re-predict cells that already carry a hand-set class, replacing it.
boolean OVERWRITE = binding.hasVariable('BAL_PREDICT_OVERWRITE') ? BAL_PREDICT_OVERWRITE as boolean : false

// Machine-specific paths come from bal_config.groovy (with bal_config.local.groovy
// overriding it); the literals below are only a fallback for running this file alone.
// This block is for THIS script. The one inside PRE further down is a separate copy,
// prepended to the sub-scripts this one evaluates -- it does not define CFG here.
def CFG = [:]
try {
    def cfgFile = [new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.groovy"),
                   new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts", "bal_config.groovy")]
            .find { it.exists() }
    if (cfgFile != null) CFG = evaluate(cfgFile) ?: [:]
} catch (Exception ignored) { }

// Where embed_cells.py and classify_cells.py live, and the working directory every command
// runs in. This never moves -- it is the code, not the data.
def BAL_DIR = new File(CFG.balClassifyDir ?: "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify")
// Where the labelled corpus lives: crops/ plus labels.csv beside it. Normally the same
// folder, but qupath_export_labels.groovy can be pointed elsewhere, and then this has to
// follow it -- training on the default folder while the crops went somewhere else would
// quietly train on the wrong set.
def CROPS_HOME = binding.hasVariable('BAL_CLASSIFY_DIR') && BAL_CLASSIFY_DIR?.toString()?.trim()
        ? new File(BAL_CLASSIFY_DIR.toString().trim()) : BAL_DIR
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

// IMPORTS ONLY. PRE is prepended to the sub-scripts this one evaluates, and every one of
// them declares its own `def CFG` -- putting a second one here made the combined script
// fail to compile with "The current scope already contains a variable of the name CFG",
// which is why no classify task ever got past the export step.
def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.regions.*
import qupath.lib.measurements.*
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

// --- 0. corpus training: the open image is not involved at all ---------------
// WHY A SEPARATE MODE: a classifier fitted on one slide's cells is fitted to that slide's
// staining and focus as much as to its cell types, and the cross-validated scores cannot
// show it -- every fold resamples the same slide. The crops folder is where labels from
// every slide accumulate, so this is the mode that produces a transferable model.
if (MODE == 'TRAIN_CORPUS') {
    def cropsDir = new File(CROPS_HOME, "crops")
    def classDirs = cropsDir.exists()
            ? cropsDir.listFiles({ f -> f.isDirectory() } as java.io.FileFilter) : null
    if (classDirs == null || classDirs.length == 0) {
        Dialogs.showErrorMessage("Classify cells",
                "No labelled crops at\n${cropsDir}\n\n" +
                "Label cells, put them inside a Training/Validation rectangle, and run\n" +
                "'Export labelled cells' once per slide. That is what fills this folder.")
        return
    }
    def counts = classDirs.collectEntries { [it.getName(), it.listFiles({ f -> f.getName().endsWith(".png") }
            as java.io.FileFilter)?.length ?: 0] }
    int total = counts.values().sum() as int
    println "=== Training on the whole corpus: ${total} labelled crop(s) in ${counts.size()} class(es) ==="
    counts.sort { -it.value }.each { cls, n -> println "  ${n}  ${cls}" }
    if (total < 20) {
        Dialogs.showErrorMessage("Classify cells",
                "Only ${total} labelled crop(s) in the corpus.\n\n" +
                "Aim for 40-60 per cell type, gathered from more than one slide.")
        return
    }
    def corpus = new File(BAL_DIR, "corpus.parquet")
    if (REUSE && corpus.exists()) {
        println "Reusing ${corpus.getName()} -- untick 'reuse' after exporting more crops."
        // Absolute, because commands run with BAL_DIR as their working directory -- a bare
        // "crops" would always mean the default folder, whatever the export was told.
    } else if (run([python, "embed_cells.py", "--crops", cropsDir.getAbsolutePath(),
                    "--out", "corpus.parquet"]) != 0) {
        Dialogs.showErrorMessage("Classify cells", "Embedding the corpus failed -- see the log.")
        return
    }
    def trainCmd = [python, "classify_cells.py", "train", "--cells", "corpus.parquet"]
    if (CLASSIFIER != null) trainCmd += ["--model", CLASSIFIER]
    if (run(trainCmd) != 0) {
        Dialogs.showErrorMessage("Classify cells", "Training on the corpus failed -- see the log.")
        return
    }
    println ""
    println "The classifier is saved. Open any slide, segment it, and run the predict task to"
    println "apply it -- the crops are cut from whichever image is open, so it travels."
    println "Correct what it gets wrong, export those cells again, and retrain: the corpus"
    println "grows, the model is refitted from scratch over all of it."
    return
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
BAL_EXPORT_CROPS = true          // the pixels the transformer sees, cut from THIS image
evaluate(PRE + new File(scriptDir, "qupath_export_cells.groovy").text)

// --- 2. embed the crops with the transformer ---------------------------------
def parquet = new File(BAL_DIR, "cells.parquet")
if (REUSE && parquet.exists()) {
    println "Reusing existing embeddings (${parquet.getName()}) -- untick 'reuse' after re-segmenting."
} else {
    // --manifest, never --geojson: the geojson path reads pixels from one hard-coded zarr,
    // so on any other slide it embeds unrelated tissue without saying so.
    if (run([python, "embed_cells.py", "--manifest", "cells_manifest.csv", "--out", "cells.parquet"]) != 0) {
        Dialogs.showErrorMessage("Classify cells", "Embedding failed -- see the log.")
        return
    }
}

// --- 3. explore / train / predict --------------------------------------------
def cmd = [python, "classify_cells.py"]
if (MODE == 'EXPLORE')      cmd += ["explore", "--cells", "cells.parquet", "--clusters", String.valueOf(CLUSTERS)]
else if (MODE == 'PREDICT') cmd += ["predict", "--cells", "cells.parquet"]
else                        cmd += ["train", "--cells", "cells.parquet"]
// Without this, predict would re-score the cells you classified by hand and the import
// would replace each label with a guess -- destroying the only data anyone spent time on.
if (MODE == 'PREDICT' && OVERWRITE) cmd += ["--overwrite"]
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
