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
 * Measured on this slide (55481 x 52623, ~3 s per 1024 px tile):
 *   whole slide          2860 tiles   143 min    border cells  0.0%
 *   4096 px blocks       1200 tiles    60 min                  2.8%   <-- default
 *   2048 px blocks       1048 tiles    52 min                  5.6%
 *   1024 px blocks        849 tiles    42 min                 11.0%
 * Smaller blocks skip more glass but cut more cells at block edges, because each block is
 * segmented independently. 4096 is the sweet spot; drop to 2048 if you want the extra speed.
 *
 * Run this first, then run the segmentation with these annotations selected (the GUI does
 * both for you if you pick "Tissue blocks" as the region).
 */

import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathObjects

// Overridable from the GUI script.
int BLOCK_SIZE          = binding.hasVariable('BAL_BLOCK_SIZE')        ? BAL_BLOCK_SIZE as int          : 4096
double BACKGROUND_LEVEL = binding.hasVariable('BAL_BACKGROUND_LEVEL')  ? BAL_BACKGROUND_LEVEL as double : 0.88
double MIN_FRACTION     = binding.hasVariable('BAL_MIN_TISSUE_FRAC')   ? BAL_MIN_TISSUE_FRAC as double  : 0.02
int TARGET_WIDTH = 2000        // thumbnail width used to find the tissue

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

int tilesPerBlock = (int) Math.pow(Math.max(1, BLOCK_SIZE / 1024), 2)
int tilesKept = blocks.size() * tilesPerBlock
int tilesWhole = (int) (Math.ceil(server.getWidth() / 1024.0) * Math.ceil(server.getHeight() / 1024.0))
println "Created and selected ${blocks.size()} tissue block(s) of ${BLOCK_SIZE} px (${nx * ny} in the full grid)."
println "  about ${tilesKept} tiles of 1024 px instead of ${tilesWhole} for the whole slide"
println String.format("  at 3 s/tile: roughly %.0f min instead of %.0f min", tilesKept * 3 / 60.0, tilesWhole * 3 / 60.0)
println "Now run the segmentation with these annotations selected."
