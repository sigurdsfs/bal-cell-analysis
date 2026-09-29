/**
 * Sets up ground-truth regions for FINE-TUNING a Cellpose model (cyto3 or cpsam).
 *
 * WHAT CELLPOSE TRAINING ACTUALLY NEEDS -- it is not the GeoJSON export used by the
 * classifier, and not cell objects:
 *   1. The RECTANGLE carries the class -- "Training" or "Validation".
 *   2. The cell outlines INSIDE it must have NO CLASS at all. The label mask is built with
 *      `.useFilter(o -> o.getPathClass() == null)` (Cellpose2D line 1459), so ANY object
 *      you classify is dropped from the mask. Classify the cells and they vanish from the
 *      ground truth while their pixels stay in the image -- i.e. you teach the model that
 *      those cells are background. The classed rectangle is excluded for the same reason,
 *      which is exactly what you want.
 *      They must also be ANNOTATIONS, not detections or cell objects.
 *   3. DENSE annotation: every relevant cell inside each rectangle must be outlined.
 *      Anything you leave out is treated as background and actively teaches the model to
 *      miss cells. Correcting "a few" cells is worse than not training at all.
 *
 * This script gives you a starting point to correct: it drops Training/Validation
 * rectangles, segments inside them as editable annotations, and strips any classes.
 *
 * YOU DON'T NEED THIS SCRIPT. Drawing a rectangle yourself and setting its class to
 * Training or Validation works identically -- the extension matches on the CLASS
 * (`a.getPathClass() == PathClass.getInstance("Training")`), not on the annotation's name
 * or on how it was made. The names are case-sensitive, and "Test" is not used: the `test`
 * output folder holds the Validation data.
 *
 * DRAW RECTANGLES, though. The export takes each annotation's BOUNDING BOX
 * (RegionRequest.createInstance(..., a.getROI())), so a freehand or ellipse region also
 * exports the corners around it -- and any unannotated cells caught in those corners are
 * taught to the model as background.
 *
 * THEN, BY HAND, in each rectangle:
 *   - fix wrong outlines with the brush (B) and wand (W)
 *   - split merged cells: select and use the scissors, or redraw
 *   - delete false positives, draw missed cells
 *   - check nothing inside the rectangle is left unannotated
 *
 * Finally run qupath_train_cellpose.groovy.
 *
 * Keep the rectangles small -- 512 px is plenty. Dense correction is slow, and a few
 * small, perfectly annotated regions beat one large sloppy one.
 */

import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.objects.classes.PathClass
import java.awt.Color

// Region centres to correct. Defaults span the range of appearances on this slide.
def TRAINING_REGIONS = binding.hasVariable('BAL_TRAIN_REGIONS') ? BAL_TRAIN_REGIONS : [
        [12288, 20480],   // dense clump -- where the models disagree most
        [45056, 24576],   // scattered cells, the benchmark region
        [32768, 18432],   // well-spread cells
]
def VALIDATION_REGIONS = binding.hasVariable('BAL_VAL_REGIONS') ? BAL_VAL_REGIONS : [
        [24576, 45056],   // held out: never used to train, only to score
]
int SIZE = binding.hasVariable('BAL_TRAIN_SIZE') ? BAL_TRAIN_SIZE as int : 512
def MODEL = binding.hasVariable('BAL_MODEL') ? BAL_MODEL.toString() : 'cyto3'
double DIAMETER = binding.hasVariable('BAL_DIAMETER') ? BAL_DIAMETER as double : 58

def scriptDir = null
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file")
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
if (scriptDir == null || !new File(scriptDir, "qupath_cellpose_detection.groovy").exists())
    scriptDir = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")

// The extension looks for exactly these class names.
def trainingClass = PathClass.fromString("Training")
trainingClass.setColor(new Color(0, 158, 115).getRGB())
def validationClass = PathClass.fromString("Validation")
validationClass.setColor(new Color(213, 94, 0).getRGB())

def gui = getQuPath()
if (gui != null) {
    qupath.fx.utils.FXUtils.runOnApplicationThread {
        def available = gui.getAvailablePathClasses()
        def names = available.collect { it.toString() } as Set
        if (!names.contains("Training")) available.add(trainingClass)
        if (!names.contains("Validation")) available.add(validationClass)
    }
}

// Remove any previous training rectangles so repeated runs don't pile up.
def old = getAnnotationObjects().findAll {
    it.getPathClass() == trainingClass || it.getPathClass() == validationClass
}
if (!old.isEmpty()) {
    println "Removing ${old.size()} rectangle(s) from a previous run"
    removeObjects(old, true)
}

def made = []
[[TRAINING_REGIONS, trainingClass], [VALIDATION_REGIONS, validationClass]].each { regions, pc ->
    regions.each { xy ->
        def roi = ROIs.createRectangleROI(xy[0], xy[1], SIZE, SIZE, ImagePlane.getDefaultPlane())
        def ann = PathObjects.createAnnotationObject(roi, pc)
        ann.setName("${pc} ${xy[0]}_${xy[1]}")
        addObject(ann)
        made << ann
    }
}
println "Created ${made.size()} rectangles of ${SIZE} px (${TRAINING_REGIONS.size()} Training, ${VALIDATION_REGIONS.size()} Validation)"

// Segment inside them, as ANNOTATIONS so you can edit the outlines.
selectObjects(made)
def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.regions.*
import qupath.lib.measurements.*
"""
BAL_MODEL = MODEL
BAL_DIAMETER = MODEL == 'cpsam' ? 0 : DIAMETER
BAL_OUTPUT_TYPE = 'ANNOTATION'
BAL_TARGETS = 'SELECTED'
evaluate(PRE + new File(scriptDir, "qupath_cellpose_detection.groovy").text)

// Ground truth must be UNCLASSIFIED annotations; the extension ignores classified ones.
int cleared = 0
made.each { rect ->
    rect.getChildObjects().each { child ->
        if (child.getPathClass() != null) { child.setPathClass(null); cleared++ }
    }
    rect.setLocked(false)
}
fireHierarchyUpdate()

def counts = made.collect { it.getChildObjects().size() }
println "Starting outlines per rectangle: ${counts.join(', ')} (${counts.sum()} total)"
if (cleared > 0) println "Cleared classes from ${cleared} annotation(s) -- ground truth must be unclassified"
println ""
println "NOW CORRECT THEM BY HAND -- this is the part that determines whether training helps:"
println "  brush B, wand W, delete false positives, draw missed cells"
println "  EVERY cell inside each rectangle must be outlined; gaps teach the model to miss cells"
println "  the Validation rectangle is scored, never trained on -- correct it just as carefully"
println ""
println "Then: save the project, and run qupath_train_cellpose.groovy"
