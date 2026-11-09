/**
 * The crop a cell becomes: does the neighbour really go, and does the cell really stay?
 *
 * WHY: a 128 px window on a BAL smear contains the neighbouring cells, and Phikon-v2's CLS
 * token summarises the WHOLE window -- so a macrophage beside a lymphocyte was embedded
 * with that lymphocyte in the picture. On the real slide, with the 40 most crowded cells in
 * the Training rectangle, a median of 37% of each window belonged to OTHER cells. This
 * checks the fix on a synthetic image where the right answer is known exactly.
 *
 * It also checks the invariant that makes the classifier valid at all: the crops it is
 * TRAINED on (qupath_export_labels.groovy) and the crops it is APPLIED to
 * (qupath_export_cells.groovy) must be made by the same code, bal_crops.groovy.
 *
 * Needs no project: it paints its own cells on a blank image.
 */
import static qupath.lib.scripting.QP.*
import qupath.lib.images.ImageData
import qupath.lib.images.servers.WrappedBufferedImageServer
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs
import java.awt.Color
import java.awt.image.BufferedImage

def DIR = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts")
def lib = evaluate(new File(DIR, "bal_crops.groovy"))

int pass = 0, fail = 0
def check = { String what, boolean ok, String got ->
    if (ok) { pass++; println "ok    ${what}" }
    else { fail++; println "FAIL  ${what}  -- got: ${got}" }
}

// --- a slide with two cells, 40 px apart: both fall in one 128 px window -----
int W = 400, H = 400
def img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
def g = img.createGraphics()
g.setColor(new Color(245, 243, 250))                 // pale empty slide
g.fillRect(0, 0, W, H)
g.setColor(new Color(90, 40, 140))                   // target cell, dark purple
g.fillOval(180, 180, 40, 40)
// The neighbour is DRAWN 2 px larger than the outline it will be given, because that is
// what a Cellpose mask does: it sits just inside the cell and leaves a dark rim behind.
g.setColor(new Color(40, 40, 160))
g.fillOval(228, 178, 44, 44)
g.dispose()

def server = new WrappedBufferedImageServer("crop-test", img)
def plane = ImagePlane.getDefaultPlane()
def target = PathObjects.createDetectionObject(ROIs.createEllipseROI(180, 180, 40, 40, plane))
def neighbour = PathObjects.createDetectionObject(ROIs.createEllipseROI(230, 180, 40, 40, plane))

def out = new File(System.getProperty("java.io.tmpdir"), "bal_crop_test")
out.mkdirs()
out.listFiles()?.each { it.delete() }

// Where the two cells land inside a 128 px crop centred on the target (centre 200,200,
// so the window starts at 136,136).
int tx = 200 - 136, ty = 200 - 136           // target centre in crop coordinates
int nx = 250 - 136, ny = 200 - 136           // neighbour centre
def isDark = { im, int x, int y -> (im.getRGB(x, y) >> 16 & 0xff) + (im.getRGB(x, y) >> 8 & 0xff) +
        (im.getRGB(x, y) & 0xff) < 450 }

def cut = { String style, String name ->
    def c = lib.newCropper(server, [target, neighbour], 128, style)
    def f = new File(out, name)
    def r = c.write(target, f)
    [img: javax.imageio.ImageIO.read(f), res: r, file: f]
}

println "=== CONTEXT (what we did before) ==="
def ctx = cut('CONTEXT', 'context.png')
check("the crop is 128x128", ctx.img.getWidth() == 128 && ctx.img.getHeight() == 128,
        "${ctx.img.getWidth()}x${ctx.img.getHeight()}")
check("the target cell is in it", isDark(ctx.img, tx, ty), "rgb=${Integer.toHexString(ctx.img.getRGB(tx, ty))}")
// This is the bug being fixed: the neighbour is in the macrophage's picture.
check("and so is the NEIGHBOUR -- the problem", isDark(ctx.img, nx, ny),
        "rgb=${Integer.toHexString(ctx.img.getRGB(nx, ny))}")
check("nothing is painted", ctx.res.painted == 0, "painted=${ctx.res.painted}")

println ""
println "=== NEIGHBOURS_OUT (the default) ==="
def no = cut('NEIGHBOURS_OUT', 'neighbours_out.png')
check("the target cell is untouched", isDark(no.img, tx, ty), "rgb=${Integer.toHexString(no.img.getRGB(tx, ty))}")
check("the neighbour is gone", !isDark(no.img, nx, ny), "rgb=${Integer.toHexString(no.img.getRGB(nx, ny))}")
check("it reports painting one cell", no.res.painted == 1, "painted=${no.res.painted}")
// The 2 px of cell the outline does not cover: filling the mask alone leaves that ring,
// which is dark and still reads as a cell, so the painted area is grown.
check("the rim OUTSIDE the neighbour's outline goes too",
        !isDark(no.img, nx - 21, ny) && !isDark(no.img, nx, ny - 21),
        "left=${Integer.toHexString(no.img.getRGB(nx - 21, ny))} top=${Integer.toHexString(no.img.getRGB(nx, ny - 21))}")
