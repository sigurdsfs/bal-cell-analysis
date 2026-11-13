/**
 * Soma outlines + nuclei, paired into QuPath cell objects. Both come from Cellpose.
 *
 * WHY: classification needs more than an outline. Pairing the two gives
 *   - a true nucleus:cytoplasm ratio (measured areas, not a brightness proxy)
 *     -> the main lymphocyte vs macrophage discriminator
 *   - nuclear shape on its own (circularity, solidity)
 *     -> lobed neutrophil and bilobed eosinophil nuclei score low
 *   - cytoplasm colour with the nucleus excluded
 *     -> granule/eosin signal no longer contaminated by chromatin colour
 *
 * WHY CELLPOSE FOR THE NUCLEI, not InstanSeg: InstanSeg cannot be fine-tuned from QuPath,
 * so a bad nucleus mask was a dead end -- you could see it was wrong and do nothing about
 * it. Cellpose's `nuclei` model goes through the same correct-export-train loop as the soma
 * model, so nuclei can be improved the same way cells are.
 *
 * TWO SOURCES (BAL_PAIR_SOURCE):
 *   'SEGMENT'     run both models now, then pair. The usual path.
 *   'ANNOTATIONS' pair what is already in the image, so you can CORRECT the outlines first.
 *                 Soma   = any annotation that is not a region rectangle and not 'Nucleus'
 *                 Nucleus = any annotation classed 'Nucleus'
 *                 The annotations are consumed and replaced by cell objects.
 *
 * A soma with no nucleus becomes a cell object with a NULL nucleus, not a plain detection.
 * That keeps the set uniform: getCellObjects() returns every soma, so nothing is silently
 * dropped downstream (the classifier takes cells when any exist and would skip detections).
 *
 * Run with a region selected, or through the GUI.
 */

import qupath.lib.objects.PathObjects
import qupath.lib.objects.PathObject
import qupath.lib.roi.RoiTools

// Which model draws the outer boundary. Defaults to cpsam, the pipeline's default soma
// model; DIAMETER is ignored for it (set to 0 below) and only used by the cyto models.
def CELL_MODEL   = binding.hasVariable('BAL_MODEL')    ? BAL_MODEL.toString()  : 'cpsam'
double DIAMETER  = binding.hasVariable('BAL_DIAMETER') ? BAL_DIAMETER as double : 58
// Which model draws the nuclei. Cellpose's own nuclei model has diam_mean 17 (cyto models
// use 30), so it rescales differently -- give it the nucleus size, not the cell size.
def NUCLEUS_MODEL = binding.hasVariable('BAL_NUCLEUS_MODEL') ? BAL_NUCLEUS_MODEL.toString() : 'nuclei'
double NUCLEUS_DIAMETER = binding.hasVariable('BAL_NUCLEUS_DIAMETER') ? BAL_NUCLEUS_DIAMETER as double : 40
def PAIR_SOURCE = binding.hasVariable('BAL_PAIR_SOURCE') ? BAL_PAIR_SOURCE.toString().toUpperCase() : 'SEGMENT'
// A nucleus is clipped to its cell, so nc_ratio can never exceed 1. Only a match that ends up
// smaller than this share of the cell is thrown away as degenerate.
// NOTE: do NOT reject nuclei for being large. BAL lymphocytes genuinely have nuclei filling
// 85-95% of the cell -- an earlier 95% cut-off was discarding exactly the cells we care about.
double MIN_NUC_FRACTION = binding.hasVariable('BAL_MIN_NUC_FRACTION') ? BAL_MIN_NUC_FRACTION as double : 0.02

def REGION_CLASSES = ["Training", "Validation"] as Set
def NUCLEUS_CLASS = "Nucleus"

def scriptDir = null
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file")
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
if (scriptDir == null || !new File(scriptDir, "qupath_cellpose_detection.groovy").exists())
    scriptDir = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")
if (!new File(scriptDir, "qupath_cellpose_detection.groovy").exists()) {
    Dialogs.showErrorMessage("Cells + nuclei", "Can't find the sibling BAL scripts.")
    return
}

