// Re-tests checkpoint pruning, including the failure that actually happened: the extension
// runs a QC prediction inside train(), wiping run.log, so the final prune had no losses and
// deleted the BEST checkpoint. The fix must (a) remember losses seen earlier and
// (b) refuse to delete anything when it has no loss data at all.
import static qupath.lib.scripting.QP.*

def src = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_train_cellpose.groovy").text
int a = src.indexOf("def epochOf = { File f ->")
int b = src.indexOf("\n// Final tidy-up", a)
def logic = src.substring(a, b)

def fakeLog = new File(System.getProperty("java.io.tmpdir"), "bal_fake_run.log")
def needle = 'new File(System.getProperty("user.home"), ".cellpose/run.log")'
assert logic.contains(needle)
logic = logic.replace(needle, 'new File("' + fakeLog.getAbsolutePath().replace('\\', '/') + '")')

def dir = new File(System.getProperty("java.io.tmpdir"), "bal_prune_test2")
if (dir.exists()) dir.listFiles().each { it.delete() }
dir.mkdirs()

def losses = [0: 0.158, 5: 0.143, 10: 0.126, 15: 0.133, 20: 0.138, 25: 0.141, 30: 0.150]
def shell = new GroovyShell(this.class.classLoader, new Binding())
def fns = shell.evaluate(logic + "\nlogSnapshot = new File('" +
        new File(dir, "snap.txt").getAbsolutePath().replace('\\','/') + "')\n" +
        "return [epochOf: epochOf, prune: pruneCheckpoints, snap: snapshotLog, losses: readTestLosses]")

println "training: loss bottoms at epoch 10, then drifts up (like the real cpsam run)"
def seen = []
[0, 5, 10, 15, 20, 25, 30].each { e ->
    new File(dir, "cellpose_123_epoch_" + String.format("%04d", e)).text = "w${e}"
    seen << e
    fakeLog.text = seen.collect { ep -> "${ep}, train_loss=0.1, test_loss=${losses[ep]}, LR=0.00001" }.join(System.lineSeparator())
    fns.snap()
    fns.prune(dir, false)
    println "  after epoch ${e} -> ${dir.listFiles().findAll{ fns.epochOf(it) >= 0 }.collect{ fns.epochOf(it) }.sort()}"
}

println ""
println "now the QC prediction wipes run.log, exactly as the extension does:"
fakeLog.text = "cellpose version: 4.2.1.1\nrunning cellpose on 1 images\n"   // no loss lines
def r = fns.prune(dir, false)
def remaining = dir.listFiles().findAll { fns.epochOf(it) >= 0 }.collect { fns.epochOf(it) }.sort()
println "  files after the final prune: ${remaining}"
println "  best identified as epoch: ${r.best != null ? fns.epochOf(r.best) : 'NONE'}"
println remaining == [10, 30] && r.best != null && fns.epochOf(r.best) == 10 ?
        "PASS: best (10) and last (30) both survive the log being wiped" :
        "FAIL: expected [10, 30] with best=10"
dir.listFiles().each { it.delete() }; dir.delete(); fakeLog.delete()
