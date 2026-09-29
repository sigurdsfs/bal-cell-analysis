// Every value the dialog can put in a field must pass the dialog's own validation.
// This catches "the default is outside its own allowed range", which shipped twice.
import static qupath.lib.scripting.QP.*
def text = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_bal_gui.groovy").text

// asNumber(field, "Name", min, max)
def bounds = [:]
(text =~ /asNumber\((\w+),\s*"([^"]+)",\s*([0-9.eE_-]+),\s*([0-9.eE_-]+)\)/).each { all, field, name, lo, hi ->
    bounds[field] = [name: name, lo: Double.parseDouble(lo.replace("_","")), hi: Double.parseDouble(hi.replace("_",""))]
}
// values the script itself writes into fields: defaults and the model-driven setText calls
def values = [:].withDefault { [] }
(text =~ /def (\w+Field) = new TextField\(prefs\.get\("[^"]*",\s*"([^"]*)"\)\)/).each { all, field, dflt ->
    if (dflt.isNumber()) values[field] << [dflt as double, "default"]
}
(text =~ /(\w+Field)\.setText\(\s*\w+\s*\?\s*"([^"]+)"\s*:\s*"([^"]+)"\)/).each { all, field, a, b ->
    if (a.isNumber()) values[field] << [a as double, "model-driven"]
    if (b.isNumber()) values[field] << [b as double, "model-driven"]
}
int bad = 0
bounds.each { field, b ->
    values[field].each { v, origin ->
        boolean ok = v >= b.lo && v <= b.hi
        if (!ok) { bad++; println "FAIL ${field} ${origin} value ${v} is outside its own range [${b.lo}, ${b.hi}] (\"${b.name}\")" }
        else println "ok   ${field} ${origin} ${v} within [${b.lo}, ${b.hi}]"
    }
}
println bad == 0 ? "\nAll dialog values pass the dialog's own validation." : "\n${bad} PROBLEM(S)"
