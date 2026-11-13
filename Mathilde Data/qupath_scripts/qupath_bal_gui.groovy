/**
 * BAL cell segmentation -- graphical front end.
 *
 * Opens a dialog to set everything up, then runs the detection and (optionally) the
 * feature measurement, so no script editing is needed for day-to-day use.
 *
 * Run it from Automate > User scripts (or Project scripts) > qupath_bal_gui.
 *
 * It drives the other two scripts rather than duplicating them:
 *   qupath_cellpose_detection.groovy   -- Cellpose-SAM segmentation
 *   qupath_cell_features.groovy        -- the notebook's 12 features per cell
 * Both still run standalone with their own defaults; this just passes BAL_* overrides.
 * All three files must sit in the same folder.
 *
 * Your choices are remembered between runs (stored in QuPath's preferences).
 */

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.ScrollPane
import javafx.scene.control.TitledPane
import javafx.scene.layout.VBox
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.control.TextField
import javafx.scene.control.ButtonType
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import qupath.fx.dialogs.Dialogs
import java.util.prefs.Preferences

// --- where the sibling scripts live -----------------------------------------
def thisFile = null
try {
    thisFile = new File(getClass().protectionDomain.codeSource.location.toURI())
} catch (ignored) { }
def scriptDir = null
// QuPath exposes the running script's own path as 'qupath.script.file' -- as a binding
// variable when run from the editor, and as a system property in some contexts.
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file") ?: (thisFile?.absolutePath)
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
// Fall back to the user scripts folder, which is where these normally live.
if (scriptDir == null || !new File(scriptDir, "qupath_cellpose_detection.groovy").exists()) {
    def guess = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")
    if (new File(guess, "qupath_cellpose_detection.groovy").exists()) scriptDir = guess
}
if (scriptDir == null || !new File(scriptDir, "qupath_cellpose_detection.groovy").exists()) {
    Dialogs.showErrorMessage("BAL segmentation",
            "Could not find qupath_cellpose_detection.groovy next to this script.\n" +
            "Keep all three BAL scripts in the same folder.")
    return
}

// Machine-specific paths. Only used for the folder the classifier boxes default to and
// browse from; everything else the dialog needs is passed to the workers, which read their
// own copy. The literal is a fallback for a fresh machine with no bal_config.
def CFG = [:]
try {
    def cfgFile = new File(scriptDir, "bal_config.groovy")
    if (cfgFile.exists()) CFG = evaluate(cfgFile) ?: [:]
} catch (Exception ignored) { }
def CLASSIFY_DIR = new File(CFG.balClassifyDir ?: "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify")

// --- remembered settings -----------------------------------------------------
def prefs = Preferences.userRoot().node("bal_segmentation")
// Saved values win over the defaults written below, so changing a default here has no
// effect on anyone who has already run the dialog. Bump this when a default changes in a
// way that matters, and the stale training settings are dropped once.
final int DEFAULTS_VERSION = 3
if (prefs.getInt("defaultsVersion", 1) < DEFAULTS_VERSION) {
    // "model" is cleared too: the default moved to Cellpose-SAM, and a remembered "cyto3" is
    // indistinguishable from a deliberate choice, so the new default would never be seen.
    ["epochs", "lr", "weightDecay", "trainSize", "model"].each { prefs.remove(it) }
    prefs.putInt("defaultsVersion", DEFAULTS_VERSION)
    println "Defaults updated: the model is now Cellpose-SAM (cpsam), and the training settings"
    println "follow Cellpose's own fine-tuning values. Pick cyto3 again if you prefer it."
}

// --- the models on offer ------------------------------------------------------
// Every entry here segments SOMA (whole-cell) outlines. Nuclei are a separate, optional
// pass below, because they are a different target with a different model and their own
// ground truth -- conflating the two is what made "cyto3 + InstanSeg nuclei" a model in
// its own right, which it never really was.
//
// InstanSeg was removed: it cannot be fine-tuned from QuPath, so a bad nucleus mask could
// be seen but not fixed. Cellpose's nuclei model goes through the same correct-export-train
// loop as everything else.
def MODELS = [
    "Cellpose-SAM (cpsam)": [
        engine: 'cellpose', arg: 'cpsam',
        desc: "DEFAULT. Whole cells, cellpose 4. SCALE-FREE, so the cell\n" +
              "diameter is ignored and nothing has to be measured first.\n" +
              "Matches the notebook exactly (106 cells on the benchmark).\n" +
              "Merges dense clumps that cyto3 resolves, and is 2-3x slower."],
    "Cellpose cyto3 (whole cells)": [
        engine: 'cellpose', arg: 'cyto3', diameter: 58,
        desc: "Whole cells, cellpose 3. SPLITS CLUMPS BETTER than cpsam: on a\n" +
              "clumpy test region it found 63 cells against cpsam's 40, and\n" +
              "it is 2-3x faster. Switch to this for crowded smears, and on a\n" +
              "machine with no GPU. Needs the cell diameter (58 px here)."],
    "Cellpose cyto2 (whole cells)": [
        engine: 'cellpose', arg: 'cyto2', diameter: 58,
        desc: "Whole cells, the older cellpose 2 model. Similar to cyto3\n" +
              "on this slide (71 vs 63 objects on the clumpy region).\n" +
              "Worth trying if cyto3 over-splits."],
    "Custom Cellpose model...": [
        engine: 'cellpose', arg: null,
        desc: "A soma model you fine-tuned yourself. You'll be asked for the\n" +
              "model file the moment you pick this. A name containing 'cpsam'\n" +
              "is run in the cellpose 4 environment, anything else in 3.x."],
]

// The optional nucleus pass. Same engine, same training loop -- only the target differs.
def NUCLEUS_MODELS = [
    "Cellpose nuclei": [
        arg: 'nuclei', diameter: 40,
        desc: "Cellpose's nuclei model. diam_mean is 17 here, not the 30 the\n" +
              "cyto models use, so give it the NUCLEUS size, not the cell size."],
    "Custom nucleus model...": [
        arg: null, diameter: 40,
        desc: "A nuclei model you fine-tuned from corrected nucleus masks."],
]

def modelBox = new ComboBox<String>()
modelBox.getItems().addAll(MODELS.keySet())
// The old default named a model that no longer exists; fall back rather than show blank.
def savedModel = prefs.get("model", "Cellpose-SAM (cpsam)")
modelBox.getSelectionModel().select(MODELS.containsKey(savedModel) ? savedModel : "Cellpose-SAM (cpsam)")
def modelDesc = new Label()
modelDesc.setWrapText(true)
modelDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-accent;")

// --- the custom model file ---------------------------------------------------
// Asked for the moment "Custom Cellpose model..." is picked, not after OK: you should be
// able to see which model you are about to run, and whether it is a cpsam fine-tune, while
// the dialog is still open and the other settings can still be changed to match.
def CUSTOM_MODEL = "Custom Cellpose model..."
def customModel = null
def rememberedCustom = prefs.get("customModel", "")
if (rememberedCustom) {
    def f = new File(rememberedCustom)
    if (f.exists()) customModel = f        // gone from disk = treat as not chosen
}
// Our training script names cpsam fine-tunes "bal_cpsam_..." / "bal_ft-<cpsam parent>",
// so the filename is what tells us which cellpose environment the model needs.
def customIsSam = { customModel != null && customModel.getName().toLowerCase().contains("cpsam") }
def pickCustomModel = {
    def start = customModel?.getParentFile()
    if (start == null || !start.exists()) {
        def modelsDir = getProject() != null ? new File(buildFilePath(PROJECT_BASE_DIR, "models")) : null
        start = (modelsDir != null && modelsDir.exists()) ? modelsDir : null
    }
    // File pickers live on FileChoosers, not Dialogs. Signature here is
    // promptForFile(Window, String title, File initialDir, ExtensionFilter...).
    def filter = new javafx.stage.FileChooser.ExtensionFilter("Cellpose models", "*.cpm", "*")
    qupath.fx.dialogs.FileChoosers.promptForFile(
            null, "Choose a fine-tuned Cellpose model", start, filter)
}
def modelBrowse = new javafx.scene.control.Button("Browse...")
def modelRow = new GridPane()
modelRow.setHgap(6)
modelRow.add(modelBox, 0, 0)
modelRow.add(modelBrowse, 1, 0)
GridPane.setHgrow(modelBox, Priority.ALWAYS)

