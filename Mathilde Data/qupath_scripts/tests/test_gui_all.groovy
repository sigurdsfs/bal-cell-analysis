import static qupath.lib.scripting.QP.*
import javafx.application.Platform
import java.util.prefs.Preferences
import java.util.concurrent.CountDownLatch

def F = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_bal_gui.groovy")
def stubbed = F.text.replace(".showAndWait()", ".with { Optional.empty() }")
def prefs = Preferences.userRoot().node("bal_segmentation")
def tasks = ["Segment cells", "Pair soma + nuclei into cells",
             "Convert selected objects (annotation <-> detection)", "Prepare training regions",
             "Export corrections to training corpus", "Train model on corrected regions",
             "Classify cells: explore (no labels yet)", "Classify cells: train classifier",
             "Classify cells: predict", "Export labelled cells (segmentation + classifier)"]
def models = ["Cellpose cyto3 (whole cells)", "Cellpose cyto2 (whole cells)",
              "Cellpose-SAM (cpsam)", "Custom Cellpose model..."]
try { Platform.startup({ } as Runnable) } catch (IllegalStateException e) { }

int pass = 0, fail = 0
tasks.each { t -> models.each { mo ->
    prefs.put("task", t); prefs.put("model", mo); prefs.flush()
    def latch = new CountDownLatch(1); def err = null
    Platform.runLater({
        try {
            new GroovyShell(this.class.classLoader, new Binding()).evaluate(
                "import static qupath.lib.scripting.QP.*\nimport static qupath.lib.gui.scripting.QPEx.*\n" + stubbed)
        } catch (Throwable x) { err = x } finally { latch.countDown() }
    } as Runnable)
    latch.await()
    if (err) { fail++; println "FAIL  ${t} / ${mo}: ${err.class.simpleName}: ${err.message?.take(90)}" }
    else pass++
}}
println "\n${pass} combinations OK, ${fail} failed"
// leave the prefs in a sensible state
prefs.put("task", "Segment cells"); prefs.put("model", "Cellpose cyto3 (whole cells)"); prefs.flush()
