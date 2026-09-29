/**
 * Exports segmented cells to GeoJSON for the classifier, and imports the predictions back.
 *
 * EXPORT (default): writes every cell -- outline, nucleus outline, measurements and any
 * class you have set by hand -- to bal_classify/cells.geojson. The Python side reads that,
 * embeds each cell with a pathology foundation model and predicts a type.
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
