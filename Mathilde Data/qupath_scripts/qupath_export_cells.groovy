/**
 * Exports segmented cells to GeoJSON for the classifier, and imports the predictions back.
 *
 * EXPORT (default): writes every cell -- outline, nucleus outline, measurements and any
 * class you have set by hand -- to bal_classify/cells.geojson. The Python side reads that,
 * embeds each cell with a pathology foundation model and predicts a type.
 *
 * It also cuts ONE CROP PER CELL from the open image (BAL_EXPORT_CROPS, on by default) and
 * writes cells_manifest.csv next to them. WHY: the transformer needs pixels, and the only
 * other way to get them was a hard-coded zarr of a single slide -- on any other image the
 * coordinates were clamped into that zarr's bounds and you silently embedded unrelated
 * tissue. Crops from QuPath work on every slide and need nothing converted first.
 *
 * The crops match qupath_export_labels.groovy exactly -- same size, same downsample 1.0,
 * centred on the centroid -- because a classifier trained on its crops is applied to
 * these, and a different framing would shift every embedding.
 *
 * IMPORT: set MODE = 'IMPORT' to read bal_classify/predictions.csv back in and apply the
 * predicted class to each cell, so you can view, correct and retrain from inside QuPath.
 * The CSV needs an 'id' column matching the exported ids, plus 'prediction' and optionally
 * 'probability'.
 *
 * Hand-labelled cells are what the classifier trains on, so label some first:
 * select a cell, right-click > Set class (or use the Annotations tab's class list).
 */

import qupath.lib.objects.PathObject
import qupath.lib.io.PathIO
import qupath.lib.images.writers.ImageWriterTools
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs

// Machine-specific paths come from bal_config.groovy (with bal_config.local.groovy
// overriding it); the literals below are only a fallback for running this file alone.
def CFG = [:]
try {
    def cfgFile = [new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.groovy"),
                   new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts", "bal_config.groovy")]
            .find { it.exists() }
    if (cfgFile != null) CFG = evaluate(cfgFile) ?: [:]
} catch (Exception ignored) { }


def MODE = binding.hasVariable('BAL_EXPORT_MODE') ? BAL_EXPORT_MODE.toString().toUpperCase() : 'EXPORT'
def DIR = new File(CFG.balClassifyDir ?: "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify")
DIR.mkdirs()
boolean WITH_CROPS = binding.hasVariable('BAL_EXPORT_CROPS') ? BAL_EXPORT_CROPS as boolean : true
// Must stay equal to qupath_export_labels.groovy's crop: the classifier is trained on
// those and applied to these.
int CROP = binding.hasVariable('BAL_CROP_PX') ? BAL_CROP_PX as int : (CFG.cropPx ?: 128)
def CROP_STYLE = binding.hasVariable('BAL_CROP_STYLE') ? BAL_CROP_STYLE.toString()
        : (CFG.cropStyle ?: 'NEIGHBOURS_OUT')

def scriptDir = null
def scriptPath = (binding.hasVariable("qupath.script.file") ? binding.getVariable("qupath.script.file") : null) ?:
        System.getProperty("qupath.script.file")
if (scriptPath) scriptDir = new File(scriptPath.toString()).getParentFile()
if (scriptDir == null || !new File(scriptDir, "bal_crops.groovy").exists())
    scriptDir = new File(System.getProperty("user.home"), "QuPath/v0.7/scripts")

def cells = getCellObjects()
if (cells.isEmpty()) cells = getDetectionObjects()
if (cells.isEmpty()) {
    Dialogs.showErrorMessage("Export cells", "No cells found. Run the segmentation first.")
    return
}

if (MODE == 'EXPORT') {
    def file = new File(DIR, "cells.geojson")
    exportObjectsToGeoJson(cells, file.getAbsolutePath(), "FEATURE_COLLECTION")
    int labelled = cells.count { it.getPathClass() != null }
    println "Exported ${cells.size()} cells to ${file}"
    println "  ${labelled} already carry a class (these become the training set)"
    if (labelled < 40)
        println "  TIP: label ~40-60 cells per type before training -- right-click a cell > Set class"

    if (!WITH_CROPS) return

    // --- one crop per cell, cut from THIS image ------------------------------
    def server = getCurrentImageData().getServer()
    def imageName = qupath.lib.common.GeneralTools
            .stripExtension(server.getMetadata().getName()).replaceAll(/[^A-Za-z0-9._#-]/, "_")
    // How the crop is made lives in bal_crops.groovy, shared with qupath_export_labels.groovy:
    // the crops a classifier is trained on and the crops it is applied to must be identical.
    def cropsLib = evaluate(new File(scriptDir, "bal_crops.groovy"))
    def cropper = cropsLib.newCropper(server, cells, CROP, CROP_STYLE)
    def cropDir = new File(DIR, "cells_crops")
    // Wipe it first. Crops left behind by the previous image would be embedded along with
    // these and classified as if they came from this slide.
    int stale = 0
    if (cropDir.exists()) cropDir.listFiles()?.each { if (it.delete()) stale++ }
    cropDir.mkdirs()
    if (stale > 0) println "  (cleared ${stale} crop(s) from a previous run)"

    // Every measurement any cell carries, so the explore montage can still report medians.
    def measNames = []
    cells.each { c -> c.getMeasurementList().getNames().each { if (!(it in measNames)) measNames << it } }

    def csvCell = { v -> '"' + v.toString().replace('"', '""') + '"' }
    def rows = []
    int written = 0, failed = 0, totalPainted = 0, withNeighbours = 0
    cells.each { cell ->
        def roi = cell.getROI()
        double cx = roi.getCentroidX(), cy = roi.getCentroidY()
        def name = "${cell.getID().toString()}.png"
        try {
            def r = cropper.write(cell, new File(cropDir, name))
            written++
            totalPainted += r.painted
            if (r.painted > 0) withNeighbours++
            def cls = cell.getPathClass()?.toString() ?: ""
            def ml = cell.getMeasurementList()
            rows << ([csvCell(cell.getID().toString()), csvCell("cells_crops/" + name), csvCell(cls),
                      csvCell(imageName), csvCell(CROP_STYLE), Math.round(cx), Math.round(cy)] +
                     measNames.collect { ml.containsKey(it) ? ml.get(it) : "" }).join(",")
        } catch (Exception e) {
            failed++
            if (failed <= 3) println "  (no crop for the cell at ${(int) cx},${(int) cy}: ${e.getMessage()})"
        }
    }
    def manifest = new File(DIR, "cells_manifest.csv")
    manifest.text = (["id", "crop", "qupath_class", "image", "crop_style", "x", "y"].collect { csvCell(it) } +
                     measNames.collect { csvCell(it) }).join(",") + "\n" + rows.join("\n") + "\n"
    println "  ${written} crop(s) of ${CROP} px written to ${cropDir.getName()}/, indexed in ${manifest.getName()}"
    println "  crop style: ${CROP_STYLE}" + (CROP_STYLE == 'NEIGHBOURS_OUT'
            ? " -- ${totalPainted} neighbouring cell(s) painted out across ${withNeighbours} of ${written} crops"
            : "")
    if (CROP_STYLE == 'CONTEXT')
        println "  WARNING: neighbouring cells are left in the crop, so a crowded cell is" +
                " embedded together with whatever is beside it"
    if (failed > 0)
        println "  WARNING: ${failed} cell(s) got no crop and are NOT in the manifest, so they" +
                " will not be classified"
    return
}

// --- IMPORT -----------------------------------------------------------------
def csv = new File(DIR, "predictions.csv")
if (!csv.exists()) {
    Dialogs.showErrorMessage("Import predictions", "Not found:\n${csv}\n\nRun the Python classifier first.")
    return
}
def lines = csv.readLines()
if (lines.size() < 2) {
    Dialogs.showErrorMessage("Import predictions", "predictions.csv has no rows.")
    return
}
def header = lines[0].split(",").collect { it.trim().replaceAll('^"|"$', '') }
int idCol = header.indexOf("id"), predCol = header.indexOf("prediction"), probCol = header.indexOf("probability")
if (idCol < 0 || predCol < 0) {
    Dialogs.showErrorMessage("Import predictions", "predictions.csv needs 'id' and 'prediction' columns.")
    return
}

def byId = [:]
cells.each { byId[it.getID().toString()] = it }

int applied = 0, missed = 0
lines.drop(1).each { line ->
    def parts = line.split(",").collect { it.trim().replaceAll('^"|"$', '') }
    if (parts.size() <= Math.max(idCol, predCol)) return
    def cell = byId[parts[idCol]]
    if (cell == null) { missed++; return }
    cell.setPathClass(getPathClass(parts[predCol]))
    if (probCol >= 0 && parts.size() > probCol) {
        try {
            def ml = cell.getMeasurementList()
            ml.put("prediction_probability", parts[probCol] as double)
            ml.close()
        } catch (ignored) { }
    }
    applied++
}
fireHierarchyUpdate()
println "Applied ${applied} predictions" + (missed > 0 ? ", ${missed} ids not found in this image" : "")
println "Cells are now classified -- correct any mistakes by hand, then re-export and retrain."
