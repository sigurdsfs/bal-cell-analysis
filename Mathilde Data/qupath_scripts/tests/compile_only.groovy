import static qupath.lib.scripting.QP.*
new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts").listFiles().findAll{it.name.endsWith(".groovy")}.each { f ->
  try { new GroovyShell(this.class.classLoader).parse(f.text); println "OK   ${f.name}" }
  catch (Exception e) { println "FAIL ${f.name}: ${e.message?.take(140)}" } }
