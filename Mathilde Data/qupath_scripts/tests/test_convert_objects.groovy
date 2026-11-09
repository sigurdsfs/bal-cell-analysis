/**
 * Annotation <-> detection conversion, RUN rather than compiled.
 *
 * WHY THIS IS WORTH A HARNESS: the conversion rebuilds every object, so it is exactly the
 * kind of code that silently loses something -- the class, the measurements, the object ID
 * the classifier matches predictions by, the nucleus outline of a cell, or the object's
 * place in the hierarchy. None of that shows up in a compile.
 *
 * NEEDS NO PROJECT. It builds a 400x400 synthetic image and makes that the current image
 * for this thread, so it never touches the real slides -- important, because the test
 * clears the hierarchy between cases.
 *
 * Dialogs.showErrorMessage is stubbed out: headless it has no window to open, and the
 * refusal paths are a part of the behaviour worth checking.
 */
import static qupath.lib.scripting.QP.*
import qupath.lib.images.ImageData
import qupath.lib.images.servers.WrappedBufferedImageServer
import qupath.lib.objects.PathObjects
import qupath.lib.objects.PathCellObject
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import java.awt.image.BufferedImage

def F = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts/qupath_convert_objects.groovy")
if (!F.exists()) { println "not found: ${F}"; return }
def SRC = F.getText("UTF-8").replace("\r\n", "\n")       // CRLF: normalise before anchoring
def STUBBED = SRC.replace("Dialogs.showErrorMessage(", "ERR(")
if (STUBBED == SRC) { println "FAIL  could not stub Dialogs.showErrorMessage"; return }

// The imports the GUI prepends; the worker is always run as PRE + itself.
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

// --- a throwaway image, so nothing real is at risk ---------------------------
def server = new WrappedBufferedImageServer("convert-test",
        new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB))
def imageData = new ImageData<BufferedImage>(server)
setBatchProjectAndImage(null, imageData)
def hierarchy = getCurrentHierarchy()
if (hierarchy == null) { println "FAIL  no hierarchy -- the synthetic image did not take"; return }

def PLANE = ImagePlane.getDefaultPlane()
int pass = 0, fail = 0
def check = { String what, boolean ok, String got ->
    if (ok) { pass++; println "ok    ${what}" }
    else { fail++; println "FAIL  ${what}  -- got: ${got}" }
}

// --- the fixtures ------------------------------------------------------------
def errors = []
def makeDetection = { double x, String cls, String name ->
    def o = PathObjects.createDetectionObject(ROIs.createEllipseROI(x, 50, 30, 30, PLANE),
            cls == null ? null : PathClass.fromString(cls))
    o.setName(name)
    o.getMeasurementList().put("area_um2", 12.5)
    o.getMeasurementList().close()
    o
}
def makeAnnotation = { double x, String cls ->
    PathObjects.createAnnotationObject(ROIs.createEllipseROI(x, 150, 30, 30, PLANE),
            cls == null ? null : PathClass.fromString(cls))
}
def makeCell = { double x, String cls ->
    PathObjects.createCellObject(ROIs.createEllipseROI(x, 250, 40, 40, PLANE),
            ROIs.createEllipseROI(x + 10, 260, 20, 20, PLANE),
            cls == null ? null : PathClass.fromString(cls), null)
}

/** Run the worker over whatever is selected, with the given BAL_* settings. */
def run = { Map opts ->
    errors = []
    def b = new Binding()
    b.setVariable("ERR", { String title, String msg -> errors << "${title}: ${msg}" })
    opts.each { k, v -> b.setVariable(k, v) }
    new GroovyShell(this.class.classLoader, b).evaluate(PRE + STUBBED)
}

// === 1. detections -> annotations ============================================
println "=== detections -> annotations ==="
removeAllObjects()
def tissue = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 400, 400, PLANE))
def det = makeDetection(50, "Macrophage", "d1")
hierarchy.insertPathObjects([tissue, det])
def oldId = det.getID()
check("the fixture nests inside the tissue annotation", det.getParent() == tissue,
        "parent=${det.getParent()}")
selectObjects([det])
run([BAL_CONVERT_TO: 'ANNOTATION'])

