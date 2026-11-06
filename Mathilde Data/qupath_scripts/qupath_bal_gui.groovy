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

// --- remembered settings -----------------------------------------------------
def prefs = Preferences.userRoot().node("bal_segmentation")
// Saved values win over the defaults written below, so changing a default here has no
// effect on anyone who has already run the dialog. Bump this when a default changes in a
// way that matters, and the stale training settings are dropped once.
final int DEFAULTS_VERSION = 2
if (prefs.getInt("defaultsVersion", 1) < DEFAULTS_VERSION) {
    ["epochs", "lr", "weightDecay", "trainSize"].each { prefs.remove(it) }
    prefs.putInt("defaultsVersion", DEFAULTS_VERSION)
    println "Training defaults updated to Cellpose's fine-tuning values (lr 0.1, 100 epochs, weight decay 1e-4)."
}

// --- the models on offer ------------------------------------------------------
// Only models that can actually run on this machine are listed. Descriptions say what
// each one segments, since nuclei and whole cells are not interchangeable.
def instansegDir = new File(System.getProperty("user.home"), "QuPath/v0.7/instanseg/downloaded")
def MODELS = [
    "cyto3 cells + InstanSeg nuclei": [
        engine: 'paired', arg: 'cyto3', diameter: 58,
        desc: "RECOMMENDED for classification. Cell outlines from cyto3 with\n" +
              "the nucleus from InstanSeg inside each one, paired into cell\n" +
              "objects. Adds a true nc_ratio, nuclear shape (lobes) and\n" +
              "cytoplasm-only colour -- the features that separate BAL types."],
    "Cellpose cyto3 (whole cells)": [
        engine: 'cellpose', arg: 'cyto3', diameter: 58,
        desc: "Whole cells only, cellpose 3. SPLITS CLUMPS BETTER than cpsam:\n" +
              "on a clumpy test region it found 63 cells against cpsam's 40,\n" +
              "and it is 2-3x faster. Needs the cell diameter (58 px here)."],
    "Cellpose-SAM (cpsam)": [
        engine: 'cellpose', arg: 'cpsam',
        desc: "Whole cells, via Python + GPU. SCALE-FREE, so cell diameter is\n" +
              "ignored. Matches the notebook exactly\n" +
              "(106 cells on the benchmark), but merges dense clumps into\n" +
              "single objects where cyto3 resolves them."],
    "Cellpose cyto2 (whole cells)": [
        engine: 'cellpose', arg: 'cyto2', diameter: 58,
        desc: "Whole cells, the older cellpose 2 model. Similar to cyto3\n" +
              "on this slide (71 vs 63 objects on the clumpy region).\n" +
              "Worth trying if cyto3 over-splits."],
    "InstanSeg: brightfield nuclei": [
        engine: 'instanseg', arg: new File(instansegDir, "brightfield_nuclei-0.1.1").absolutePath,
        desc: "NUCLEI only, not whole cells -- areas are NOT comparable\n" +
              "with Cellpose. Runs inside QuPath, no Python needed.\n" +
              "Currently CPU-only here, so slower than Cellpose on GPU."],
    "InstanSeg: fluorescence nuclei + cells": [
        engine: 'instanseg', arg: new File(instansegDir, "fluorescence_nuclei_and_cells-0.1.1").absolutePath,
        desc: "For FLUORESCENCE images. Your slide is brightfield MGG,\n" +
              "so this is the wrong model for it -- listed for completeness."],
    "Custom Cellpose model...": [
        engine: 'cellpose', arg: null,
        desc: "A model you fine-tuned yourself, e.g. from corrected masks\n" +
              "in the Cellpose GUI. You'll be asked for the model file.\n" +
              "It must be trained with the same cellpose version (4.x)."],
]

