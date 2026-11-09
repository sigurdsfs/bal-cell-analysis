/**
 * How a cell becomes a crop for the transformer. ONE definition, used by both exporters.
 *
 * THE PROBLEM THIS SOLVES: a BAL smear is crowded. A 128 px (17.5 um) window centred on a
 * macrophage contains whole lymphocytes sitting beside it, and Phikon-v2's CLS token
 * summarises the WHOLE crop -- so those neighbours end up in the macrophage's embedding.
 * Every macrophage next to a lymphocyte then looks a little like a lymphocyte, and the
 * classifier learns the neighbourhood instead of the cell.
 *
 * THREE STYLES:
 *   'NEIGHBOURS_OUT'  (default) keep the target cell and the empty slide around it, but
 *                     paint every OTHER segmented cell out with the local background
 *                     colour. The cell keeps its true size and its real surroundings; what
 *                     disappears is the competing cells. The painted area is grown ~2 px so
 *                     a neighbour's dark rim does not survive.
 *   'CELL_ONLY'       paint everything outside the target cell. No context at all, so no
 *                     neighbour can leak in -- but the model then sees a cut-out, which is
 *                     unlike anything it was trained on, and loses the local background as
 *                     a brightness reference.
 *   'CONTEXT'         the raw window, neighbours and all. What we did before; keep it only
 *                     to reproduce an older run.
 *
 * SCALE IS PRESERVED on purpose. Cropping tight to each cell's outline and letting the
 * processor resize would also exclude neighbours, but it would make a 5 um lymphocyte and a
 * 25 um macrophage fill the frame identically -- and size is the strongest signal we have
 * between exactly those two.
 *
 * TRAINING AND INFERENCE CROPS MUST MATCH. A classifier fitted on one style and applied to
 * another is being fed a different measurement; the style is recorded in the manifest, in
 * crops/crop_style.txt and in the saved classifier so the mismatch is caught rather than
 * quietly costing accuracy.
 *
 * Returns a map; `newCropper(...)` gives you something that can write crops.
 */

import qupath.lib.regions.RegionRequest
import qupath.lib.roi.RoiTools
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import javax.imageio.ImageIO

def DEFAULT_STYLE = 'NEIGHBOURS_OUT'
def STYLES = ['NEIGHBOURS_OUT', 'CELL_ONLY', 'CONTEXT']

/**
 * @param server     the image to read pixels from
 * @param candidates every segmented object that could be a neighbour (cells, detections or
 *                   annotations -- the caller knows which list it is working from)
 * @param cropPx     crop size in full-resolution pixels
 * @param style      one of STYLES
 */
