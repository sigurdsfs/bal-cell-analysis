/**
 * Puts the BAL cell types into the project's class list, ready for hand-labelling.
 *
 * QuPath 0.7 has NO "Set class" in the right-click menu. Classes are applied from the
 * class list in the Annotations tab:
 *
 *   1. Run this script once -- the classes below appear in the list.
 *   2. Annotations tab (left panel). The class list is the lower half.
 *   3. Click a cell in the viewer to select it.
 *      Several at once: Ctrl+click to add, or drag with the Move tool over a group.
 *   4. In the class list, click the class you want, then right-click it > "Set selected".
 *      (Right-click also offers "Reset selected classes" to undo a mistake.)
 *   5. The cell takes the class colour, so labelled cells are obvious as you go.
 *
 * Aim for 40-60 cells per type. The cluster montage in bal_classify/clusters.png shows
 * where each type is concentrated, which is much faster than hunting at random.
 *
 * Detections must be visible to be clickable: check View > Show detections (or press D).
 */

import qupath.lib.objects.classes.PathClass
import qupath.fx.utils.FXUtils
import java.awt.Color

// Colours chosen to stay distinguishable over a blue-violet MGG slide.
def WANTED = [
    "Macrophage" : new Color(230, 159, 0),     // orange
    "Lymphocyte" : new Color(0, 158, 115),     // green
    "Neutrophil" : new Color(0, 114, 178),     // blue
    "Eosinophil" : new Color(213, 94, 0),      // vermilion
    "Epithelial" : new Color(204, 121, 167),   // pink
    "Clump/debris": new Color(120, 120, 120),  // grey -- also set automatically by the feature script
    "Uncertain"  : new Color(240, 228, 66),    // yellow -- park anything you can't call
]

// IMPORTANT: the class list you see in the Annotations tab belongs to the QuPath window,
// not to the project -- QuPath copies window -> project when saving, and overwrites
// anything a script wrote straight into the project. So update the window's list.
def gui = getQuPath()
int added = 0
def names = []

if (gui != null) {
    // The list is bound to the UI, so it must be changed on the JavaFX thread.
    FXUtils.runOnApplicationThread {
        def available = gui.getAvailablePathClasses()
        def present = available.collect { it.toString() } as Set
        WANTED.each { name, colour ->
            def pc = PathClass.fromString(name)
            pc.setColor(colour.getRGB())
            if (!present.contains(name)) {
                available.add(pc)
                added++
            }
        }
        names = available.collect { it.toString() }
    }
    def project = getProject()
    if (project != null) {
        project.setPathClasses(gui.getAvailablePathClasses())
        project.syncChanges()
    }
} else {
    // Headless (command line): no window, so write to the project directly.
    def project = getProject()
    if (project == null) {
        Dialogs.showErrorMessage("Set up classes", "Open a project first.")
        return
    }
    def existing = new ArrayList<>(project.getPathClasses())
    def present = existing.collect { it.toString() } as Set
    WANTED.each { name, colour ->
        def pc = PathClass.fromString(name)
        pc.setColor(colour.getRGB())
        if (!present.contains(name)) { existing << pc; added++ }
    }
    project.setPathClasses(existing)
    project.syncChanges()
    names = existing.collect { it.toString() }
}

println "Class list updated: ${added} added, ${names.size()} total."
println "Classes now available: " + names.join(", ")
if (added == 0)
    println "(nothing added -- they were already there; look in the Annotations tab)"
println ""
println "TO LABEL A CELL:"
println "  1. Annotations tab (left panel) -- the class list is the lower half"
println "  2. Click a cell in the viewer (Ctrl+click to add more)"
println "  3. Click the class in the list, then right-click it > 'Set selected'"
println ""
println "If you can't click cells: View > Show detections must be on (shortcut: D)."
println "When you've labelled enough, run qupath_export_cells.groovy and train the classifier."
