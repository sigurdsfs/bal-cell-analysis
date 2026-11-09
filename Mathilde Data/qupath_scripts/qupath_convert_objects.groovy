/**
 * Convert the selected objects between annotations and detections.
 *
 * WHY: the two types are the same outline with different privileges, and the pipeline needs
 * both.
 *   ANNOTATION  editable. Only an annotation can be reshaped with the brush/wand, so ground
 *               truth has to be annotations before you can correct it.
 *   DETECTION   cheap and classifiable. Segmentation output and anything the classifier
 *               reads is a detection (or a cell, which IS a detection).
 * Segmenting straight to one and then wanting the other is the normal case, and QuPath 0.7
 * has no menu command for it.
 *
 * WHAT IS KEPT: the ROI, the classification, the name, the colour, every measurement, the
 * metadata, and the object's ID. The ID matters: bal_classify/predictions.csv is matched
 * back onto cells BY ID, so a conversion partway through a classify round would otherwise
 * orphan every prediction.
 *
 * CELLS are a special case, because a cell carries a second ROI (the nucleus) that neither
 * plain type has anywhere to put:
 *   to annotations  the soma becomes one annotation and, by default, the nucleus becomes a
 *                   second one classed 'Nucleus' -- which is exactly what
 *                   "Pair soma + nuclei into cells" reads back, so correct-and-repair works.
 *   to detections   the nucleus has nowhere to go and is DISCARDED, so cells are skipped
 *                   unless you ask for it (BAL_CONVERT_CELLS).
 *
 * Training/Validation rectangles are never converted. They mark ground-truth regions and
 * the exporters find them by class on annotations; as detections they would vanish from
 * every export and the corpus would silently lose a region.
 *
 * Standalone: select some objects and run. Defaults to detections -> annotations.
 */

import qupath.lib.objects.PathObjects
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathCellObject
import qupath.lib.objects.classes.PathClass

// 'ANNOTATION' (editable, for correcting) or 'DETECTION' (classifiable).
def TO = binding.hasVariable('BAL_CONVERT_TO') ? BAL_CONVERT_TO.toString().toUpperCase() : 'ANNOTATION'
// 'SELECTED' (the usual) or 'ALL' -- every object in the image, for batch use.
def SCOPE = binding.hasVariable('BAL_CONVERT_SCOPE') ? BAL_CONVERT_SCOPE.toString().toUpperCase() : 'SELECTED'
// Cell -> annotations: keep the nucleus outline as its own 'Nucleus' annotation.
boolean SPLIT_NUCLEI = binding.hasVariable('BAL_CONVERT_SPLIT_NUCLEI') ? BAL_CONVERT_SPLIT_NUCLEI as boolean : true
// Cell -> detections: allowed only on request, because it throws the nucleus away.
boolean INCLUDE_CELLS = binding.hasVariable('BAL_CONVERT_CELLS') ? BAL_CONVERT_CELLS as boolean : false

if (!(TO in ['ANNOTATION', 'DETECTION'])) {
    Dialogs.showErrorMessage("Convert objects", "BAL_CONVERT_TO must be ANNOTATION or DETECTION (got '${TO}')")
    return
}

def REGION_CLASSES = ["Training", "Validation"] as Set
def NUCLEUS_CLASS = "Nucleus"

def hierarchy = getCurrentHierarchy()
if (hierarchy == null) {
    Dialogs.showErrorMessage("Convert objects", "No image is open.")
    return
}

/**
 * The conversion itself: a new object of the requested type over `roi`, carrying everything
 * from `src` that the new type can hold.
 *
 * Follows PathObjectTools.transformObjectImpl in QuPath 0.7 -- measurements are putAll'd and
 * the list CLOSED (an open list keeps its builder and serialises badly), then name, colour
 * and metadata, then the ID last.
 */
def convertOne = { PathObject src, String type, roi, boolean keepId ->
    def out = type == 'ANNOTATION'
            ? PathObjects.createAnnotationObject(roi, src.getPathClass(), null)
            : PathObjects.createDetectionObject(roi, src.getPathClass(), null)
    if (!src.getMeasurementList().isEmpty()) {
        out.getMeasurementList().putAll(src.getMeasurementList())
        out.getMeasurementList().close()
    }
    if (!src.getMetadata().isEmpty()) out.getMetadata().putAll(src.getMetadata())
    out.setName(src.getName())
    out.setColor(src.getColor())
    // Deliberately NOT copying the locked flag onto an annotation: you convert to an
    // annotation in order to edit it, and a locked annotation ignores the brush with no
    // message at all. Detections are never editable anyway, so the flag is moot there.
    if (type == 'DETECTION' && src.isLocked()) out.setLocked(true)
    if (keepId) out.setID(src.getID())
    out
}

// --- pick the objects --------------------------------------------------------
def pool = SCOPE == 'ALL' ? getAllObjects(false) : new ArrayList<>(getSelectedObjects())
pool = pool.findAll { it.getROI() != null && !it.isRootObject() && !it.isTMACore() }

