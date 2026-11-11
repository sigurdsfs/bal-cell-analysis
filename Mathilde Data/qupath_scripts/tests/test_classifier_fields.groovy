/**
 * The two classifier boxes: the right one is live for the right task, and what you type in
 * either becomes the path you meant.
 *
 * WHY TWO BOXES: saving and loading are different questions. Training WRITES a file that need
 * not exist yet and that you want to name, so one model per experiment survives the next
 * retrain; predicting READS one that must already exist. With a single box those were the
 * same text, so naming a new model silently redirected the next prediction at a file that
 * had not been written -- and the error came out of Python, not the dialog.
 *
 * Headless: it reads enabled/disabled state and exercises the path resolver. It cannot click
 * either Browse button, so the save-prompt vs open-prompt distinction is GUI-only.
 */
import static qupath.lib.scripting.QP.*
import javafx.application.Platform
import java.util.prefs.Preferences
import java.util.concurrent.CountDownLatch

def F = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_bal_gui.groovy")
def SRC = F.text.replace("\r\n", "\n")        // CRLF files: normalise before anchoring
// Hand the dialog's own locals out instead of showing it.
def stubbed = SRC.replace(".showAndWait()",
        ".with { REPORT([out: classifierOutField, outBrowse: classifierOutBrowse," +
        " inp: classifierInField, inBrowse: classifierInBrowse," +
        " resolve: resolveModelPath, dir: CLASSIFY_DIR]); Optional.empty() }")
if (stubbed == SRC) throw new IllegalStateException("could not stub showAndWait -- the anchor moved")

def prefs = Preferences.userRoot().node("bal_segmentation")
def savedTask = prefs.get("task", "Segment cells")
def savedModel = prefs.get("model", "Cellpose cyto3 (whole cells)")
try { Platform.startup({ } as Runnable) } catch (IllegalStateException e) { }

def run = { String task ->
    prefs.put("task", task)
    prefs.put("model", "Cellpose cyto3 (whole cells)")
    prefs.flush()
    def got = [:]
    def err = null
    def latch = new CountDownLatch(1)
    def b = new Binding()
    b.setVariable("REPORT", { m -> got.putAll(m) })
    Platform.runLater({
        try {
            new GroovyShell(this.class.classLoader, b).evaluate(
                    "import static qupath.lib.scripting.QP.*\nimport static qupath.lib.gui.scripting.QPEx.*\n" + stubbed)
        } catch (Throwable x) { err = x } finally { latch.countDown() }
    } as Runnable)
    latch.await()
    if (err) throw err
    got
}

int pass = 0, fail = 0
def check = { String what, boolean ok, String got ->
    if (ok) { pass++; println "ok    ${what}" }
    else { fail++; println "FAIL  ${what}  -- got: ${got}" }
}

// task -> [save box live?, predict box live?]
def EXPECTED = [
    "Classify cells: explore (no labels yet)"            : [false, false],
    "Classify cells: train classifier"                   : [true,  false],
    "Classify cells: train on labelled corpus (all slides)": [true,  false],
    "Classify cells: predict"                            : [false, true],
    // Not a classification task at all: neither box applies.
    "Segment cells"                                      : [false, false],
]

def anyState = null
try {
    EXPECTED.each { task, expect ->
        println ""
        println "--- ${task} ---"
        def g = run(task)
        anyState = g
        boolean saveLive = !g.out.isDisabled(), predictLive = !g.inp.isDisabled()
        check("save box ${expect[0] ? 'live' : 'greyed'}", saveLive == expect[0], "live=${saveLive}")
        check("predict box ${expect[1] ? 'live' : 'greyed'}", predictLive == expect[1], "live=${predictLive}")
        // A live box with a dead Browse button is a box you cannot fill in.
        check("each Browse matches its box",
                g.outBrowse.isDisabled() == g.out.isDisabled() && g.inBrowse.isDisabled() == g.inp.isDisabled(),
                "out=${g.outBrowse.isDisabled()}/${g.out.isDisabled()} in=${g.inBrowse.isDisabled()}/${g.inp.isDisabled()}")
        // The whole point of splitting them: never both at once.
        check("never both live at once", !(saveLive && predictLive), "both live")
    }

    println ""
    println "--- what you type becomes a path ---"
    def resolve = anyState.resolve
    def dir = anyState.dir
    // Empty means "use the worker's own default", which is how it has always behaved.
    check("empty resolves to null (worker default applies)", resolve("") == null, "${resolve('')}")
    check("whitespace too", resolve("   ") == null, "${resolve('   ')}")
    // A bare name is the common case -- naming a model is for keeping several of them.
    check("a bare name lands in bal_classify with .joblib",
            resolve("corpus_v2") == new File(dir, "corpus_v2.joblib"), "${resolve('corpus_v2')}")
    check("an existing .joblib extension is not doubled",
            resolve("corpus_v2.joblib") == new File(dir, "corpus_v2.joblib"), "${resolve('corpus_v2.joblib')}")
    check("the extension check ignores case",
            resolve("Corpus_V2.JOBLIB") == new File(dir, "Corpus_V2.JOBLIB"), "${resolve('Corpus_V2.JOBLIB')}")
    check("surrounding spaces are trimmed",
            resolve("  corpus_v2  ") == new File(dir, "corpus_v2.joblib"), "${resolve('  corpus_v2  ')}")
    def abs = resolve("C:/tmp/models/mine.joblib")
    check("an absolute path is left where it is",
            abs == new File("C:/tmp/models/mine.joblib"), "${abs}")
    def absNoExt = resolve("C:/tmp/models/mine")
    check("an absolute path still gains .joblib",
            absNoExt == new File("C:/tmp/models/mine.joblib"), "${absNoExt}")
    check("a relative subfolder stays under bal_classify",
            resolve("runs/mine") == new File(dir, "runs/mine.joblib"), "${resolve('runs/mine')}")
} finally {
    prefs.put("task", savedTask); prefs.put("model", savedModel); prefs.flush()
}

println ""
println "--- the dispatch uses the right box for the right mode ---"
// Structural: the enable states above would look identical if the dispatch read the wrong
// field, because syncEnabled and the dispatch are different code.
check("PREDICT reads the predict box, otherwise the save box",
        SRC.contains("BAL_CLASSIFY_MODE == 'PREDICT'\n            ? classifierInField.getText() : classifierOutField.getText()"),
        "not found")
check("a missing model for predict is refused in the dialog",
        SRC.contains("No classifier at"), "not found")
check("the old single preference still seeds both boxes",
        SRC.contains('def legacyModel = prefs.get("classifier", "")') &&
        SRC.contains('prefs.get("classifierOut", legacyModel)') &&
        SRC.contains('prefs.get("classifierIn", legacyModel)'), "not found")
check("saving uses a save prompt, loading an open prompt",
        SRC.contains("promptToSaveFile") && SRC.contains("promptForFile"), "not found")
check("the folder comes from bal_config, not a literal",
        SRC.contains("CFG.balClassifyDir"), "not found")

println ""
println "${pass} checks passed, ${fail} failed"
if (fail > 0) throw new IllegalStateException("${fail} check(s) failed")
