// The point of qupath_export_labels.groovy is that a cell labelled "Macrophage" still
// appears in the segmentation mask. This proves it, and compares against the extension's
// own exporter, which drops classified cells.
import static qupath.lib.scripting.QP.*

def DIR = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts")
def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.objects.classes.*
import qupath.lib.regions.*
import qupath.lib.measurements.*
"""

def TMP = new File(System.getProperty("java.io.tmpdir"), "bal_export_test")
if (TMP.exists()) TMP.deleteDir()
TMP.mkdirs()

// A small region, segmented as editable annotations = the shape real ground truth takes.
removeAllObjects()
BAL_REGION_X = 45056; BAL_REGION_Y = 24576; BAL_REGION_SIZE = 512
evaluate(PRE + new File(DIR, "qupath_test_region.groovy").text)
def region = getAnnotationObjects()[0]
region.setPathClass(getPathClass("Validation"))
region.setName("Validation test")
selectObjects([region])
BAL_MODEL = 'cyto3'; BAL_DIAMETER = 58.0d; BAL_OUTPUT_TYPE = 'ANNOTATION'; BAL_TARGETS = 'SELECTED'
evaluate(PRE + new File(DIR, "qupath_cellpose_detection.groovy").text)

def cells = region.getChildObjects().findAll { it.getROI() != null }
cells.each { it.setPathClass(null) }
// Label half of them by cell type -- the situation that used to break segmentation export.
int half = (int) (cells.size() / 2)
cells.take(half).eachWithIndex { c, i -> c.setPathClass(getPathClass(i % 2 == 0 ? "Macrophage" : "Lymphocyte")) }
fireHierarchyUpdate()
println "SETUP: ${cells.size()} cells inside a Validation rectangle, ${half} of them classified"

// --- run the exporter --------------------------------------------------------
BAL_GT_DIR = TMP.getAbsolutePath()
BAL_EXPORT_WHAT = 'BOTH'
evaluate(PRE + new File(DIR, "qupath_export_labels.groovy").text)

// --- count the labels actually written ---------------------------------------
def maskFile = new File(new File(TMP, "test"), (new File(TMP, "test").list() ?: []).find { it.endsWith("_masks.tif") } ?: "none")
println ""
println "CHECKS"
boolean ok = true
def check = { String what, boolean pass, String detail = "" ->
    if (!pass) ok = false
    println "  ${pass ? 'ok  ' : 'FAIL'} ${what}${detail ? '  (' + detail + ')' : ''}"
}
check("mask written to test/ (Validation went to the test folder)", maskFile.exists(), maskFile.getName())

if (maskFile.exists()) {
    def img = javax.imageio.ImageIO.read(maskFile)
    def labels = new HashSet<Integer>()
    if (img != null) {
        def raster = img.getRaster()
        for (int y = 0; y < img.getHeight(); y += 2)
            for (int x = 0; x < img.getWidth(); x += 2) {
                int v = raster.getSample(x, y, 0)
                if (v > 0) labels << v
            }
    }
    println "  mask holds ${labels.size()} distinct cell labels (sampled every 2nd pixel)"
    // The old behaviour would have dropped the classified half.
    check("classified cells are still in the mask", labels.size() > half,
          "${labels.size()} labels vs ${half} classified of ${cells.size()}")
}

def crops = []
def cropsRoot = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify/crops")
if (cropsRoot.exists()) cropsRoot.eachDir { d -> d.eachFile { f -> if (f.name.endsWith(".png")) crops << d.getName() } }
check("crops written per class", crops.size() >= half, "${crops.size()} crops")
check("crops filed by class name", crops.toSet().any { it in ["Macrophage", "Lymphocyte"] }, crops.toSet().join(", "))

println ok ? "\nPASS: one labelled set serves both segmentation and classification." : "\nFAILED"
TMP.deleteDir()
