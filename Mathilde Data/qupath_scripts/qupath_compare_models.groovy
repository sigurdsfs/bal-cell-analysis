/**
 * Runs cpsam, cyto3 and cyto2 on the SAME region and keeps all three sets of outlines,
 * each with its own classification and colour, so you can compare them by eye.
 *
 * HOW TO USE
 *   1. Select one annotation (the benchmark rectangle, or any region you want to judge).
 *   2. Run this script. It takes about 30 s for a 1024 px region -- three model runs.
 *   3. In the Annotations tab, use the class list to show/hide each model in turn:
 *        model: cpsam   red
 *        model: cyto3   green
 *        model: cyto2   blue
 *      Toggling two at a time is the clearest way to see where they disagree.
 *
 * Objects are plain detections, so nothing here interferes with a real segmentation run --
 * delete them (Objects > Delete > Delete all detections) when you're done comparing.
 *
 * Normally the Cellpose extension wipes a region's previous children before each run, so
 * this script harvests each model's outlines and re-adds them all at the end.
 */

import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import java.awt.Color

double DIAMETER = binding.hasVariable('BAL_DIAMETER') ? BAL_DIAMETER as double : 58

def scriptDir = null
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file")
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
if (scriptDir == null || !new File(scriptDir, "qupath_cellpose_detection.groovy").exists())
    scriptDir = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")
if (!new File(scriptDir, "qupath_cellpose_detection.groovy").exists()) {
    Dialogs.showErrorMessage("Compare models", "Can't find qupath_cellpose_detection.groovy.")
    return
}

def selected = getSelectedObjects().findAll { it.getROI() != null && it.getROI().isArea() }
if (selected.size() != 1) {
    Dialogs.showErrorMessage("Compare models", "Select exactly one region annotation first.")
    return
}
def region = selected[0]

def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.regions.*
import qupath.lib.measurements.*
"""
def detectionScript = new File(scriptDir, "qupath_cellpose_detection.groovy").text

def colours = ["cpsam": Color.RED, "cyto3": Color.GREEN, "cyto2": Color.BLUE]
def harvested = [:]

for (modelName in ["cpsam", "cyto3", "cyto2"]) {
    println "\n--- running ${modelName} ---"
    region.setLocked(false)
    region.clearChildObjects()
    selectObjects([region])

    BAL_MODEL = modelName
    BAL_DIAMETER = modelName == 'cpsam' ? 0 : DIAMETER    // cpsam is scale-free
    BAL_OUTPUT_TYPE = 'DETECTION'
    BAL_TARGETS = 'SELECTED'

    long t0 = System.currentTimeMillis()
    evaluate(PRE + detectionScript)
    def found = new ArrayList<>(region.getChildObjects())
    harvested[modelName] = found.collect { it.getROI() }          // keep the shapes, drop the objects
    println "${modelName}: ${found.size()} objects in ${(System.currentTimeMillis() - t0) / 1000.0}s"
}

// Rebuild everything as classified detections.
region.setLocked(false)
region.clearChildObjects()
def toAdd = []
harvested.each { modelName, rois ->
    def pc = PathClass.fromString("model: ${modelName}")
    pc.setColor(colours[modelName].getRGB())
    rois.each { toAdd << PathObjects.createDetectionObject(it, pc) }
}
addObjects(toAdd)
fireHierarchyUpdate()

println "\n=== comparison ready ==="
harvested.each { modelName, rois -> println String.format("  %-6s %4d objects", modelName, rois.size()) }
println "Show/hide each 'model: ...' class in the Annotations tab to compare them."
println "Delete them afterwards with Objects > Delete > Delete all detections."
