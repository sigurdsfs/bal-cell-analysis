/**
 * The custom-model file is chosen when the dropdown changes, not at OK.
 *
 * What this can check headlessly: the state the dialog is in once a custom model is known
 * -- which is the whole point of choosing it early. It stubs showAndWait and, at that exact
 * moment, reports the controls through a closure placed in the binding.
 *
 * What it CANNOT check: the file chooser actually opening on selection, and the revert when
 * you cancel it. Both need a real window. Verify those by hand in QuPath.
 */
import static qupath.lib.scripting.QP.*
import javafx.application.Platform
import java.util.prefs.Preferences
import java.util.concurrent.CountDownLatch

def F = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_bal_gui.groovy")
// The scripts are CRLF; normalise first or any multi-line anchor below silently misses.
def SRC = F.text.replace("\r\n", "\n")
// Report the control states at the moment the dialog would have been shown.
def stubbed = SRC.replace(".showAndWait()",
        ".with { REPORT(modelDesc, diameterField, modelBrowse, customModel, lrField, sgdCheck); Optional.empty() }")

def prefs = Preferences.userRoot().node("bal_segmentation")
def savedModel = prefs.get("model", "cyto3 cells + InstanSeg nuclei")
def savedTask = prefs.get("task", "Segment cells")
def savedCustom = prefs.get("customModel", "")

try { Platform.startup({ } as Runnable) } catch (IllegalStateException e) { }

def tmp = new File(System.getProperty("java.io.tmpdir"), "bal_custom_model_test")
tmp.mkdirs()
def samModel = new File(tmp, "bal_cpsam_20260101_e100.cpm")
def cytoModel = new File(tmp, "bal_cyto3_20260101_e100.cpm")
[samModel, cytoModel].each { it.text = "not a real model; only the name is read" }

/** Run the dialog with these prefs and return what REPORT saw. */
def run = { String task, String model, String customPath ->
    prefs.put("task", task); prefs.put("model", model)
    if (customPath == null) prefs.remove("customModel") else prefs.put("customModel", customPath)
    prefs.flush()
    def latch = new CountDownLatch(1)
    def seen = [:]
    def err = null
    def binding = new Binding()
    binding.setVariable("REPORT", { desc, diam, browse, custom, lr, sgd ->
        seen.desc = desc.getText()
        seen.diameterDisabled = diam.isDisabled()
        seen.browseDisabled = browse.isDisabled()
        seen.custom = custom?.getName()
        seen.lr = lr.getText()
        seen.sgd = sgd.isSelected()
    })
    Platform.runLater({
        try {
            new GroovyShell(this.class.classLoader, binding).evaluate(
                    "import static qupath.lib.scripting.QP.*\nimport static qupath.lib.gui.scripting.QPEx.*\n" + stubbed)
        } catch (Throwable x) { err = x } finally { latch.countDown() }
    } as Runnable)
    latch.await()
    if (err) throw err
    seen
}

int pass = 0, fail = 0
def check = { String what, boolean ok, String got ->
    if (ok) { pass++; println "ok    ${what}" }
    else { fail++; println "FAIL  ${what}  -- got: ${got}" }
}

println "=== a remembered cpsam fine-tune ==="
def r = run("Segment cells", "Custom Cellpose model...", samModel.getAbsolutePath())
check("the file is picked up from prefs, not asked for again",
        r.custom == samModel.getName(), "${r.custom}")
check("the description names the file", r.desc?.contains(samModel.getName()), "${r.desc}")
check("it is recognised as cellpose 4", r.desc?.contains("cellpose 4"), "${r.desc}")
check("cell diameter is OFF (a cpsam fine-tune is scale-free)",
        r.diameterDisabled, "disabled=${r.diameterDisabled}")
check("Browse is enabled", !r.browseDisabled, "disabled=${r.browseDisabled}")

println "\n=== a remembered cyto3 fine-tune ==="
r = run("Segment cells", "Custom Cellpose model...", cytoModel.getAbsolutePath())
check("it is recognised as cellpose 3", r.desc?.contains("cellpose 3"), "${r.desc}")
check("cell diameter is ON (cyto models rescale by it)",
        !r.diameterDisabled, "disabled=${r.diameterDisabled}")

println "\n=== no file remembered ==="
r = run("Segment cells", "Custom Cellpose model...", null)
check("the dialog says so rather than looking ready",
        r.desc?.contains("No model file chosen"), "${r.desc}")
check("Browse is still enabled so it can be fixed", !r.browseDisabled, "disabled=${r.browseDisabled}")

println "\n=== the remembered file was deleted ==="
def ghost = new File(tmp, "bal_cpsam_deleted.cpm")
r = run("Segment cells", "Custom Cellpose model...", ghost.getAbsolutePath())
check("a path that is gone counts as nothing chosen",
        r.custom == null && r.desc?.contains("No model file chosen"), "custom=${r.custom}")