// The fill must look like this slide's empty background, not like white or like a cell.
int fill = no.img.getRGB(nx, ny)
int bgr = fill >> 16 & 0xff, bgg = fill >> 8 & 0xff, bgb = fill & 0xff
check("the fill is the slide's own background colour (245,243,250 +/- grain)",
        Math.abs(bgr - 245) < 20 && Math.abs(bgg - 243) < 20 && Math.abs(bgb - 250) < 20,
        "rgb=${bgr},${bgg},${bgb}")
check("the empty slide around the cell is left alone",
        !isDark(no.img, 5, 5) && Math.abs((no.img.getRGB(5, 5) >> 16 & 0xff) - 245) < 6,
        "rgb=${Integer.toHexString(no.img.getRGB(5, 5))}")

println ""
println "=== CELL_ONLY ==="
def only = cut('CELL_ONLY', 'cell_only.png')
check("the target cell is untouched", isDark(only.img, tx, ty), "rgb=${Integer.toHexString(only.img.getRGB(tx, ty))}")
check("the neighbour is gone", !isDark(only.img, nx, ny), "rgb=${Integer.toHexString(only.img.getRGB(nx, ny))}")
check("so is everything else outside the cell",
        Math.abs((only.img.getRGB(tx + 30, ty) >> 16 & 0xff) - 245) < 20,
        "rgb=${Integer.toHexString(only.img.getRGB(tx + 30, ty))}")

println ""
println "=== reproducibility and refusals ==="
// The fill carries grain from a seeded generator: the same cell must give the same crop,
// or re-exporting would move every embedding slightly.
def again = cut('NEIGHBOURS_OUT', 'neighbours_out2.png')
boolean identical = java.util.Arrays.equals(
        java.nio.file.Files.readAllBytes(no.file.toPath()),
        java.nio.file.Files.readAllBytes(again.file.toPath()))
check("the same cell gives a byte-identical crop", identical, "files differ")
boolean threw = false
try { lib.newCropper(server, [target], 128, 'SOMETHING_ELSE') } catch (IllegalArgumentException e) { threw = true }
check("an unknown style is rejected", threw, "no exception")

println ""
println "=== both exporters use the shared maker ==="
// Structural, not behavioural: a classifier trained on one kind of crop and applied to
// another is being shown a different measurement, and nothing downstream could tell.
["qupath_export_labels.groovy", "qupath_export_cells.groovy"].each { name ->
    def src = new File(DIR, name).getText("UTF-8").replace("\r\n", "\n")
    check("${name} builds its cropper from bal_crops.groovy",
            src.contains('evaluate(new File(scriptDir, "bal_crops.groovy"))'), "not found")
    check("${name} writes crops through it", src.contains('cropper.write('), "not found")
    check("${name} records the style it used", src.contains('CROP_STYLE'), "not found")
}

println ""
println "=== labels.csv survives gaining a column ==="
// A real near-miss: labels.csv gained a crop_style column, and simply writing the new
// header above the rows already there shifted every old field one place left -- the x
// coordinate was read as the crop style, which then looked like 26 different crop styles.
// The logic below is a COPY of the migration in qupath_export_labels.groovy (that script
// needs a project and cannot run here); the structural check underneath it is what
// notices if the script stops doing this.
def COLS = ["crop", "class", "image", "crop_style", "x", "y"]
def oldLines = ['crop,class,image,x,y', '"a.png","Macrophage","slide17",23266,28105']
def oldCols = oldLines[0].split(",").collect { it.trim() }
def migrated = oldLines.drop(1).collect { line ->
    if (oldCols == COLS) return line
    def vals = line.split(",")
    def byName = [:]
    oldCols.eachWithIndex { c, i -> byName[c] = i < vals.length ? vals[i] : "" }
    COLS.collect { byName.containsKey(it) ? byName[it] : '"unknown"' }.join(",")
}
def f6 = migrated[0].split(",")
check("an old row keeps its x", f6[4] == "23266", "x=${f6[4]}")
check("an old row keeps its y", f6[5] == "28105", "y=${f6[5]}")
check("and gains crop_style='unknown'", f6[3] == '"unknown"', "crop_style=${f6[3]}")
def expSrc = new File(DIR, "qupath_export_labels.groovy").getText("UTF-8")
check("qupath_export_labels.groovy still maps old rows by name", expSrc.contains("byName"), "not found")

out.listFiles()?.each { it.delete() }
println ""
println "${pass} checks passed, ${fail} failed"
if (fail > 0) throw new IllegalStateException("${fail} check(s) failed")