// --- the optional nucleus pass -----------------------------------------------
def nucleiCheck = new CheckBox("Also segment nuclei, and pair them into cell objects")
nucleiCheck.setSelected(prefs.getBoolean("nuclei", true))
def nucleusBox = new ComboBox<String>()
nucleusBox.getItems().addAll(NUCLEUS_MODELS.keySet())
def savedNucleus = prefs.get("nucleusModel", "Cellpose nuclei")
nucleusBox.getSelectionModel().select(NUCLEUS_MODELS.containsKey(savedNucleus) ? savedNucleus : "Cellpose nuclei")
def nucleusDiameterField = new TextField(prefs.get("nucleusDiameter", "40"))
def customNucleus = null
def rememberedNucleus = prefs.get("customNucleusModel", "")
if (rememberedNucleus) {
    def f = new File(rememberedNucleus)
    if (f.exists()) customNucleus = f
}
def nucleusBrowse = new javafx.scene.control.Button("Browse...")
def nucleusRow = new GridPane()
nucleusRow.setHgap(6)
nucleusRow.add(nucleusBox, 0, 0)
nucleusRow.add(nucleusBrowse, 1, 0)
GridPane.setHgrow(nucleusBox, Priority.ALWAYS)
def nucleusDesc = new Label()
nucleusDesc.setWrapText(true)
nucleusDesc.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-accent;")

// Which ground truth the export writes and the trainer reads. Soma and nuclei are separate
// corpora over the same pixels, so this picks one; it is never both.
def targetBox = new ComboBox<String>()
targetBox.getItems().addAll("Soma (whole cells)", "Nuclei")
targetBox.getSelectionModel().select(prefs.get("target", "Soma (whole cells)"))

def outputBox = new ComboBox<String>()
outputBox.getItems().addAll("Detections", "Annotations", "Cells")
outputBox.getSelectionModel().select(prefs.get("output", "Detections"))

def regionBox = new ComboBox<String>()
regionBox.getItems().addAll("Selected objects only", "All annotations in image")
regionBox.getSelectionModel().select(prefs.get("region", "Selected objects only"))

// What the dialog is being used for. Everything shares the model and diameter settings;
// the rest of the fields enable themselves according to the task.
def taskBox = new ComboBox<String>()
taskBox.getItems().addAll(
        "Segment cells",
        "Pair soma + nuclei into cells",
        "Convert selected objects (annotation <-> detection)",
        "Prepare training regions",
        "Train model on corrected regions",
        "Classify cells: explore (no labels yet)",
        "Classify cells: train classifier",
        "Classify cells: train on labelled corpus (all slides)",
        "Classify cells: predict",
        "Export labelled cells (segmentation + classifier)")
taskBox.getSelectionModel().select(prefs.get("task", "Segment cells"))
def taskDesc = new Label()
taskDesc.setWrapText(true)
taskDesc.setStyle("-fx-font-size: 11px;")

def pixelField = new TextField(prefs.get("pixelSize", "0.137"))
def diameterField = new TextField(prefs.get("diameter", "58"))
// --- training-only fields ---
def trainRegionsField = new TextField(prefs.get("trainRegions", "12288,20480; 45056,24576; 32768,18432"))
def valRegionsField = new TextField(prefs.get("valRegions", "24576,45056"))
def trainSizeField = new TextField(prefs.get("trainSize", "512"))
// Cellpose's own fine-tuning defaults (its training dialog), NOT its command-line defaults.
def epochsField = new TextField(prefs.get("epochs", "100"))
def lrField = new TextField(prefs.get("lr", "0.1"))
def weightDecayField = new TextField(prefs.get("weightDecay", "0.0001"))
def sgdCheck = new CheckBox("SGD (cellpose 3 models). Unchecked = AdamW, which cpsam needs")
// cpsam only: cellpose 3's CLI has no --save_each.
def saveEachCheck = new CheckBox("Keep best + last checkpoint (cpsam only; prunes as it trains)")
saveEachCheck.setSelected(prefs.getBoolean("saveEach", true))
def saveEveryField = new TextField(prefs.get("saveEvery", "5"))
Boolean lastTrainModelWasSam = null
sgdCheck.setSelected(prefs.getBoolean("sgd", true))
// Shared corpus: leave empty to use this project's own rectangles only.
// Pre-filled with a sensible default so the export task works without hunting for a path.
def defaultCorpus = "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/cellpose_ground_truth"
def corpusField = new TextField(prefs.get("corpus", ""))
corpusField.setPromptText("empty = this project only; set a folder to pool slides")
// --- conversion-only fields ---
// Annotations are the editable type and detections the classifiable one, so moving between
// them is a step in its own right rather than something to re-segment for.
def convertToBox = new ComboBox<String>()
convertToBox.getItems().addAll("Annotations (editable)", "Detections (classifiable)")
convertToBox.getSelectionModel().select(prefs.get("convertTo", "Annotations (editable)"))
def convertScopeBox = new ComboBox<String>()
convertScopeBox.getItems().addAll("Selected objects", "Every object in the image")
convertScopeBox.getSelectionModel().select(prefs.get("convertScope", "Selected objects"))
def convertNucleiCheck = new CheckBox("Cells: keep each nucleus as its own 'Nucleus' annotation")
convertNucleiCheck.setSelected(prefs.getBoolean("convertNuclei", true))
def convertCellsCheck = new CheckBox("Include cell objects -- DISCARDS their nucleus outline")
convertCellsCheck.setSelected(prefs.getBoolean("convertCells", false))
def convertDesc = new Label()
convertDesc.setWrapText(true)
convertDesc.setStyle("-fx-font-size: 11px;")

def clustersField = new TextField(prefs.get("clusters", "5"))
// TWO boxes, because saving and loading are different questions. Training WRITES a file that
// need not exist yet and that you want to NAME, so one model per experiment survives the
// next retrain; predicting READS one that must already exist. A single box made those the
// same text, so training then predicting only worked as long as nobody touched it -- and
// naming a new model silently redirected the next prediction to a file not yet written.
def modelFilter = new javafx.stage.FileChooser.ExtensionFilter("Classifiers", "*.joblib", "*")
def startDir = { CLASSIFY_DIR.exists() ? CLASSIFY_DIR : null }
// Old single-box preference seeds both, so nobody loses the path they had.
def legacyModel = prefs.get("classifier", "")

def classifierOutField = new TextField(prefs.get("classifierOut", legacyModel))
classifierOutField.setPromptText("name or path; empty = bal_classify/classifier.joblib")
def classifierOutBrowse = new javafx.scene.control.Button("Save as...")
classifierOutBrowse.setOnAction({
    // A save prompt, not an open prompt: the file being named usually does not exist yet,
    // and an open prompt cannot express that.
    def f = qupath.fx.dialogs.FileChoosers.promptToSaveFile(null, "Save the classifier as",
            new File(startDir() ?: CLASSIFY_DIR, "classifier.joblib"), modelFilter)
    if (f != null) classifierOutField.setText(f.getAbsolutePath())
} as javafx.event.EventHandler)
def classifierOutRow = new GridPane()
classifierOutRow.setHgap(6)
classifierOutRow.add(classifierOutField, 0, 0)
classifierOutRow.add(classifierOutBrowse, 1, 0)
GridPane.setHgrow(classifierOutField, Priority.ALWAYS)