println "\n=== training settings follow the custom model's parent ==="
r = run("Train model on corrected regions", "Custom Cellpose model...", samModel.getAbsolutePath())
check("a cpsam fine-tune trains at 1e-5", r.lr == "0.00001", "lr=${r.lr}")
check("...with AdamW, not SGD", !r.sgd, "sgd=${r.sgd}")
r = run("Train model on corrected regions", "Custom Cellpose model...", cytoModel.getAbsolutePath())
check("a cyto3 fine-tune trains at 0.1", r.lr == "0.1", "lr=${r.lr}")
check("...with SGD", r.sgd, "sgd=${r.sgd}")

println "\n=== Browse stays off for the built-in models ==="
["cyto3 cells + InstanSeg nuclei", "Cellpose cyto3 (whole cells)", "Cellpose-SAM (cpsam)",
 "InstanSeg: brightfield nuclei"].each { mo ->
    def s = run("Segment cells", mo, samModel.getAbsolutePath())
    check("Browse disabled for '${mo}'", s.browseDisabled, "disabled=${s.browseDisabled}")
}

// --- the selection itself -----------------------------------------------------
// The real file chooser cannot open headlessly, so swap it for one that answers straight
// away. Everything else -- the listener, the assignment back into the script's own
// variable, the revert on cancel -- is the code that actually ships.
def PICK_CALL = """qupath.fx.dialogs.FileChoosers.promptForFile(
            null, "Choose a fine-tuned Cellpose model", start, filter)"""
if (!SRC.contains(PICK_CALL))
    throw new IllegalStateException("the file-chooser call has moved; this test needs updating")
def driven = SRC.replace(PICK_CALL, "FAKE_PICK()")
        .replace(".showAndWait()", ".with { CAPTURE(modelBox, modelDesc, diameterField); Optional.empty() }")

/** Load the dialog, then select the custom entry with FAKE_PICK standing in for the chooser. */
def drive = { File answer, String startingModel ->
    prefs.put("task", "Segment cells"); prefs.put("model", startingModel)
    prefs.remove("customModel"); prefs.flush()
    def refs = [:]
    def binding = new Binding()
    binding.setVariable("FAKE_PICK", { answer })
    binding.setVariable("CAPTURE", { box, desc, diam -> refs.box = box; refs.desc = desc; refs.diam = diam })
    def err = null
    def latch = new CountDownLatch(1)
    Platform.runLater({
        try {
            new GroovyShell(this.class.classLoader, binding).evaluate(
                    "import static qupath.lib.scripting.QP.*\nimport static qupath.lib.gui.scripting.QPEx.*\n" + driven)
        } catch (Throwable x) { err = x } finally { latch.countDown() }
    } as Runnable)
    latch.await()
    if (err) throw err
    // Selecting fires the listener, which defers the pick with runLater; each step below is
    // a separate pass through the event queue so that deferred work has run.
    def step = { Closure body ->
        def l = new CountDownLatch(1)
        Platform.runLater({ try { body() } finally { l.countDown() } } as Runnable)
        l.await()
    }
    step { refs.box.getSelectionModel().select("Custom Cellpose model...") }
    step { }      // lets the listener's runLater (the pick) run
    step { }      // lets a revert queued by the pick run
    [model: refs.box.getValue(), desc: refs.desc.getText(), diameterDisabled: refs.diam.isDisabled()]
}

println "\n=== choosing the file when the dropdown changes ==="
r = drive(samModel, "Cellpose cyto3 (whole cells)")
check("the model stays on the custom entry", r.model == "Custom Cellpose model...", "${r.model}")
check("the chosen file is in hand before OK", r.desc?.contains(samModel.getName()), "${r.desc}")
check("and the dialog has already adapted to it (diameter off)",
        r.diameterDisabled, "disabled=${r.diameterDisabled}")

println "\n=== cancelling the chooser ==="
r = drive(null, "Cellpose cyto3 (whole cells)")
check("the dropdown goes back to the previous model",
        r.model == "Cellpose cyto3 (whole cells)", "${r.model}")
check("cell diameter is live again for cyto3", !r.diameterDisabled, "disabled=${r.diameterDisabled}")

// put the prefs back the way they were
prefs.put("task", savedTask); prefs.put("model", savedModel)
if (savedCustom) prefs.put("customModel", savedCustom) else prefs.remove("customModel")
prefs.flush()
[samModel, cytoModel].each { it.delete() }
tmp.delete()

println "\n${pass} checks passed, ${fail} failed"
if (fail > 0) throw new IllegalStateException("${fail} check(s) failed")
