/**
 * Regression test: QuPath and the notebook must keep measuring the same things.
 *
 * They agreed when first compared (106 vs 107 cells, features within ~2%), but nothing
 * enforced it -- a change to either side could drift silently. This runs both on the same
 * benchmark region and fails if they diverge beyond the tolerances below.
 *
 * Run it after changing the detection script, the feature script, a model default, or the
 * notebook's feature code:
 *     QuPath script -p "<project>/project.qpproj" -i "Image_BAL_#2.vsi - 40x_BF_01" \
 *         test_notebook_parity.groovy
 *
 * It does not modify the project (nothing is saved).
 */

import static qupath.lib.scripting.QP.*

// Cell counts are compared as "any pixel in the region", which is what QuPath reports.
// The notebook's headline 98 uses centroid-in-core stitching, a different convention.
double COUNT_TOLERANCE = 0.05     // 5% -- models are deterministic, but ROI polygons round
double FEATURE_TOLERANCE = 0.10   // 10% per feature median; std_intensity is the loosest at ~6%

def SCRIPTS = new File("C:/Users/Labsfs/Desktop/Mathilde/Mathilde Data/qupath_scripts")
def CFG = [:]
try { CFG = evaluate(new File(SCRIPTS, "bal_config.groovy")) ?: [:] } catch (Exception ignored) { }
def python = (CFG.pythonCellpose4 ?: ["C:/Users/Labsfs/AppData/Local/miniconda3/envs/cellpose_seg/python.exe"])
        .find { new File(it).exists() }
if (python == null) { println "FAIL: no cellpose_seg environment"; return }

def PRE = """
import static qupath.lib.scripting.QP.*
import static qupath.lib.gui.scripting.QPEx.*
import qupath.fx.dialogs.Dialogs
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.objects.classes.*
import qupath.lib.regions.*
import qupath.lib.measurements.*
"""

// --- QuPath side -------------------------------------------------------------
// cpsam, because that is what the notebook uses; cyto3 is the pipeline default but would
// not be a like-for-like comparison.
removeAllObjects()
BAL_REGION_X = 45056; BAL_REGION_Y = 24576; BAL_REGION_SIZE = 1024
evaluate(PRE + new File(SCRIPTS, "qupath_test_region.groovy").text)
BAL_MODEL = 'cpsam'; BAL_DIAMETER = 0; BAL_OUTPUT_TYPE = 'DETECTION'; BAL_TARGETS = 'SELECTED'
evaluate(PRE + new File(SCRIPTS, "qupath_cellpose_detection.groovy").text)
selectObjects(getAnnotationObjects())
BAL_MEASURE_ROI = 'CELL'; BAL_CLUMP_AREA_UM2 = 0
evaluate(PRE + new File(SCRIPTS, "qupath_cell_features.groovy").text)

def cells = getDetectionObjects()
def qp = [count: cells.size()]
["area_um2", "equiv_diameter_um", "eccentricity", "solidity", "extent",
 "mean_r", "mean_g", "mean_b", "std_intensity", "mean_hue", "mean_sat", "nucleus_fraction"].each { f ->
    def v = cells.collect { it.getMeasurementList().get(f) }.findAll { it != null && !Double.isNaN(it) }.sort()
    if (v) qp[f] = v[(int) (v.size() / 2)]
}
println "QuPath: ${qp.count} cells"

// --- notebook side -----------------------------------------------------------
// Runs the notebook's own feature definitions on the same pixels, straight from the zarr.
def py = new File(System.getProperty("java.io.tmpdir"), "bal_parity.py")
py.text = '''
import sys
import numpy as np, zarr
from cellpose import models
from skimage import measure, filters, color as skcolor
PS = ''' + (CFG.pixelSizeUm ?: 0.137) + '''
z = zarr.open(r"''' + (CFG.zarrPath ?: "C:/Users/Labsfs/Desktop/Mathilde/SFS/bal2.zarr/2/0") + '''", mode="r")
Y, X, S = 24576, 45056, 1024
tile = np.moveaxis(z[0, :, 0, Y:Y+S, X:X+S], 0, -1)
masks, _, _ = models.CellposeModel(gpu=True).eval(tile, diameter=None)
def nucleus_fraction(rgb, mk):
    g = skcolor.rgb2gray(rgb); v = g[mk]
    if v.min() == v.max(): return 0.0
    return float((v < filters.threshold_otsu(v)).mean())
rows = []
for p in measure.regionprops(masks):
    y0, x0, y1, x1 = p.bbox
    mk = masks[y0:y1, x0:x1] == p.label
    rgb = tile[y0:y1, x0:x1]; px = rgb[mk]
    hsv = skcolor.rgb2hsv(rgb)[mk]
    rows.append(dict(area_um2=p.area*PS**2, equiv_diameter_um=p.equivalent_diameter_area*PS,
        eccentricity=p.eccentricity, solidity=p.solidity, extent=p.extent,
        mean_r=px[:,0].mean(), mean_g=px[:,1].mean(), mean_b=px[:,2].mean(),
        std_intensity=skcolor.rgb2gray(rgb)[mk].std(), mean_hue=hsv[:,0].mean(),
        mean_sat=hsv[:,1].mean(), nucleus_fraction=nucleus_fraction(rgb, mk)))
# key=value, one line: QuPath's Groovy has no groovy-json module.
parts = ["count=%d" % len(rows)]
for k in rows[0]:
    parts.append("%s=%.6f" % (k, float(np.median([r[k] for r in rows]))))
print("PARITY " + " ".join(parts))
'''
def pb = new ProcessBuilder([python, py.getAbsolutePath()])
pb.redirectErrorStream(true)
def proc = pb.start()
def jsonLine = null
proc.inputStream.eachLine { line -> if (line.startsWith("PARITY ")) jsonLine = line.substring(7) }
proc.waitFor()
if (jsonLine == null) { println "FAIL: the notebook-side script produced no result"; return }
// "count=107 area_um2=46.6 ..." -> map. Avoids groovy-json, which QuPath does not bundle.
def nb = [:]
jsonLine.split(" ").each { pair ->
    def kv = pair.split("=")
    if (kv.length == 2) nb[kv[0]] = kv[1] as double
}
println "notebook: ${nb.count} cells"

// --- compare -------------------------------------------------------------------
println ""
println String.format("%-22s %12s %12s %9s", "measurement", "QuPath", "notebook", "diff")
boolean pass = true
double countDiff = Math.abs(qp.count - nb.count) / (double) nb.count
println String.format("%-22s %12d %12d %8.1f%%", "cell count", qp.count as int, nb.count as int, countDiff * 100)
if (countDiff > COUNT_TOLERANCE) pass = false

nb.keySet().findAll { it != "count" }.sort().each { k ->
    if (qp[k] == null) { println String.format("%-22s %12s %12.3f   MISSING", k, "-", nb[k]); pass = false; return }
    double d = nb[k] == 0 ? 0 : Math.abs(qp[k] - nb[k]) / Math.abs(nb[k])
    boolean ok = d <= FEATURE_TOLERANCE
    if (!ok) pass = false
    println String.format("%-22s %12.3f %12.3f %8.1f%%%s", k, qp[k], nb[k], d * 100, ok ? "" : "   OUT OF TOLERANCE")
}
println ""
println pass ? "PASS: QuPath and the notebook still agree." :
        "FAIL: they have drifted. Check what changed in the detection or feature scripts."