def classifierInField = new TextField(prefs.get("classifierIn", legacyModel))
classifierInField.setPromptText("empty = bal_classify/classifier.joblib")
def classifierInBrowse = new javafx.scene.control.Button("Browse...")
classifierInBrowse.setOnAction({
    def f = qupath.fx.dialogs.FileChoosers.promptForFile(null, "Choose a saved classifier",
            startDir(), modelFilter)
    if (f != null) classifierInField.setText(f.getAbsolutePath())
} as javafx.event.EventHandler)
def classifierInRow = new GridPane()
classifierInRow.setHgap(6)
classifierInRow.add(classifierInField, 0, 0)
classifierInRow.add(classifierInBrowse, 1, 0)
GridPane.setHgrow(classifierInField, Priority.ALWAYS)

// What you type becomes a path. A bare name is the common case -- the point of naming a
// model is to keep several of them, not to retype a folder -- so it is resolved against
// bal_classify/ and given the .joblib extension if it has none. Returns null for empty,
// which is how the worker is told to use its own default.
def resolveModelPath = { String raw ->
    def t = raw?.trim()
    if (!t) return null
    def f = new File(t)
    if (!f.isAbsolute()) f = new File(CLASSIFY_DIR, t)
    if (!f.getName().toLowerCase().endsWith(".joblib"))
        f = new File(f.getParentFile(), f.getName() + ".joblib")
    f
}
def reuseCheck = new CheckBox("Reuse existing embeddings (skip the transformer; only if cells are unchanged)")
reuseCheck.setSelected(prefs.getBoolean("reuseEmb", false))
// Off by default and deliberately blunt: predicting a cell you labelled by hand replaces
// your label with a guess, and afterwards nothing tells them apart.
def overwriteCheck = new CheckBox("Re-predict cells I classified by hand (REPLACES those labels)")
overwriteCheck.setSelected(false)

def corpusBrowse = new javafx.scene.control.Button("Browse...")
corpusBrowse.setOnAction({
    def start = corpusField.getText()?.trim()
    def dir = qupath.fx.dialogs.FileChoosers.promptForDirectory(
            "Folder for pooled training data",
            start ? new File(start) : new File(defaultCorpus).getParentFile())
    if (dir != null) corpusField.setText(dir.getAbsolutePath())
} as javafx.event.EventHandler)
def corpusRow = new GridPane()
corpusRow.setHgap(6)
corpusRow.add(corpusField, 0, 0)
corpusRow.add(corpusBrowse, 1, 0)
GridPane.setHgrow(corpusField, Priority.ALWAYS)

// One export, two outputs, each with its own switch and its own folder. They were two
// separate tasks until now, and the older one drove the extension's exporter, which drops
// every classified cell from the mask -- so labelling a cell for the classifier and using it
// for segmentation were mutually exclusive. Both are on by default because the whole point
// is that one set of hand-labelled cells serves both jobs.
def exportSegCheck = new CheckBox("Segmentation masks (Cellpose ground truth)")
exportSegCheck.setSelected(prefs.getBoolean("exportSeg", true))
def exportCropsCheck = new CheckBox("Classifier crops (cell-type labels)")
exportCropsCheck.setSelected(prefs.getBoolean("exportCrops", true))

// Where crops/ and labels.csv go. embed_cells.py reads labels.csv from BESIDE the crops
// folder, so this names the folder that holds both, not the crops folder itself.
def cropsDirField = new TextField(prefs.get("cropsDir", ""))
cropsDirField.setPromptText("empty = ${CLASSIFY_DIR}")
def cropsDirBrowse = new javafx.scene.control.Button("Browse...")
cropsDirBrowse.setOnAction({
    def start = cropsDirField.getText()?.trim()
    def dir = qupath.fx.dialogs.FileChoosers.promptForDirectory(
            "Folder to hold crops/ and labels.csv",
            start ? new File(start) : (CLASSIFY_DIR.exists() ? CLASSIFY_DIR : null))
    if (dir != null) cropsDirField.setText(dir.getAbsolutePath())
} as javafx.event.EventHandler)
def cropsDirRow = new GridPane()
cropsDirRow.setHgap(6)
cropsDirRow.add(cropsDirField, 0, 0)
cropsDirRow.add(cropsDirBrowse, 1, 0)
GridPane.setHgrow(cropsDirField, Priority.ALWAYS)
def tileField = new TextField(prefs.get("tileSize", "1024"))
// How far outside each region Cellpose may look, so a cell crossing the region edge is
// segmented whole rather than clipped to the edge and then measured as a whole cell.
def regionPadField = new TextField(prefs.get("regionPadUm", "20"))
def expansionField = new TextField(prefs.get("expansion", "2.0"))
def featuresCheck = new CheckBox("Measure the notebook's 12 features per cell")
featuresCheck.setSelected(prefs.getBoolean("features", true))
def clumpCheck = new CheckBox("Flag oversized objects as 'Clump/debris' above")
clumpCheck.setSelected(prefs.getBoolean("clumpOn", true))
def clumpField = new TextField(prefs.get("clumpArea", "300"))
clumpField.setPrefColumnCount(5)

// The collapsible sections, and which of them the current task actually uses. Declared
// here because syncEnabled fills `groupRelevant` and is defined before the panes exist.
def groupPanes = [:]
def groupRelevant = [:]

