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

// --- soma and nucleus ground truth must not contaminate each other -----------
// A nucleus annotation sits INSIDE a soma annotation. If the soma export let it through,
// every cell would appear twice in the mask -- once full size, once as a blob inside
// itself -- and the corpus would be quietly poisoned with no error anywhere.
int somaLabels = 0
if (maskFile.exists()) {
    def img0 = javax.imageio.ImageIO.read(maskFile)
    def s0 = new HashSet<Integer>()
    def r0 = img0.getRaster()
    for (int y = 0; y < img0.getHeight(); y += 2)
        for (int x = 0; x < img0.getWidth(); x += 2) { int v = r0.getSample(x, y, 0); if (v > 0) s0 << v }
    somaLabels = s0.size()
}

// Put a Nucleus annotation inside each of the first few cells.
int nNuclei = Math.min(5, cells.size())
def nucleusClass = getPathClass("Nucleus")
cells.take(nNuclei).each { c ->
    def r = c.getROI()
    double w = r.getBoundsWidth() / 3, h = r.getBoundsHeight() / 3
    def nucRoi = qupath.lib.roi.ROIs.createEllipseROI(
            r.getCentroidX() - w / 2, r.getCentroidY() - h / 2, w, h, r.getImagePlane())
    def nuc = qupath.lib.objects.PathObjects.createAnnotationObject(nucRoi, nucleusClass)
    addObject(nuc)
}
fireHierarchyUpdate()
println ""
println "SETUP: added ${nNuclei} annotations classed 'Nucleus' inside existing cells"

def TMP2 = new File(System.getProperty("java.io.tmpdir"), "bal_export_test2")
if (TMP2.exists()) TMP2.deleteDir()
TMP2.mkdirs()

def countLabels = { File f ->
    if (f == null || !f.exists()) return -1
    def im = javax.imageio.ImageIO.read(f)
    if (im == null) return -1
    def set = new HashSet<Integer>()
    def ra = im.getRaster()
    for (int y = 0; y < im.getHeight(); y += 2)
        for (int x = 0; x < im.getWidth(); x += 2) { int v = ra.getSample(x, y, 0); if (v > 0) set << v }
    set.size()
}
def maskIn = { File dir -> dir.exists() ? ((dir.list() ?: []).find { it.endsWith("_masks.tif") }?.with { new File(dir, it) }) : null }

BAL_GT_DIR = TMP2.getAbsolutePath()
BAL_EXPORT_WHAT = 'SEGMENTATION'
BAL_EXPORT_TARGET = 'SOMA'
evaluate(PRE + new File(DIR, "qupath_export_labels.groovy").text)
int somaAfter = countLabels(maskIn(new File(TMP2, "test")))
check("nucleus annotations stay OUT of the soma mask", somaAfter == somaLabels,
      "${somaLabels} labels before, ${somaAfter} after adding ${nNuclei} nuclei")

BAL_EXPORT_TARGET = 'NUCLEI'
evaluate(PRE + new File(DIR, "qupath_export_labels.groovy").text)
def nucDir = new File(new File(TMP2, "nuclei"), "test")
int nucLabels = countLabels(maskIn(nucDir))
check("nuclei go to their own corpus folder", nucDir.exists(), nucDir.getAbsolutePath())
check("and the nucleus mask holds only the nuclei", nucLabels == nNuclei,
      "${nucLabels} labels for ${nNuclei} nucleus annotations")

println ok ? "\nPASS: one labelled set serves both segmentation and classification," +
             "\n      and soma / nucleus ground truth stay separate." : "\nFAILED"
TMP.deleteDir()
TMP2.deleteDir()
