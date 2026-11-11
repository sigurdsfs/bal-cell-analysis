// The one export, and its two independent halves.
//
// The point of qupath_export_labels.groovy is that a cell labelled "Macrophage" still appears
// in the segmentation mask -- the extension's own exporter drops it, which is why this script
// replaced qupath_export_training_data.groovy. Since the two exports merged, each half is a
// separate tick box with its own folder, so this also checks that one can run without the
// other and that neither writes into the other's folder.
//
// IT WRITES ONLY TO TEMP. Both output folders are overridden; an earlier version had no
// override for the crops folder and added to the REAL corpus every time it ran.
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

def fresh = { String name ->
    def d = new File(System.getProperty("java.io.tmpdir"), name)
    if (d.exists()) d.deleteDir()
    d.mkdirs()
    d
}
def TMP = fresh("bal_export_test")
def CROPS_HOME = fresh("bal_export_crops")
def REAL_CROPS = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify/crops")
int realCropsBefore = REAL_CROPS.exists() ? (REAL_CROPS.listFiles() ?: []).sum { d ->
    d.isDirectory() ? (d.listFiles({ f -> f.name.endsWith(".png") } as java.io.FileFilter)?.length ?: 0) : 0
} ?: 0 : 0

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

boolean ok = true
def check = { String what, boolean pass, String detail = "" ->
    if (!pass) ok = false
    // The detail is what went WRONG, so printing it next to "ok" reads as a contradiction.
    println "  ${pass ? 'ok  ' : 'FAIL'} ${what}${!pass && detail ? '  -- got: ' + detail : ''}"
}
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
def countCrops = { File home ->
    def root = new File(home, "crops")
    if (!root.exists()) return []
    def found = []
    root.eachDir { d -> d.eachFile { f -> if (f.name.endsWith(".png")) found << d.getName() } }
    found
}

// =============================================================================
println ""
println "=== both halves on ==="
BAL_GT_DIR = TMP.getAbsolutePath()
BAL_CLASSIFY_DIR = CROPS_HOME.getAbsolutePath()
BAL_EXPORT_SEG = true
BAL_EXPORT_CROPS = true
BAL_EXPORT_TARGET = 'SOMA'
evaluate(PRE + new File(DIR, "qupath_export_labels.groovy").text)

def maskFile = maskIn(new File(TMP, "test"))
check("mask written to test/ (Validation went to the test folder)", maskFile != null && maskFile.exists(),
        "${maskFile?.getName()}")
int somaLabels = countLabels(maskFile)
println "  mask holds ${somaLabels} distinct cell labels (sampled every 2nd pixel)"
// The old exporter would have dropped the classified half.
check("classified cells are still in the mask", somaLabels > half,
      "${somaLabels} labels vs ${half} classified of ${cells.size()}")

def crops = countCrops(CROPS_HOME)
check("crops written per class", crops.size() >= half, "${crops.size()} crops")
check("crops filed by class name", crops.toSet().any { it in ["Macrophage", "Lymphocyte"] }, crops.toSet().join(", "))
check("labels.csv sits BESIDE the crops folder, where embed_cells.py looks",
        new File(CROPS_HOME, "labels.csv").exists(), "missing")
check("the crop style is recorded in the folder too",
        new File(new File(CROPS_HOME, "crops"), "crop_style.txt").exists(), "missing")

// =============================================================================
// Each half must be usable on its own: re-cutting crops after a cropStyle change should not
// rewrite masks that have not changed, and vice versa.
println ""
println "=== masks only ==="
def TMP_SEG = fresh("bal_export_seg_only")
def CROPS_SEG = fresh("bal_export_crops_seg_only")
BAL_GT_DIR = TMP_SEG.getAbsolutePath()
BAL_CLASSIFY_DIR = CROPS_SEG.getAbsolutePath()
BAL_EXPORT_SEG = true
BAL_EXPORT_CROPS = false
evaluate(PRE + new File(DIR, "qupath_export_labels.groovy").text)
check("masks written", maskIn(new File(TMP_SEG, "test")) != null, "none")
check("NO crops written", countCrops(CROPS_SEG).isEmpty(), "${countCrops(CROPS_SEG).size()} crops")
check("no labels.csv either", !new File(CROPS_SEG, "labels.csv").exists(), "written anyway")

println ""
println "=== crops only ==="
def TMP_CROP = fresh("bal_export_crop_only")
def CROPS_CROP = fresh("bal_export_crops_crop_only")
BAL_GT_DIR = TMP_CROP.getAbsolutePath()
BAL_CLASSIFY_DIR = CROPS_CROP.getAbsolutePath()
BAL_EXPORT_SEG = false
BAL_EXPORT_CROPS = true
evaluate(PRE + new File(DIR, "qupath_export_labels.groovy").text)
check("crops written", !countCrops(CROPS_CROP).isEmpty(), "none")
check("NO masks written", maskIn(new File(TMP_CROP, "test")) == null && maskIn(new File(TMP_CROP, "train")) == null,
        "a mask appeared")