def syncEnabled = {
    def task = taskBox.getValue()
    boolean segmenting = task == "Segment cells"
    boolean preparing = task == "Prepare training regions"
    boolean training = task == "Train model on corrected regions"
    boolean classifying = task.startsWith("Classify cells")
    boolean clsExplore = task.endsWith("explore (no labels yet)")
    boolean clsTrain = task.endsWith("train classifier")
    boolean clsCorpus = task.endsWith("corpus (all slides)")
    boolean clsPredict = task.endsWith("predict")
    boolean exportLabels = task.startsWith("Export labelled cells")
    boolean pairing = task == "Pair soma + nuclei into cells"
    boolean converting = task.startsWith("Convert selected objects")

    taskDesc.setText(
        segmenting ? "Segment cells in the selected region(s) and measure them." :
        pairing    ? "Pair outlines ALREADY in the image into cell objects: every annotation\n" +
                     "that is not a region rectangle counts as a soma, and any classed\n" +
                     "'Nucleus' is matched into the soma that contains it. Use this after\n" +
                     "correcting the outlines by hand. The annotations are consumed." :
        preparing  ? "Drop Training/Validation rectangles and segment inside them as editable\n" +
                     "annotations. You then correct every outline by hand -- densely: a cell you\n" +
                     "leave out teaches the model that it is background." :
                     "Fine-tune the chosen model. With a corpus folder set, it trains on the\n" +
                     "pooled data from every slide; empty, it uses this project's rectangles.\n" +
                     "Correct the outlines FIRST -- training on uncorrected output just\n" +
                     "teaches the model to repeat itself.")
    if (clsExplore)
        taskDesc.setText("Embed every cell with the Phikon-v2 pathology transformer and cluster them.\n" +
                         "Writes bal_classify/clusters.png showing the most typical cells of each\n" +
                         "cluster -- name those, label examples, then come back and train.")
    if (clsTrain)
        taskDesc.setText("Train a classifier on the cells you labelled by hand IN THIS IMAGE, using\n" +
                         "the transformer's embeddings. Reports cross-validated per-class recall,\n" +
                         "saves the classifier, then classifies the unlabelled cells. Good for one\n" +
                         "slide; for a model to use on other slides, train on the corpus instead.")
    if (clsCorpus)
        taskDesc.setText("Train on every labelled crop exported so far, from all slides -- the\n" +
                         "bal_classify/crops folder that 'Export labelled cells' fills. Needs no\n" +
                         "cells in the open image. This is the one that gives a classifier worth\n" +
                         "applying to a slide it has never seen; one slide's labels only fit that\n" +
                         "slide's staining. Training is always a fresh fit over ALL the labels.")
    if (clsPredict)
        taskDesc.setText("Apply a saved classifier to the cells in this image. Classes you set by\n" +
                         "hand are kept unless you tick the box below. Predictions come back as\n" +
                         "classifications you can correct, then retrain.")
    if (converting)
        taskDesc.setText("Turn the selected objects into the other type, in place. Only annotations\n" +
                         "can be reshaped with the brush, and only detections/cells are classified,\n" +
                         "so this is how you move between correcting and classifying. Class, name,\n" +
                         "measurements and object ID all survive.")

    // segmentation-only
    [outputBox, regionBox, featuresCheck, clumpCheck, tileField, regionPadField].each { it.setDisable(!segmenting) }
    // The nucleus pass belongs to segmentation; pairing uses the models only to describe
    // what it is matching, so the fields stay off there.
    nucleiCheck.setDisable(!segmenting)
    boolean withNuclei = nucleiCheck.isSelected()
    boolean customNucleusPicked = nucleusBox.getValue() == "Custom nucleus model..."
    nucleusBox.setDisable(!segmenting || !withNuclei)
    nucleusBrowse.setDisable(!segmenting || !withNuclei || !customNucleusPicked)
    nucleusDiameterField.setDisable(!segmenting || !withNuclei)
    def nm = NUCLEUS_MODELS[nucleusBox.getValue()]
    nucleusDesc.setText(!withNuclei ? "" : (nm?.desc ?: "") + (customNucleusPicked
            ? "\n\n" + (customNucleus != null ? "Using: ${customNucleus.getName()}"
                                             : "No nucleus model chosen yet -- click Browse...")
            : ""))
    // Cells need an inner boundary to be worth anything. Without the nucleus pass the inner
    // boundary would be the soma itself and the outer an invented ring, which turns nc_ratio
    // into a function of cell diameter (0.445 for a 7.8 um cell, 0.78 for a 30 um one) --
    // backwards from the real ratio. So the option is only offered when a nucleus exists.
    boolean cellsAllowed = segmenting && withNuclei
    if (!cellsAllowed && outputBox.getValue() == "Cells")
        outputBox.getSelectionModel().select("Detections")
    outputBox.getItems().retainAll(cellsAllowed ? ["Detections", "Annotations", "Cells"]
                                                : ["Detections", "Annotations"])
    if (cellsAllowed && !outputBox.getItems().contains("Cells")) outputBox.getItems().add("Cells")
    expansionField.setDisable(!segmenting || outputBox.getValue() != "Cells")
    clumpField.setDisable(!segmenting || !clumpCheck.isSelected() || !featuresCheck.isSelected())
    // training-only
    [trainRegionsField, valRegionsField, trainSizeField].each { it.setDisable(!preparing) }
    [epochsField, lrField, weightDecayField, sgdCheck].each { it.setDisable(!training) }
    targetBox.setDisable(!(training || exportLabels))
    // The two switches belong to the export only. Each folder follows its own switch, so a
    // path you cannot write to is never editable: training reads the masks folder, which is
    // why that one stays live for training too.
    exportSegCheck.setDisable(!exportLabels)
    exportCropsCheck.setDisable(!exportLabels)
    boolean wantSeg = exportSegCheck.isSelected(), wantCrops = exportCropsCheck.isSelected()
    corpusField.setDisable(!(training || (exportLabels && wantSeg)))
    corpusBrowse.setDisable(!(training || (exportLabels && wantSeg)))
    // Also live for corpus training, which READS the folder the export wrote crops into.
    // One field for both directions, so they cannot drift apart.
    boolean cropsFolderUsed = (exportLabels && wantCrops) || clsCorpus
    cropsDirField.setDisable(!cropsFolderUsed)
    cropsDirBrowse.setDisable(!cropsFolderUsed)
    // conversion-only
    boolean toAnnotations = convertToBox.getValue().startsWith("Annotations")
    convertToBox.setDisable(!converting)
    convertScopeBox.setDisable(!converting)
    // Each cell option belongs to one direction only: a nucleus can be SPLIT OUT into an
    // annotation, but there is nowhere to put it in a plain detection.
    convertNucleiCheck.setDisable(!converting || !toAnnotations)
    convertCellsCheck.setDisable(!converting || toAnnotations)
    convertDesc.setText(!converting ? "" : toAnnotations
            ? "Editable outlines you can fix with the Brush (B) or Wand (W). Training and\n" +
              "Validation rectangles are never converted. Save (Ctrl+S) before exporting or\n" +
              "training -- both read the SAVED data."
            : "Classifiable objects. The classifier takes CELL objects if the image has any,\n" +
              "otherwise every detection -- so avoid leaving a mix, or the plain detections\n" +
              "are silently skipped.")
    clustersField.setDisable(!clsExplore)
    // Each box belongs to one direction: training writes, predicting reads. Showing both as
    // editable would re-create the confusion they were split up to remove.
    boolean saving = clsTrain || clsCorpus
    classifierOutField.setDisable(!saving); classifierOutBrowse.setDisable(!saving)
    classifierInField.setDisable(!clsPredict); classifierInBrowse.setDisable(!clsPredict)
    reuseCheck.setDisable(!classifying)
    overwriteCheck.setDisable(!clsPredict)
    if (!clsPredict) overwriteCheck.setSelected(false)      // never carries over by accident
    // Classification works on whatever cells already exist, so the segmentation model and
    // its settings are irrelevant here. The model row itself is switched off at the END of
    // this closure: the model-description lines below would overwrite the explanation.
    if (classifying || exportLabels || converting) {
        [outputBox, regionBox, featuresCheck, clumpCheck, tileField, regionPadField, expansionField,
         clumpField, pixelField, diameterField].each { it.setDisable(true) }
    }

    def m = MODELS[modelBox.getValue()]
    boolean isCustom = modelBox.getValue() == CUSTOM_MODEL
    modelBrowse.setDisable(!isCustom)
    // A custom model inherits its parent's rules: a cpsam fine-tune is still scale-free and
    // still needs AdamW at 1e-5. Knowing the file already (it is chosen when the dropdown
    // changes, not at OK) is what lets the fields below follow it.
    boolean samModel = (m?.arg == 'cpsam') || (isCustom && customIsSam())
    // Training settings depend on WHICH model: cpsam is a 300M-parameter transformer trained
    // at lr 1e-5 with AdamW; cyto3 is a 6.6M UNet fine-tuned at lr 0.1 with SGD. Mixing them
    // destroys the model, so the fields follow the model choice.
    if (training) {
        boolean sam = samModel
        if (lastTrainModelWasSam == null || lastTrainModelWasSam != sam) {
            lrField.setText(sam ? "0.00001" : "0.1")
            weightDecayField.setText(sam ? "0.1" : "0.0001")
            sgdCheck.setSelected(!sam)
            lastTrainModelWasSam = sam
        }
    }
    saveEachCheck.setDisable(!training || !samModel)
    saveEveryField.setDisable(!training || !samModel || !saveEachCheck.isSelected())
    modelDesc.setText(m?.desc ?: "")
    // Diameter only means something for cyto2/cyto3-style models, which rescale the image by
    // diam_mean/diameter. cpsam is scale-free (cellpose 4 calls --diam_mean deprecated and
    // unused), so the field stays off for it in every task, including training.
    boolean usesDiameter = !samModel
    diameterField.setDisable(!usesDiameter)
    if (isCustom)
        modelDesc.setText((m?.desc ?: "") + "\n\n" + (customModel != null
                ? "Using: ${customModel.getName()}\nRuns in ${customIsSam() ? 'cellpose 4 (cpsam): scale-free, so cell diameter is off' : 'cellpose 3 (cyto-style): cell diameter applies'}"
                : "No model file chosen yet -- click Browse..."))

    // LAST, so the description survives: these tasks never segment anything, they act on the
    // cells already in the image, so the whole model row is switched off rather than left
    // looking like a choice that matters.
    // Which sections this task uses. The task listener collapses the rest, so the dialog
    // only ever shows the controls that do something -- it had grown taller than the screen.
    groupRelevant["Segmentation"] = segmenting
    groupRelevant["Resolution and tiling"] = segmenting
    groupRelevant["Measurements"] = segmenting || pairing
    groupRelevant["Convert objects"] = converting
    groupRelevant["Training regions"] = preparing
    groupRelevant["Fine-tuning"] = training
    // Corpus training reads the crops folder, which lives in this section -- a section
    // nobody claims stays shut, and the field would look missing.
    groupRelevant["Ground truth"] = training || exportLabels || clsCorpus
    groupRelevant["Classification"] = classifying

    modelBox.setDisable(classifying || exportLabels || converting)
    if (classifying || exportLabels || converting) {
        modelBrowse.setDisable(true)
        modelDesc.setText(converting
                ? "Nothing is segmented here. The objects already exist and only change\n" +
                  "type, keeping their outline, class and measurements exactly as they are."
                : "Works on the cells already in the image -- no segmentation model is\n" +
                  "used here. It takes the CELL objects if there are any, otherwise every\n" +
                  "detection. Annotations are never included, and there is no way to\n" +
                  "restrict it to a selection.")
    }
}
def chooseCustomModel = {
    def f = pickCustomModel()
    if (f != null) {
        customModel = f
        prefs.put("customModel", f.getAbsolutePath())
    }
    syncEnabled()
    f
}
modelBrowse.setOnAction({ chooseCustomModel() } as javafx.event.EventHandler)

