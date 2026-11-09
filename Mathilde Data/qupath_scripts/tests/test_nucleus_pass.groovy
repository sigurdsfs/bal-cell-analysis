/**
 * The nucleus pass, and the rule that Output type = Cells depends on it.
 *
 * WHY THIS MATTERS: a cell object whose inner boundary is the soma (because no nucleus was
 * segmented) produces an nc_ratio that is a function of cell diameter and runs BACKWARDS
 * against the real one -- 0.445 for a 7.8 um cell, 0.78 for a 30 um one, where the true
 * ratio is high for small lymphocytes and low for big macrophages. Offering Cells without
 * a nucleus is therefore not a harmless option, and this checks it cannot be chosen.
 *
 * Headless-only: this reports the state the dialog is in, not what the models produce.
 */
import static qupath.lib.scripting.QP.*
import javafx.application.Platform
import java.util.prefs.Preferences
import java.util.concurrent.CountDownLatch

def F = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_bal_gui.groovy")
def SRC = F.text.replace("\r\n", "\n")        // the scripts are CRLF; normalise before anchoring
def stubbed = SRC.replace(".showAndWait()",
        ".with { REPORT(outputBox, nucleiCheck, nucleusBox, nucleusDiameterField, nucleusBrowse, modelBox); Optional.empty() }")

def prefs = Preferences.userRoot().node("bal_segmentation")
def saved = ["task", "model", "nuclei", "output", "nucleusModel"].collectEntries { [it, prefs.get(it, null)] }
try { Platform.startup({ } as Runnable) } catch (IllegalStateException e) { }

def run = { String task, boolean nuclei, String output ->
    prefs.put("task", task)
    prefs.put("model", "Cellpose cyto3 (whole cells)")
    prefs.putBoolean("nuclei", nuclei)
    prefs.put("output", output)
    prefs.flush()
    def seen = [:]
    def err = null
    def latch = new CountDownLatch(1)
    def b = new Binding()
    b.setVariable("REPORT", { out, chk, nbox, ndiam, nbrowse, mbox ->
        seen.outputItems = out.getItems().collect { it.toString() }
        seen.output = out.getValue()
        seen.nucleiOn = chk.isSelected()
        seen.nucleiDisabled = chk.isDisabled()
        seen.nucleusBoxDisabled = nbox.isDisabled()
        seen.nucleusDiamDisabled = ndiam.isDisabled()
        seen.nucleusBrowseDisabled = nbrowse.isDisabled()
        seen.nucleusModel = nbox.getValue()
    })
    Platform.runLater({
        try {
            new GroovyShell(this.class.classLoader, b).evaluate(
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

println "=== nuclei ON ==="
def r = run("Segment cells", true, "Cells")
check("Cells is offered", r.outputItems.contains("Cells"), "${r.outputItems}")
check("and stays selected", r.output == "Cells", "${r.output}")
check("the nucleus model is live", !r.nucleusBoxDisabled, "disabled=${r.nucleusBoxDisabled}")
check("the nucleus diameter is live", !r.nucleusDiamDisabled, "disabled=${r.nucleusDiamDisabled}")
check("Browse is off for the built-in nuclei model", r.nucleusBrowseDisabled,
        "disabled=${r.nucleusBrowseDisabled}")

println ""
println "=== nuclei OFF ==="
r = run("Segment cells", false, "Detections")
check("Cells is NOT offered", !r.outputItems.contains("Cells"), "${r.outputItems}")
check("Detections and Annotations still are",
        r.outputItems.containsAll(["Detections", "Annotations"]), "${r.outputItems}")
check("the nucleus model is greyed out", r.nucleusBoxDisabled, "disabled=${r.nucleusBoxDisabled}")
check("the nucleus diameter is greyed out", r.nucleusDiamDisabled, "disabled=${r.nucleusDiamDisabled}")

println ""
println "=== nuclei OFF with Cells remembered from last time ==="
// The prefs outlive a settings change, so a stale "Cells" must not survive into a run
// that has no nucleus to put inside it.
r = run("Segment cells", false, "Cells")
check("it falls back instead of keeping Cells", r.output != "Cells", "${r.output}")
check("...and Cells is not in the list either", !r.outputItems.contains("Cells"), "${r.outputItems}")

println ""
println "=== the nucleus controls belong to segmentation only ==="
["Pair soma + nuclei into cells", "Train model on corrected regions",
 "Classify cells: predict"].each { t ->
    def x = run(t, true, "Detections")
    check("nuclei checkbox greyed out for '${t.take(32)}'", x.nucleiDisabled,
            "disabled=${x.nucleiDisabled}")
}

saved.each { k, v -> if (v == null) prefs.remove(k) else prefs.put(k, v) }
prefs.flush()

println ""
println "${pass} checks passed, ${fail} failed"
if (fail > 0) throw new IllegalStateException("${fail} check(s) failed")