def anns = getAnnotationObjects().findAll { it != tissue }
check("one annotation came out", anns.size() == 1, "${anns.size()}")
def conv = anns[0]
check("the detection is gone", getDetectionObjects().isEmpty(), "${getDetectionObjects().size()}")
check("the class survives", conv?.getPathClass()?.toString() == "Macrophage", "${conv?.getPathClass()}")
check("the name survives", conv?.getName() == "d1", "${conv?.getName()}")
check("the measurement survives", conv?.getMeasurementList()?.get("area_um2") == 12.5,
        "${conv?.getMeasurementList()?.get('area_um2')}")
// The ID is what bal_classify/predictions.csv is joined on.
check("the object ID survives", conv?.getID() == oldId, "${conv?.getID()} vs ${oldId}")
// A locked annotation ignores the brush with no message at all, which would defeat the
// whole point of converting.
check("it is editable, not locked", conv != null && !conv.isLocked(), "locked=${conv?.isLocked()}")
check("it is still inside the tissue annotation", conv?.getParent() == tissue, "parent=${conv?.getParent()}")
check("the result is left selected", getSelectedObjects().contains(conv), "${getSelectedObjects()}")
check("no error was raised", errors.isEmpty(), "${errors}")

// === 2. annotations -> detections, with a region rectangle in the selection ===
println ""
println "=== annotations -> detections ==="
removeAllObjects()
def a1 = makeAnnotation(50, "Lymphocyte")
def rect = PathObjects.createAnnotationObject(ROIs.createRectangleROI(200, 200, 100, 100, PLANE),
        PathClass.fromString("Training"))
hierarchy.insertPathObjects([a1, rect])
selectObjects([a1, rect])
run([BAL_CONVERT_TO: 'DETECTION'])

def dets = getDetectionObjects()
check("one detection came out", dets.size() == 1, "${dets.size()}")
check("and it is a plain detection, not a cell", dets.every { !it.isCell() }, "${dets*.class*.simpleName}")
check("the class survives", dets[0]?.getPathClass()?.toString() == "Lymphocyte", "${dets[0]?.getPathClass()}")
// A Training rectangle as a detection would vanish from every exporter -- they find the
// ground-truth regions by class ON ANNOTATIONS -- and the corpus would lose a region.
check("the Training rectangle is left as an annotation",
        getAnnotationObjects().size() == 1 && getAnnotationObjects()[0] == rect,
        "${getAnnotationObjects()*.getPathClass()}")

// === 3. cells -> annotations, nucleus split out ==============================
println ""
println "=== cells -> annotations (nucleus split out) ==="
removeAllObjects()
def cell = makeCell(50, "Macrophage")
hierarchy.insertPathObjects([cell])
def cellId = cell.getID()
selectObjects([cell])
run([BAL_CONVERT_TO: 'ANNOTATION', BAL_CONVERT_SPLIT_NUCLEI: true])

def all = getAnnotationObjects()
check("two annotations came out", all.size() == 2, "${all.size()}")
def soma = all.find { it.getPathClass()?.toString() == "Macrophage" }
def nuc = all.find { it.getPathClass()?.toString() == "Nucleus" }
check("the soma keeps its class", soma != null, "${all*.getPathClass()}")
// 'Nucleus' is the class qupath_cells_with_nuclei.groovy reads back, so correcting a
// nucleus by hand and re-pairing is a closed loop.
check("the nucleus becomes its own 'Nucleus' annotation", nuc != null, "${all*.getPathClass()}")
check("the soma keeps the cell's ID", soma?.getID() == cellId, "${soma?.getID()} vs ${cellId}")
check("the nucleus gets its OWN ID", nuc != null && nuc.getID() != cellId, "${nuc?.getID()}")
check("the nucleus ROI is the inner one, not the soma",
        nuc != null && nuc.getROI().getArea() < soma.getROI().getArea(),
        "nucleus=${nuc?.getROI()?.getArea()} soma=${soma?.getROI()?.getArea()}")
check("the cell object is gone", getCellObjects().isEmpty(), "${getCellObjects().size()}")

println ""
println "=== cells -> annotations (nucleus dropped on request) ==="
removeAllObjects()
hierarchy.insertPathObjects([makeCell(50, "Macrophage")])
selectObjects(getCellObjects())
run([BAL_CONVERT_TO: 'ANNOTATION', BAL_CONVERT_SPLIT_NUCLEI: false])
check("only the soma comes out", getAnnotationObjects().size() == 1, "${getAnnotationObjects().size()}")
check("and no Nucleus annotation is made",
        getAnnotationObjects().every { it.getPathClass()?.toString() != "Nucleus" },
        "${getAnnotationObjects()*.getPathClass()}")