// Same deal for the nucleus model: pick the file when the entry is chosen, not at OK.
def pickNucleusModel = {
    def start = customNucleus?.getParentFile()
    if (start == null || !start.exists()) {
        def modelsDir = getProject() != null ? new File(buildFilePath(PROJECT_BASE_DIR, "models")) : null
        start = (modelsDir != null && modelsDir.exists()) ? modelsDir : null
    }
    def filter = new javafx.stage.FileChooser.ExtensionFilter("Cellpose models", "*.cpm", "*")
    qupath.fx.dialogs.FileChoosers.promptForFile(
            null, "Choose a fine-tuned nucleus model", start, filter)
}
def chooseNucleusModel = {
    def f = pickNucleusModel()
    if (f != null) {
        customNucleus = f
        prefs.put("customNucleusModel", f.getAbsolutePath())
    }
    syncEnabled()
    f
}
nucleusBrowse.setOnAction({ chooseNucleusModel() } as javafx.event.EventHandler)
nucleiCheck.selectedProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
nucleusBox.valueProperty().addListener({ obs, oldN, newN ->
    if (newN == "Custom nucleus model...") {
        Platform.runLater {
            if (chooseNucleusModel() == null && customNucleus == null)
                nucleusBox.getSelectionModel().select(oldN)
        }
    }
    syncEnabled()
} as javafx.beans.value.ChangeListener)

taskBox.valueProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
modelBox.valueProperty().addListener({ obs, oldModel, newModel ->
    if (newModel == CUSTOM_MODEL) {
        // runLater, not inline: the dropdown's popup is still closing, and opening a modal
        // file chooser underneath it leaves the dialog unresponsive.
        Platform.runLater {
            if (chooseCustomModel() == null && customModel == null) {
                // Cancelled with nothing chosen -- go back rather than leave the dialog
                // pointing at a model that does not exist.
                modelBox.getSelectionModel().select(oldModel)
            }
        }
    }
    syncEnabled()
} as javafx.beans.value.ChangeListener)
outputBox.valueProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
// The direction decides which of the two cell options applies, so it has to re-sync.
convertToBox.valueProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
featuresCheck.selectedProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
clumpCheck.selectedProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
// Each export switch owns a folder field, so toggling it has to re-run the enable pass or
// the folder stays greyed out after you tick its box.
exportSegCheck.selectedProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
exportCropsCheck.selectedProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
syncEnabled()

// --- layout -------------------------------------------------------------------
// One scrolling column of collapsible sections. The dialog used to be a single grid and
// had grown taller than the screen, so the lower half (training, classification) could
// not be reached at all on a laptop.
def rowOf = [:]                                   // grid -> next free row
def newGrid = {
    def g = new GridPane()
    g.setHgap(8); g.setVgap(7); g.setPadding(new Insets(10))
    rowOf[g] = 0
    g
}
def addTo = { GridPane g, String label, javafx.scene.Node node ->
    int r = rowOf[g]
    g.add(new Label(label), 0, r)
    g.add(node, 1, r)
    GridPane.setHgrow(node, Priority.ALWAYS)
    rowOf[g] = r + 1
}
def rightTo = { GridPane g, javafx.scene.Node node ->      // no label, second column
    g.add(node, 1, rowOf[g])
    rowOf[g] = rowOf[g] + 1
}
def wideTo = { GridPane g, javafx.scene.Node node ->       // spans both columns
    g.add(node, 0, rowOf[g], 2, 1)
    rowOf[g] = rowOf[g] + 1
}
def section = { String title, GridPane g ->
    def tp = new TitledPane(title, g)
    tp.setAnimated(false)                         // instant, so the dialog does not jump
    groupPanes[title] = tp
    tp
}

// Task sits outside the sections: it decides what everything else means, so it is always
// visible however much is collapsed.
def taskGrid = newGrid()
addTo(taskGrid, "Task:", taskBox)
rightTo(taskGrid, taskDesc)

def segGrid = newGrid()
addTo(segGrid, "Model:", modelRow)
rightTo(segGrid, modelDesc)
rightTo(segGrid, nucleiCheck)
addTo(segGrid, "Nucleus model:", nucleusRow)
rightTo(segGrid, nucleusDesc)
addTo(segGrid, "Nucleus diameter (px):", nucleusDiameterField)
addTo(segGrid, "Output type:", outputBox)
addTo(segGrid, "Run on:", regionBox)
addTo(segGrid, "Cell expansion (um):", expansionField)

def resGrid = newGrid()
addTo(resGrid, "Pixel size (um/px):", pixelField)
addTo(resGrid, "Tile size (px):", tileField)
addTo(resGrid, "Region padding (um):", regionPadField)
addTo(resGrid, "Cell diameter (px):", diameterField)

def measGrid = newGrid()
wideTo(measGrid, featuresCheck)
def clumpRow = new GridPane()
clumpRow.setHgap(6)
clumpRow.add(clumpCheck, 0, 0)
clumpRow.add(clumpField, 1, 0)
clumpRow.add(new Label("um2"), 2, 0)
wideTo(measGrid, clumpRow)

def convGrid = newGrid()
addTo(convGrid, "Convert to:", convertToBox)
rightTo(convGrid, convertDesc)
addTo(convGrid, "Apply to:", convertScopeBox)
rightTo(convGrid, convertNucleiCheck)
rightTo(convGrid, convertCellsCheck)