def modelBox = new ComboBox<String>()
modelBox.getItems().addAll(MODELS.keySet())
modelBox.getSelectionModel().select(prefs.get("model", "cyto3 cells + InstanSeg nuclei"))
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
        "Prepare training regions",
        "Export corrections to training corpus",
        "Train model on corrected regions",
        "Classify cells: explore (no labels yet)",
        "Classify cells: train classifier",
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
def clustersField = new TextField(prefs.get("clusters", "5"))
def classifierField = new TextField(prefs.get("classifier", ""))
classifierField.setPromptText("empty = bal_classify/classifier.joblib")
def classifierBrowse = new javafx.scene.control.Button("Browse...")
classifierBrowse.setOnAction({
    def start = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify")
    def filter = new javafx.stage.FileChooser.ExtensionFilter("Classifiers", "*.joblib", "*")
    def f = qupath.fx.dialogs.FileChoosers.promptForFile(null, "Choose a saved classifier",
            start.exists() ? start : null, filter)
    if (f != null) classifierField.setText(f.getAbsolutePath())
} as javafx.event.EventHandler)
def classifierRow = new GridPane()
classifierRow.setHgap(6)
classifierRow.add(classifierField, 0, 0)
classifierRow.add(classifierBrowse, 1, 0)
GridPane.setHgrow(classifierField, Priority.ALWAYS)
def reuseCheck = new CheckBox("Reuse existing embeddings (skip the transformer; only if cells are unchanged)")
reuseCheck.setSelected(prefs.getBoolean("reuseEmb", false))

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
def tileField = new TextField(prefs.get("tileSize", "1024"))
def expansionField = new TextField(prefs.get("expansion", "2.0"))
def featuresCheck = new CheckBox("Measure the notebook's 12 features per cell")
featuresCheck.setSelected(prefs.getBoolean("features", true))
def clumpCheck = new CheckBox("Flag oversized objects as 'Clump/debris' above")
clumpCheck.setSelected(prefs.getBoolean("clumpOn", true))
def clumpField = new TextField(prefs.get("clumpArea", "300"))
clumpField.setPrefColumnCount(5)

