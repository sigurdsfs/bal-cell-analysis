/**
 * The collapsible sections: they exist, and each task opens the ones it uses.
 *
 * The dialog outgrew the screen once already -- the training and classification controls
 * were below the fold and unreachable on a laptop. This checks the thing that prevents a
 * repeat: a task never leaves every section shut, and never opens one it cannot use.
 *
 * Headless-only: it reports expanded/collapsed state, not how tall the window really is.
 */
import static qupath.lib.scripting.QP.*
import javafx.application.Platform
import java.util.prefs.Preferences
import java.util.concurrent.CountDownLatch

def F = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_bal_gui.groovy")
def SRC = F.text.replace("\r\n", "\n")        // CRLF files: normalise before anchoring
def stubbed = SRC.replace(".showAndWait()", ".with { REPORT(groupPanes); Optional.empty() }")

def prefs = Preferences.userRoot().node("bal_segmentation")
def savedTask = prefs.get("task", "Segment cells")
def savedModel = prefs.get("model", "Cellpose cyto3 (whole cells)")
try { Platform.startup({ } as Runnable) } catch (IllegalStateException e) { }

def run = { String task ->
    prefs.put("task", task)
    prefs.put("model", "Cellpose cyto3 (whole cells)")
    prefs.flush()
    def seen = [:]
    def err = null
    def latch = new CountDownLatch(1)
    def b = new Binding()
    b.setVariable("REPORT", { panes ->
        panes.each { title, tp -> seen[title] = tp.isExpanded() }
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

def EXPECTED = [
    "Segment cells":
        ["Segmentation", "Resolution and tiling", "Measurements"],
    "Pair soma + nuclei into cells":
        ["Measurements"],
    "Convert selected objects (annotation <-> detection)":
        ["Convert objects"],
    "Prepare training regions":
        ["Training regions"],
    "Train model on corrected regions":
        ["Fine-tuning", "Ground truth"],
    "Export corrections to training corpus":
        ["Ground truth"],
    "Export labelled cells (segmentation + classifier)":
        ["Ground truth"],
    "Classify cells: explore (no labels yet)":
        ["Classification"],
    "Classify cells: train classifier":
        ["Classification"],
    "Classify cells: predict":
        ["Classification"],
]

def allSections = null
EXPECTED.each { task, wanted ->
    def seen = run(task)
    if (allSections == null) {
        allSections = seen.keySet()
        println "sections: ${allSections.join(', ')}"
        println ""
    }
    def open = seen.findAll { k, v -> v }.keySet()
    check("'${task.take(38)}' opens exactly ${wanted}",
            open as Set == wanted as Set, "open=${open}")
    check("...and leaves something open", !open.isEmpty(), "open=${open}")
}

prefs.put("task", savedTask); prefs.put("model", savedModel); prefs.flush()
println ""
println "${pass} checks passed, ${fail} failed"
if (fail > 0) throw new IllegalStateException("${fail} check(s) failed")
