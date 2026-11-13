/**
 * The default soma model, and that a saved preference does not hide a change to it.
 *
 * "Saved preferences beat new defaults" is the trap this guards: the dialog remembers the
 * model, so moving the default from cyto3 to cpsam reaches nobody who has already run it
 * unless DEFAULTS_VERSION is bumped and the stale key dropped. That is invisible on a fresh
 * machine and invisible in a compile check, so it is checked by running the dialog.
 *
 * Headless-only: it reads the dropdown's value, not what the window looks like.
 */
import static qupath.lib.scripting.QP.*
import javafx.application.Platform
import java.util.prefs.Preferences
import java.util.concurrent.CountDownLatch

final String DEFAULT_MODEL = "Cellpose-SAM (cpsam)"

def F = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_bal_gui.groovy")
def SRC = F.text.replace("\r\n", "\n")        // CRLF files: normalise before anchoring
def stubbed = SRC.replace(".showAndWait()", ".with { REPORT(modelBox); Optional.empty() }")

def prefs = Preferences.userRoot().node("bal_segmentation")
def savedModel = prefs.get("model", DEFAULT_MODEL)
def savedTask = prefs.get("task", "Segment cells")
def savedVersion = prefs.getInt("defaultsVersion", 1)

try { Platform.startup({ } as Runnable) } catch (IllegalStateException e) { }

/** Run the dialog with the prefs left in whatever state the caller set, and report the box. */
def run = {
    def seen = [:]
    def err = null
    def latch = new CountDownLatch(1)
    def b = new Binding()
    b.setVariable("REPORT", { box ->
        seen.value = box.getValue()
        seen.items = box.getItems().collect { it }
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

// What DEFAULTS_VERSION the GUI currently declares. Read from the source rather than
// hard-coded, so the test follows the bump instead of having to be edited alongside it.
def m = (SRC =~ /final int DEFAULTS_VERSION = (\d+)/)
assert m.find() : "no DEFAULTS_VERSION in the GUI"
int version = m.group(1) as int
println "DEFAULTS_VERSION = ${version}\n"

println "=== a machine that has never run the dialog ==="
prefs.put("task", "Segment cells")
prefs.remove("model")
prefs.remove("defaultsVersion")
prefs.flush()
def r = run()
check("the model defaults to ${DEFAULT_MODEL}", r.value == DEFAULT_MODEL, "${r.value}")
check("and it is first in the dropdown, where the default belongs",
        r.items && r.items[0] == DEFAULT_MODEL, "${r.items}")
check("the custom entry is still last", r.items && r.items[-1].startsWith("Custom"), "${r.items}")

println "\n=== a machine that remembers the OLD default ==="
prefs.put("model", "Cellpose cyto3 (whole cells)")
prefs.putInt("defaultsVersion", version - 1)
prefs.flush()
r = run()
check("the stale remembered model is dropped, so the new default is seen",
        r.value == DEFAULT_MODEL, "${r.value}")

println "\n=== a model chosen deliberately, at the current version ==="
prefs.put("model", "Cellpose cyto3 (whole cells)")
prefs.putInt("defaultsVersion", version)
prefs.flush()
r = run()
check("a choice made since the bump is still honoured",
        r.value == "Cellpose cyto3 (whole cells)", "${r.value}")

println "\n=== a model that no longer exists ==="
prefs.put("model", "cyto3 cells + InstanSeg nuclei")
prefs.putInt("defaultsVersion", version)
prefs.flush()
r = run()
check("falls back to the default rather than showing blank",
        r.value == DEFAULT_MODEL, "${r.value}")

prefs.put("task", savedTask); prefs.put("model", savedModel)
prefs.putInt("defaultsVersion", savedVersion); prefs.flush()
println ""
println "${pass} checks passed, ${fail} failed"
if (fail > 0) throw new IllegalStateException("${fail} check(s) failed")