def newCropper = { server, Collection candidates, int cropPx, String style ->
    if (!(style in STYLES))
        throw new IllegalArgumentException("Unknown crop style '${style}' -- expected one of ${STYLES}")

    // A grid index over the candidates, so finding a cell's neighbours does not mean
    // testing it against every other cell on the slide (which is O(n^2) and the slide can
    // hold tens of thousands).
    def grid = [:].withDefault { [] }
    def keysFor = { double x, double y, double w, double h ->
        def ks = []
        for (int gx = (int) Math.floor(x / cropPx); gx <= (int) Math.floor((x + w) / cropPx); gx++)
            for (int gy = (int) Math.floor(y / cropPx); gy <= (int) Math.floor((y + h) / cropPx); gy++)
                ks << "${gx}:${gy}"
        ks
    }
    candidates.each { o ->
        def r = o.getROI()
        if (r == null) return
        keysFor(r.getBoundsX(), r.getBoundsY(), r.getBoundsWidth(), r.getBoundsHeight())
                .each { grid[it] << o }
    }

    int imgW = server.getWidth(), imgH = server.getHeight()
    int size = Math.min(cropPx, Math.min(imgW, imgH))

    /**
     * The colour of the empty slide in this crop, plus how much it varies.
     *
     * NOT the mean of the non-cell pixels: segmentation is never complete, and a cell that
     * was missed is "background" by that definition. Averaging then pulls the fill towards
     * purple and paints a lilac blob where empty slide should be. In brightfield the empty
     * slide is the BRIGHT end, so a high percentile of the sample survives a few missed
     * cells where a mean does not.
     */
    def backgroundOf = { img, Area cellsArea, int w, int h ->
        def px = []
        for (int y = 0; y < h; y += 2)
            for (int x = 0; x < w; x += 2)
                if (!cellsArea.contains(x + 0.5d, y + 0.5d)) px << img.getRGB(x, y)
        // A crop can be solid cells with no slide visible at all; then use the whole crop,
        // whose bright end is still the closest thing to background here.
        if (px.size() < 20) {
            px = []
            for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) px << img.getRGB(x, y)
        }
        def lum = { int c -> (c >> 16 & 0xff) * 0.299d + (c >> 8 & 0xff) * 0.587d + (c & 0xff) * 0.114d }
        px.sort { lum(it) }
        int pick = px[(int) Math.min(px.size() - 1, px.size() * 0.8)]
        // Grain, measured on the brightest fifth -- real empty slide is not a flat colour,
        // and a perfectly flat patch is a texture the model has never seen.
        def bright = px.subList((int) (px.size() * 0.8), px.size())
        double mean = bright.sum { lum(it) } / bright.size()
        double var = bright.sum { Math.pow(lum(it) - mean, 2) } / bright.size()
        [colour: new Color(pick >> 16 & 0xff, pick >> 8 & 0xff, pick & 0xff),
         sigma : Math.min(12d, Math.sqrt(var))]
    }

    return [
        style: style,
        cropPx: size,
        /**
         * Write one crop. Returns a map: x0/y0 of the window, and how many neighbouring
         * cells were painted out (0 for CONTEXT).
         */
        write: { target, File out ->
            def roi = target.getROI()
            double cx = roi.getCentroidX(), cy = roi.getCentroidY()
            // Clamp, so every crop is the same size and framed by the same rule. An
            // unclamped request at the image edge comes back smaller, which reframes the
            // cell and moves its embedding.
            int x0 = (int) Math.max(0, Math.min(Math.round(cx - size / 2d), imgW - size))
            int y0 = (int) Math.max(0, Math.min(Math.round(cy - size / 2d), imgH - size))
            def img = server.readRegion(
                    RegionRequest.createInstance(server.getPath(), 1.0, x0, y0, size, size))
            int w = img.getWidth(), h = img.getHeight()
            int painted = 0

            if (style != 'CONTEXT') {
                def toCrop = AffineTransform.getTranslateInstance(-x0, -y0)
                def targetArea = new Area(toCrop.createTransformedShape(RoiTools.getShape(roi)))
                def cellsArea = new Area(targetArea)
                def paint = new Area()

                if (style == 'CELL_ONLY') {
                    paint.add(new Area(new Rectangle(0, 0, w, h)))
                } else {
                    def window = new Rectangle(x0, y0, size, size)
                    def seen = new HashSet()
                    keysFor(x0, y0, size, size).each { k ->
                        grid[k].each { o ->
                            if (o == target || !seen.add(o)) return
                            def r2 = o.getROI()
                            if (r2 == null) return
                            if (!window.intersects(r2.getBoundsX(), r2.getBoundsY(),
                                    Math.max(r2.getBoundsWidth(), 1), Math.max(r2.getBoundsHeight(), 1))) return
                            def shape = toCrop.createTransformedShape(RoiTools.getShape(r2))
                            def a = new Area(shape)
                            // Grow by ~2 px: a Cellpose outline sits just inside the cell,
                            // so filling the mask alone leaves the neighbour's dark rim.
                            a.add(new Area(new BasicStroke(4f).createStrokedShape(shape)))
                            paint.add(a)
                            cellsArea.add(new Area(shape))
                            painted++
                        }
                    }
                }
                // Never paint over the cell this crop is about, whatever overlaps it.
                paint.subtract(targetArea)

                if (!paint.isEmpty()) {
                    def bg = backgroundOf(img, cellsArea, w, h)
                    // Rasterise the area ONCE into a mask, then write pixels through it.
                    // Testing Area.contains per pixel instead would be ~16k geometry hits
                    // per crop and turns a 30 ms crop into a second of work.
                    def mask = new java.awt.image.BufferedImage(w, h,
                            java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
                    def mg = mask.createGraphics()
                    mg.setColor(Color.WHITE); mg.fill(paint); mg.dispose()
                    def mData = mask.getRaster()
                    def rnd = new Random(42)          // reproducible: same cell, same crop
                    int br = bg.colour.getRed(), bgn = bg.colour.getGreen(), bb = bg.colour.getBlue()
                    double s = bg.sigma
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            if (mData.getSample(x, y, 0) == 0) continue
                            int d = (int) Math.round(rnd.nextGaussian() * s)
                            int rr = Math.max(0, Math.min(255, br + d))
                            int gg = Math.max(0, Math.min(255, bgn + d))
                            int bbv = Math.max(0, Math.min(255, bb + d))
                            img.setRGB(x, y, (rr << 16) | (gg << 8) | bbv)
                        }
                    }
                }
            }
            out.getParentFile()?.mkdirs()
            ImageIO.write(img, "PNG", out)
            [x0: x0, y0: y0, painted: painted]
        }
    ]
}

return [DEFAULT_STYLE: DEFAULT_STYLE, STYLES: STYLES, newCropper: newCropper]
