# BAL cell analysis — user guide

How to segment, label, train and classify cells in bronchoalveolar lavage slides using
QuPath. Written for day-to-day use at the microscope rather than for editing the code; for
that, see `CLAUDE.md`. For setting the tools up on a new machine, see `INSTALL.md`.

> **Screenshots.** There are 19 slots, each a line reading *[Screenshot: …]*. Save your
> image into `Screenshots/` and replace that whole line with, for example:
> `![The Automate menu](Screenshots/03-automate-menu.png)`
>
> `USER_GUIDE.docx` is the same guide in Word, with a frame at each slot to paste into
> directly.
>
> **Five slots now show a dialog that no longer exists** and are worth retaking: the Task
> dropdown (section 3), the Segment cells settings (section 4), the Annotations-output setup
> (section 6 step 2), the export task (section 6 step 4), and the Train task (section 6
> step 5). The dialog is now a set of collapsible sections rather than one long panel, and
> several fields have moved or been added.

---

## Contents

1. [Before you start](#1-before-you-start)
2. [Opening the project](#2-opening-the-project)
3. [The dialog](#3-the-dialog)
4. [Segmenting cells](#4-segmenting-cells)
5. [Labelling cells by type](#5-labelling-cells-by-type)
6. [Improving the segmentation (fine-tuning)](#6-improving-the-segmentation-fine-tuning)
7. [Classifying cell types](#7-classifying-cell-types)
8. [Processing many slides](#8-processing-many-slides)
9. [Reading the results](#9-reading-the-results)
10. [When something goes wrong](#10-when-something-goes-wrong)

---

## 1. Before you start

You need QuPath 0.7.0 with the **Cellpose** extension, and the two conda environments the
scripts call (`cellpose_seg` and `cellpose3`). These are already set up on this machine.

A quick check that everything is in place: open QuPath and look under
**Extensions → Manage extensions**. Cellpose should be listed.

*[Screenshot: QuPath's Extensions → Manage extensions window, showing the Cellpose extension installed]*


> **Two environments, because cellpose 3 and cellpose 4 cannot share one.** They are
> different packages with the same name. `cyto3`, `cyto2` and `nuclei` are cellpose 3
> models; `cpsam` is cellpose 4. The scripts pick the right environment for you from the
> model you choose, so this only matters when something is missing.

**On a new machine**, run `install.cmd` from the project folder — it creates both
environments, installs the Cellpose extension, links the scripts into QuPath, writes the
paths config and fetches the model weights. `install.cmd -DryRun` shows what it would do
without changing anything. `INSTALL.md` covers the same ground by hand.

**No NVIDIA GPU?** Everything still works. Measured: segmentation with cyto3 is about **7×
slower** on CPU (4.4 s against 0.6 s for a 1024-pixel tile). A region you draw by hand still
finishes in seconds to a minute; a whole slide becomes an overnight job rather than an
afternoon one. Prefer `cyto3` over `Cellpose-SAM` on such a machine — cpsam is a
304-million-parameter transformer and will be much worse than 7× slower.

---

## 2. Opening the project

1. **File → Open Project** and choose `QuPath Project/project.qpproj`.
2. Double-click the image you want. **Pick the `40x_BF_01` series**, not `label` or
   `overview` — those are the small thumbnail images stored inside the same `.vsi` file.

*[Screenshot: the QuPath project pane with the image list, showing which series to pick]*


The slide is about 55,000 × 52,000 pixels at 0.137 µm per pixel, so a typical cell is
roughly 58 pixels across. QuPath opens it as a pyramid, so zooming is quick.

---

## 3. The dialog

Everything runs from one window: **Automate → User scripts → `qupath_bal_gui`**.

If the menu is empty, restart QuPath once — the scripts folder is linked in, and QuPath
builds that menu at startup.

*[Screenshot: the Automate menu, showing User scripts → qupath_bal_gui]*


The dialog has one **Task** dropdown at the top. Below it the settings are grouped into
**collapsible sections** — Segmentation, Resolution and tiling, Measurements, Convert
objects, Training regions, Fine-tuning, Ground truth, Classification — inside a scrolling
panel. **Only the sections your task uses are open**; the rest fold away, so you are never
looking at more than the job needs. Fields that do not apply grey out, and the small text
under each choice explains what it will do. Your settings are remembered between runs.

*[Screenshot: the BAL cell segmentation dialog, with the Task dropdown open showing all ten tasks]*


### The ten tasks

| Task | Use it when |
|---|---|
| **Segment cells** | You want cells found and measured in a region |
| **Pair soma + nuclei into cells** | You have corrected cell and nucleus outlines by hand and want them joined into cell objects |
| **Convert selected objects** (annotation ⇄ detection) | You need to edit objects by hand (annotations) or classify them (detections) |
| **Prepare training regions** | You are about to correct outlines to improve the model |
| **Train model on corrected regions** | Fine-tuning Cellpose on your corrections |
| **Classify cells: explore** | You have cells but no labels yet, and want to see the types present |
| **Classify cells: train classifier** | You have labelled cells in *this* image and want a classifier for *this* slide |
| **Classify cells: train on labelled corpus (all slides)** | You want a classifier that works on slides it has not seen |
| **Classify cells: predict** | Applying a trained classifier to new cells |
| **Export labelled cells** (segmentation + classifier) | You have labelled cells and want to use them for training — masks, crops, or both |

> **One export, not two.** An older version had a separate *"Export corrections to training
> corpus"* task. It has gone, because it drove Cellpose's own exporter, which **drops every
> classified cell from the mask** — so labelling a cell for the classifier and using it for
> segmentation were mutually exclusive. *Export labelled cells* writes the masks itself and
> keeps classified cells in them.

---

## 4. Segmenting cells

1. Draw a **rectangle** over the area you want (the rectangle tool in the toolbar).
2. Make sure it is **selected** — it turns yellow.
3. Open the dialog, choose **Segment cells**, and press OK.

*[Screenshot: a rectangle drawn over a cell-rich area, selected and highlighted yellow]*


### Which model to choose

The dropdown explains each one, but in short:

| Model | What it gives you |
|---|---|
| **Cellpose cyto3 (whole cells)** *(default)* | Cell outlines. **Best at separating touching cells** — 63 cells against cpsam's 40 on a clumpy test region, and 2–3× faster |
| **Cellpose-SAM (cpsam)** | Cell outlines. Scale-free, matches the original notebook exactly (106 cells on the benchmark), but merges dense clumps that cyto3 resolves |
| **Cellpose cyto2 (whole cells)** | The older model. Similar here (71 vs 63 objects on the clumpy region). Worth trying if cyto3 over-splits |
| **Custom Cellpose model…** | A model you fine-tuned yourself (section 6) |

**Use cyto3** unless you have a reason not to.

Picking **Custom Cellpose model…** **opens the file chooser straight away**, so you can see
which model you are about to run while the rest of the settings are still in front of you.
Cancel the chooser and the dropdown goes back to the model you had. The **Browse…** button
beside the dropdown changes the file later; it is enabled only for this entry.

Once a file is chosen, the small text under the dropdown names it and says which cellpose
version it will run in — taken from the filename, since a model whose name contains `cpsam`
needs the cellpose 4 environment. The rest of the dialog then follows: cell diameter greys
out for a cpsam fine-tune, and the training settings fill in that model's learning rate.
Your choice is remembered between runs.

### The nucleus pass

**“Also segment nuclei, and pair them into cell objects”** is a tick box in the Segmentation
section. With it on, the chosen model finds the cell bodies, a second model finds the nuclei,
and each nucleus is matched into the cell that contains it — giving **cell objects** with a
real inner and outer boundary.

| Nucleus model | Notes |
|---|---|
| **Cellpose nuclei** *(default)* | Cellpose's own nuclei model. **Nucleus diameter 40 px**, not the cell's 58 — its internal scale is different, so giving it the cell size makes it look for nuclei the size of cells |
| **Custom nucleus model…** | A nuclei model you fine-tuned from corrected nucleus masks |

**Turn it on if you intend to type the cells.** Pairing is what makes cell typing work: it
gives a true nucleus-to-cell ratio, the nuclear shape on its own, and the cytoplasm colour
measured with the nucleus excluded. Leave it off if you only want outlines and counts.

> **Why Cellpose and not InstanSeg for nuclei.** An earlier version used InstanSeg here.
> InstanSeg cannot be fine-tuned from QuPath, so a bad nucleus mask was a dead end — you
> could see it was wrong and do nothing about it. It also works at its own fixed 0.5 µm/px,
> which made the nucleus almost as large as the cell and gutted `nc_ratio`. Cellpose's
> nuclei model goes through the same correct → export → fine-tune loop as the cell model.

### The settings

Only the settings this task uses stay enabled; the rest grey out. The defaults are right for
these slides, so the short answer is *change nothing*.

| Setting | Default | What it does | When to change it |
|---|---|---|---|
| **Output type** | Detections | The kind of object created (see below) | Switch to Annotations when you want to edit outlines by hand |
| **Run on** | Selected objects only | Segment inside what you selected, or inside every annotation in the image | Use *All annotations* to do several regions in one go, unattended |
| **Cell expansion (µm)** | 2.0 | Only for Output type **Cells**: how far the outline grows outwards | Leave it |
| **Pixel size (µm/px)** | 0.137 | The resolution the model works at. Larger = coarser and faster | 0.274 halves the resolution and runs ~4× faster |
| **Tile size (px)** | 1024 | How the region is cut up for the GPU | Leave it. Throughput was flat across tile sizes, so this buys nothing |
| **Region padding (µm)** | 20 | How far **outside** your region Cellpose may look, so a cell crossing the edge is segmented whole (see below) | 0 goes back to cutting cells at the edge. Rarely useful |
| **Cell diameter (px)** | 58 | What cyto2/cyto3 expect a cell to measure; the image is rescaled to match | Only if your cells really are a different size. **0** means "use the model's own value" — the right choice for a model you fine-tuned |
| **Nucleus diameter (px)** | 40 | The nucleus model's expected size | Only with the nucleus pass on, and only if your nuclei differ |
| **Measure the notebook's 12 features** | on | Adds all the per-cell measurements after segmentation | Turn off only if you want outlines and nothing else; the classifier needs these |
| **Flag oversized objects as Clump/debris above … µm²** | on, 300 | Classifies anything bigger as `Clump/debris` so you can exclude it | Raise it if genuine cells are being flagged. The largest genuine cell measured here was 263 µm² |

Values are checked before anything runs, and a bad one stops the dialog with a message
naming the fix. The ranges are: pixel size 0.001–10, tile size 64–4096, region padding 0–100,
cell expansion 0–100, nucleus diameter 1–1000, clump area 1–1,000,000.

**Cell diameter is greyed out for cpsam, and that is deliberate.** cpsam is scale-free —
cellpose 4 marks the setting deprecated and ignores it. The field is only live for cyto2,
cyto3 and models fine-tuned from them.

### Cells at the edge of your region

A cell that straddles the edge of your rectangle used to be **cut off at the edge and then
measured as though it were whole** — a half cell entering the data with a half area, a wrong
diameter and a wrong `nc_ratio`, with nothing to mark it. That is fixed.

Each region is now padded by **Region padding (µm)** so Cellpose can see past the edge, and
every cell then belongs to the single region holding its **centre**. So edge cells come out
whole and are counted exactly once, even when you segment many adjoining blocks.

Two consequences worth knowing:

- **Cells will visibly stick out past the rectangle you drew.** That is correct.
- It costs about **19% more time**, because the padding is segmented too.

> **Why not just discard edge cells?** Because bigger cells reach further past the line, so
> discarding them throws away **2.9× as many macrophages as lymphocytes** — and the
> macrophage-to-lymphocyte ratio is the number this whole pipeline exists to produce.

### Output type

| Choice | What you get | Use it for |
|---|---|---|
| **Detections** *(default)* | Lightweight, read-only objects | Big runs, measurement export, classification |
| **Annotations** | Editable objects — brush, wand and split all work on them | Checking and correcting outlines by hand. Slow once there are many thousands |
| **Cells** | Cell objects with an inner and an outer boundary | Only offered with the nucleus pass on |

> **"Cells" only appears when the nucleus pass is on, and the dialog enforces that.**
> Without a real nucleus the inner boundary would be the cell body and the outer an invented
> ring, which makes `nc_ratio` a function of cell diameter running *backwards* against the
> real one: it measured 0.445 for a 7.8 µm cell and 0.779 for a 30 µm one, where real
> lymphocytes are 0.8+ and macrophages 0.3–0.5. The number would look plausible and be
> upside down.

### What you get

Objects in the image, plus a line in the log for each stage. With the nucleus pass on you get
**cell objects**: an outer boundary with its nucleus inside, which is what section 7's
classifier reads. A cell body where no nucleus was found is still kept as a cell object, with
an empty nucleus, so nothing is silently dropped downstream.

Each object carries up to **24 measurements**:

| Group | Measurements |
|---|---|
| **Shape** | `area_um2`, `equiv_diameter_um`, `eccentricity`, `solidity`, `extent` |
| **Colour** | `mean_r`, `mean_g`, `mean_b`, `std_intensity`, `mean_hue`, `mean_sat` |
| **Nucleus estimate** | `nucleus_fraction` — the dark share of the cell by thresholding; a rough stand-in when there is no real nucleus |
| **With a paired nucleus** | `nc_ratio`, `nucleus_area_um2`, `nucleus_diameter_um`, `nucleus_solidity`, `nucleus_circularity`, `nucleus_eccentricity` |
| **Cytoplasm only** | `cyto_area_um2`, `cyto_mean_r`, `cyto_mean_g`, `cyto_mean_b`, `cyto_r_minus_g`, `cyto_std_intensity` |

The four that do most of the work when telling cell types apart:

- **`nc_ratio`** — nucleus area ÷ cell area. High (0.8+) for lymphocytes, low (0.3–0.5) for
  macrophages. The single most useful number. It is a true measured ratio, unlike
  `nucleus_fraction`.
- **`nucleus_circularity`** — low for the lobed nuclei of neutrophils and eosinophils.
- **`cyto_r_minus_g`** — eosin and granules in the cytoplasm, with the nucleus excluded.
  Measured as red minus green, because eosin renders magenta in MGG.
- **`equiv_diameter_um`** — cell size in micrometres.

*[Screenshot: segmented cells with outlines visible, and the measurement table open below]*


### What the log tells you

Worth reading rather than skipping — it is how you find out that pairing went wrong:

```
Region padding: 20.0 um (146 px) -- cells may extend past the region edge.
Step 1/3: soma outlines with cyto3...
Cellpose-SAM done (OUTPUT_TYPE=DETECTION): 68 objects created in the selected region(s)
  63 kept, 5 dropped as belonging elsewhere:
    63 whose centroid is inside the region that produced them
    5 centred outside every region, i.e. found in the padding ring
  63 soma
Step 2/3: nuclei with nuclei...   61 nuclei
Step 3/3: pairing...
  58 cells paired with a nucleus
  5 cells with no nucleus (kept as cell objects with an empty nucleus)
  2 cells held more than one nucleus -- the largest was used
Flagged 1 object(s) as 'Clump/debris' (area > 300.0 um2)
```

A large "no nucleus" count means the nuclei are being missed, and every one of those cells is
left without `nc_ratio` or any nuclear shape — so the classifier has much less to go on.
"Fell outside every soma" means the opposite: nuclei found where the cell model saw nothing.

> **Timing.** About 3 seconds per 1024-pixel tile on the GPU. A small region is a few
> seconds; a whole slide at full resolution is roughly 2.4 hours. See
> [section 8](#8-processing-many-slides) before starting anything that large.

---

## 5. Labelling cells by type

Labelling is how you teach both the segmentation model and the cell-type classifier. The
same labels serve both.

### Setting up the class list (once per project)

Run **`qupath_setup_classes`** from Automate → User scripts. It adds Macrophage, Lymphocyte,
Neutrophil, Eosinophil, Epithelial, Clump/debris and Uncertain to the class list, in
colours that stand out against the blue-violet stain.

`Training` and `Validation` are not in that list — they appear by themselves the first time
you use them on a region rectangle (section 6).

*[Screenshot: the Annotations tab with the BAL class list populated and colour-coded]*


> **Save the project (Ctrl+S) after running it.** The class list belongs to the QuPath
> *window*, not the project, and QuPath writes window → project on save. Without the save
> the classes are gone next time.

### Labelling a cell

QuPath 0.7 has **no "Set class" in the right-click menu**. Instead:

1. **Click a cell** in the viewer to select it (Ctrl+click to add more).
2. In the **Annotations tab**, click the class you want in the list.
3. **Right-click that class → "Set selected".**

The cell takes the class colour, so labelled cells are obvious as you work.

*[Screenshot: the class list with "Set selected" shown in the right-click menu]*


> If you cannot click cells, turn detections on: **View → Show detections**, or press **D**.

> **Annotations cannot be classified usefully, and detections cannot be reshaped.** If the
> objects in front of you are the wrong kind, use **Convert selected objects** — it swaps
> them in place and keeps the class, name, measurements and internal ID. See section 6.

### How to tell the types apart

| Type | Size | Nucleus | Cytoplasm | The giveaway |
|---|---|---|---|---|
| **Macrophage** | 15–40 µm | round, off-centre | plentiful, grey-blue, often **vacuoles** | Biggest cell, lots of cytoplasm |
| **Lymphocyte** | 7–12 µm | round, dense, fills the cell | barely a rim | Small, almost all nucleus |
| **Neutrophil** | 10–14 µm | **2–5 lobes** joined by strands | pale | The lobes |
| **Eosinophil** | 12–17 µm | **two lobes** | coarse granules | Two lobes plus granular cytoplasm |
| **Epithelial** | elongated | at one end | columnar, may show cilia | Not round |
| **Clump/debris** | varies | none distinct | none — uniform magenta | No pale rim at all |

*[Screenshot: a gallery of example cells, one of each type, labelled]*


Park anything you cannot confidently call as **Uncertain** rather than guessing. A wrong
label does more damage than a missing one.

> **A caution specific to these slides.** No orange or red was found anywhere on this slide,
> so eosinophil granules do not show their usual colour. Eosinophils must be identified by
> their two-lobed nucleus instead. This may be a staining-buffer issue worth confirming with
> the lab.

> **Lymphocyte nuclei fill 85–98% of the cell here.** That is normal and not a pairing
> error — never discard a nucleus for looking too big.

---

## 6. Improving the segmentation (fine-tuning)

Only worth doing if you can point at something the model gets wrong repeatedly — merged
clumps, missed pale cells. If the outlines already look right, your effort is better spent
on labelling for the classifier.

The loop is always the same four moves:

> **pick a region → predict the cells as annotations → correct them by hand → save them
> into the ground-truth folder.** Repeat until the folder holds enough, then train on all
> of it at once.

Nothing is thrown away between rounds: the ground-truth folder accumulates, across regions
and across slides, and every training run uses everything in it.

### Step 1 — pick a region and class it

Draw a **rectangle** over an area worth teaching from, then class it (select it, then in the
Annotations tab right-click the class → **Set selected**, as in section 5):

| Class the rectangle | Where its data lands | What it is for |
|---|---|---|
| `Training` | `<masks folder>/train/` | What the model learns from |
| `Validation` | `<masks folder>/test/` | Scored but never trained on — how you know whether it worked |

Make at least one of each. Without a Validation region you cannot tell whether training
helped, only that it finished.

Choose regions that **show the model failing** — the clumps it merges, the pale cells it
misses — plus some ordinary areas, so it does not forget the easy cases. About 512 px square
is a good size: big enough to hold a few dozen cells, small enough to correct properly.

> **Many small regions beat one big one.** Training draws one crop per image per epoch, so
> ten rectangles give ten times the training signal of one of the same total area.

> **Inside a Training or Validation rectangle, every cell that reaches it is kept** — even
> one whose centre is outside. That is the opposite of the rule in section 4, and it is
> deliberate: a cell whose pixels are in the mask but whose outline was dropped would be
> taught to Cellpose as *background*.

### Step 2 — predict the cells as annotations

Select the rectangle, open the dialog and run **Segment cells** with two settings that
matter more here than anywhere else:

- **Output type = Annotations.** Only annotations can be edited with the brush, and the
  exporter reads annotations — detections are invisible to it. This is the single setting
  that makes the rest of the pipeline work.
- **Nucleus pass off**, if you are correcting cell bodies. With it on you get cell objects,
  which cannot be reshaped.

Leave **Run on** at *Selected objects only* so it works inside your rectangle and nowhere
else.

*[Screenshot: the dialog set to Segment cells with Output type Annotations and the nucleus pass off]*


> **Already have detections you want to correct?** Run **Convert selected objects →
> Annotations (editable)** instead of segmenting again. It keeps the outline, class and
> measurements, and splits each cell object's nucleus out as its own `Nucleus` annotation so
> you can correct that too. Training and Validation rectangles are never converted.

### Step 3 — correct every outline

This is the part that decides whether training helps.

- **Brush (B)** and **wand (W)** to reshape an outline
- **Delete** outlines around things that are not cells
- **Draw** the cells the model missed entirely

**Correct densely.** Every cell inside the rectangle must end up outlined. A cell you leave
out keeps its pixels in the image but not in the mask, so the model is taught that a cell
there is background — a half-corrected rectangle teaches worse than no rectangle at all.

While you are here you can also **class each cell by type** (section 5). The same corrected
cells then serve both jobs: their outlines train Cellpose, their classes train the cell-type
classifier. Classifying a cell does **not** remove it from the segmentation masks.

**Correcting nuclei too?** An annotation classed **`Nucleus`** is a nucleus; anything else
that is not a region rectangle is a cell body. The two are separate training sets over the
same pixels, so they go to separate folders and must never be mixed — a nucleus allowed into
a cell-body mask puts a second, smaller object inside every cell and quietly poisons the
corpus. When you have corrected both, **Pair soma + nuclei into cells** joins them into cell
objects, matching each nucleus into the body that contains it and clipping it to fit.

*[Screenshot: before and after correcting outlines with the brush inside a training rectangle]*


### Step 4 — save them into the ground-truth folder

**Press Ctrl+S first.** Both the export and the training read the image's *saved* data, so
unsaved corrections simply are not there — the symptom is "Found 0 Training objects".

Then run **Export labelled cells (segmentation + classifier)**. The Ground truth section has
**a tick box and a folder for each half**, and both are on by default:

| | Writes | Default folder |
|---|---|---|
| ☑ **Segmentation masks** (Cellpose ground truth) | `<masks>/train|test/<project>__<image>_region_N.tif` plus `…_masks.tif` — one label per cell | `Mathilde Data/cellpose_ground_truth` |
| ☑ **Classifier crops** (cell-type labels) | `<classifier data>/crops/<ClassName>/….png`, one per classified cell, plus `labels.csv` beside it | `Mathilde Data/bal_classify` |

Untick one to do only the other — useful when you have re-labelled cells and want fresh
crops without rewriting masks that have not changed. Untick both and it refuses rather than
reporting a successful export of nothing.

**Ground truth** at the top of the section chooses the target: **Soma (whole cells)** or
**Nuclei**. Nuclei go to `<masks>/nuclei/train|test`, kept apart from the cell bodies. Nuclei
carry no cell type, so no crops are written for them and the log says so.

The filenames carry the project and image, so **regions from different slides never collide**.
The export **adds to what is already there and never deletes**, so repeat steps 1–4 on as
many regions and slides as you like; the corpus just grows. The log reports how many
image/mask pairs the corpus holds before and after, and which slides they came from — if
that count does not move, nothing was exported.

Run **`qupath_check_training_setup`** if anything looks wrong — it reports exactly what the
trainer will see and catches the silent failures: a class spelled wrong, unsaved work, a
region that is not a rectangle.

*[Screenshot: the Export labelled cells task with both tick boxes and both folders, and the log listing the regions written]*


### Step 5 — train on everything banked so far

Run **Train model on corrected regions** with the **same masks folder** and a plain Cellpose
model. It trains on the pooled data from every slide in that folder, not just this image.
Set **Ground truth** to match what you are training — Soma or Nuclei.

Training settings follow the model automatically: cyto3 trains at learning rate 0.1 with
SGD, cpsam at 0.00001 with AdamW. **Do not mix these** — the dialog refuses combinations
that would destroy the model. The finished model is saved into the project's `models/`
folder under a name recording what it came from.

*[Screenshot: the dialog on the Train task, with the masks folder and the training settings]*


### Step 6 — check it actually helped

When training finishes, the log reports where the validation loss bottomed and whether the
saved model ended better than it started.

**Always compare the new model against the stock one on a region you did not train on.** A
fine-tune that looks better is often just a diameter effect: stock cyto3 at diameter 98 once
matched an "improved" fine-tune exactly, meaning the gain was scale and not learning.

To use the result, pick **Custom Cellpose model…** in the model list and choose the file
(section 4) — then set **Cell diameter** to 0, so the model uses the size it learned.

> **How much ground truth?** A few hundred corrected cells, spread over several regions and
> ideally several slides, before expecting a difference.

> **Shortcut for the first round.** **Prepare training regions** does steps 1 and 2 in one
> go: give it region centres as `x,y; x,y` (read the coordinates off QuPath's status bar)
> and it places the Training/Validation rectangles and segments inside them as annotations,
> ready to correct.

---

## 7. Classifying cell types

The classifier has two parts. **Phikon-v2**, a pathology vision transformer, turns a small
image of each cell into 1024 numbers; it is never trained and never changes. A small
classifier is then fitted on those numbers. So **your labels accumulate, the model does
not** — every training run is a fresh fit over everything exported so far, which takes about
a second.

### If you have no labels yet

Run **Classify cells: explore**. It embeds every cell and groups them, writing
`bal_classify/clusters.png` — each row shows the most typical cells of one group. Use it to
see which types are on the slide before labelling anything.

*[Screenshot: clusters.png, the montage of cells grouped by cluster]*


### How a cell becomes a picture

Each cell is cut out as a 128-pixel window — about 17.5 µm, the cell plus a little of its
surroundings. On a crowded smear that window also contains the **neighbouring** cells, and
the transformer summarises the whole window: measured here, a median of **37%** of each
window belonged to other cells, and every one of the 40 crops tested contained at least one
neighbour. So a macrophage beside a lymphocyte was being described partly as a lymphocyte.

Every other segmented cell in the window is therefore **painted out** with the local
background before embedding. The cell keeps its true size, because size is the strongest
signal between a 7 µm lymphocyte and a 25 µm macrophage.

> **Training crops and prediction crops must be cut the same way**, or the model is shown a
> different kind of picture than it learned from. The style is recorded with every crop and
> inside the saved classifier, and a mismatch is **refused** rather than scored. If you ever
> change it, re-export the crops from every slide and retrain.

### Training on one slide

1. Label 40–60 cells per type ([section 5](#5-labelling-cells-by-type)).
2. Run **Classify cells: train classifier**.

This fits on the cells labelled **in the open image**. Good for working on one slide; the log
reports cross-validated scores.

**Read the per-class recall, not the accuracy.** BAL is macrophage-dominant, so a model that
never predicts "eosinophil" can still look 90% accurate while being useless for the rare
types you care about.

*[Screenshot: the classification report in the log, with per-class precision and recall]*


### Training on everything — the one to use on a new slide

**Classify cells: train on labelled corpus (all slides)** fits on every crop exported by
*Export labelled cells*, from every slide. It needs no cells in the open image at all.

This is the task that produces a classifier worth applying to a slide it has never seen. A
model fitted on one slide is fitted to that slide's staining and focus as much as to its cell
types, and **the cross-validated scores cannot show you that** — every fold resamples the
same slide. The log prints which slides the labels came from and warns when they are all from
one.

Before it will run, every crop in the corpus must have been cut the same way; it stops and
says so otherwise. The fix is to run *Export labelled cells* again on the slides that are out
of date.

### Saving and choosing a classifier

The Classification section has **two separate boxes**:

| Box | Used by | Meaning |
|---|---|---|
| **Save classifier as** | the two training tasks | Where to write the model. A bare name is fine: `corpus_v2` becomes `bal_classify/corpus_v2.joblib`. Empty means `bal_classify/classifier.joblib` |
| **Classifier to predict with** | predict | Which saved model to apply. Must already exist — the dialog refuses a path that does not, naming the file |

They are separate because training **writes** and predicting **reads**. With one box, naming
a new model silently redirected the next prediction at a file that had not been written yet.

Training always refits from scratch, so it **replaces** the file it writes — the dialog says
*"Will OVERWRITE…"* when it is about to. Give models separate names if you want to compare
two.

### Applying it

**Classify cells: predict** applies a saved classifier and writes the classes back into
QuPath, so you can see them in colour, correct the wrong ones, and retrain. Each round of
corrections makes the next one better.

**Cells you classified by hand are kept.** Predict scores only the unlabelled ones, so your
own work is never quietly replaced by a guess. The tick box *"Re-predict cells I classified
by hand (REPLACES those labels)"* overrides that; it is off by default and resets itself
whenever you leave the task.

Predictions below 0.6 confidence are counted in the log — correcting those first is the
quickest way to improve the model.

*[Screenshot: cells coloured by predicted class in the viewer]*


> **The classifier takes cell objects if the image has any, otherwise plain detections** —
> so avoid leaving a mixture, or the detections are silently skipped. **Convert selected
> objects** is how you make them all one kind.

---

## 8. Processing many slides

`qupath_batch_slides` runs the whole pipeline over every image in the project, unattended.

Run it from the QuPath window (not the command line, where QuPath would start it once per
image). It:

- skips the `label` and `overview` series automatically
- **skips images already processed**, so an interrupted run can simply be restarted
- keeps going if one slide fails
- saves each image as it finishes
- writes `batch_summary.csv` next to the project

*[Screenshot: batch_summary.csv open in Excel, showing cells per slide]*


> **Plan the time.** Roughly 2.4 hours per slide at full resolution on the GPU — about four
> days for 39 slides, and considerably longer on a machine without one. Before committing to
> that, either segment only tissue regions, or try half resolution (pixel size 0.274), which
> is four times faster and gave the same cell counts in testing. Run two or three slides
> first and check the results are what you want.

**Tissue blocks instead of the whole slide.** `qupath_tissue_annotation` finds the parts of
the slide holding material and covers them with block annotations, which you then segment
with *Run on → All annotations*. Measured on this slide: 2176 tiles instead of 2860, so about
109 minutes instead of 143. It sizes the blocks itself so that the padding from section 4
lands on the tile grid — ask for 4096 and you are given 3804, which is 16 tiles per block
instead of 25. Padded small blocks are a false economy: 1756 px blocks came to *more* tiles
than segmenting the whole slide, because each block pays for its own ring of padding.

---

## 9. Reading the results

**In QuPath:** Measure → Show detection measurements for the table; Measure → Show
measurement maps to colour cells by any single measurement. Colouring by `nc_ratio` makes
the lymphocyte/macrophage split visible at a glance.

*[Screenshot: the measurement map coloured by nc_ratio]*


**Outside QuPath:** `batch_summary.csv` holds per-slide counts;
`bal_classify/predictions.csv` holds each cell's predicted type and confidence.

### Things to know before reporting numbers

- **Cells at a region edge are now whole.** They used to be cut at the edge and measured as
  though complete — about 11% of cells on a 1024 px block, 2.8% on a 4096 px one. Region
  padding fixed that. Two gaps remain: a cell whose centre falls in a block skipped as blank
  glass is still lost, and a classifier crop at a region edge still contains its neighbours,
  because cells outside the region were never segmented and so cannot be painted out.
- **Objects over 300 µm² are flagged `Clump/debris`.** The largest genuine cell measured
  here was 263 µm². Keep them out of differential counts, or re-segment them.
- **No expert-confirmed labels exist yet.** Every cell-type statement so far is
  measurement-backed inference, not cytopathology. Have a cytologist confirm a sample before
  publishing a differential count.

---

## 10. When something goes wrong

| What you see | What it means |
|---|---|
| **"Found 0 Training objects"** | The project is not saved. Press Ctrl+S and run again. |
| **Masks look completely black** | They are fine. Masks store cell *numbers* (1, 2, 3…), which look black. In Fiji use Image → Adjust → Brightness/Contrast → Auto. |
| **The User scripts menu is empty** | Restart QuPath; the menu is built at startup. |
| **"No cells found"** | Segment a region first, and check View → Show detections. |
| **"No cellpose_seg environment found for this Windows user"** | The paths config is wrong or not being read. See `INSTALL.md`; re-running `install.cmd` rewrites it. |
| **Training loss goes up** | Learning rate too high for that model, or too little ground truth. Check the model and its settings match (section 6, step 5). |
| **A fine-tuned model behaves oddly** | Set the cell diameter to 0 — a fine-tuned model stores its own and overriding it undoes part of the training. |
| **Classes do not appear in the list** | Run `qupath_setup_classes`, then **save the project**. |
| **"the corpus holds crops made N different ways"** | Some crops were cut before the crop style changed. Re-run *Export labelled cells* on the slides that are out of date. |
| **"No classifier at …"** | Nothing has been trained to that path yet, or the name in *Classifier to predict with* is wrong. |
| **Cells stick out past the rectangle I drew** | Correct — that is region padding giving you whole edge cells (section 4). |
| **The classifier skipped most of my objects** | The image holds a mixture of cell objects and plain detections; it takes cell objects when any exist. Use *Convert selected objects*. |

*[Screenshot: the QuPath log window, where all script messages appear]*


The log is where every script reports what it did. If something looks wrong, read it from
the top — the scripts are written to say what happened, what it means, and what to do next.
