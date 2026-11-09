/**
 * The region boundary: does a cell crossing it survive, exactly once, and whole?
 *
 * WHY: the Cellpose extension clips every detection to its parent annotation, so a cell
 * crossing a tissue-block edge entered the data as a whole cell with a truncated area and
 * nc_ratio, and nothing marked it -- 2.8% of cells at 4096 px blocks, 11.0% at 1024 px.
 * The fix pads each region so Cellpose sees past the edge, then gives every cell to the one
 * region holding its CENTROID. Padding alone would DOUBLE every edge cell, so the claiming
 * half is not a refinement, it is what makes the padding safe.
 *
 * The real path needs Cellpose, a GPU and a slide, so it can only be checked by hand. This
 * exercises the half that decides the data: bal_regions.groovy, on objects whose right
 * answer is known exactly. Needs no project and no image.
 */
import static qupath.lib.scripting.QP.*
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

def DIR = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts")
def REG = evaluate(new File(DIR, "bal_regions.groovy"))

int pass = 0, fail = 0
def check = { String what, boolean ok, String got ->
    if (ok) { pass++; println "ok    ${what}" }
    else { fail++; println "FAIL  ${what}  -- got: ${got}" }
}

def plane = ImagePlane.getDefaultPlane()
def ann = { double x, double y, double w, double h, String cls ->
    def o = PathObjects.createAnnotationObject(ROIs.createRectangleROI(x, y, w, h, plane))
    if (cls != null) o.setPathClass(getPathClass(cls))
    o
}
// A cell of diameter d centred on (cx, cy).
def cell = { double cx, double cy, double d ->
    PathObjects.createDetectionObject(ROIs.createEllipseROI(cx - d / 2, cy - d / 2, d, d, plane))
}

println "=== fitBlockSize: the padded block must land on the tile grid ==="
// Measured: a 4096 px block padded by 146 px is 4388 px, which needs 5x5 tiles of 1024
// instead of 4x4 -- 94 min instead of 70 for the same slide. Shrinking the block fixes it.
def fit = REG.fitBlockSize(4096, 1024, 146)
check("a 4096 request becomes 3804", fit.size == 3804, "${fit.size}")
check("so the padded block is exactly 4096", fit.padded == 4096, "${fit.padded}")
check("16 tiles per block, not 25", fit.tilesPerBlock == 16 && fit.tilesIfUnfitted == 25,
        "${fit.tilesPerBlock} vs ${fit.tilesIfUnfitted}")
check("and it reports that it changed the size", fit.fitted, "fitted=${fit.fitted}")
def fit0 = REG.fitBlockSize(4096, 1024, 0)
check("with no padding 4096 is left alone", fit0.size == 4096 && !fit0.fitted, "${fit0.size}")
// A block smaller than its own padding is not a block; the request must survive intact
// rather than come back as 0 or a negative number.
def fitTiny = REG.fitBlockSize(256, 1024, 146)
check("an absurdly small block is returned unchanged", fitTiny.size == 256, "${fitTiny.size}")

println ""
println "=== two blocks side by side, a cell on the seam ==="
// Blocks A and B meet at x = 1000. The cell is centred at 990, so A owns it; both blocks
// see it, because both were padded, and both produced a whole copy of it.
def a = ann(0, 0, 1000, 1000, null)
def b = ann(1000, 0, 1000, 1000, null)
def fromA = cell(990, 500, 60)       // centroid in A, body crossing into B
def fromB = cell(990, 500, 60)       // B's own copy of the same cell
def verdict = REG.claimByCentroid([(a): [fromA], (b): [fromB]])
check("exactly one copy survives", verdict.keep.size() == 1, "kept ${verdict.keep.size()}")
check("and it is the one A produced", verdict.keep[0].is(fromA),
        verdict.keep[0].is(fromB) ? "B's copy" : "neither")
check("B's copy is dropped as belonging elsewhere", verdict.drop.size() == 1 && verdict.released == 1,
        "drop=${verdict.drop.size()} released=${verdict.released}")
// The point of the whole change: the surviving cell is NOT cut at x=1000.
def kept = verdict.keep[0].getROI()
check("the surviving cell still crosses the seam (it was not clipped)",
        kept.getBoundsX() + kept.getBoundsWidth() > 1000, "right edge ${kept.getBoundsX() + kept.getBoundsWidth()}")
check("and keeps its full width", Math.round(kept.getBoundsWidth()) == 60, "${kept.getBoundsWidth()}")

println ""
println "=== a centroid exactly on the seam is claimed once, not twice and not never ==="
// Adjacent rectangles meet on a half-open boundary, but relying on float equality for that
// would be a silent way to lose or duplicate a cell. claimByCentroid takes the FIRST
// containing region, so the answer is one either way.
def onSeamA = cell(1000, 500, 60), onSeamB = cell(1000, 500, 60)
def seam = REG.claimByCentroid([(a): [onSeamA], (b): [onSeamB]])
check("exactly one survives", seam.keep.size() == 1, "kept ${seam.keep.size()}")
check("nothing is orphaned", seam.orphaned == 0, "orphaned=${seam.orphaned}")

