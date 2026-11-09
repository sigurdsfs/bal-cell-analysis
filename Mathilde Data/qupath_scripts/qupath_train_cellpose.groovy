/**
 * Fine-tunes a Cellpose model on the corrected ground truth in this project.
 *
 * Based on the extension's own training template, wired to this machine's two
 * environments and defaults.
 *
 * BEFORE RUNNING:
 *   - qupath_prepare_training.groovy has made Training/Validation rectangles
 *   - you have densely corrected the annotations inside them (see that script's notes)
 *   - the project is SAVED (training reads the saved data, not the screen)
 *
 * Every image in the project with Training or Validation rectangles is used, so you can
 * build ground truth across several regions or slides and train on all of it at once.
 *
 * WHICH BASE MODEL: cyto3 fine-tunes in the cellpose3 environment, cpsam in cellpose_seg.
 * Fine-tuning from cyto3 is the natural choice here, since cyto3 is what the pipeline uses.
 *
 * OUTPUT: a model file under <project>/models/, plus QC results. Point the detection
 * script at it by choosing "Custom Cellpose model..." in the GUI and picking that file.
 *
 * MUST BE RUN FROM THE QUPATH WINDOW, not the command line: the extension's
 * saveTrainingImages() calls QPEx.getQuPath().getProject(), which is null in headless
 * mode and throws a NullPointerException. Everything before training (making the
 * rectangles, segmenting, clearing classes) works either way.
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


def MODEL     = binding.hasVariable('BAL_MODEL')     ? BAL_MODEL.toString()  : 'cyto3'
double DIAM   = binding.hasVariable('BAL_DIAMETER')  ? BAL_DIAMETER as double : 58
// CELLPOSE HAS THREE DIFFERENT SETS OF "DEFAULTS", and they disagree. The learning rate
// must match the optimiser, so never mix rows of this table:
//
//   entry point                     optimiser     lr      weight_decay  epochs   meant for
//   Python API train_seg()          RAdam/AdamW   0.005   1e-5          2000     full training runs
//   command line (--train)          SGD           0.2     1e-5          500      training from scratch
//   cellpose GUI training dialog    SGD           0.1     1e-4          100      FINE-TUNING
//
// The BIOP extension drives the command line, so it inherits the from-scratch row -- which
// measurably wrecks a fine-tune here (train_loss 0.17 -> 3.83 in 10 epochs at lr 0.2).
// We therefore default to the GUI's fine-tuning row. To use RAdam/AdamW instead, set
// BAL_USE_SGD = false AND drop the learning rate to ~0.005 (cellpose's own tooltip says
// ~0.001); the GUI refuses mismatched combinations.
int EPOCHS    = binding.hasVariable('BAL_EPOCHS')    ? BAL_EPOCHS as int     : 100
// Left null here: the right values depend on which model is being fine-tuned, and cellpose 3
// and cellpose 4 differ by four orders of magnitude. Filled in below once that is known.
Double LR = binding.hasVariable('BAL_LR') ? BAL_LR as double : null
Double WEIGHT_DECAY = binding.hasVariable('BAL_WEIGHT_DECAY') ? BAL_WEIGHT_DECAY as double : null
Boolean USE_SGD = binding.hasVariable('BAL_USE_SGD') ? BAL_USE_SGD as boolean : null
def OUT_NAME  = binding.hasVariable('BAL_MODEL_NAME')? BAL_MODEL_NAME.toString() : "bal_${MODEL}"
// Per-epoch checkpoints. Only cellpose 4 (cpsam) has --save_each; cellpose 3's CLI does not,
// so for cyto2/cyto3 the last epoch is all you can get.
boolean SAVE_EACH = binding.hasVariable('BAL_SAVE_EACH') ? BAL_SAVE_EACH as boolean : false
int SAVE_EVERY = binding.hasVariable('BAL_SAVE_EVERY') ? BAL_SAVE_EVERY as int : 5
// Point this at a shared corpus (see qupath_export_training_data.groovy) to train on ground
// truth gathered from several slides. Left empty, training exports and uses this project only.
def GT_DIR    = binding.hasVariable('BAL_GT_DIR') && BAL_GT_DIR?.toString()?.trim() ?
        new File(BAL_GT_DIR.toString().trim()) : null
// Soma and nucleus ground truth live side by side under one corpus folder: soma at the top
// (where the existing hand-corrected data already is) and nuclei one level down. Training
// on both at once would teach the model an average of a cell and a nucleus.
def TRAIN_TARGET = binding.hasVariable('BAL_TRAIN_TARGET') ? BAL_TRAIN_TARGET.toString().toUpperCase() : 'SOMA'
if (GT_DIR != null && TRAIN_TARGET == 'NUCLEI') GT_DIR = new File(GT_DIR, "nuclei")

// --- environments ------------------------------------------------------------
def samPython = (CFG.pythonCellpose4 ?: [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose_seg/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose_seg/python.exe',
]).find { new File(it).exists() }
def legacyPython = (CFG.pythonCellpose3 ?: [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose3/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose3/python.exe',
]).find { new File(it).exists() }
if (samPython != null) CellposeSetup.getInstance().setCellposeSAMPythonPath(samPython)
if (legacyPython != null) CellposeSetup.getInstance().setCellposePythonPath(legacyPython)

boolean isSAM = MODEL == 'cpsam' || new File(MODEL).exists()

// cellpose 3 (cyto2/cyto3) and cellpose 4 (cpsam) need COMPLETELY different training settings.
// cpsam is a 300M-parameter transformer; cyto3 is a 6.6M-parameter UNet. Using cyto3's rate on
// cpsam is 10,000x too high and destroys it -- the loss climbs from the first epoch.
if (LR == null)           LR = isSAM ? 0.00001 : 0.1
if (WEIGHT_DECAY == null) WEIGHT_DECAY = isSAM ? 0.1 : 0.0001
if (USE_SGD == null)      USE_SGD = !isSAM
if (SAVE_EACH && !isSAM) {
    println "NOTE: cellpose 3 has no --save_each, so per-epoch checkpoints aren't available for '${MODEL}'."
    SAVE_EACH = false
}
println "Training settings for ${isSAM ? 'cpsam (cellpose 4)' : 'cyto-style (cellpose 3)'}: " +
        "lr=${LR}, weight_decay=${WEIGHT_DECAY}, optimiser=${USE_SGD ? 'SGD' : 'AdamW'}, epochs=${EPOCHS}"

// "Custom_model_2026-09-28_13_29.cpm" says nothing about how a model was made, and after a
// few runs they are indistinguishable. Build the name from the settings that actually
// changed, so a model can be identified months later without opening a log.
// The extension appends "_yyyy-MM-dd_HH_mm.cpm" itself.
def tidy = { double v ->
    def str = (v == (long) v) ? String.valueOf((long) v) : String.valueOf(v)
    if (Math.abs(v) < 0.001 && v != 0) str = String.format("%.0e", v)   // 1.0E-5 -> 1e-05
    str.replace(".", "p").replace("E", "e")
}
def baseName = new File(MODEL).exists() ? "ft-" + new File(MODEL).getName().replaceAll(/\.cpm$/, "") : MODEL

if (!isSAM && legacyPython == null) {
    Dialogs.showErrorMessage("Cellpose training", "cyto3 needs the cellpose3 environment, which wasn't found.")
    return
}


// --- keep only the best and the latest checkpoint ----------------------------
// With --save_each, cellpose writes "<model>_epoch_0010", "_epoch_0020", ... Each cpsam
// checkpoint is ~1.2 GB, so keeping them all would fill the disk. This prunes while
// training runs: whenever a newer epoch beats the best test_loss so far, the previous best
// is deleted; anything that is neither the best nor the newest goes too. The end state is
// exactly two files -- best and last.
def epochOf = { File f ->
    def m = (f.name =~ /_epoch_(\d+)$/)
    m ? (m[0][1] as int) : -1
}
// The losses have to be remembered as they appear. The extension runs a QC prediction
// INSIDE train(), and that overwrites ~/.cellpose/run.log -- so by the time train() returns,
// every loss line is gone. Reading the log only at the end therefore finds nothing, which
// once caused the best checkpoint to be deleted as "unscored".
def lossHistory = Collections.synchronizedMap(new TreeMap<Integer, Double>())
def logSnapshot = null   // set once we know where to keep a copy

def readTestLosses = {
    def log = new File(System.getProperty("user.home"), ".cellpose/run.log")
    [log, logSnapshot].each { f ->
        if (f != null && f.exists()) {
            f.eachLine { line ->
                def m = (line =~ /(\d+), train_loss=[0-9.]+, test_loss=([0-9.]+)/)
                if (m) lossHistory.put(m[0][1] as int, m[0][2] as double)
            }
        }
    }
    new TreeMap<Integer, Double>(lossHistory)
}
// Keep a copy of the log while the loss lines still exist.
def snapshotLog = {
    def log = new File(System.getProperty("user.home"), ".cellpose/run.log")
    if (logSnapshot == null || !log.exists()) return
    if (log.text.contains("test_loss=")) logSnapshot.text = log.text
}
// Returns [kept: [...], deleted: n]. Safe to call repeatedly, during or after training.
def pruneCheckpoints = { File dir, boolean verbose ->
    if (dir == null || !dir.exists()) return [kept: [], deleted: 0]
    def epochFiles = (dir.listFiles() ?: []).findAll { it.isFile() && epochOf(it) >= 0 }
    if (epochFiles.size() < 2) return [kept: epochFiles, deleted: 0]
    def losses = readTestLosses()
    def newest = epochFiles.max { epochOf(it) }
    // Best = lowest test_loss among epochs we have both a file and a loss for.
    def scored = epochFiles.findAll { losses.containsKey(epochOf(it)) }
    def best = scored.isEmpty() ? null : scored.min { losses[epochOf(it)] }
    // FAIL SAFE: with no loss information every checkpoint except the newest would look
    // disposable, and the best one would be thrown away. Rather delete nothing.
    if (best == null) {
        if (verbose) println "  no loss data yet -- keeping all ${epochFiles.size()} checkpoint(s)"
        return [kept: epochFiles, deleted: 0, best: null, newest: newest, losses: losses]
    }
    int deleted = 0
    epochFiles.each { f ->
        if (f != newest && f != best) {
            if (f.delete()) deleted++
        }
    }
    if (verbose && deleted > 0)
        println "  pruned ${deleted} checkpoint(s); keeping best" +
                (best ? " (epoch ${epochOf(best)}, test_loss ${losses[epochOf(best)]})" : "") +
                " and latest (epoch ${epochOf(newest)})"
    [kept: [best, newest].findAll { it != null }.unique(), deleted: deleted, best: best, newest: newest, losses: losses]
}

// Final tidy-up: leave exactly two models in <project>/models -- the best epoch and the last
// -- under names that say which is which, and clear the epoch files from the training folder.
def keepBestAndLast = { File dir, File finalModel ->
    def result = pruneCheckpoints(dir, false)
    def best = result.best, losses = result.losses
    def outDir = finalModel?.getParentFile()
    if (best == null || outDir == null) {
        println "\nNo per-epoch checkpoints to choose from -- you have the final model only."
        return
    }
    int bestEpoch = (best.name =~ /_epoch_(\d+)$/)[0][1] as int
    // Name the best checkpoint with its epoch AND its loss, so the two files in the folder
    // are self-describing: "..._e200_adamw_2026-09-28_13_29_BEST-ep10-loss0p126.cpm"
    def lossTag = losses[bestEpoch] != null ? String.valueOf(losses[bestEpoch]).replace(".", "p") : "na"
    def bestCopy = new File(outDir, finalModel.getName().replaceAll(/\.cpm$/, "") +
            "_BEST-ep${bestEpoch}-loss${lossTag}.cpm")
    try {
        bestCopy.bytes = best.bytes
        println "\nTwo models kept:"
        println "  BEST  epoch ${bestEpoch} (test_loss ${losses[bestEpoch]}) -> ${bestCopy.getName()}"
        println "  LAST  epoch ${EPOCHS - 1}                    -> ${finalModel.getName()}"
        println "  Score both on a region you did not train on -- lowest test_loss does not always win."
    } catch (Exception e) {
        println "Could not copy the best checkpoint: ${e.getMessage()}"
    }
    // The epoch files themselves are no longer needed; the two .cpm copies are what you use.
    int removed = 0
    (dir.listFiles() ?: []).each { f -> if (f.isFile() && (f.name =~ /_epoch_\d+$/) && f.delete()) removed++ }
    if (removed > 0) println "  cleared ${removed} checkpoint file(s) from the training folder"
}

// Cellpose saves the LAST epoch's weights, never the best: train.py ends with an
// unconditional net.save_model(filename), and the test_losses it tracks are only reported,
// never used to pick a checkpoint. (cellpose 3's CLI has no --save_each either, so there
// are no intermediate files to choose from.) This reads the loss log afterwards and says
// where the validation loss actually bottomed, so you can retrain with that many epochs.
def summariseTraining = { File modelFile ->
    // run.log may already have been overwritten by the QC prediction that train() runs, so
    // prefer whichever source still holds loss lines.
    def log = new File(System.getProperty("user.home"), ".cellpose/run.log")
    def source = (log.exists() && log.text.contains("test_loss=")) ? log :
                 ((logSnapshot != null && logSnapshot.exists()) ? logSnapshot : null)
    if (source == null) { println "No training loss lines found (run.log was overwritten)."; return }
    def rows = []
    source.eachLine { line ->
        def mm = (line =~ /(\d+), train_loss=([0-9.]+), test_loss=([0-9.]+)/)
        if (mm) rows << [epoch: mm[0][1] as int, train: mm[0][2] as double, test: mm[0][3] as double]
    }
    if (rows.isEmpty()) { println "No loss lines found."; return }
    // Keep a copy: run.log is overwritten by the next cellpose call, training or prediction.
    if (modelFile != null) {
        def copy = new File(modelFile.getParentFile(), modelFile.getName().replaceAll(/\.cpm$/, "") + "_trainlog.txt")
        copy.text = source.text
        println "Training log copied to ${copy.getName()} (run.log gets overwritten by the next run)."
    }
    def first = rows.first(), last = rows.last()
    def best = rows.min { it.test }
    println ""
    println "Loss summary: start test_loss=${first.test} (epoch ${first.epoch}) -> " +
            "end test_loss=${last.test} (epoch ${last.epoch})"
    println "  lowest test_loss was ${best.test} at epoch ${best.epoch}"
    if (last.test > first.test)
        println "  WARNING: the model ended WORSE than it started. The saved weights are the final" +
                " ones, so this model is worse than the one you began with. Lower the learning rate."
    else if (best.epoch < last.epoch * 0.7)
        println "  NOTE: the best point was epoch ${best.epoch} but you keep epoch ${last.epoch}." +
                " Retrain with about ${best.epoch} epochs to keep the better model."
    else
        println "  The final epoch was close to the best, so the saved model is a reasonable choice."
}

// --- corpus mode: train on ground truth gathered from several slides ---------
// The extension only exports patches when cleanTrainingDir is set, and cleaning ERASES the
// folder first -- which would destroy a corpus. So in corpus mode we never clean, and the
// patches must already be there, put by qupath_export_training_data.groovy.
if (GT_DIR != null) {
    def countPairs = { File dir ->
        dir.exists() ? (dir.listFiles()?.count { it.name.toLowerCase().endsWith("_masks.tif") } ?: 0) : 0
    }
    int nTrain = countPairs(new File(GT_DIR, "train"))
    int nTest = countPairs(new File(GT_DIR, "test"))
    println "Corpus: ${GT_DIR}"
    println "  ${nTrain} training patches, ${nTest} validation patches"
    // Cellpose trains on every image/mask pair in the folder (verified: 4 pairs -> n_train=4),
    // so this is the whole training set, gathered from however many slides.
    def slidesIn = { File d ->
        (d.exists() ? d.listFiles() : [] ?: []).findAll { it.name.endsWith("_masks.tif") }
                .groupBy { it.name.replaceAll(/_region_\d+_masks\.tif$/, "") }
    }
    def trainSlides = slidesIn(new File(GT_DIR, "train"))
    def testSlides = slidesIn(new File(GT_DIR, "test"))
    println "  from ${trainSlides.size()} slide(s) for training, ${testSlides.size()} for validation:"
    trainSlides.sort { it.key }.each { slide, files -> println "    ${files.size()} patch(es)  ${slide}" }
    if (trainSlides.size() == 1)
        println "  NOTE: ground truth from a single slide. Corrections from several slides"
        println "        generalise far better -- stain and cell appearance vary between them."
    // A slide used for BOTH training and validation inflates the scores.
    def overlap = trainSlides.keySet().intersect(testSlides.keySet())
    if (!overlap.isEmpty())
        println "  WARNING: ${overlap.size()} slide(s) appear in BOTH train and test " +
                "(${overlap.take(3).join(', ')}) -- validation scores will look better than they are."
    if (nTrain == 0) {
        Dialogs.showErrorMessage("Cellpose training",
                "No training patches in:\n${GT_DIR}\n\n" +
                "Run qupath_export_training_data.groovy on each project whose corrections you " +
                "want to include, then train again.")
        return
    }

    OUT_NAME = "bal_${baseName}_${nTrain}gt_lr${tidy(LR)}_e${EPOCHS}_${USE_SGD ? 'sgd' : 'adamw'}"
    println "Model will be saved as ${OUT_NAME}_<date>.cpm"

    def cbuilder = Cellpose2D.builder(MODEL)
            .pixelSize(0.137)
            .epochs(EPOCHS)
            .learningRate(LR)
            .batchSize(8)
            .groundTruthDirectory(GT_DIR)
            .addParameter("SGD", USE_SGD ? "1" : "0")
            .addParameter("weight_decay", String.valueOf(WEIGHT_DECAY))
            .setOutputModelName(OUT_NAME)
            .modelDirectory(new File(buildFilePath(PROJECT_BASE_DIR, "models")))
    // NO cleanTrainingDir() here: it would wipe the corpus before training.
    if (isSAM) cbuilder.useCellposeSAM()
    if (!isSAM && DIAM > 0) cbuilder.diameter(DIAM)
    if (SAVE_EACH) {
        // --save_each writes a separate file per save; --save_every sets how often.
        cbuilder.addParameter("save_each")
        cbuilder.addParameter("save_every", String.valueOf(SAVE_EVERY))
    }
    def cmodel = cbuilder.build()

    // The extension takes the FIRST file it finds in <train>/models (Cellpose2D ~line 1490:
    // "Find the first file in there"), not the newest. With a corpus we never clean that
    // folder, so old models linger and every run hands back the OLDEST one -- silently
    // returning a model trained days ago while the new one sits unused. Move them aside.
    def modelsDir = new File(new File(GT_DIR, "train"), "models")
    if (modelsDir.exists()) {
        def stale = modelsDir.listFiles()?.findAll { it.isFile() && it.name.contains("cellpose") } ?: []
        if (!stale.isEmpty()) {
            def archive = new File(modelsDir, "previous")
            archive.mkdirs()
            stale.each { f -> f.renameTo(new File(archive, f.name)) }
            println "Moved ${stale.size()} earlier model(s) to ${archive.getName()}/ so THIS run's model is the one returned."
        }
    }

    println "\nTraining from '${MODEL}' on the corpus for ${EPOCHS} epochs..."
    long ct0 = System.currentTimeMillis()

    // Prune checkpoints while cellpose runs, so only the best-so-far and the newest survive.
    def pruner = null
    if (SAVE_EACH) {
        logSnapshot = new File(modelsDir, "train_log_snapshot.txt")
        pruner = new Thread({
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(15000)
                    snapshotLog()          // capture losses before the QC run wipes run.log
                    pruneCheckpoints(modelsDir, false)
                }
            } catch (InterruptedException ignored) { }
        } as Runnable)
        pruner.setDaemon(true)
        pruner.start()
        println "  keeping only the best and latest checkpoint as training proceeds"
    }

    def cresult = cmodel.train()
    if (pruner != null) pruner.interrupt()
    println "\nDone in ${(System.currentTimeMillis() - ct0) / 60000.0} min"
    println "Model saved: " + cresult.getAbsolutePath().replace('\\', '/')
    summariseTraining(cresult)
    if (SAVE_EACH) keepBestAndLast(modelsDir, cresult)
    println "\nTO USE IT: choose 'Custom Cellpose model...' in the GUI and pick that file."
    return
}

// --- project mode: export this project's rectangles, then train on them ------
def training = getAnnotationObjects().findAll { it.getPathClass()?.toString() == "Training" }
def validation = getAnnotationObjects().findAll { it.getPathClass()?.toString() == "Validation" }
if (training.isEmpty()) {
    Dialogs.showErrorMessage("Cellpose training",
            "No rectangles classified 'Training' in this image.\nRun qupath_prepare_training.groovy first.\n\n" +
            "(Other images in the project may still have some -- training uses them all.)")
    return
}
int gt = training.sum { it.getChildObjects().size() } ?: 0
int gtVal = validation.sum { it.getChildObjects().size() } ?: 0
println "This image: ${training.size()} Training rectangle(s) holding ${gt} outlines, " +
        "${validation.size()} Validation rectangle(s) holding ${gtVal}"

// CRITICAL: training reads each image's SAVED data from the project, not what is on
// screen. Unsaved rectangles produce "Found 0 Training objects" and then cellpose fails
// with "ERROR: no files in --dir folder". So save this image's data before going further.
def entry = getProjectEntry()
if (entry != null) {
    try {
        entry.saveImageData(getCurrentImageData())
        println "Saved this image's data to the project (training reads the saved copy)."
    } catch (Exception e) {
        Dialogs.showErrorMessage("Cellpose training",
                "Could not save the image data, and training reads the saved copy:\n${e.getMessage()}\n\n" +
                "Save the project by hand (Ctrl+S), then run this again.")
        return
    }
} else {
    println "WARNING: no project entry for this image -- save the project by hand before training."
}

// Now check what the extension will actually see, across every image in the project.
def project = getProject()
int savedTrain = 0, savedVal = 0
if (project != null) {
    project.getImageList().each { e ->
        try {
            def data = e.readImageData()
            def objs = data.getHierarchy().getAnnotationObjects()
            savedTrain += objs.findAll { it.getPathClass()?.toString() == "Training" }.sum { it.getChildObjects().size() } ?: 0
            savedVal += objs.findAll { it.getPathClass()?.toString() == "Validation" }.sum { it.getChildObjects().size() } ?: 0
        } catch (Exception ignored) { }
    }
    println "Saved in the project, across all images: ${savedTrain} training outlines, ${savedVal} validation outlines"
    if (savedTrain == 0) {
        Dialogs.showErrorMessage("Cellpose training",
                "The project's saved data holds no Training outlines, so there is nothing to train on.\n\n" +
                "Save the project (Ctrl+S) and try again.")
        return
    }
}
if (savedVal < 20)
    println "WARNING: only ${savedVal} validation outlines -- QC scores from so few will be very noisy."
if (gt < 50)
    println "WARNING: only ${gt} training outlines here. Fine-tuning usually needs a few hundred to help."
if (validation.isEmpty())
    println "WARNING: no Validation rectangle in this image -- you won't get QC scores."

// --- train -------------------------------------------------------------------
OUT_NAME = "bal_${baseName}_${savedTrain}gt_lr${tidy(LR)}_e${EPOCHS}_${USE_SGD ? 'sgd' : 'adamw'}"
println "Model will be saved as ${OUT_NAME}_<date>.cpm"

def builder = Cellpose2D.builder(MODEL)
        .pixelSize(0.137)
        .epochs(EPOCHS)
        .learningRate(LR)
        .batchSize(8)
        .addParameter("SGD", USE_SGD ? "1" : "0")
        .addParameter("weight_decay", String.valueOf(WEIGHT_DECAY))
        .setOutputModelName(OUT_NAME)
        .modelDirectory(new File(buildFilePath(PROJECT_BASE_DIR, "models")))
        .cleanTrainingDir()
if (isSAM) builder.useCellposeSAM()
if (!isSAM && DIAM > 0) builder.diameter(DIAM)
def cellpose = builder.build()

println "\nTraining from '${MODEL}' for ${EPOCHS} epochs -- this takes a while; watch the log."
long t0 = System.currentTimeMillis()
def resultModel = cellpose.train()
println "\nDone in ${(System.currentTimeMillis() - t0) / 60000.0} min"
println "Model saved: " + resultModel.getAbsolutePath().replace('\\', '/')
summariseTraining(resultModel)

try {
    cellpose.getTrainingResults().show("Training Results")
    cellpose.getQCResults().show("QC Results")
    cellpose.showTrainingGraph()
} catch (Exception e) {
    println "(training finished, but QC display failed: ${e.getMessage()})"
}

println ""
println "TO USE IT: in the BAL GUI choose 'Custom Cellpose model...' and pick the file above."
println "Then compare it against plain ${MODEL} on a region you did NOT train on -- that is the"
println "only honest way to tell whether fine-tuning helped."