def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.regions.*
import qupath.lib.measurements.*
"""

def somaObjects = []          // the source objects, so their class/measurements carry over
def nucRois = []

if (PAIR_SOURCE == 'ANNOTATIONS') {
    // --- pair outlines that are already in the image ---------------------------
    def anns = getAnnotationObjects()
    somaObjects = anns.findAll {
        def c = it.getPathClass()?.toString()
        !(c in REGION_CLASSES) && c != NUCLEUS_CLASS && it.getROI()?.isArea()
    }
    nucRois = anns.findAll { it.getPathClass()?.toString() == NUCLEUS_CLASS && it.getROI()?.isArea() }
                  .collect { it.getROI() }
    if (somaObjects.isEmpty()) {
        Dialogs.showErrorMessage("Pair into cells",
                "No soma annotations found.\n\n" +
                "Expected annotations that are not classed Training/Validation/Nucleus.\n" +
                "Segment with Output type = Annotations first, then correct them.")
        return
    }
    println "Pairing existing annotations: ${somaObjects.size()} soma, ${nucRois.size()} nucleus"
} else {
    // --- segment both, then pair -----------------------------------------------
    def regions = getSelectedObjects().findAll { it.getROI() != null && it.getROI().isArea() }
    if (regions.isEmpty()) {
        Dialogs.showErrorMessage("Cells + nuclei", "Select at least one region annotation first.")
        return
    }

    println "Step 1/3: soma outlines with ${CELL_MODEL}..."
    regions.each { it.setLocked(false); it.clearChildObjects() }
    selectObjects(regions)
    BAL_MODEL = CELL_MODEL
    BAL_DIAMETER = CELL_MODEL == 'cpsam' ? 0 : DIAMETER
    BAL_OUTPUT_TYPE = 'DETECTION'
    BAL_TARGETS = 'SELECTED'
    // The soma pass owns the region: one cell, one region, decided by its centroid.
    BAL_REGION_OWNERSHIP = true
    evaluate(PRE + new File(scriptDir, "qupath_cellpose_detection.groovy").text)
    somaObjects = regions.collectMany { r -> r.getChildObjects().collect { it } }
    println "  ${somaObjects.size()} soma"

    println "Step 2/3: nuclei with ${NUCLEUS_MODEL}..."
    regions.each { it.setLocked(false); it.clearChildObjects() }
    selectObjects(regions)
    BAL_MODEL = NUCLEUS_MODEL
    // The nuclei model is a cellpose 3 model and is NOT scale-free, so it always gets a
    // diameter -- passing the cell diameter here would look for nuclei the size of cells.
    BAL_DIAMETER = NUCLEUS_DIAMETER
    BAL_MODEL_IS_SAM = NUCLEUS_MODEL.toLowerCase().contains("cpsam")
    BAL_OUTPUT_TYPE = 'DETECTION'
    BAL_TARGETS = 'SELECTED'
    // Keep every nucleus the padded pass found, including those centred outside the region.
    // A soma kept by the ownership pass straddles the edge, and its nucleus can sit on the
    // far side of that line -- claiming nuclei by region first would strip the nucleus off
    // exactly the cells this change exists to rescue. Pairing below discards the rest
    // anyway, since a nucleus is only kept when some soma's outline contains its centroid.
    BAL_REGION_OWNERSHIP = false
    evaluate(PRE + new File(scriptDir, "qupath_cellpose_detection.groovy").text)
    nucRois = regions.collectMany { r -> r.getChildObjects().collect { it.getROI() } }
    println "  ${nucRois.size()} nuclei"

    regions.each { it.setLocked(false); it.clearChildObjects() }
}

// --- pair them ---------------------------------------------------------------
println "Step 3/3: pairing..."
def somaRois = somaObjects.collect { it.getROI() }

// Index nuclei by the soma whose outline contains their centroid.
def nucBySoma = [:].withDefault { [] }
int unmatchedNuclei = 0
for (nuc in nucRois) {
    double cx = nuc.getCentroidX(), cy = nuc.getCentroidY()
    def owner = somaRois.find { it.contains(cx, cy) }
    if (owner == null) unmatchedNuclei++
    else nucBySoma[owner] << nuc
}

int paired = 0, noNucleus = 0, multi = 0, rejected = 0
def toAdd = []
for (soma in somaObjects) {
    def somaRoi = soma.getROI()
    def candidates = nucBySoma[somaRoi]
    def nuc = null
    if (candidates.size() == 1) nuc = candidates[0]
    else if (candidates.size() > 1) {
        multi++
        nuc = candidates.max { it.getArea() }      // binucleate or a split nucleus: keep the largest
    }
    // Clip the nucleus to its soma: the two models outline independently, so a nucleus can
    // stick out past the soma boundary. Clipping keeps nc_ratio meaningful (always <= 1).
    if (nuc != null) {
        try {
            def clipped = RoiTools.intersection([somaRoi, nuc])
            if (clipped == null || clipped.isEmpty() || clipped.getArea() / somaRoi.getArea() < MIN_NUC_FRACTION) {
                nuc = null
                rejected++
            } else {
                nuc = clipped
            }
        } catch (Exception e) {
            nuc = null
            rejected++
        }
    }
    if (nuc == null) noNucleus++ else paired++
    // A null nucleus is allowed and still gives a PathCellObject, so every soma ends up in
    // getCellObjects() whether or not its nucleus was found.
    toAdd << PathObjects.createCellObject(somaRoi, nuc, soma.getPathClass(), soma.getMeasurementList())
}

// Consume the source annotations: leaving them behind would double every cell, and the
// ground-truth exporter counts annotations.
if (PAIR_SOURCE == 'ANNOTATIONS') {
    def consumed = getAnnotationObjects().findAll {
        def c = it.getPathClass()?.toString()
        (c == NUCLEUS_CLASS || somaObjects.contains(it)) && !(c in REGION_CLASSES)
    }
    removeObjects(consumed, true)
}
addObjects(toAdd)
fireHierarchyUpdate()

println "\n=== cells with nuclei ==="
println "  ${paired} cells paired with a nucleus"
println "  ${noNucleus} cells with no nucleus (kept as cell objects with an empty nucleus)"
if (multi > 0)      println "  ${multi} cells held more than one nucleus -- the largest was used"
if (rejected > 0)   println "  ${rejected} nuclei rejected (overlap with their soma too small)"
if (unmatchedNuclei > 0) println "  ${unmatchedNuclei} nuclei fell outside every soma (ignored)"
if (noNucleus > 0) {
    double pct = 100.0 * noNucleus / Math.max(1, paired + noNucleus)
    println ""
    println String.format("  %.0f%% of cells have no nucleus. Those get no nc_ratio and no nuclear", pct)
    println "  shape, so the classifier has less to work with for exactly those cells."
    if (pct > 20)
        println "  That is high: check the nucleus diameter before blaming the model."
}