// === 4. cells -> detections: refused unless asked ============================
println ""
println "=== cells -> detections ==="
removeAllObjects()
hierarchy.insertPathObjects([makeCell(50, "Macrophage")])
selectObjects(getCellObjects())
run([BAL_CONVERT_TO: 'DETECTION'])
// Flattening a cell throws the nucleus away, so it must be asked for, not assumed.
check("a cell is NOT flattened by default", getCellObjects().size() == 1, "${getCellObjects().size()}")
check("and it says why", errors.any { it.contains("nucleus") }, "${errors}")

removeAllObjects()
hierarchy.insertPathObjects([makeCell(50, "Macrophage")])
selectObjects(getCellObjects())
run([BAL_CONVERT_TO: 'DETECTION', BAL_CONVERT_CELLS: true])
check("with 'include cells' it is flattened", getCellObjects().isEmpty(), "${getCellObjects().size()}")
check("into a plain detection", getDetectionObjects().size() == 1 && !getDetectionObjects()[0].isCell(),
        "${getDetectionObjects()*.class*.simpleName}")
check("keeping its class", getDetectionObjects()[0]?.getPathClass()?.toString() == "Macrophage",
        "${getDetectionObjects()[0]?.getPathClass()}")

// === 5. the refusals =========================================================
println ""
println "=== refusals ==="
removeAllObjects()
def a2 = makeAnnotation(50, "Lymphocyte")
hierarchy.insertPathObjects([a2])
selectObjects([a2])
run([BAL_CONVERT_TO: 'ANNOTATION'])
check("converting to the type it already is changes nothing",
        getAnnotationObjects().size() == 1 && getAnnotationObjects()[0] == a2, "${getAnnotationObjects()}")
check("and says they are already annotations", errors.any { it.contains("already") }, "${errors}")

removeAllObjects()
def rect2 = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 100, 100, PLANE),
        PathClass.fromString("Validation"))
hierarchy.insertPathObjects([rect2])
selectObjects([rect2])
run([BAL_CONVERT_TO: 'DETECTION'])
check("a Validation rectangle alone is refused",
        getDetectionObjects().isEmpty() && getAnnotationObjects().size() == 1,
        "det=${getDetectionObjects().size()} ann=${getAnnotationObjects().size()}")
check("and says it is never converted", errors.any { it.contains("Training/Validation") }, "${errors}")

removeAllObjects()
resetSelection()
run([BAL_CONVERT_TO: 'ANNOTATION'])
check("an empty selection is refused, not crashed on", errors.any { it.contains("elect") }, "${errors}")

removeAllObjects()
hierarchy.insertPathObjects([makeDetection(50, "Macrophage", "d1")])
selectObjects(getDetectionObjects())
run([BAL_CONVERT_TO: 'TILE'])
check("an unknown target type is rejected", errors.any { it.contains("ANNOTATION or DETECTION") },
        "${errors}")
check("and nothing was converted", getAnnotationObjects().isEmpty(), "${getAnnotationObjects().size()}")

// === 6. scope = every object in the image ====================================
println ""
println "=== scope ALL ==="
removeAllObjects()
hierarchy.insertPathObjects([makeDetection(50, "Macrophage", "d1"),
                             makeDetection(150, "Lymphocyte", "d2"),
                             PathObjects.createAnnotationObject(
                                     ROIs.createRectangleROI(200, 200, 100, 100, PLANE),
                                     PathClass.fromString("Training"))])
resetSelection()                                 // nothing selected: scope must not need it
run([BAL_CONVERT_TO: 'ANNOTATION', BAL_CONVERT_SCOPE: 'ALL'])
check("both detections converted with nothing selected",
        getDetectionObjects().isEmpty() && getAnnotationObjects().size() == 3,
        "det=${getDetectionObjects().size()} ann=${getAnnotationObjects().size()}")
check("the Training rectangle is still an annotation",
        getAnnotationObjects().count { it.getPathClass()?.toString() == "Training" } == 1,
        "${getAnnotationObjects()*.getPathClass()}")

removeAllObjects()
resetBatchProjectAndImage()
println ""
println "${pass} checks passed, ${fail} failed"
if (fail > 0) throw new IllegalStateException("${fail} check(s) failed")
