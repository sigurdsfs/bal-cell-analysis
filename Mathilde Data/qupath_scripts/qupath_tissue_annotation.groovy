/**
 * Creates annotations covering only the parts of the slide that hold material, so Cellpose
 * doesn't spend hours segmenting blank glass.
 *
 * WHY BLOCKS AND NOT ONE TISSUE OUTLINE: the Cellpose extension replaces every annotation
 * with its BOUNDING BOX before tiling (Cellpose2D.detectObjectsImpl). The cells on this slide
 * are scattered, so one tissue outline has a bounding box covering nearly the whole slide --
 * measured 2756 tiles against 2860 for the whole slide, i.e. no saving at all. A grid of
 * blocks keeps each bounding box tight, which is what actually cuts the runtime.
 *
 * "Border cells" are cells crossing a block edge, which USED to be clipped to it and then
 * measured as whole cells. THAT IS NOW FIXED: each block is padded by `regionPadUm` so
 * Cellpose can see past its edge, and each cell then goes to the block holding its centroid
 * (bal_regions.groovy). Border cells drop to ~0% at any block size.
 *
 * But the padding is tiled too, and it costs a whole extra row AND column of tiles per block
 * unless the PADDED extent lands on the tile grid -- which is why the size below is fitted
 * rather than round. Re-measured on this slide on 2026-11-09 (55481 x 52623, 1024 px tiles,
 * ~3 s each) at the thresholds this script actually uses:
 *
 *   requested  used    blocks   tiles    min    border cells
 *   whole slide        1        2860     143      0.0%
 *   4096       4096    136      2176     109      2.8%
 *   2048       2048    534      2136     107      5.6%
 *   1024       1024    2056     2056     103     11.0%
 *   4096       3804    162      2592     130      ~0%     <-- default, fitted
 *   4096       4096*   136      3400     170      ~0%     <-- the UNFITTED trap, 25 tiles/block
 *   2048       1756    717      2868     143      ~0%     <-- no better than the whole slide
 *
 * So: the fix costs 19% (109 -> 130 min), and fitting the block size is what keeps it to 19%
 * instead of 56%. It also settles the old question of whether to drop to 2048 for speed --
 * under padding, small blocks are worse than doing nothing, because each one pays for its
 * own ring of padding. Ask for 4096 and you are given 3804.
 *
 * NOTE: the earlier version of this header claimed 1200 tiles / 60 min at 4096 px blocks.
 * That does not reproduce; the numbers above are what this script reports today. The old
 * figures were probably taken at a different BACKGROUND_LEVEL or MIN_FRACTION.
 *
 * Run this first, then run the segmentation with these annotations selected.
 */

import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathObjects

// Machine-specific paths and settings; the literals are only a fallback for standalone runs.
def CFG = [:]
def balScriptDir = null
try {
    def cfgFile = [new File(System.getProperty("user.home") + "/QuPath/v0.7/scripts", "bal_config.groovy"),
                   new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts", "bal_config.groovy")]
            .find { it.exists() }
    if (cfgFile != null) {
        CFG = evaluate(cfgFile) ?: [:]
        balScriptDir = cfgFile.getParentFile()
    }
} catch (Exception ignored) { }

// Overridable from the GUI script.
int BLOCK_SIZE          = binding.hasVariable('BAL_BLOCK_SIZE')        ? BAL_BLOCK_SIZE as int          : 4096
double BACKGROUND_LEVEL = binding.hasVariable('BAL_BACKGROUND_LEVEL')  ? BAL_BACKGROUND_LEVEL as double : 0.88
double MIN_FRACTION     = binding.hasVariable('BAL_MIN_TISSUE_FRAC')   ? BAL_MIN_TISSUE_FRAC as double  : 0.02
int TARGET_WIDTH = 2000        // thumbnail width used to find the tissue
// The segmentation pads each block by this much, and tiles the PADDED box -- so the block
// size has to be chosen knowing the padding, not after it.
double REGION_PAD_UM    = binding.hasVariable('BAL_REGION_PAD_UM')     ? BAL_REGION_PAD_UM as double    : (CFG.regionPadUm ?: 20) as double
int TILE_SIZE           = binding.hasVariable('BAL_TILE_SIZE')         ? BAL_TILE_SIZE as int           : 1024

// --- fit the block size to the padded tile grid ------------------------------
double pxUm = getCurrentServer()?.getPixelCalibration()?.getAveragedPixelSizeMicrons() ?: (CFG.pixelSizeUm ?: 0.137)
int padPx = (int) Math.round(REGION_PAD_UM / (pxUm ?: 0.137))
if (REGION_PAD_UM > 0 && balScriptDir != null) {
    try {
        def libFile = new File(balScriptDir, "bal_regions.groovy")
        if (libFile.exists()) {
            def fit = (evaluate(libFile)).fitBlockSize(BLOCK_SIZE, TILE_SIZE, padPx)
            if (fit.fitted) {
                println "Block size ${BLOCK_SIZE} -> ${fit.size} px, so that ${fit.size} + 2x${padPx} px of padding is"
                println "  exactly ${fit.padded} px = ${fit.tilesPerBlock} tiles per block instead of ${fit.tilesIfUnfitted}."
                BLOCK_SIZE = fit.size
            }
        } else {
            println "WARNING: bal_regions.groovy is missing, so the block size was not fitted to the"
            println "  padded tile grid. Expect roughly 50% more tiles than necessary."
        }
    } catch (Exception e) {
        println "WARNING: could not fit the block size (${e.getMessage()}); using ${BLOCK_SIZE} as asked."
    }
}

def imageData = getCurrentImageData()
def server = imageData.getServer()

double downsample = Math.max(1.0, server.getWidth() / (double) TARGET_WIDTH)
def request = RegionRequest.createInstance(server, downsample)
def img = server.readRegion(request)
int tw = img.getWidth(), th = img.getHeight()
println "Looking for tissue on a ${tw} x ${th} thumbnail..."

// Mark every thumbnail pixel darker than the background.
boolean[][] dark = new boolean[th][tw]
for (int y = 0; y < th; y++) {
    for (int x = 0; x < tw; x++) {
        int rgb = img.getRGB(x, y)
        double gray = (0.2125 * ((rgb >> 16) & 0xff) + 0.7154 * ((rgb >> 8) & 0xff) + 0.0721 * (rgb & 0xff)) / 255.0
        dark[y][x] = gray < BACKGROUND_LEVEL
    }
}

// Walk the full-resolution block grid; keep blocks holding enough material.
double sx = tw / (double) server.getWidth(), sy = th / (double) server.getHeight()
int nx = (int) Math.ceil(server.getWidth() / (double) BLOCK_SIZE)
int ny = (int) Math.ceil(server.getHeight() / (double) BLOCK_SIZE)

def previous = getAnnotationObjects().findAll { it.getName()?.startsWith("Tissue block") }
if (!previous.isEmpty()) {
    println "Removing ${previous.size()} tissue block(s) from a previous run"
    removeObjects(previous, true)
}

def blocks = []
for (int j = 0; j < ny; j++) {
    for (int i = 0; i < nx; i++) {
        int x0 = i * BLOCK_SIZE, y0 = j * BLOCK_SIZE
        int x1 = Math.min(x0 + BLOCK_SIZE, server.getWidth()), y1 = Math.min(y0 + BLOCK_SIZE, server.getHeight())
        int tx0 = (int) (x0 * sx), tx1 = Math.max((int) (x1 * sx), (int) (x0 * sx) + 1)
        int ty0 = (int) (y0 * sy), ty1 = Math.max((int) (y1 * sy), (int) (y0 * sy) + 1)
        int total = 0, hits = 0
        for (int y = ty0; y < Math.min(ty1, th); y++)
            for (int x = tx0; x < Math.min(tx1, tw); x++) { total++; if (dark[y][x]) hits++ }
        if (total > 0 && (hits / (double) total) >= MIN_FRACTION) {
            def roi = ROIs.createRectangleROI(x0, y0, x1 - x0, y1 - y0, ImagePlane.getDefaultPlane())
            def block = PathObjects.createAnnotationObject(roi)
            block.setName("Tissue block ${i}_${j}")
            blocks << block
        }
    }
}

if (blocks.isEmpty()) {
    Dialogs.showErrorMessage("Tissue detection",
            "No tissue found. Raise BACKGROUND_LEVEL (now ${BACKGROUND_LEVEL}) or lower MIN_FRACTION (now ${MIN_FRACTION}).")
    return
}

addObjects(blocks)
selectObjects(blocks)

// Each block is tiled at its PADDED size, which is what actually gets read and segmented.
int perSide = (int) Math.ceil((BLOCK_SIZE + 2 * padPx) / (double) TILE_SIZE)
int tilesPerBlock = perSide * perSide
int tilesKept = blocks.size() * tilesPerBlock
int tilesWhole = (int) (Math.ceil(server.getWidth() / (double) TILE_SIZE) * Math.ceil(server.getHeight() / (double) TILE_SIZE))
println "Created and selected ${blocks.size()} tissue block(s) of ${BLOCK_SIZE} px (${nx * ny} in the full grid)."
if (REGION_PAD_UM > 0)
    println "  each padded to ${BLOCK_SIZE + 2 * padPx} px during segmentation (${REGION_PAD_UM} um either side), so cells on a block edge stay whole"
println "  about ${tilesKept} tiles of ${TILE_SIZE} px instead of ${tilesWhole} for the whole slide"
println String.format("  at 3 s/tile: roughly %.0f min instead of %.0f min", tilesKept * 3 / 60.0, tilesWhole * 3 / 60.0)
println "Now run the segmentation with these annotations selected."
