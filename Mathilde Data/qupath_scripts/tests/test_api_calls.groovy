// Every Dialogs.x / FileChoosers.x call in the BAL scripts must actually exist, with a
// matching argument count. Groovy resolves these at runtime, so a wrong one only shows up
// when a user clicks the button that reaches it -- which is how promptForFile slipped past.
import static qupath.lib.scripting.QP.*
import java.lang.reflect.Modifier

def classes = [
    "Dialogs"     : qupath.fx.dialogs.Dialogs,
    "FileChoosers": qupath.fx.dialogs.FileChoosers,
]
// name -> set of allowed argument counts (varargs count as "at least")
def available = [:]
classes.each { alias, cls ->
    def byName = [:].withDefault { [] as Set }
    cls.getDeclaredMethods().findAll { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }.each { m ->
        byName[m.name] << (m.isVarArgs() ? -m.parameterCount : m.parameterCount)
    }
    available[alias] = byName
}

def dir = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts")
int problems = 0, checked = 0
dir.listFiles().findAll { it.name.endsWith(".groovy") }.sort { it.name }.each { f ->
    def text = f.text
    // Matches Dialogs.foo( or qupath.fx.dialogs.FileChoosers.foo(
    def matcher = (text =~ /(?:qupath\.fx\.dialogs\.)?(Dialogs|FileChoosers)\.(\w+)\s*\(/)
    matcher.each { all, alias, method ->
        checked++
        def counts = available[alias][method]
        if (counts.isEmpty()) {
            problems++
            def near = available[alias].keySet().findAll { it.toLowerCase().contains(method.toLowerCase().take(6)) }
            println "MISSING  ${f.name}: ${alias}.${method}(...) does not exist" +
                    (near ? "  -- did you mean ${near}?" : "")
            // say where it does exist, if it does
            classes.each { otherAlias, cls ->
                if (otherAlias != alias && !available[otherAlias][method].isEmpty())
                    println "         (${method} exists on ${otherAlias})"
            }
        }
    }
}
println ""
println "${checked} Dialogs/FileChoosers call(s) checked across the BAL scripts"
println problems == 0 ? "All exist." : "${problems} PROBLEM(S) -- fix before shipping."
