/**
 * Checks your training setup the way the Cellpose extension sees it, BEFORE you spend
 * hours correcting outlines or minutes training on nothing.
 *
 * Every failure mode here is silent: the extension logs "Found 0 Training objects", or
 * exports an empty mask, and training either dies with "no files in --dir folder" or
 * quietly produces a worse model. This script turns each of those into a clear message.
 *
 * It checks:
 *   - rectangles classed exactly "Training" / "Validation" (case-sensitive)
 *   - the region shape, since export uses the BOUNDING BOX
 *   - unclassified ANNOTATIONS inside each region -- these become the ground truth
 *   - classified objects inside, which are DROPPED from the mask (the usual mistake)
 *   - detections or cell objects inside, which are ignored entirely
 *   - whether the project on disk matches what you see, since export reads the saved copy
 *
 * Read-only: it changes nothing.
 */

import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathDetectionObject

double PIXEL_SIZE_UM = 0.137

def annotations = getAnnotationObjects()
def regions = annotations.findAll {
    def c = it.getPathClass()?.toString()
    c == "Training" || c == "Validation"
}

println "=" * 70
if (regions.isEmpty()) {
    println "NO TRAINING REGIONS FOUND in this image."
    def nearMisses = annotations.findAll {
        def c = it.getPathClass()?.toString()?.toLowerCase()
        c in ["training", "validation", "test", "train", "val"]
    }.findAll { !(it.getPathClass()?.toString() in ["Training", "Validation"]) }
    if (!nearMisses.isEmpty()) {
        println "But these look close -- the names are CASE-SENSITIVE and must be exactly"
        println "'Training' or 'Validation':"
        nearMisses.each { println "   '${it.getPathClass()}'" }
    } else {
        println "Draw a rectangle, then set its class to 'Training' (or 'Validation')."
    }
    println "=" * 70
    return
}

int totalGt = 0, totalVal = 0
boolean problem = false

regions.each { region ->
    def roi = region.getROI()
    def cls = region.getPathClass().toString()
    // The extension exports the bounding box, so that is the area to judge.
    double wUm = roi.getBoundsWidth() * PIXEL_SIZE_UM, hUm = roi.getBoundsHeight() * PIXEL_SIZE_UM

    // Geometry decides what is included, not the object hierarchy.
    def inside = annotations.findAll { a ->
        a != region && roi.getGeometry().intersects(a.getROI().getGeometry())
    }
    def groundTruth = inside.findAll { it.getPathClass() == null }
    def classified = inside.findAll { it.getPathClass() != null }
    def detectionsInside = getDetectionObjects().findAll {
        roi.getGeometry().intersects(it.getROI().getGeometry())
    }

    println ""
    println "${cls} region '${region.getName() ?: "(unnamed)"}'"
    println String.format("  %.0f x %.0f um (%.0f x %.0f px)", wUm, hUm,
            roi.getBoundsWidth(), roi.getBoundsHeight())
    println "  ${groundTruth.size()} unclassified annotations -> THESE become the ground truth"
    if (cls == "Training") totalGt += groundTruth.size() else totalVal += groundTruth.size()

    // "Effectively rectangular": its area fills its bounding box. Catches a polygon drawn
    // as a rectangle as well as a true RectangleROI.
    double bboxArea = roi.getBoundsWidth() * roi.getBoundsHeight()
    boolean rectangular = bboxArea > 0 && roi.getArea() >= 0.99 * bboxArea
    if (!rectangular) {
        println "  WARNING: not a rectangle. Export takes the bounding box, so the corners"
        println "           around your shape go in too -- any unannotated cells there are"
        println "           taught to the model as background."
        problem = true
    }
    if (groundTruth.isEmpty()) {
        println "  PROBLEM: nothing to learn from here. Segment inside it, or draw the cells."
        problem = true
    }
    if (!classified.isEmpty()) {
        println "  PROBLEM: ${classified.size()} CLASSIFIED object(s) inside will be DROPPED from"
        println "           the mask (the filter keeps only objects with no class), while their"
        println "           pixels stay in the image -- teaching the model they are background."
        def byClass = classified.groupBy { it.getPathClass().toString() }
        byClass.each { k, v -> println "             ${v.size()} x '${k}'" }
        println "           Clear their classes, or move them out of this region."
        problem = true
    }
    if (!detectionsInside.isEmpty()) {
        println "  NOTE: ${detectionsInside.size()} detection/cell object(s) inside are ignored --"
        println "        only ANNOTATIONS become ground truth. Re-run segmentation with output"
        println "        type 'Annotations' if you meant to use them."
        if (groundTruth.isEmpty()) problem = true
    }
    double densityPerMm2 = groundTruth.size() / ((wUm / 1000.0) * (hUm / 1000.0))
    println String.format("  density: %.0f outlines per mm2", densityPerMm2)
}

// The export reads the SAVED project data, not the screen.
println ""
def entry = getProjectEntry()
if (entry == null) {
    println "WARNING: this image isn't in a project -- training needs one."
    problem = true
} else {
    try {
        def saved = entry.readImageData()
        def savedRegions = saved.getHierarchy().getAnnotationObjects().findAll {
            it.getPathClass()?.toString() in ["Training", "Validation"]
        }
        int savedGt = savedRegions.sum { r ->
            saved.getHierarchy().getAnnotationObjects().count { a ->
                a != r && a.getPathClass() == null && r.getROI().getGeometry().intersects(a.getROI().getGeometry())
            }
        } ?: 0
        println "Saved on disk: ${savedRegions.size()} region(s), ${savedGt} unclassified outlines inside them"
        if (savedRegions.size() != regions.size() || savedGt != (totalGt + totalVal)) {
            println "  PROBLEM: this does NOT match what you see (${regions.size()} regions, " +
                    "${totalGt + totalVal} outlines)."
            println "           Export and training read the saved copy -- press Ctrl+S first."
            problem = true
        }
    } catch (Exception e) {
        println "Could not read the saved data (${e.getMessage()}) -- save the project."
        problem = true
    }
}

println ""
println "-" * 70
println "TOTAL: ${totalGt} training outlines, ${totalVal} validation outlines"
if (totalGt > 0 && totalGt < 200) {
    println "NOTE: ${totalGt} is on the low side. Fine-tuning usually needs several hundred"
    println "      outlines before it beats the stock model."
}
if (totalVal == 0) {
    println "NOTE: no validation outlines -- you'll get no QC scores, and no honest way to"
    println "      tell whether training helped."
}
println problem ? "NOT READY -- fix the problems above first." : "READY to export or train."
println "=" * 70
