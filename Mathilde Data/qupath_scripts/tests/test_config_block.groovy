/**
 * Every script that reads CFG must declare CFG first -- in its OWN body.
 *
 * This exists because qupath_classify_cells.groovy read CFG.balClassifyDir at line 33 while
 * its only `def CFG = [:]` sat inside the triple-quoted PRE string further down. That string
 * is the prelude prepended to the SUB-scripts it evaluates, so it declares nothing in the
 * script holding it. The result was "No such property: CFG", and nothing caught it: the
 * script parses fine, and test_gui_all stops at the dialog, never reaching the worker.
 *
 * So the check masks triple-quoted strings and comments before looking, which is exactly the
 * distinction that was missed.
 */
import static qupath.lib.scripting.QP.*

def dir = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts")
if (!dir.exists()) {
    println "script folder not found: ${dir}"
    return
}

/** Blank out triple-quoted strings, block comments and line comments, keeping line numbers. */
def strip = { String src ->
    // The closure gets the WHOLE match when the pattern has no capture groups, so take it
    // as-is: `it[0]` would be its first character, which silently shifts every line number.
    def keepLines = { String s -> "\n" * s.count("\n") }
    src = src.replaceAll(/(?s)""".*?"""/) { String m -> keepLines(m) }
    src = src.replaceAll(/(?s)'''.*?'''/) { String m -> keepLines(m) }
    src = src.replaceAll(/(?s)\/\*.*?\*\//) { String m -> keepLines(m) }
    src = src.replaceAll(/(?m)\/\/.*$/, "")
    src
}

int pass = 0, fail = 0
dir.listFiles({ f -> f.getName().endsWith(".groovy") } as java.io.FileFilter).sort { it.getName() }.each { f ->
    // bal_config.groovy IS the config; it has no CFG of its own to declare.
    if (f.getName() == "bal_config.groovy") return
    def lines = strip(f.getText("UTF-8").replace("\r\n", "\n")).split("\n", -1)
    def uses = [], decls = []
    lines.eachWithIndex { line, i ->
        if (line =~ /\bCFG\b/) uses << (i + 1)
        if (line =~ /^\s*def\s+CFG\b/) decls << (i + 1)
    }
    if (uses.isEmpty()) return
    if (decls.isEmpty()) {
        fail++
        println "FAIL  ${f.getName()}: reads CFG at line ${uses[0]} but never declares it " +
                "(is the only 'def CFG' inside a PRE string?)"
    } else if (uses[0] < decls[0]) {
        fail++
        println "FAIL  ${f.getName()}: reads CFG at line ${uses[0]}, declared only at line ${decls[0]}"
    } else {
        pass++
        println "ok    ${f.getName()}: declares CFG at line ${decls[0]}, first read at ${uses[0]}"
    }
}

println ""
println "${pass} script(s) correct, ${fail} broken"
if (fail > 0) throw new IllegalStateException("${fail} script(s) read CFG before declaring it")