def regionsGrid = newGrid()
addTo(regionsGrid, "Training regions (x,y; ...):", trainRegionsField)
addTo(regionsGrid, "Validation regions (x,y; ...):", valRegionsField)
addTo(regionsGrid, "Region size (px):", trainSizeField)

def trainGrid = newGrid()
addTo(trainGrid, "Epochs:", epochsField)
addTo(trainGrid, "Learning rate:", lrField)
addTo(trainGrid, "Weight decay:", weightDecayField)
rightTo(trainGrid, sgdCheck)
rightTo(trainGrid, saveEachCheck)
addTo(trainGrid, "Checkpoint every N epochs:", saveEveryField)

def gtGrid = newGrid()
addTo(gtGrid, "Ground truth:", targetBox)
wideTo(gtGrid, exportSegCheck)
addTo(gtGrid, "Masks folder:", corpusRow)
wideTo(gtGrid, exportCropsCheck)
addTo(gtGrid, "Classifier data folder:", cropsDirRow)

def clsGrid = newGrid()
addTo(clsGrid, "Clusters (explore):", clustersField)
addTo(clsGrid, "Save classifier as:", classifierOutRow)
addTo(clsGrid, "Classifier to predict with:", classifierInRow)
rightTo(clsGrid, reuseCheck)
rightTo(clsGrid, overwriteCheck)

def box = new VBox(6)
box.setPadding(new Insets(10))
box.getChildren().addAll(
        taskGrid,
        new Separator(),
        section("Segmentation", segGrid),
        section("Resolution and tiling", resGrid),
        section("Measurements", measGrid),
        section("Convert objects", convGrid),
        section("Training regions", regionsGrid),
        section("Fine-tuning", trainGrid),
        section("Ground truth", gtGrid),
        section("Classification", clsGrid))

/** Open the sections this task uses, close the rest. Runs when the task changes only, so
 *  anything you open by hand afterwards stays open. */
def syncSections = {
    groupPanes.each { title, tp -> tp.setExpanded(groupRelevant[title] as boolean) }
}
taskBox.valueProperty().addListener({ a, b, c -> syncSections() } as javafx.beans.value.ChangeListener)
syncSections()

def scroller = new ScrollPane(box)
scroller.setFitToWidth(true)
scroller.setPrefViewportWidth(620)
// Tall enough to be useful, short enough to leave the OK button on screen on a laptop.
scroller.setPrefViewportHeight(520)
scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)

def response = Dialogs.builder()
        .title("BAL cell segmentation")
        .headerText("Segmentation, fine-tuning and classification for BAL slides")
        .content(scroller)
        .buttons(ButtonType.OK, ButtonType.CANCEL)
        .resizable()
        .showAndWait()

if (!response.isPresent() || response.get() != ButtonType.OK) {
    println "Cancelled."
    return
}

// --- validate ----------------------------------------------------------------
def asNumber = { TextField f, String name, double min, double max ->
    def v = f.getText()?.trim()?.replace(",", ".")
    if (!v || !v.isNumber()) throw new IllegalArgumentException("${name}: '${f.getText()}' is not a number")
    double d = v as double
    if (d < min || d > max) throw new IllegalArgumentException("${name} must be between ${min} and ${max} (got ${d})")
    d
}
// "12288,20480; 45056,24576" -> [[12288, 20480], [45056, 24576]]
def parseRegions = { TextField f, String name ->
    def out = []
    f.getText()?.split(";")?.each { part ->
        def t = part.trim()
        if (!t) return
        def xy = t.split(",")
        if (xy.length != 2 || !xy[0].trim().isInteger() || !xy[1].trim().isInteger())
            throw new IllegalArgumentException("${name}: '${t}' should be two whole numbers, x,y")
        out << [xy[0].trim() as int, xy[1].trim() as int]
    }
    out
}

def task = taskBox.getValue()
double pixelSize, tileSize, expansion, clumpArea, epochs, learningRate, trainSize, weightDecay
double regionPadUm
double nucleusDiameter
def trainRegions = [], valRegions = []
try {
    pixelSize = asNumber(pixelField, "Pixel size", 0.001, 10)
    tileSize = asNumber(tileField, "Tile size", 64, 4096)
    // 0 means "clip to the region", the old behaviour. The upper bound is deliberately
    // modest: padding is read and segmented pixels, and a pad wider than a tile would mean
    // every region costs an extra ring of tiles for cells that are then thrown away.
    regionPadUm = asNumber(regionPadField, "Region padding", 0, 100)
    expansion = asNumber(expansionField, "Cell expansion", 0, 100)
    clumpArea = clumpCheck.isSelected() ? asNumber(clumpField, "Clump area", 1, 1_000_000) : 0
    // Upper bound is the cell diameter's: a "nucleus" larger than a cell is a typo.
    nucleusDiameter = asNumber(nucleusDiameterField, "Nucleus diameter", 1, 1000)
    epochs = asNumber(epochsField, "Epochs", 1, 10000)
    // Lower bound must reach cpsam's 1e-5 (cellpose 4's default). An earlier 1e-4 floor
    // rejected the very value the dialog fills in for cpsam.
    learningRate = asNumber(lrField, "Learning rate", 0.0000001, 10)
    weightDecay = asNumber(weightDecayField, "Weight decay", 0.0, 1)
    // The learning rate has to match the optimiser -- cellpose's own defaults differ by a
    // factor of ~20 between them (SGD 0.1, RAdam/AdamW 0.005), so a mismatch trains badly.
    if (task == "Train model on corrected regions") {
        if (!sgdCheck.isSelected() && learningRate > 0.01)
            throw new IllegalArgumentException(
                    "Learning rate ${learningRate} is far too high for AdamW/RAdam.\n\n" +
                    "Cellpose uses 0.005 (its API default) or ~0.001 with that optimiser.\n" +
                    "Either lower the learning rate, or tick SGD (which pairs with 0.1).")
        if (sgdCheck.isSelected() && learningRate < 0.01)
            throw new IllegalArgumentException(
                    "Learning rate ${learningRate} is very low for SGD.\n\n" +
                    "Cellpose fine-tunes with 0.1 under SGD. Rates near 0.005 belong with\n" +
                    "AdamW/RAdam -- untick SGD if that's what you meant.")
    }
    trainSize = asNumber(trainSizeField, "Region size", 64, 4096)
    if (task == "Prepare training regions") {
        trainRegions = parseRegions(trainRegionsField, "Training regions")
        valRegions = parseRegions(valRegionsField, "Validation regions")
        if (trainRegions.isEmpty())
            throw new IllegalArgumentException("Give at least one training region, as x,y")
        if (valRegions.isEmpty())
            throw new IllegalArgumentException("Give at least one validation region -- without it " +
                    "you cannot tell whether training helped")
    }
} catch (IllegalArgumentException e) {
    Dialogs.showErrorMessage("BAL segmentation", e.getMessage())
    return
}

// --- remember for next time --------------------------------------------------
// --- resolve the chosen model ------------------------------------------------
def chosen = MODELS[modelBox.getValue()]
// Every model on offer is now a Cellpose model, so there is no engine to guard against.
def modelArg = chosen.arg
if (chosen.engine == 'cellpose' && modelArg == null) {
    // The file was chosen when the dropdown changed, so by here it should already be known.
    // Re-check it exists: it may have been deleted or renamed since, or come from prefs.
    if (customModel == null || !customModel.exists()) {
        Dialogs.showErrorMessage("BAL segmentation", customModel == null
                ? "No custom model file is chosen.\n\nClick Browse... next to the Model list."
                : "That custom model is no longer on disk:\n${customModel.getAbsolutePath()}\n\n" +
                  "Click Browse... next to the Model list and pick it again.")
        return
    }
    modelArg = customModel.getAbsolutePath()
    // Models fine-tuned from cpsam must run in the cellpose 4 environment; the name carries
    // that, since our training script writes "bal_cpsam_..." or "bal_ft-<cpsam parent>".
    BAL_MODEL_IS_SAM = customIsSam()
    println "Custom model: ${customModel.getName()} -> treating as ${BAL_MODEL_IS_SAM ? 'cellpose 4 (cpsam)' : 'cellpose 3 (cyto-style)'}"
}

