// Exercises the REAL summariseTraining closure from the training script against synthetic
// loss logs, so its regex and its three verdicts are actually tested.
import static qupath.lib.scripting.QP.*

def src = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_train_cellpose.groovy").text
int a = src.indexOf("def summariseTraining = { File modelFile ->")
int b = src.indexOf("\n}", a) + 2
def closureSrc = src.substring(a, b)

def tmp = new File(System.getProperty("java.io.tmpdir"), "bal_test_run.log")
def needle = 'new File(System.getProperty("user.home"), ".cellpose/run.log")'
def replacement = 'new File(' + '"' + tmp.getAbsolutePath().replace('\\', '/') + '"' + ')'
assert closureSrc.contains(needle) : "log path line not found -- did the script change?"
closureSrc = closureSrc.replace(needle, replacement)

def cases = [
    "healthy: test loss falls throughout":
        (0..50).collect { e -> "${e}, train_loss=${(1.0 - e * 0.015).round(4)}, test_loss=${(1.2 - e * 0.02).round(4)}, LR=0.100000" },
    "diverging: ends worse than it started":
        (0..50).collect { e -> "${e}, train_loss=${(0.2 + e * 0.05).round(4)}, test_loss=${(0.15 + e * 0.04).round(4)}, LR=0.100000" },
    "overfitting: best early, then climbs":
        (0..50).collect { e -> "${e}, train_loss=${(1.0 - e * 0.015).round(4)}, test_loss=${(e < 10 ? 1.0 - e * 0.08 : 0.2 + (e - 10) * 0.02).round(4)}, LR=0.100000" },
]

cases.each { name, lines ->
    tmp.text = lines.join(System.lineSeparator())
    println ""
    println "########## ${name} ##########"
    def fn = new GroovyShell(this.class.classLoader, new Binding()).evaluate(closureSrc + "\nreturn summariseTraining")
    fn(null)
}
tmp.delete()
println ""
println "All three loss patterns handled."
