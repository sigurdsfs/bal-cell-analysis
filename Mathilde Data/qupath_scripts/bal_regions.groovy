/**
 * Who owns a cell at a region boundary, and how large a tissue block may be.
 *
 * WHY THIS EXISTS: the Cellpose extension CLIPS every detection to its parent annotation.
 * `constrainToParent` defaults to true in CellposeBuilder and nothing here ever overrode it,
 * so a cell crossing the edge of a tissue block entered the data as a whole cell carrying a
 * truncated area, diameter and nc_ratio -- and nothing marked it. Measured on this slide:
 * 2.8% of cells at 4096 px blocks, 5.6% at 2048, 11.0% at 1024.
 *
 * DROPPING THOSE CELLS IS NOT THE FIX. The chance of touching a 4096 px block edge grows with
 * the cell, because a bigger cell reaches further past the line: 2.8% for a 58 px lymphocyte,
 * 5.0% for the 103 px median cell here, 8.3% for the largest 173 px macrophage. Excluding
 * edge-touching cells therefore removes 2.9x as many macrophages as lymphocytes, and tilts
 * the differential count -- the one number this whole pipeline exists to produce.
 *
 * So instead: pad each region, segment it WITHOUT clipping, then give every cell to exactly
 * one region, the one containing its CENTROID. Tissue blocks tile the slide without
 * overlapping, so centroids partition it: no cell is counted twice, and none is cut.
 *
 * TRAINING REGIONS ARE THE EXCEPTION, and it matters. A Training/Validation rectangle becomes
 * a ground-truth mask. A cell whose pixels sit in that mask but whose label was dropped is
 * taught to Cellpose as BACKGROUND -- the same silent corpus poisoning that the extension's
 * own exporter causes with classified cells (see CLAUDE.md). Those regions therefore keep
 * every cell that reaches them, and `claimByCentroid` leaves them alone.
 *
 * Shared rather than inlined so it can be tested on synthetic objects: the real path needs
 * Cellpose, a GPU and a slide, and so can only be checked by hand.
 *
 * Loaded with:  def REG = evaluate(new File(scriptDir, "bal_regions.groovy"))
 */

// Classes whose regions are ground truth, not counting frames.
def GROUND_TRUTH_CLASSES = ["Training", "Validation"] as Set

/**
 * True when a region is a counting frame, i.e. a cell belongs to it only if its centroid
 * does. False for ground-truth rectangles, which keep everything that reaches them.
 */
def isCountingRegion = { region ->
    def c = region?.getPathClass()?.toString()
    !(c in GROUND_TRUTH_CLASSES)
}

/**
 * Pick a block size whose PADDED extent lands on the tile grid.
 *
 * WHY: the extension tiles the padded bounding box on a fixed grid of `tileSize`, so padding
 * that pushes a block one pixel over a multiple costs a whole extra row AND column of tiles.
 * Measured for this slide at 4096 px blocks with 146 px of padding: 25 tiles per block
 * instead of 16, i.e. 94 min instead of 70 for the same result. Shrinking the block so the
 * padded extent is exactly 4096 keeps it at 16 tiles and only needs 16% more blocks.
 *
 * Returns a map: size (use this), requested, padded, tilesPerBlock, tilesIfUnfitted.
 */
def fitBlockSize = { int requested, int tileSize, int padPx ->
    int pad2 = 2 * Math.max(0, padPx)
    int tilesUnfitted = (int) Math.pow(Math.ceil((requested + pad2) / (double) tileSize), 2)
    // The largest padded extent that is a whole number of tiles and still fits the request.
    int steps = (int) Math.floor((requested + pad2) / (double) tileSize)
    int padded = steps * tileSize
    int size = padded - pad2
    // A block smaller than its own padding is not a block. Happens for tiny requests; leave
    // the request alone and let the caller pay for the extra tiles rather than return
    // something absurd.
    if (steps < 1 || size < tileSize / 2) {
        return [size: requested, requested: requested, padded: requested + pad2,
                tilesPerBlock: tilesUnfitted, tilesIfUnfitted: tilesUnfitted, fitted: false]
    }
    [size: size, requested: requested, padded: padded,
     tilesPerBlock: steps * steps, tilesIfUnfitted: tilesUnfitted, fitted: size != requested]
}

/**
 * Decide which of the objects produced under each region that region actually owns.
 *
 * `regionChildren` maps each region object to the objects produced inside it. Regions are
 * considered in the order given and the FIRST one whose ROI contains a centroid claims it,
 * so overlapping regions can never claim the same cell twice -- adjacent rectangles meet on
 * a half-open boundary, but relying on that alone would be relying on float equality.
 *
 * Returns [keep: [...], drop: [...], claimed: n, released: n, orphaned: n, groundTruth: n].
 *   released  = owned by a different region in this run (its neighbour will produce it whole)
 *   orphaned  = centroid in no region at all, i.e. it came out of the padding ring
 */
def claimByCentroid = { Map regionChildren ->
    def regions = regionChildren.keySet().toList()
    def keep = [], drop = []
    int claimed = 0, released = 0, orphaned = 0, groundTruth = 0

    regionChildren.each { region, children ->
        if (!isCountingRegion(region)) {
            // Ground truth: completeness beats ownership. See the header.
            groundTruth += children.size()
            keep.addAll(children)
            return
        }
        children.each { obj ->
            def roi = obj?.getROI()
            if (roi == null) { keep << obj; return }
            double cx = roi.getCentroidX(), cy = roi.getCentroidY()
            def owner = regions.find { r -> r.getROI()?.contains(cx, cy) }
            if (owner == null)            { drop << obj; orphaned++ }
            else if (owner.is(region))    { keep << obj; claimed++ }
            else                          { drop << obj; released++ }
        }
    }
    [keep: keep, drop: drop, claimed: claimed, released: released,
     orphaned: orphaned, groundTruth: groundTruth]
}

[
    GROUND_TRUTH_CLASSES: GROUND_TRUTH_CLASSES,
    isCountingRegion    : isCountingRegion,
    fitBlockSize        : fitBlockSize,
    claimByCentroid     : claimByCentroid,
]