// The nucleus model, resolved the same way.
def chosenNucleus = NUCLEUS_MODELS[nucleusBox.getValue()]
def nucleusArg = chosenNucleus?.arg
if (nucleiCheck.isSelected() && nucleusArg == null) {
    if (customNucleus == null || !customNucleus.exists()) {
        Dialogs.showErrorMessage("BAL segmentation", customNucleus == null
                ? "No custom nucleus model is chosen.\n\nClick Browse... next to the Nucleus model list."
                : "That nucleus model is no longer on disk:\n${customNucleus.getAbsolutePath()}")
        return
    }
    nucleusArg = customNucleus.getAbsolutePath()
    println "Custom nucleus model: ${customNucleus.getName()}"
}

prefs.put("model", modelBox.getValue())
prefs.put("output", outputBox.getValue())
prefs.put("region", regionBox.getValue())
prefs.put("pixelSize", pixelField.getText()); prefs.put("diameter", diameterField.getText())
prefs.put("tileSize", tileField.getText())
prefs.put("regionPadUm", regionPadField.getText())
prefs.put("expansion", expansionField.getText())
prefs.putBoolean("features", featuresCheck.isSelected())
prefs.putBoolean("clumpOn", clumpCheck.isSelected())
prefs.put("clumpArea", clumpField.getText())
prefs.put("task", task)
prefs.put("trainRegions", trainRegionsField.getText()); prefs.put("valRegions", valRegionsField.getText())
prefs.put("trainSize", trainSizeField.getText())
prefs.put("epochs", epochsField.getText()); prefs.put("lr", lrField.getText())
prefs.put("weightDecay", weightDecayField.getText()); prefs.putBoolean("sgd", sgdCheck.isSelected())
prefs.putBoolean("saveEach", saveEachCheck.isSelected()); prefs.put("saveEvery", saveEveryField.getText())
prefs.put("convertTo", convertToBox.getValue()); prefs.put("convertScope", convertScopeBox.getValue())
prefs.putBoolean("convertNuclei", convertNucleiCheck.isSelected())
prefs.putBoolean("convertCells", convertCellsCheck.isSelected())
prefs.put("clusters", clustersField.getText())
prefs.put("classifierOut", classifierOutField.getText())
prefs.put("classifierIn", classifierInField.getText())
prefs.putBoolean("reuseEmb", reuseCheck.isSelected())
prefs.put("corpus", corpusField.getText())
prefs.put("target", targetBox.getValue())
prefs.putBoolean("exportSeg", exportSegCheck.isSelected())
prefs.putBoolean("exportCrops", exportCropsCheck.isSelected())
prefs.put("cropsDir", cropsDirField.getText())
prefs.putBoolean("nuclei", nucleiCheck.isSelected())
prefs.put("nucleusModel", nucleusBox.getValue())
prefs.put("nucleusDiameter", nucleusDiameterField.getText())

// --- hand the settings to the worker scripts ---------------------------------
// Assigning without 'def' puts these in the shared binding, where the other scripts read them.
BAL_OUTPUT_TYPE = [Detections: 'DETECTION', Annotations: 'ANNOTATION', Cells: 'CELL'][outputBox.getValue()]
BAL_TARGETS = regionBox.getValue().startsWith("Selected") ? 'SELECTED' : 'ALL_ANNOTATIONS'
BAL_PIXEL_SIZE_UM = pixelSize
BAL_TILE_SIZE = tileSize as int
BAL_REGION_PAD_UM = regionPadUm
BAL_CELL_EXPANSION_UM = expansion
BAL_CLUMP_AREA_UM2 = clumpArea

