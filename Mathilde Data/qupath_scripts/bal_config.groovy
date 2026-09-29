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