// =============================================================================
// A nucleus annotation sits INSIDE a soma annotation. If the soma export let it through,
// every cell would appear twice in the mask -- once full size, once as a blob inside itself --
// and the corpus would be quietly poisoned with no error anywhere.
// Only cells CENTRED inside the rectangle get one. Since regions are padded, a cell may
// legitimately straddle the rectangle edge and be kept (ground-truth regions keep everything
// that reaches them), and a nucleus at such a cell's centroid would sit outside the exported
// region -- correctly absent from a mask that covers the rectangle only. Placing nuclei
// without that filter made this test fail for a reason that was not a bug.
def regionRoi = region.getROI()
def inside = cells.findAll { regionRoi.contains(it.getROI().getCentroidX(), it.getROI().getCentroidY()) }
int nNuclei = Math.min(5, inside.size())
def nucleusClass = getPathClass("Nucleus")
inside.take(nNuclei).each { c ->
    def r = c.getROI()
    double w = r.getBoundsWidth() / 3, h = r.getBoundsHeight() / 3
    def nucRoi = qupath.lib.roi.ROIs.createEllipseROI(
            r.getCentroidX() - w / 2, r.getCentroidY() - h / 2, w, h, r.getImagePlane())
    addObject(qupath.lib.objects.PathObjects.createAnnotationObject(nucRoi, nucleusClass))
}
fireHierarchyUpdate()
println "  (${cells.size()} cells, ${inside.size()} centred inside the rectangle -- the rest" +
        " straddle its edge, which padding now allows)"
println ""
println "=== soma and nucleus corpora stay separate (${nNuclei} 'Nucleus' annotations added) ==="

def TMP2 = fresh("bal_export_test2")
BAL_GT_DIR = TMP2.getAbsolutePath()
BAL_CLASSIFY_DIR = fresh("bal_export_crops2").getAbsolutePath()
BAL_EXPORT_SEG = true
BAL_EXPORT_CROPS = false
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

// =============================================================================
println ""
println "=== the real corpus was not touched ==="
int realCropsAfter = REAL_CROPS.exists() ? (REAL_CROPS.listFiles() ?: []).sum { d ->
    d.isDirectory() ? (d.listFiles({ f -> f.name.endsWith(".png") } as java.io.FileFilter)?.length ?: 0) : 0
} ?: 0 : 0
check("the real crops folder has the same number of crops", realCropsAfter == realCropsBefore,
        "${realCropsBefore} before, ${realCropsAfter} after")

// Structural: refusing an export with both boxes unticked puts up a dialog, which cannot be
// driven headlessly, so check the guard is there rather than triggering it.
def src = new File(DIR, "qupath_export_labels.groovy").getText("UTF-8")
check("refuses to run with both halves off", src.contains("if (!SEG && !CROPS)"), "guard missing")
check("the crops folder is overridable", src.contains("BAL_CLASSIFY_DIR"), "not found")
check("the older single-valued BAL_EXPORT_WHAT is still honoured",
        src.contains("binding.hasVariable('BAL_EXPORT_WHAT')"), "not found")

// Legacy form LAST: once BAL_EXPORT_WHAT is in the binding it takes precedence over the two
// booleans for the rest of this script, so nothing after it could use them.
println ""
println "=== legacy BAL_EXPORT_WHAT still works ==="
def TMP3 = fresh("bal_export_test3")
BAL_GT_DIR = TMP3.getAbsolutePath()
BAL_EXPORT_TARGET = 'SOMA'
BAL_EXPORT_WHAT = 'SEGMENTATION'
evaluate(PRE + new File(DIR, "qupath_export_labels.groovy").text)
check("'SEGMENTATION' writes masks", maskIn(new File(TMP3, "test")) != null, "none")
check("and no crops", countCrops(new File(BAL_CLASSIFY_DIR)).isEmpty(), "crops appeared")

println ok ? "\nPASS: one labelled set serves both jobs, each half runs on its own," +
             "\n      soma / nucleus corpora stay separate, and only temp folders were written."
           : "\nFAILED"
[TMP, TMP2, TMP3, CROPS_HOME, TMP_SEG, CROPS_SEG, TMP_CROP, CROPS_CROP].each { it.deleteDir() }
new File(System.getProperty("java.io.tmpdir"), "bal_export_crops2").deleteDir()
if (!ok) throw new IllegalStateException("export checks failed")