def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.roi.interfaces.*
import qupath.lib.objects.*
import qupath.lib.objects.classes.*
import qupath.lib.regions.*
import qupath.lib.images.servers.*
import qupath.lib.measurements.*
"""

long t0 = System.currentTimeMillis()

// --- change the type of objects that already exist ---------------------------
if (task.startsWith("Convert selected objects")) {
    BAL_CONVERT_TO = convertToBox.getValue().startsWith("Annotations") ? 'ANNOTATION' : 'DETECTION'
    BAL_CONVERT_SCOPE = convertScopeBox.getValue().startsWith("Selected") ? 'SELECTED' : 'ALL'
    BAL_CONVERT_SPLIT_NUCLEI = convertNucleiCheck.isSelected()
    BAL_CONVERT_CELLS = convertCellsCheck.isSelected()
    println "=== ${task}: to ${BAL_CONVERT_TO}, ${convertScopeBox.getValue().toLowerCase()} ==="
    evaluate(PRE + new File(scriptDir, "qupath_convert_objects.groovy").text)
    def secs = (System.currentTimeMillis() - t0) / 1000.0
    println "=== finished in ${secs}s ==="
    return
}

// --- export hand-labelled cells for both pipelines ---------------------------
if (task.startsWith("Export labelled cells")) {
    BAL_EXPORT_TARGET = targetBox.getValue().startsWith("Nuclei") ? 'NUCLEI' : 'SOMA'
    BAL_EXPORT_SEG = exportSegCheck.isSelected()
    // Cell-type crops come from soma outlines; a nucleus carries no cell-type class, so the
    // worker reports that rather than writing nothing and calling it success.
    BAL_EXPORT_CROPS = exportCropsCheck.isSelected()
    if (!BAL_EXPORT_SEG && !BAL_EXPORT_CROPS) {
        Dialogs.showErrorMessage("Export labelled cells",
                "Nothing to export.\n\n" +
                "Tick 'Segmentation masks', 'Classifier crops', or both.")
        return
    }
    // Each half falls back to its own default folder rather than refusing, and says which.
    BAL_GT_DIR = corpusField.getText()?.trim() ?: ""
    BAL_CLASSIFY_DIR = cropsDirField.getText()?.trim() ?: ""
    if (BAL_EXPORT_SEG) {
        if (!BAL_GT_DIR) {
            BAL_GT_DIR = defaultCorpus
            println "No masks folder given -- using the default corpus: ${BAL_GT_DIR}"
        }
        println "Masks      -> ${BAL_GT_DIR}"
    }
    if (BAL_EXPORT_CROPS) {
        def cropsHome = new File(BAL_CLASSIFY_DIR ?: CLASSIFY_DIR.getAbsolutePath())
        println "Crops      -> ${new File(cropsHome, 'crops')}  (labels.csv beside it)"
        // The classifier reads the corpus from its own folder, so sending crops elsewhere
        // and then training on the default would train on the wrong set, silently.
        if (BAL_CLASSIFY_DIR && new File(BAL_CLASSIFY_DIR) != CLASSIFY_DIR) {
            println "  NOTE: this is not the default ${CLASSIFY_DIR}. Put the SAME folder in"
            println "  'Classifier data folder' when you run 'train on labelled corpus', or it"
            println "  will train on whatever is in the default folder instead."
        }
    }
    println "=== ${task} ==="
    evaluate(PRE + new File(scriptDir, "qupath_export_labels.groovy").text)
    def mins = (System.currentTimeMillis() - t0) / 60000.0
    println "=== finished in ${String.format('%.1f', mins)} min ==="
    Dialogs.showInfoNotification("BAL export", String.format("Export finished in %.1f min", mins))
    return
}

// --- classification tasks: they use the cells already present ----------------
if (task.startsWith("Classify cells")) {
    BAL_CLASSIFY_MODE = task.endsWith("explore (no labels yet)") ? 'EXPLORE' :
                        task.endsWith("train classifier") ? 'TRAIN' :
                        task.endsWith("corpus (all slides)") ? 'TRAIN_CORPUS' : 'PREDICT'
    BAL_PREDICT_OVERWRITE = overwriteCheck.isSelected()
    BAL_CLUSTERS = Math.max(2, (clustersField.getText()?.trim() ?: "5") as int)
    BAL_REUSE_EMBEDDINGS = reuseCheck.isSelected()
    // The same folder the export writes crops into: 'train on labelled corpus' reads them
    // back from here, so one field has to serve both or the two drift apart.
    BAL_CLASSIFY_DIR = cropsDirField.getText()?.trim() ?: ""

    // The worker takes ONE --model path and already knows whether it is writing or reading.
    // Which box that path comes from depends on the task, and this is the only place that
    // knows the task, so the choice is made here -- and the worker stays unchanged, so it
    // still runs standalone on a single BAL_CLASSIFIER.
    def modelFile = resolveModelPath(BAL_CLASSIFY_MODE == 'PREDICT'
            ? classifierInField.getText() : classifierOutField.getText())
    if (BAL_CLASSIFY_MODE == 'PREDICT') {
        // Fail here, naming the file, rather than letting Python exit on a missing path --
        // the usual cause is a model that was named but never trained.
        if (modelFile != null && !modelFile.exists()) {
            Dialogs.showErrorMessage("Classify cells",
                    "No classifier at\n${modelFile}\n\n" +
                    "Train one first with 'train classifier' or 'train on labelled corpus',\n" +
                    "or pick an existing .joblib with Browse.")
            return
        }
        def dflt = new File(CLASSIFY_DIR, "classifier.joblib")
        if (modelFile == null && !dflt.exists()) {
            Dialogs.showErrorMessage("Classify cells",
                    "No classifier at\n${dflt}\n\n" +
                    "That is the default used when 'Classifier to predict with' is empty.\n" +
                    "Train one first, or point the box at a saved .joblib.")
            return
        }
        println "Predicting with ${modelFile ?: dflt}"
    } else if (BAL_CLASSIFY_MODE != 'EXPLORE') {
        if (modelFile != null && modelFile.getParentFile() != null && !modelFile.getParentFile().exists()) {
            Dialogs.showErrorMessage("Classify cells",
                    "The folder for\n${modelFile}\ndoes not exist.\n\n" +
                    "Create it first, or give just a name to save into bal_classify/.")
            return
        }
        def target = modelFile ?: new File(CLASSIFY_DIR, "classifier.joblib")
        // Training always refits from scratch, so this really does replace the old file.
        println target.exists() ? "Will OVERWRITE the classifier at ${target}" : "Will save the classifier to ${target}"
    }
    BAL_CLASSIFIER = modelFile?.getAbsolutePath() ?: ""
    println "=== ${task} ==="
    evaluate(PRE + new File(scriptDir, "qupath_classify_cells.groovy").text)
    def mins = (System.currentTimeMillis() - t0) / 60000.0
    println "=== finished in ${String.format('%.1f', mins)} min ==="
    Dialogs.showInfoNotification("BAL classification", String.format("%s finished in %.1f min", task, mins))
    return
}

// --- pair outlines that are already in the image ------------------------------
if (task == "Pair soma + nuclei into cells") {
    BAL_PAIR_SOURCE = 'ANNOTATIONS'
    BAL_MEASURE_ROI = 'CELL'
    println "=== ${task} ==="
    evaluate(PRE + new File(scriptDir, "qupath_cells_with_nuclei.groovy").text)
    if (featuresCheck.isSelected())
        evaluate(PRE + new File(scriptDir, "qupath_cell_features.groovy").text)
    def secs = (System.currentTimeMillis() - t0) / 1000.0
    println "=== finished in ${secs}s ==="
    Dialogs.showInfoNotification("BAL segmentation", String.format("Pairing finished in %.1f s", secs))
    return
}

// --- the two training tasks leave early; segmentation continues below ---------
if (task != "Segment cells") {
    BAL_MODEL = modelArg
    // A cpsam fine-tune is as scale-free as stock cpsam, so it gets 0 too -- the dropdown
    // entry alone cannot tell us that, the chosen filename can.
    boolean samHere = (modelArg == 'cpsam') || (chosen.arg == null && customIsSam())
    BAL_DIAMETER = samHere ? 0 : (diameterField.getText().trim() as double)
    // Don't clobber the flag a custom model's filename already set above.
    if (chosen.arg != null) BAL_MODEL_IS_SAM = (modelArg == 'cpsam')

    BAL_GT_DIR = corpusField.getText()?.trim() ?: ""
    BAL_TRAIN_TARGET = targetBox.getValue().startsWith("Nuclei") ? 'NUCLEI' : 'SOMA'

    if (task == "Prepare training regions") {
        println "=== Preparing training regions: ${modelBox.getValue()}, ${trainRegions.size()} training + " +
                "${valRegions.size()} validation of ${trainSize as int} px ==="
        BAL_TRAIN_REGIONS = trainRegions
        BAL_VAL_REGIONS = valRegions
        BAL_TRAIN_SIZE = trainSize as int
        evaluate(PRE + new File(scriptDir, "qupath_prepare_training.groovy").text)
    } else {
        println "=== Training ${modelBox.getValue()} for ${epochs as int} epochs" +
                (BAL_GT_DIR ? " on the corpus at ${BAL_GT_DIR}" : " on this project's rectangles") + " ==="
        BAL_EPOCHS = epochs as int
        BAL_LR = learningRate
        BAL_WEIGHT_DECAY = weightDecay
        BAL_USE_SGD = sgdCheck.isSelected()
        BAL_SAVE_EACH = saveEachCheck.isSelected()
        BAL_SAVE_EVERY = Math.max(1, (saveEveryField.getText()?.trim() ?: "5") as int)
        evaluate(PRE + new File(scriptDir, "qupath_train_cellpose.groovy").text)
    }
    def mins = (System.currentTimeMillis() - t0) / 60000.0
    println "=== finished in ${String.format('%.1f', mins)} min ==="
    Dialogs.showInfoNotification("BAL segmentation", String.format("%s finished in %.1f min", task, mins))
    return
}

println "=== BAL segmentation: ${modelBox.getValue()}, ${outputBox.getValue()}, ${regionBox.getValue()} ==="

def worker
BAL_MODEL = modelArg                         // 'cpsam', 'cyto3', 'cyto2', or a fine-tuned model path
BAL_DIAMETER = chosen.diameter != null ? (diameterField.getText().trim() as double) : 0
if (nucleiCheck.isSelected()) {
    BAL_NUCLEUS_MODEL = nucleusArg
    BAL_NUCLEUS_DIAMETER = nucleusDiameter
    BAL_PAIR_SOURCE = 'SEGMENT'
    BAL_MEASURE_ROI = 'CELL'                 // the 12 features describe the whole cell here
    worker = "qupath_cells_with_nuclei.groovy"
} else {
    worker = "qupath_cellpose_detection.groovy"
}
evaluate(PRE + new File(scriptDir, worker).text)
if (featuresCheck.isSelected())
    evaluate(PRE + new File(scriptDir, "qupath_cell_features.groovy").text)

def secs = (System.currentTimeMillis() - t0) / 1000.0
println "=== finished in ${secs}s ==="
Dialogs.showInfoNotification("BAL segmentation", String.format("Finished in %.1f s", secs))