println ""
println "=== the padding ring ==="
// Found only because the region was padded; its centroid belongs to no region at all, and
// in a whole-slide run its real owner is a block that was skipped as glass.
def inRing = cell(1500, 500, 60)       // well inside B's area, but produced under A
def ring = REG.claimByCentroid([(a): [inRing]])
check("a cell centred outside every region is dropped", ring.keep.isEmpty(), "kept ${ring.keep.size()}")
check("and counted as orphaned, not as released", ring.orphaned == 1 && ring.released == 0,
        "orphaned=${ring.orphaned} released=${ring.released}")

println ""
println "=== an ordinary interior cell is untouched ==="
def inside = cell(500, 500, 60)
def interior = REG.claimByCentroid([(a): [inside]])
check("kept", interior.keep.size() == 1 && interior.claimed == 1, "kept ${interior.keep.size()}")

println ""
println "=== Training/Validation regions keep everything ==="
// THE TRAP: a Training rectangle becomes a ground-truth mask. A cell whose pixels are in
// that mask but whose label was dropped is taught to Cellpose as BACKGROUND -- the same
// silent corpus poisoning as the extension's own exporter. So ownership must not apply here.
["Training", "Validation"].each { cls ->
    def rect = ann(0, 0, 1000, 1000, cls)
    def spilling = cell(1010, 500, 60)      // centroid OUTSIDE the rectangle
    def gt = REG.claimByCentroid([(rect): [spilling]])
    check("a ${cls} region keeps a cell centred outside it", gt.keep.size() == 1 && gt.drop.isEmpty(),
            "kept ${gt.keep.size()} dropped ${gt.drop.size()}")
    check("  and says so as groundTruth, not as claimed", gt.groundTruth == 1 && gt.claimed == 0,
            "groundTruth=${gt.groundTruth} claimed=${gt.claimed}")
    check("  isCountingRegion is false for ${cls}", !REG.isCountingRegion(rect), "true")
}
check("isCountingRegion is true for an unclassed region", REG.isCountingRegion(a), "false")
check("isCountingRegion is true for a cell-type class",
        REG.isCountingRegion(ann(0, 0, 10, 10, "Macrophage")), "false")

println ""
println "=== a region holding both kinds of object ==="
// Mixed, because this is what a real block looks like: most cells interior, a few on each
// edge, and one that the neighbour will claim.
// Note the difference between the last two: a cell centred in B is RELEASED, because B is in
// this run and will produce its own whole copy, while one centred beyond every region is
// ORPHANED and simply lost. Only the orphan is a cell nobody will count.
def many = [cell(500, 500, 60),      // interior          -> claimed
            cell(100, 100, 60),      // interior          -> claimed
            cell(990, 500, 60),      // crosses into B    -> claimed by A
            cell(1500, 500, 60),     // centred in B      -> released to B
            cell(2500, 500, 60)]     // centred nowhere   -> orphaned
def mixed = REG.claimByCentroid([(a): many, (b): []])
check("3 of 5 kept", mixed.keep.size() == 3 && mixed.claimed == 3, "kept ${mixed.keep.size()}")
check("1 released to the neighbouring region", mixed.released == 1, "released=${mixed.released}")
check("1 orphaned beyond every region", mixed.orphaned == 1, "orphaned=${mixed.orphaned}")
check("counts add up to the input", mixed.keep.size() + mixed.drop.size() == 5,
        "${mixed.keep.size()} + ${mixed.drop.size()}")

println ""
println "=== an object with no ROI is never silently deleted ==="
def noRoi = PathObjects.createDetectionObject(ROIs.createEmptyROI())
def empty = REG.claimByCentroid([(a): [noRoi]])
check("kept rather than dropped", empty.keep.size() == 1, "kept ${empty.keep.size()}")

println ""
println "=== the detection script actually does this ==="
// Structural: the padding is worthless without the claiming, and the claiming is worthless
// without the padding. Both have to be in the script that runs.
def det = new File(DIR, "qupath_cellpose_detection.groovy").getText("UTF-8")
check("it turns the extension's clipping OFF", det.contains("constrainToParent(false"), "not found")
check("it passes the padding to the extension", det.contains("REGION_PAD_UM"), "not found")
check("it claims the cells afterwards", det.contains("claimByCentroid"), "not found")
check("it loads bal_regions.groovy to do it", det.contains('"bal_regions.groovy"'), "not found")
check("it removes the losers with keepChildren=true (a soma must not take its nucleus)",
        det.contains("removeObjects(verdict.drop, true)"), "not found")
check("padding 0 restores the old clipping", det.contains("REGION_PAD_UM > 0"), "not found")

def nuc = new File(DIR, "qupath_cells_with_nuclei.groovy").getText("UTF-8")
check("the soma pass claims by region", nuc.contains("BAL_REGION_OWNERSHIP = true"), "not found")
// If the nucleus pass claimed by region too, a soma straddling the edge would be stripped
// of the nucleus sitting on the far side of that line -- exactly the cells being rescued.
check("the nucleus pass does NOT", nuc.contains("BAL_REGION_OWNERSHIP = false"), "not found")

def tis = new File(DIR, "qupath_tissue_annotation.groovy").getText("UTF-8")
check("the block maker fits the block size to the padded grid", tis.contains("fitBlockSize"), "not found")

println ""
println "${pass} checks passed, ${fail} failed"
if (fail > 0) throw new IllegalStateException("${fail} check(s) failed")