if (pool.isEmpty()) {
    Dialogs.showErrorMessage("Convert objects", SCOPE == 'ALL'
            ? "This image holds no objects to convert."
            : "Nothing is selected.\n\nSelect the objects first -- Ctrl+A selects everything\n" +
              "visible, or drag with the Move tool. Detections must be shown (press D)\n" +
              "to be selectable.")
    return
}

int skippedRegion = 0, skippedSame = 0, skippedCell = 0, nucleiKept = 0, nucleiLost = 0
boolean hadChildren = false
def toRemove = [], toAdd = []

pool.each { PathObject src ->
    def cls = src.getPathClass()?.toString()
    // Region rectangles stay annotations -- see the header.
    if (cls in REGION_CLASSES) { skippedRegion++; return }
    boolean isCell = src instanceof PathCellObject
    // A cell is already a detection as far as the type system is concerned, so "to
    // detections" has to decide about it explicitly rather than call it done.
    boolean alreadyThere = TO == 'ANNOTATION' ? src.isAnnotation() : (src.isDetection() && !isCell)
    if (alreadyThere) { skippedSame++; return }
    if (isCell && TO == 'DETECTION' && !INCLUDE_CELLS) { skippedCell++; return }

    if (src.hasChildObjects()) hadChildren = true
    toRemove << src
    toAdd << convertOne(src, TO, src.getROI(), true)

    if (isCell) {
        def nuc = ((PathCellObject) src).getNucleusROI()
        if (nuc == null) return
        if (TO == 'ANNOTATION' && SPLIT_NUCLEI) {
            // A new object, so a new ID: the soma copy already inherited the cell's.
            // No measurements either -- they describe the whole cell, not the nucleus.
            def nucObj = PathObjects.createAnnotationObject(nuc, PathClass.fromString(NUCLEUS_CLASS))
            toAdd << nucObj
            nucleiKept++
        } else {
            nucleiLost++
        }
    }
}

if (toAdd.isEmpty()) {
    def why = skippedSame > 0
            ? "All ${skippedSame} of them are already ${TO == 'ANNOTATION' ? 'annotations' : 'detections'}."
            : skippedCell > 0
            ? "All ${skippedCell} are cell objects. Converting a cell to a plain detection\n" +
              "throws its nucleus outline away, so it is not done unless you ask:\n" +
              "tick 'Include cell objects' in the dialog."
            : "Only Training/Validation rectangles were selected, and those are never\n" +
              "converted -- the exporters find the ground-truth regions by class on\n" +
              "annotations."
    Dialogs.showErrorMessage("Convert objects", "Nothing to convert.\n\n" + why)
    println "Nothing converted. " + why.replace("\n", " ")
    return
}

// --- swap them over ----------------------------------------------------------
// keepChildren: an outline being converted may hold child objects (detections inside a
// tissue annotation), and they must survive their parent being replaced.
hierarchy.removeObjects(toRemove, true)
// insertPathObjects, NOT addObjects: addObjects hangs everything off the root, so cells
// converted inside a tissue annotation would jump out of it. insert places each object
// under the outline that contains it.
hierarchy.insertPathObjects(toAdd)
if (hadChildren) {
    // Children were re-attached to the old parent's parent above; this puts them back
    // inside whatever now contains them. Only when needed -- it is slow with many
    // annotations, and QuPath warns about it past 100.
    println "Some converted objects had children -- resolving the hierarchy, which can take a moment."
    hierarchy.resolveHierarchy()
}
// Leave the result selected, so a class can be set straight away without re-finding them.
selectObjects(toAdd)
fireHierarchyUpdate()

// --- report ------------------------------------------------------------------
int converted = toAdd.size() - nucleiKept
println "=== Converted ${converted} object(s) to ${TO == 'ANNOTATION' ? 'annotations' : 'detections'} ==="
println "  class, name, colour, measurements and object ID all carried over"
if (nucleiKept > 0)
    println "  ${nucleiKept} nucleus outline(s) kept as separate '${NUCLEUS_CLASS}' annotations"
if (nucleiLost > 0)
    println "  WARNING: ${nucleiLost} nucleus outline(s) DISCARDED -- " + (TO == 'ANNOTATION'
            ? "'keep each nucleus as its own annotation' is switched off"
            : "a plain detection has nowhere to keep one")
if (skippedSame > 0)
    println "  ${skippedSame} already the right type, left alone"
if (skippedCell > 0)
    println "  ${skippedCell} cell object(s) skipped (would lose the nucleus; tick 'Include cell objects' to force it)"
if (skippedRegion > 0)
    println "  ${skippedRegion} Training/Validation rectangle(s) left as annotations, as they must be"
println ""
if (TO == 'ANNOTATION') {
    println "They are editable now: pick the Brush (B) or Wand (W) and correct the outlines."
    println "Draw densely -- a cell you leave unoutlined teaches the model it is background."
    println "Then: 'Pair soma + nuclei into cells', or 'Export labelled cells' to add them to the corpus."
    println "SAVE the image (Ctrl+S) before exporting or training: both read the SAVED data,"
    println "and unsaved annotations give 'Found 0 Training objects'."
} else {
    println "They are classifiable now, and the classifier will pick them up."
    println "NOTE: it takes CELL objects if the image has any, otherwise every detection -- so"
    println "don't leave a mix of cells and plain detections, or the detections are skipped."
}
