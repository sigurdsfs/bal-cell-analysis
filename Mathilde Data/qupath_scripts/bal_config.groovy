/**
 * Every machine-specific path in one place.
 *
 * Scripts load it with:
 *     def CFG = evaluate(new File(scriptDir, "bal_config.groovy"))
 * and fall back to their own defaults if it is missing, so they still run standalone.
 *
 * TO MOVE THIS PROJECT TO ANOTHER COMPUTER: copy this file to `bal_config.local.groovy`
 * and edit the copy. The local file is git-ignored and overrides the values below, so your
 * paths never conflict with anyone else's. Only the keys you set are overridden.
 *
 * Paths are checked when used, not here, so a missing optional tool costs nothing.
 */

def cfg = [
    // --- Python environments --------------------------------------------------
    // cellpose 4 (cpsam) + transformers + Phikon-v2: segmentation and all classification.
    pythonCellpose4 : [
        "C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose_seg/python.exe",
        "C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose_seg/python.exe",
    ],
    // cellpose 3 (cyto2/cyto3): a separate env because the two versions cannot coexist.
    pythonCellpose3 : [
        "C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose3/python.exe",
        "C:/Users/lababr/AppData/Local/anaconda3/envs/cellpose3/python.exe",
    ],

    // --- Folders ---------------------------------------------------------------
    projectRoot     : "C:/Users/Labsfs/Desktop/Mathilde",
    balClassifyDir  : "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/bal_classify",
    groundTruthDir  : "C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/cellpose_ground_truth",
    // Full-resolution pixels for the Python side (series 2, level 0 of the OME-Zarr).
    zarrPath        : "C:/Users/Labsfs/Desktop/Mathilde/SFS/bal2.zarr/2/0",
    instansegDir    : System.getProperty("user.home") + "/QuPath/v0.7/instanseg/downloaded",

    // --- Slide defaults --------------------------------------------------------
    // 40x_BF_01 of Image_BAL_#2.vsi, from its own OME metadata.
    pixelSizeUm     : 0.137,
    // Median cell diameter measured on this slide; cyto2/cyto3 need it, cpsam ignores it.
    cellDiameterPx  : 58,
    // How far outside each region Cellpose may look, so a cell crossing the region edge is
    // segmented whole instead of being clipped to the edge. A cell this script KEEPS has its
    // centroid inside the region, so it reaches at most half its own width outwards --
    // 87 px = 12 um for the largest cell measured here (173 px). 20 um leaves margin.
    // Set 0 to go back to clipping. See bal_regions.groovy for why clipping was the problem.
    regionPadUm     : 20,

    // --- Classifier crops ------------------------------------------------------
    // The window the transformer sees around each cell, in full-resolution pixels.
    // 128 px = 17.5 um: the cell plus a little of its surroundings.
    cropPx          : 128,
    // How that window is cleaned up before embedding. See bal_crops.groovy.
    //   NEIGHBOURS_OUT  paint the OTHER segmented cells out with the local background --
    //                   the default, because a crowded smear otherwise puts a lymphocyte
    //                   in its macrophage neighbour's embedding
    //   CELL_ONLY       paint everything outside the cell (no context at all)
    //   CONTEXT         raw window, neighbours included (what we did before)
    // CHANGING THIS INVALIDATES A SAVED CLASSIFIER. The style is recorded in the manifest
    // and in the saved model, and predicting with a mismatch is refused rather than
    // silently scored on the wrong kind of picture -- re-export and retrain after a change.
    cropStyle       : "NEIGHBOURS_OUT",
]

// A per-machine override file, git-ignored. Only the keys it sets are replaced.
// Looked for beside this file (whichever copy QuPath is running) -- an evaluated script
// cannot reliably report its own location, so both usual homes are checked.
def localCandidates = [
    new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.local.groovy"),
    new File(cfg.projectRoot + "/Mathilde Data/qupath_scripts", "bal_config.local.groovy"),
]
try {
    def local = localCandidates.find { it.exists() }
    if (local != null) {
        def overrides = evaluate(local)
        if (overrides instanceof Map) {
            cfg.putAll(overrides)
            println "bal_config: applied ${overrides.size()} override(s) from ${local.getName()}"
        }
    }
} catch (Exception e) {
    // A broken local file must never stop the pipeline; the defaults above still work.
    println "bal_config: ignoring bal_config.local.groovy (${e.getMessage()})"
}

// Convenience: first path in the list that exists, or null.
cfg.firstExisting = { List<String> candidates -> candidates.find { new File(it).exists() } }

cfg
