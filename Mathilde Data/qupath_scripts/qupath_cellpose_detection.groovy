/**
 * Cellpose-SAM detection script for the BAL slide, run from inside QuPath.
 *
 * Tested end-to-end on this machine on 2026-09-07: opens Image_BAL_#2.vsi (series 2,
 * "40x_BF_01") as a real 8-level pyramid, calls Cellpose-SAM through the cellpose_seg
 * conda env on the GPU, and imports the result as an editable annotation.
 *
 * PREREQUISITES (one-time setup):
 * 1. Create a QuPath project and add Image_BAL_#2.vsi to it. When prompted for a
 *    series, pick "Image_BAL_#2.vsi - 40x_BF_01" (NOT the "label" or "macro" series --
 *    those are the small overview/label images bundled in the same .vsi file).
 * 2. The qupath-extension-cellpose jar is already installed in your QuPath user
 *    extensions folder -- restart QuPath once if you haven't since it was added.
 * 3. Open the image, draw or select an annotation over the region you want to segment,
 *    then run this script (Extensions > Script Editor, or drag this file onto QuPath).
 *    Only the SELECTED annotation(s) are processed -- see the bottom of the script to
 *    switch to "all annotations" for batch/whole-slide runs.
 *
 * NOTE ON PARAMETERS: this ran successfully but only found 1 cell in a quick test tile
 * where more were expected. Cellpose-SAM's default overlap/normalization may not match
 * what your notebook pipeline does with the same tiles -- if detections look sparse,
 * try uncommenting .normalizePercentiles() below, or compare against the notebook's
 * model.eval(tile, diameter=None) result on the same region before trusting counts.
 */

import qupath.ext.biop.cellpose.Cellpose2D
import qupath.ext.biop.cellpose.CellposeSetup