def syncEnabled = {
    def task = taskBox.getValue()
    boolean segmenting = task == "Segment cells"
    boolean preparing = task == "Prepare training regions"
    boolean exporting = task == "Export corrections to training corpus"
    boolean training = task == "Train model on corrected regions"
    boolean classifying = task.startsWith("Classify cells")
    boolean clsExplore = task.endsWith("explore (no labels yet)")
    boolean clsTrain = task.endsWith("train classifier")
    boolean clsPredict = task.endsWith("predict")
    boolean exportLabels = task.startsWith("Export labelled cells")

    taskDesc.setText(
        segmenting ? "Segment cells in the selected region(s) and measure them." :
        preparing  ? "Drop Training/Validation rectangles and segment inside them as editable\n" +
                     "annotations. You then correct every outline by hand -- densely: a cell you\n" +
                     "leave out teaches the model that it is background." :
        exporting  ? "Add this project's corrected rectangles to a shared corpus folder, so\n" +
                     "ground truth accumulates across slides. Adds to what is there; never\n" +
                     "deletes. Run it once per project, then train on the whole corpus." :
                     "Fine-tune the chosen model. With a corpus folder set, it trains on the\n" +
                     "pooled data from every slide; empty, it uses this project's rectangles.\n" +
                     "Correct the outlines FIRST -- training on uncorrected output just\n" +
                     "teaches the model to repeat itself.")
    if (clsExplore)
        taskDesc.setText("Embed every cell with the Phikon-v2 pathology transformer and cluster them.\n" +
                         "Writes bal_classify/clusters.png showing the most typical cells of each\n" +
                         "cluster -- name those, label examples, then come back and train.")
    if (clsTrain)
        taskDesc.setText("Train a classifier on the cells you labelled by hand, using the\n" +
                         "transformer's embeddings. Reports cross-validated per-class recall,\n" +
                         "saves the classifier, then classifies the unlabelled cells.")
    if (clsPredict)
        taskDesc.setText("Apply a saved classifier to every cell in this image. Predictions come\n" +
                         "back as classifications you can correct, then retrain.")

    // segmentation-only
    [outputBox, regionBox, featuresCheck, clumpCheck, tileField].each { it.setDisable(!segmenting) }
    expansionField.setDisable(!segmenting || outputBox.getValue() != "Cells")
    clumpField.setDisable(!segmenting || !clumpCheck.isSelected() || !featuresCheck.isSelected())
    // training-only
    [trainRegionsField, valRegionsField, trainSizeField].each { it.setDisable(!preparing) }
    [epochsField, lrField, weightDecayField, sgdCheck].each { it.setDisable(!training) }
    corpusField.setDisable(!(exporting || training || exportLabels))
    corpusBrowse.setDisable(!(exporting || training || exportLabels))
    clustersField.setDisable(!clsExplore)
    classifierField.setDisable(!(clsTrain || clsPredict)); classifierBrowse.setDisable(!(clsTrain || clsPredict))
    reuseCheck.setDisable(!classifying)
    // Classification works on whatever cells already exist, so the segmentation model and
    // its settings are irrelevant here.
    if (classifying || exportLabels) {
        [outputBox, regionBox, featuresCheck, clumpCheck, tileField, expansionField, clumpField,
         pixelField, diameterField].each { it.setDisable(true) }
        modelDesc.setText("Classification uses the cells already in the image -- the segmentation\n" +
                          "model above is not used. Run a segmentation first if there are none.")
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
    boolean isInstanSeg = m?.engine == 'instanseg'
    pixelField.setDisable(isInstanSeg && segmenting)
    // Diameter only means something for cyto2/cyto3-style models, which rescale the image by
    // diam_mean/diameter. cpsam is scale-free (cellpose 4 calls --diam_mean deprecated and
    // unused) and InstanSeg has its own resolution, so the field stays off for both -- in
    // every task, including training.
    boolean usesDiameter = (m?.engine in ['cellpose', 'paired']) && !samModel
    diameterField.setDisable(!usesDiameter)
    if (isCustom)
        modelDesc.setText((m?.desc ?: "") + "\n\n" + (customModel != null
                ? "Using: ${customModel.getName()}\nRuns in ${customIsSam() ? 'cellpose 4 (cpsam): scale-free, so cell diameter is off' : 'cellpose 3 (cyto-style): cell diameter applies'}"
                : "No model file chosen yet -- click Browse..."))
    if (isInstanSeg && m.arg != null && !new File(m.arg.toString()).exists())
        modelDesc.setText(m.desc + "\n\nNOT FOUND on disk - download it via Extensions > InstanSeg.")
    // Training only works on Cellpose models: InstanSeg fine-tuning isn't supported from QuPath,
    // and the paired mode is two models at once.
    if ((preparing || training) && !classifying && m?.engine != 'cellpose')
        modelDesc.setText((m?.desc ?: "") + "\n\nThis task needs a plain Cellpose model " +
                "(cyto3, cyto2, cpsam or a custom one).")
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
featuresCheck.selectedProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
clumpCheck.selectedProperty().addListener({ a, b, c -> syncEnabled() } as javafx.beans.value.ChangeListener)
syncEnabled()

def pane = new GridPane()
pane.setHgap(8); pane.setVgap(7); pane.setPadding(new Insets(12))
int row = 0
def addRow = { String label, javafx.scene.Node node ->
    pane.add(new Label(label), 0, row)
    pane.add(node, 1, row++)
    GridPane.setHgrow(node, Priority.ALWAYS)
}
addRow("Task:", taskBox)
pane.add(taskDesc, 1, row++)
pane.add(new Separator(), 0, row++, 2, 1)
addRow("Model:", modelRow)
pane.add(modelDesc, 1, row++)
addRow("Output type:", outputBox)
addRow("Run on:", regionBox)
addRow("Cell expansion (um):", expansionField)
pane.add(new Separator(), 0, row++, 2, 1)
addRow("Pixel size (um/px):", pixelField)
addRow("Tile size (px):", tileField)
addRow("Cell diameter (px):", diameterField)
pane.add(new Separator(), 0, row++, 2, 1)
pane.add(featuresCheck, 0, row++, 2, 1)
def clumpRow = new GridPane()
clumpRow.setHgap(6)
clumpRow.add(clumpCheck, 0, 0)
clumpRow.add(clumpField, 1, 0)
clumpRow.add(new Label("um2"), 2, 0)
pane.add(clumpRow, 0, row++, 2, 1)
pane.add(new Separator(), 0, row++, 2, 1)
addRow("Training regions (x,y; ...):", trainRegionsField)
addRow("Validation regions (x,y; ...):", valRegionsField)
addRow("Region size (px):", trainSizeField)
addRow("Epochs:", epochsField)
addRow("Learning rate:", lrField)
addRow("Weight decay:", weightDecayField)
pane.add(sgdCheck, 1, row++)
pane.add(saveEachCheck, 1, row++)
addRow("Checkpoint every N epochs:", saveEveryField)
addRow("Training data folder:", corpusRow)
pane.add(new Separator(), 0, row++, 2, 1)
addRow("Clusters (explore):", clustersField)
addRow("Classifier file:", classifierRow)
pane.add(reuseCheck, 1, row++)

def response = Dialogs.builder()
        .title("BAL cell segmentation")
        .headerText("Cellpose-SAM segmentation of the BAL slide")
        .content(pane)
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
def trainRegions = [], valRegions = []
try {
    pixelSize = asNumber(pixelField, "Pixel size", 0.001, 10)
    tileSize = asNumber(tileField, "Tile size", 64, 4096)
    expansion = asNumber(expansionField, "Cell expansion", 0, 100)
    clumpArea = clumpCheck.isSelected() ? asNumber(clumpField, "Clump area", 1, 1_000_000) : 0
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
if (!task.startsWith("Classify cells") && task != "Segment cells" && chosen.engine != 'cellpose') {
    Dialogs.showErrorMessage("BAL segmentation",
            "'${task}' needs a plain Cellpose model (cyto3, cyto2, cpsam or a custom one).\n\n" +
            "'${modelBox.getValue()}' can't be fine-tuned from QuPath.")
    return
}
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
} else if (chosen.engine == 'instanseg' && !new File(modelArg.toString()).exists()) {
    Dialogs.showErrorMessage("BAL segmentation",
            "That InstanSeg model isn't downloaded:\n${modelArg}\n\nGet it via Extensions > InstanSeg.")
    return
}

prefs.put("model", modelBox.getValue())
prefs.put("output", outputBox.getValue())
prefs.put("region", regionBox.getValue())
prefs.put("pixelSize", pixelField.getText()); prefs.put("diameter", diameterField.getText())
prefs.put("tileSize", tileField.getText())
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
prefs.put("clusters", clustersField.getText()); prefs.put("classifier", classifierField.getText())
prefs.putBoolean("reuseEmb", reuseCheck.isSelected())
prefs.put("corpus", corpusField.getText())

// --- hand the settings to the worker scripts ---------------------------------
// Assigning without 'def' puts these in the shared binding, where the other scripts read them.
BAL_OUTPUT_TYPE = [Detections: 'DETECTION', Annotations: 'ANNOTATION', Cells: 'CELL'][outputBox.getValue()]
BAL_TARGETS = regionBox.getValue().startsWith("Selected") ? 'SELECTED' : 'ALL_ANNOTATIONS'
BAL_PIXEL_SIZE_UM = pixelSize
BAL_TILE_SIZE = tileSize as int
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

// --- export hand-labelled cells for both pipelines ---------------------------
if (task.startsWith("Export labelled cells")) {
    BAL_GT_DIR = corpusField.getText()?.trim() ?: ""
    BAL_EXPORT_WHAT = 'BOTH'
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
                        task.endsWith("train classifier") ? 'TRAIN' : 'PREDICT'
    BAL_CLUSTERS = Math.max(2, (clustersField.getText()?.trim() ?: "5") as int)
    BAL_CLASSIFIER = classifierField.getText()?.trim() ?: ""
    BAL_REUSE_EMBEDDINGS = reuseCheck.isSelected()
    println "=== ${task} ==="
    evaluate(PRE + new File(scriptDir, "qupath_classify_cells.groovy").text)
    def mins = (System.currentTimeMillis() - t0) / 60000.0
    println "=== finished in ${String.format('%.1f', mins)} min ==="
    Dialogs.showInfoNotification("BAL classification", String.format("%s finished in %.1f min", task, mins))
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

    if (task == "Prepare training regions") {
        println "=== Preparing training regions: ${modelBox.getValue()}, ${trainRegions.size()} training + " +
                "${valRegions.size()} validation of ${trainSize as int} px ==="
        BAL_TRAIN_REGIONS = trainRegions
        BAL_VAL_REGIONS = valRegions
        BAL_TRAIN_SIZE = trainSize as int
        evaluate(PRE + new File(scriptDir, "qupath_prepare_training.groovy").text)
    } else if (task == "Export corrections to training corpus") {
        // Exporting needs somewhere to put the patches; fall back to the default folder
        // rather than refusing, and say which one is being used.
        if (!BAL_GT_DIR) {
            BAL_GT_DIR = defaultCorpus
            println "No folder given -- using the default corpus: ${BAL_GT_DIR}"
        }
        println "=== Exporting corrections to ${BAL_GT_DIR} ==="
        evaluate(PRE + new File(scriptDir, "qupath_export_training_data.groovy").text)
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
if (chosen.engine == 'instanseg') {
    BAL_INSTANSEG_MODEL = modelArg
    BAL_DEVICE = 'cpu'                       // QuPath has the CPU build of PyTorch installed
    BAL_TILE_SIZE = Math.min(BAL_TILE_SIZE, 512)   // InstanSeg works in smaller tiles
    worker = "qupath_instanseg_detection.groovy"
} else if (chosen.engine == 'paired') {
    BAL_MODEL = modelArg
    BAL_DIAMETER = diameterField.getText().trim() as double
    BAL_DEVICE = 'cpu'
    BAL_MEASURE_ROI = 'CELL'                 // the 12 features describe the whole cell here
    worker = "qupath_cells_with_nuclei.groovy"
} else {
    BAL_MODEL = modelArg                     // 'cpsam', 'cyto3', 'cyto2', or a fine-tuned model path
    BAL_DIAMETER = chosen.diameter != null ? (diameterField.getText().trim() as double) : 0
    worker = "qupath_cellpose_detection.groovy"
}
evaluate(PRE + new File(scriptDir, worker).text)
if (featuresCheck.isSelected())
    evaluate(PRE + new File(scriptDir, "qupath_cell_features.groovy").text)

def secs = (System.currentTimeMillis() - t0) / 1000.0
println "=== finished in ${secs}s ==="
Dialogs.showInfoNotification("BAL segmentation", String.format("Finished in %.1f s", secs))
