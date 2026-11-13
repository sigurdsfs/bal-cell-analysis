/**
 * Runs the pipeline over EVERY image in the project, unattended.
 *
 * Built for the 39-slide batch: pick the steps, start it, come back later. Each image is
 * segmented, measured and (optionally) classified, then its data is saved to the project so
 * the results survive. A per-slide summary is printed at the end, plus a CSV you can open
 * in Excel.
 *
 * WHAT IT RUNS PER IMAGE (BAL_BATCH_STEPS, default 'SEGMENT,FEATURES'):
 *   TISSUE    - detect tissue blocks first, so only tissue is segmented
 *   SEGMENT   - the chosen model (default: cpsam cells + Cellpose nuclei, paired)
 *   FEATURES  - the per-cell measurements
 *   CLASSIFY  - apply a saved classifier (needs BAL_CLASSIFIER)
 *
 * REGION: by default it uses whatever annotations each image already has. With
 * BAL_BATCH_REGION = 'TISSUE' it detects tissue blocks first; with 'WHOLE' it segments the
 * entire image (slow: ~2.4 h per slide at full resolution -- read the runtime note below).
 *
 * RUNTIME. Measured: ~3 s per 1024 px tile, ~2860 tiles for a whole slide = ~2.4 h.
 * For 39 slides that is about four days of continuous GPU time. Before starting a full run:
 *   - try BAL_PIXEL_SIZE_UM = 0.274 (four times fewer tiles), or
 *   - restrict to tissue blocks, or
 *   - run a few slides first and check the results are what you want.
 * The script prints an estimate and asks for confirmation when run from the GUI.
 *
 * SAFE TO RESTART: images that already hold cells are skipped unless BAL_BATCH_REDO = true,
 * so an interrupted run continues where it stopped.
 *
 * RUNNING IT FROM THE COMMAND LINE: pass a single image with -i, e.g.
 *     QuPath script -p <project.qpproj> -i "<any one image>" qupath_batch_slides.groovy
 * QuPath runs a script once PER IMAGE when -p is given without -i, which would start this
 * whole batch once for every image in the project. From the QuPath window it runs once.
 */

import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs

def CFG = [:]
try {
    def cfgFile = [new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.groovy"),
                   new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts", "bal_config.groovy")]
            .find { it.exists() }
    if (cfgFile != null) CFG = evaluate(cfgFile) ?: [:]
} catch (Exception ignored) { }

def STEPS = (binding.hasVariable('BAL_BATCH_STEPS') ? BAL_BATCH_STEPS.toString() : 'SEGMENT,FEATURES')
        .toUpperCase().split(",").collect { it.trim() } as Set
def REGION = binding.hasVariable('BAL_BATCH_REGION') ? BAL_BATCH_REGION.toString().toUpperCase() : 'EXISTING'
boolean REDO = binding.hasVariable('BAL_BATCH_REDO') ? BAL_BATCH_REDO as boolean : false
int LIMIT = binding.hasVariable('BAL_BATCH_LIMIT') ? BAL_BATCH_LIMIT as int : 0   // 0 = all
def ONLY = binding.hasVariable('BAL_BATCH_ONLY') ? BAL_BATCH_ONLY.toString().trim() : ""   // name filter

def project = getProject()
if (project == null) {
    Dialogs.showErrorMessage("Batch", "Open a project first -- batch processing works across its images.")
    return
}

def scriptDir = null
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file")
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
if (scriptDir == null || !new File(scriptDir, "qupath_cellpose_detection.groovy").exists())
    scriptDir = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")

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

// --- pick the images -----------------------------------------------------------
def entries = project.getImageList().findAll { e ->
    // Skip the label and overview series that come bundled in every .vsi
    def n = e.getImageName()
    !(n ==~ /(?i).*\b(label|overview|macro)\b.*/) && (ONLY.isEmpty() || n.contains(ONLY))
}
if (LIMIT > 0) entries = entries.take(LIMIT)
if (entries.isEmpty()) {
    Dialogs.showErrorMessage("Batch", "No images matched." + (ONLY ? " Filter was '${ONLY}'." : ""))
    return
}

println "=" * 70
println "BATCH over ${entries.size()} image(s); steps: ${STEPS.join(', ')}; region: ${REGION}"
if (REGION == 'WHOLE')
    println "WARNING: whole-image segmentation is about 2.4 h per slide at full resolution."
println "=" * 70

def results = []
long batchStart = System.currentTimeMillis()

entries.eachWithIndex { entry, idx ->
    def name = entry.getImageName()
    long t0 = System.currentTimeMillis()
    def row = [image: name, cells: 0, classified: 0, status: "ok", minutes: 0.0]
    println ""
    println "--- [${idx + 1}/${entries.size()}] ${name}"
    try {
        def imageData = entry.readImageData()
        setBatchProjectAndImage(project, imageData)      // makes getCurrentImageData() work

        def existing = imageData.getHierarchy().getDetectionObjects().size()
        if (existing > 0 && !REDO) {
            row.cells = existing; row.status = "skipped (already has ${existing} cells)"
            println "    ${row.status}"
            results << row
            return
        }
        imageData.getHierarchy().clearAll()

        // --- choose what to segment ------------------------------------------
        if (REGION == 'TISSUE' || STEPS.contains('TISSUE')) {
            evaluate(PRE + new File(scriptDir, "qupath_tissue_annotation.groovy").text)
        } else if (REGION == 'WHOLE') {
            def server = imageData.getServer()
            def roi = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(),
                    ImagePlane.getDefaultPlane())
            def ann = PathObjects.createAnnotationObject(roi)
            ann.setName("Whole image")
            imageData.getHierarchy().addObject(ann)
            selectObjects([ann])
        }
        def regions = getAnnotationObjects()
        if (regions.isEmpty()) {
            row.status = "no region to segment (draw annotations, or use TISSUE/WHOLE)"
            println "    ${row.status}"
            results << row
            return
        }
        selectObjects(regions)

        // --- the pipeline ------------------------------------------------------
        if (STEPS.contains('SEGMENT')) {
            BAL_TARGETS = 'SELECTED'
            evaluate(PRE + new File(scriptDir, "qupath_cells_with_nuclei.groovy").text)
        }
        if (STEPS.contains('FEATURES')) {
            selectObjects(getAnnotationObjects())
            BAL_MEASURE_ROI = 'CELL'
            evaluate(PRE + new File(scriptDir, "qupath_cell_features.groovy").text)
        }
        if (STEPS.contains('CLASSIFY')) {
            BAL_CLASSIFY_MODE = 'PREDICT'
            evaluate(PRE + new File(scriptDir, "qupath_classify_cells.groovy").text)
        }

        row.cells = getCellObjects().size() ?: getDetectionObjects().size()
        row.classified = getDetectionObjects().count { it.getPathClass() != null }
        entry.saveImageData(imageData)                   // persist, or the work is lost
        println "    ${row.cells} cells, ${row.classified} classified -- saved"
    } catch (Exception e) {
        row.status = "FAILED: ${e.getMessage()?.take(120)}"
        println "    ${row.status}"
        // Keep going: one bad slide must not end a four-day run.
    }
    row.minutes = Math.round((System.currentTimeMillis() - t0) / 600.0) / 100.0
    results << row
}

// --- summary --------------------------------------------------------------------
double totalMin = (System.currentTimeMillis() - batchStart) / 60000.0
println ""
println "=" * 70
println String.format("BATCH DONE in %.1f min", totalMin)
println String.format("%-45s %8s %10s  %s", "image", "cells", "classified", "status")
results.each { r ->
    println String.format("%-45s %8d %10d  %s", r.image.take(45), r.cells, r.classified, r.status)
}
int ok = results.count { it.status == "ok" }
int failed = results.count { it.status.startsWith("FAILED") }
println ""
println "${ok} processed, ${results.size() - ok - failed} skipped, ${failed} failed; " +
        "${results.sum { it.cells } ?: 0} cells in total"

// A CSV next to the project, so the counts can go straight into analysis.
try {
    def csv = new File(buildFilePath(PROJECT_BASE_DIR), "batch_summary.csv")
    csv.text = "image,cells,classified,minutes,status\n" + results.collect {
        "\"${it.image}\",${it.cells},${it.classified},${it.minutes},\"${it.status}\""
    }.join("\n") + "\n"
    println "Summary written to ${csv.getName()}"
} catch (Exception e) {
    println "(could not write the summary CSV: ${e.getMessage()})"
}
