/**
 * PRE + sub-script must COMPILE as one script, because that is how it is run.
 *
 * Each caller prepends its PRE block to a worker and evaluates the concatenation, so the
 * two share one scope. qupath_classify_cells.groovy carried a `def CFG = [:]` inside its
 * PRE while every worker declares its own, and the combination failed with
 *     "The current scope already contains a variable of the name CFG"
 * -- so no classify task ever got past the export step. Each file on its own parsed
 * perfectly, which is exactly why compile_only could not see it.
 *
 * Compiling is normally not enough for this project (Groovy resolves names at runtime),
 * but a duplicate declaration IS a compile error, so here it is the right instrument.
 */
import static qupath.lib.scripting.QP.*

def DIR = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts")
if (!DIR.exists()) { println "script folder not found: ${DIR}"; return }

int pass = 0, fail = 0, pairs = 0

DIR.listFiles({ f -> f.getName().endsWith(".groovy") } as java.io.FileFilter).sort { it.getName() }.each { f ->
    def src = f.getText("UTF-8").replace("\r\n", "\n")       // CRLF: normalise before matching
    // The PRE block: def PRE = """ ... """
    def m = (src =~ /(?s)def\s+PRE\s*=\s*"""(.*?)"""/)
    if (!m) return
    def pre = m[0][1]
    // Every worker it evaluates with that PRE.
    def subs = (src =~ /evaluate\(\s*PRE\s*\+\s*new File\([^,]+,\s*"([^"]+\.groovy)"\)/)
                   .collect { it[1] }.unique()
    if (subs.isEmpty()) return
    subs.each { name ->
        def sub = new File(DIR, name)
        if (!sub.exists()) {
            fail++
            println "FAIL  ${f.getName()} -> ${name}: the sub-script does not exist"
            return
        }
        pairs++
        def combined = pre + sub.getText("UTF-8").replace("\r\n", "\n")
        try {
            new GroovyShell(this.class.classLoader, new Binding()).parse(combined)
            pass++
            println "ok    ${f.getName().padRight(34)} + ${name}"
        } catch (Throwable t) {
            fail++
            def msg = (t.message ?: t.toString()).readLines().findAll { it.trim() }.take(3).join(" | ")
            println "FAIL  ${f.getName().padRight(34)} + ${name}"
            println "        ${msg}"
        }
    }
}

println ""
println "${pairs} PRE+worker combination(s) checked: ${pass} compile, ${fail} broken"
if (fail > 0) throw new IllegalStateException("${fail} combination(s) do not compile")