// Machine-specific paths come from bal_config.groovy (with bal_config.local.groovy
// overriding it); the literals below are only a fallback for running this file alone.
def CFG = [:]
def balScriptDir = null
try {
    def cfgFile = [new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.groovy"),
                   new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts", "bal_config.groovy")]
            .find { it.exists() }
    if (cfgFile != null) {
        CFG = evaluate(cfgFile) ?: [:]
        balScriptDir = cfgFile.getParentFile()        // also where bal_regions.groovy lives
    }
} catch (Exception ignored) { }


// --- one-time env setup -----------------------------------------------------
// Same as Edit > Preferences > Cellpose/Omnipose in the GUI -- setting it here too
// means this script is self-contained even on a fresh QuPath user profile.
// Each Windows account on this PC has its own cellpose_seg env -- use whichever this user can see.
def cellposePython = (CFG.pythonCellpose4 ?: [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose_seg/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose_seg/python.exe',
]).find { new File(it).exists() }
if (cellposePython == null) {
    Dialogs.showErrorMessage("Cellpose", "No cellpose_seg environment found for this Windows user.")
    return
}
CellposeSetup.getInstance().setCellposeSAMPythonPath(cellposePython)

// cyto2/cyto3 belong to cellpose 3 and will NOT load in the cellpose 4 environment, so they
// get their own env and their own path setting in the extension.
def legacyPython = (CFG.pythonCellpose3 ?: [
        'C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose3/python.exe',
        'C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose3/python.exe',
]).find { new File(it).exists() }
if (legacyPython != null)
    CellposeSetup.getInstance().setCellposePythonPath(legacyPython)


// --- CHOOSE THE OUTPUT TYPE ------------------------------------------------
// 'ANNOTATION' - editable objects. Use while checking or correcting results by hand:
//               the brush, wand and split tools all work on them. Slow once there are
//               many thousands, so prefer DETECTION for whole-slide runs.
// 'DETECTION'  - lightweight, read-only objects. The right choice for big runs, for
//               QuPath's object classifier, and for measurement export.
// 'CELL'       - cell objects, each holding an inner (nucleus) and outer (cell) boundary.
//               NOTE: this fits nucleus-segmenting models. cpsam segments WHOLE cells here,
//               so the inner boundary is the whole cell and CELL_EXPANSION_UM just adds a
//               ring around it -- only useful if you actually want a peri-cellular zone.
// Each setting below can be overridden by qupath_bal_gui.groovy, which passes BAL_* variables
// before running this script. Run standalone and the defaults here apply.
def OUTPUT_TYPE          = binding.hasVariable('BAL_OUTPUT_TYPE')       ? BAL_OUTPUT_TYPE              : 'DETECTION'
double CELL_EXPANSION_UM = binding.hasVariable('BAL_CELL_EXPANSION_UM') ? BAL_CELL_EXPANSION_UM as double : 2.0
double PIXEL_SIZE_UM     = binding.hasVariable('BAL_PIXEL_SIZE_UM')     ? BAL_PIXEL_SIZE_UM as double  : 0.137
int TILE_SIZE            = binding.hasVariable('BAL_TILE_SIZE')         ? BAL_TILE_SIZE as int         : 1024
// 'SELECTED' = only what you have selected; 'ALL_ANNOTATIONS' = every annotation in the image
def TARGETS              = binding.hasVariable('BAL_TARGETS')           ? BAL_TARGETS                  : 'SELECTED'
// 'cpsam' is the only pretrained model in cellpose 4.x. Older names (cyto2, cyto3, nuclei)
// belong to cellpose 3 and will NOT load in this environment. A path to a model you
// fine-tuned yourself from cpsam works here too.
def MODEL                = binding.hasVariable('BAL_MODEL')             ? BAL_MODEL.toString()         : 'cpsam'
// cyto2/cyto3 are scale-sensitive and need the cell diameter in pixels (0 = let cellpose guess).
// Measured on this slide: median cell diameter is 58 px at 0.137 um/px.
double DIAMETER          = binding.hasVariable('BAL_DIAMETER')          ? BAL_DIAMETER as double       : 0
// How far OUTSIDE each region Cellpose is allowed to look, in microns.
// WHY: the extension clips every detection to its parent annotation, so a cell crossing the
// region edge used to be kept as a whole cell with a truncated area, diameter and nc_ratio.
// With padding the region is grown, nothing is clipped, and the ownership pass below hands
// each cell to the one region holding its centroid -- so edge cells come out whole and are
// counted exactly once. 0 restores the old clipping behaviour. See bal_regions.groovy.
double REGION_PAD_UM     = binding.hasVariable('BAL_REGION_PAD_UM')     ? BAL_REGION_PAD_UM as double  : (CFG.regionPadUm ?: 20) as double
// Set false to keep everything the padded pass produced, duplicates and all. The nucleus
// half of qupath_cells_with_nuclei.groovy does this: nuclei are matched to a soma by
// centroid-in-soma straight afterwards, and a nucleus whose own centroid fell outside the
// region would otherwise be thrown away before its soma could claim it.
boolean REGION_OWNERSHIP = binding.hasVariable('BAL_REGION_OWNERSHIP')  ? BAL_REGION_OWNERSHIP as boolean : true

// --- build the detector -------------------------------------------------
// Which environment a model needs depends on what it was trained FROM, which cannot be read
// from the file. cpsam -> cellpose 4; cyto2/cyto3 and anything fine-tuned from them -> cellpose 3.
// Custom models default to cellpose 3: the filename is the only clue, so a fine-tune is
// treated as cyto-style unless its name says cpsam (our trainer writes that in).
// Set BAL_MODEL_IS_SAM = true for a model fine-tuned from cpsam.
boolean isSAM = MODEL == 'cpsam' ||
        (binding.hasVariable('BAL_MODEL_IS_SAM') ? BAL_MODEL_IS_SAM as boolean : false)
if (!isSAM && legacyPython == null) {
    Dialogs.showErrorMessage("Cellpose", "'${MODEL}' is a cellpose 3 model, but no cellpose3 environment was found.")
    return
}

def builder = Cellpose2D.builder(MODEL)
        .pixelSize(PIXEL_SIZE_UM)                      // um/px, from the VSI's own OME metadata (40x_BF_01)
        .tileSize(TILE_SIZE)                           // matches the notebook's tile size / measured throughput
// Overlap between the extension's own internal tiles. The builder's default is diameter x 2,
// or 60 px when no diameter is given -- which is the cpsam path here, since cpsam is
// scale-free and gets diameter 0. Each tile is grown by `overlap` on every side, so a cell
// is whole in at least one tile only while its bounding box is <= 2 x overlap (120 px at the
// default). Cells here reach 173 px, but a cell has to land on a seam to be at risk, which
// works out at 0.36% of cells; raising this to 116 removes it for 21% more pixels per tile.
//        .setOverlap(116)
//        .normalizePercentilesGlobal(1, 99, 10)        // try this if detections look sparse vs. the notebook's own run
        .measureShape()                                // adds shape measurements comparable to the notebook's regionprops features

// Let Cellpose see past the region edge instead of clipping cells to it. This is the
// extension's own feature, and the two-argument form matters: build() forces padding to 0
// whenever constrainToParent is true, so the padding only survives if clipping is off.
if (REGION_PAD_UM > 0)
    builder.constrainToParent(false, REGION_PAD_UM)

if (isSAM)
    builder.useCellposeSAM()                           // cellpose 4.x model, matches the notebook pipeline
// cpsam is scale-free: it does no rescaling, and cellpose 4 warns that --diam_mean is
// "deprecated in v4.0.1+ and no longer used". Only cyto2/cyto3 (and models fine-tuned from
// them) rescale the image by diam_mean/diameter, so only they get the setting.
if (!isSAM && DIAMETER > 0)
    builder.diameter(DIAMETER)

switch (OUTPUT_TYPE) {
    case 'ANNOTATION':
        builder.createAnnotations()
        break
    case 'CELL':
        builder.cellExpansion(CELL_EXPANSION_UM)       // expand the mask outwards to form the cell boundary
               .cellConstrainScale(1.5)                // stop the expansion running away on large cells
        break
    case 'DETECTION':
        break                                          // the builder's default
    default:
        Dialogs.showErrorMessage("Cellpose", "OUTPUT_TYPE must be ANNOTATION, DETECTION or CELL -- got '${OUTPUT_TYPE}'")
        return
}
def cellpose = builder.build()

// --- run on the selected annotation(s) ----------------------------------
def imageData = getCurrentImageData()
def pathObjects = TARGETS == 'ALL_ANNOTATIONS' ? getAnnotationObjects() : getSelectedObjects()

if (pathObjects.isEmpty()) {
    Dialogs.showErrorMessage("Cellpose", TARGETS == 'ALL_ANNOTATIONS'
            ? "This image has no annotations to process."
            : "Please draw or select an annotation first, then run this script again.")
    return
}

// --- what the padding costs ----------------------------------------------
// The extension tiles the PADDED bounding box on a fixed grid, so padding that pushes a
// region one pixel over a multiple of the tile size buys a whole extra row AND column of
// tiles. Worth saying out loud, because the fix is to shrink the block, not the padding.
if (REGION_PAD_UM > 0) {
    double calUm = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons() ?: PIXEL_SIZE_UM
    int padPx = (int) Math.round(REGION_PAD_UM / (calUm ?: PIXEL_SIZE_UM))
    long bare = 0, padded = 0
    pathObjects.each { r ->
        def roi = r.getROI()
        if (roi == null) return
        double w = roi.getBoundsWidth(), h = roi.getBoundsHeight()
        bare += Math.ceil(w / TILE_SIZE) * Math.ceil(h / TILE_SIZE)
        padded += Math.ceil((w + 2 * padPx) / TILE_SIZE) * Math.ceil((h + 2 * padPx) / TILE_SIZE)
    }
    println "Region padding: ${REGION_PAD_UM} um (${padPx} px) -- cells may extend past the region edge."
    if (padded > bare * 1.1 && bare > 0) {
        println String.format("  NOTE: the padding adds %d tiles to %d (%.0f%% more work) because the padded",
                padded - bare, bare, 100.0 * (padded - bare) / bare)
        println "  regions no longer fit the ${TILE_SIZE} px tile grid. Shrink the blocks instead of the"
        println "  padding -- qupath_tissue_annotation.groovy sizes them to fit automatically."
    }
}

// Remember what was already there, so the ownership pass below only judges this run's work.
def beforeByRegion = [:]
pathObjects.each { r ->
    def seen = Collections.newSetFromMap(new IdentityHashMap())
    seen.addAll(r.getChildObjects())
    beforeByRegion[r] = seen
}

cellpose.detectObjects(imageData, pathObjects)

// Count only what this run produced, i.e. objects inside the regions we processed.
def newByRegion = [:]
pathObjects.each { r ->
    def seen = beforeByRegion[r]
    newByRegion[r] = r.getChildObjects().findAll { !seen.contains(it) }
}
int produced = (int) (newByRegion.values().sum { it.size() } ?: 0)

// --- one region per cell -------------------------------------------------
// With the padding above, two neighbouring regions both see the cells on their shared edge
// and each produces a whole copy. Hand every cell to the single region holding its centroid:
// blocks tile the slide without overlapping, so centroids partition it exactly.
def verdict = null
if (REGION_PAD_UM > 0 && REGION_OWNERSHIP && produced > 0) {
    def regionsLib = null
    try {
        def libFile = balScriptDir == null ? null : new File(balScriptDir, "bal_regions.groovy")
        if (libFile != null && libFile.exists()) regionsLib = evaluate(libFile)
    } catch (Exception e) {
        println "  WARNING: bal_regions.groovy failed to load (${e.getMessage()})"
    }
    if (regionsLib == null) {
        println "  WARNING: bal_regions.groovy is missing, so no region claimed its cells. A cell on"
        println "  the edge between two regions is now in the data TWICE. Counts from this run are"
        println "  not trustworthy -- restore the file, or set the padding to 0 to go back to clipping."
    } else {
        verdict = regionsLib.claimByCentroid(newByRegion)
        if (!verdict.drop.isEmpty()) {
            // keepChildren = true: a dropped soma must not take a nucleus with it.
            getCurrentHierarchy().removeObjects(verdict.drop, true)
            fireHierarchyUpdate()
        }
    }
}

int nCells = getCellObjects().size()
println "Cellpose-SAM done (OUTPUT_TYPE=${OUTPUT_TYPE}): ${produced} objects created in the selected region(s)"
if (verdict != null) {
    int kept = verdict.claimed + verdict.groundTruth
    println "  ${kept} kept, ${verdict.released + verdict.orphaned} dropped as belonging elsewhere:"
    println "    ${verdict.claimed} whose centroid is inside the region that produced them"
    if (verdict.groundTruth > 0) {
        println "    ${verdict.groundTruth} in Training/Validation regions, kept whatever their centroid -- a cell"
        println "      with pixels in a ground-truth mask and no label is taught to Cellpose as background"
    }
    if (verdict.released > 0)
        println "    ${verdict.released} claimed by a neighbouring region in this run (it has its own whole copy)"
    if (verdict.orphaned > 0)
        println "    ${verdict.orphaned} centred outside every region, i.e. found in the padding ring"
}
println "  totals now -- annotations: ${getAnnotationObjects().size()}, detections: ${getDetectionObjects().size()}, of which cell objects: ${nCells}"
