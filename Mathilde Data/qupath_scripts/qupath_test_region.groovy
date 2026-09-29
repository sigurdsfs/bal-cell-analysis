/**
 * Creates the shared benchmark region used to compare QuPath against the notebook.
 *
 * Run this FIRST, then run qupath_cellpose_detection.groovy with the rectangle selected.
 *
 * The notebook's Cellpose-SAM found 98 cells in exactly this 1024x1024 region
 * (median cell diameter 58.5 px = 8.0 um at 0.137 um/px). Whatever QuPath reports
 * for the same rectangle is directly comparable to that number.
 *
 * Coordinates are full-resolution pixels of the 40x_BF_01 series -- the same pixel
 * grid as SFS/bal2.zarr series 2, level 0 (52623 x 55481).
 */

// Interesting regions found so far (full-resolution pixel coordinates):
//   x=45056, y=24576  benchmark, scattered cells -- notebook reference: 98 cells
//   x=12288, y=20480  dense clump -- cpsam 40 vs cyto3 63 vs cyto2 71, the biggest disagreement
//   x=32768, y=18432  well-spread cells, good for judging outline quality
//   x=33253, y=18574  the only red/amber debris particle on the whole slide
int X    = binding.hasVariable('BAL_REGION_X')    ? BAL_REGION_X as int    : 45056
int Y    = binding.hasVariable('BAL_REGION_Y')    ? BAL_REGION_Y as int    : 24576
int SIZE = binding.hasVariable('BAL_REGION_SIZE') ? BAL_REGION_SIZE as int : 1024

def imageData = getCurrentImageData()
def server = imageData.getServer()

// Sanity check: make sure the open image really is the 40x series the coordinates refer to.
if (server.getHeight() != 52623 || server.getWidth() != 55481) {
    println "WARNING: open image is ${server.getWidth()} x ${server.getHeight()}, expected 55481 x 52623 (40x_BF_01)."
    println "If you opened a different series, these coordinates point somewhere else."
}

def roi = ROIs.createRectangleROI(X, Y, SIZE, SIZE, ImagePlane.getDefaultPlane())
def annotation = PathObjects.createAnnotationObject(roi)
annotation.setName("benchmark_y${Y}_x${X}")
addObject(annotation)
selectObjects([annotation])

println "Benchmark region added and selected: x=${X}, y=${Y}, ${SIZE}x${SIZE}"
println "Notebook reference for this region: 98 cells."
println "Now run qupath_cellpose_detection.groovy without changing the selection."
