// Multi-slide corpus handling: prefix tagging, per-slide counting, and the train/test
// overlap warning. Uses fake patch files -- the real export needs the QuPath GUI.
import static qupath.lib.scripting.QP.*

def root = new File(System.getProperty("java.io.tmpdir"), "bal_corpus_test")
if (root.exists()) root.deleteDir()
["train", "test"].each { new File(root, it).mkdirs() }

// Three slides in train, one of which is also in test (the mistake we want flagged).
def write = { String sub, String name ->
    new File(new File(root, sub), name + ".tif").text = "img"
    new File(new File(root, sub), name + "_masks.tif").text = "mask"
}
write("train", "projA__Image_BAL_#2.vsi - 40x_BF_01_region_0")
write("train", "projA__Image_BAL_#2.vsi - 40x_BF_01_region_1")
write("train", "projA__Image_BAL_#17.vsi - 40x_BF_01_region_0")
write("train", "projB__Image_BAL_#2.vsi - 40x_BF_01_region_0")   // same slide name, other project
write("test",  "projA__Image_BAL_#31.vsi - 40x_BF_01_region_0")
write("test",  "projA__Image_BAL_#17.vsi - 40x_BF_01_region_0")  // also in train -> should warn

// --- the counting logic used by the training script -------------------------
def countPairs = { File d -> d.exists() ? (d.listFiles()?.count { it.name.endsWith("_masks.tif") } ?: 0) : 0 }
def slidesIn = { File d ->
    (d.exists() ? d.listFiles() : [] ?: []).findAll { it.name.endsWith("_masks.tif") }
            .groupBy { it.name.replaceAll(/_region_\d+_masks\.tif$/, "") }
}
int nTrain = countPairs(new File(root, "train")), nTest = countPairs(new File(root, "test"))
def trainSlides = slidesIn(new File(root, "train")), testSlides = slidesIn(new File(root, "test"))

println "corpus: ${nTrain} training patches, ${nTest} validation patches"
println "from ${trainSlides.size()} slide(s) for training:"
trainSlides.sort { it.key }.each { s, f -> println "  ${f.size()} patch(es)  ${s}" }
def overlap = trainSlides.keySet().intersect(testSlides.keySet())
println "train/test overlap detected: ${overlap}"

// --- the prefix tagging used by the export script ---------------------------
new File(new File(root, "train"), "untagged_region_0.tif").text = "img"
new File(new File(root, "train"), "untagged_region_0_masks.tif").text = "mask"
def safePrefix = "projC"
int renamed = 0
(new File(root, "train").listFiles() ?: []).each { f ->
    if (f.isFile() && f.name.endsWith(".tif") && !f.name.contains("__")) {
        def target = new File(f.getParentFile(), safePrefix + "__" + f.name)
        if (target.exists()) target.delete()
        if (f.renameTo(target)) renamed++
    }
}

println ""
println "CHECKS"
boolean ok = true
def check = { String what, boolean pass -> if (!pass) ok = false; println "  ${pass ? 'ok  ' : 'FAIL'} ${what}" }
check("4 training pairs counted", nTrain == 4)
check("same slide name from two projects kept separate", trainSlides.size() == 3)
check("two patches grouped under one slide", trainSlides.find { it.key.contains("#2.vsi") && it.key.startsWith("projA") }?.value?.size() == 2)
check("train/test overlap flagged", overlap.size() == 1)
check("untagged files got the project prefix", renamed == 2 &&
        new File(new File(root, "train"), "projC__untagged_region_0_masks.tif").exists())
check("already-tagged files untouched", new File(new File(root, "train"),
        "projA__Image_BAL_#17.vsi - 40x_BF_01_region_0_masks.tif").exists())
println ok ? "\nPASS" : "\nFAILED"
root.deleteDir()
