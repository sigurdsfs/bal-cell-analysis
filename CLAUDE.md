# Working on this project

Read `README.md` first for what the project is. This file is about *editing* it: the
conventions, the traps, and how to test. Most entries here cost hours to discover.

---

## Non-negotiables

**Test by running, not by compiling.** Groovy resolves names at runtime, so
`new GroovyShell().parse(text)` accepts code that crashes on the user's first click. Two
real examples that passed compilation: a closure using a variable defined 10 lines below it,
and `Dialogs.promptForFile(...)`, which does not exist. Use the harnesses below.

**Verify against the source, not memory.** The QuPath and BIOP APIs are not what you expect.
When unsure, read the actual source:
- QuPath: `https://raw.githubusercontent.com/qupath/qupath/v0.7.0/<path>`
- Cellpose extension: `https://raw.githubusercontent.com/BIOP/qupath-extension-cellpose/v0.12.1/src/main/java/qupath/ext/biop/cellpose/<Class>.java`
- Installed Python: read the package under `.../envs/<env>/Lib/site-packages/cellpose/`

**Say what was not tested.** Several paths cannot be exercised headlessly (below). When
handing work over, name them rather than implying everything was verified.

---

## How to test

The harnesses live in `Mathilde Data/qupath_scripts/tests/`. Run with:

```bash
"/c/Users/Labsfs/AppData/Local/QuPath-0.7.0/QuPath-0.7.0 (console).exe" script <file.groovy>
# add  -p "<project>/project.qpproj" -i "Image_BAL_#2.vsi - 40x_BF_01"  when an image is needed
```

| Harness | Checks |
|---|---|
| `compile_only.groovy` | every script parses (necessary, never sufficient) |
| `test_gui_all.groovy` | **runs** the dialog's setup + `syncEnabled()` for all 7 tasks x 5 models |
| `test_bounds.groovy` | every value the dialog writes into a field passes its own validation |
| `test_api_calls.groovy` | every `Dialogs.*` / `FileChoosers.*` call actually exists |
| `test_custom_model.groovy` | a custom model is known at *selection* time: diameter, training defaults, Browse |
| `test_config_block.groovy` | every script that reads `CFG` declares it **in its own body**, before use |
| `test_pre_combination.groovy` | every `PRE` + worker pair compiles **as one script**, which is how it runs |
| `test_nucleus_pass.groovy` | the nucleus pass, and that `Output type = Cells` needs it |
| `test_layout_sections.groovy` | each task opens exactly the collapsible sections it uses |
| `test_export_labels.groovy` | classified cells stay in the masks; soma and nucleus corpora stay separate (needs a project) |
| `test_prune2.groovy` | checkpoint pruning, including the log being wiped mid-run |
| `test_summary2.groovy` | training-loss verdicts against synthetic curves |
| `test_corpus_multislide.groovy` | corpus prefixing, per-slide counts, train/test overlap |
| `test_notebook_parity.groovy` | **QuPath vs the notebook** on the benchmark region (needs a project) |

**JavaFX initialises headlessly here.** `Platform.startup{}`, then evaluate the GUI script
with `.showAndWait()` replaced by `.with { Optional.empty() }` inside `Platform.runLater`.
Drive the branches by writing to the prefs node (`task`, `model`) before each run.

**Paths live in `bal_config.groovy`.** Never hard-code a new one: add a key there, read it
with `CFG.key ?: <literal fallback>` so the script still runs standalone. A machine-specific
`bal_config.local.groovy` (git-ignored) overrides it.

### What cannot be tested headlessly

- **Cellpose training and ground-truth export** — `saveTrainingImages()` calls
  `QPEx.getQuPath().getProject()`, null on the CLI. GUI only.
- **The dialog's visual layout**, and anything after the OK button in a real session.
- **Any file chooser actually opening** — so the custom-model picker firing when the model
  dropdown changes, and the dropdown reverting when you cancel it, are GUI-only.
  `test_custom_model.groovy` covers the state either outcome leaves behind, not the click.
- Always say so when handing over.

---

## Architecture

**One dialog, thin workers.** `qupath_bal_gui.groovy` collects settings and passes them as
`BAL_*` binding variables, then `evaluate()`s a worker script. Every worker also runs
standalone with its own defaults via `binding.hasVariable('BAL_X') ? BAL_X : <default>`.
Keep it that way: no logic duplicated between GUI and worker.

```
qupath_bal_gui.groovy
├── qupath_cellpose_detection.groovy    cellpose (cpsam / cyto3 / cyto2 / custom)
├── qupath_cells_with_nuclei.groovy     soma + nuclei (both Cellpose) -> cell objects
├── qupath_cell_features.groovy         all per-cell measurements
├── qupath_prepare_training.groovy      Training/Validation rectangles
├── qupath_export_training_data.groovy  adds corrections to the shared corpus
├── qupath_train_cellpose.groovy        fine-tuning (+ checkpoint pruning)
├── qupath_classify_cells.groovy        drives the Python classifier
├── qupath_export_labels.groovy         labelled cells -> masks AND per-class crops
└── qupath_batch_slides.groovy          the whole pipeline over every image in a project
        └── bal_classify/embed_cells.py + classify_cells.py
```

Standalone helpers: `qupath_test_region.groovy` (benchmark regions),
`qupath_compare_models.groovy`, `qupath_check_training_setup.groovy`,
`qupath_setup_classes.groovy`, `qupath_export_cells.groovy`, `qupath_tissue_annotation.groovy`.

**`evaluate()` does not inherit QuPath's default imports.** Prepend the `PRE` block
(see any worker-calling script) or you get `No such property: ROIs`.

**`PRE` is imports and nothing else.** It is concatenated with the worker, so the two share
one scope: a `def` in `PRE` collides with the same `def` in every worker that has it.
`qupath_classify_cells.groovy` carried a `def CFG` in its `PRE` and the combination failed
to compile — which is why no classify task ever reached the embedding step, while both
files parsed fine on their own. `test_pre_combination.groovy` compiles every pair.

---

## Traps

### QuPath

- **The class list belongs to the WINDOW, not the project.** Edit
  `getQuPath().getAvailablePathClasses()` on the FX thread; QuPath syncs window → project on
  save and will overwrite anything written straight into the project. Classes persist in
  `<project>/classifiers/classes.json`, not `project.qpproj`.
- **File pickers are on `FileChoosers`, not `Dialogs`.**
- **A script's own path** is the binding variable `qupath.script.file`.
- **Saved preferences beat new defaults.** Bump `DEFAULTS_VERSION` in the GUI when a default
  changes, or nobody who has run the dialog will see it.
- **Scripts reach QuPath via directory junctions** (`mklink /J`). Never publish with
  hard links: Edit/Write replace the inode and the menu copy silently goes stale.
- **Script files are CRLF.** Splitting source on `"\n\n"` fails; anchor on text.
- **The dialog is a `ScrollPane` of `TitledPane` sections, not one grid.** It outgrew the
  screen once and the lower half was unreachable. Add a control to a section's grid via
  `addTo/rightTo/wideTo`, and mark the section relevant for its tasks in `groupRelevant`
  inside `syncEnabled` — sections open and close on task change only, so a section nobody
  claims stays shut and the control looks missing.
- **The `def CFG` inside a `PRE` string declares nothing in the script holding it.** That
  copy is prepended to the SUB-scripts it evaluates. A worker that reads `CFG` needs its own
  block too, above the first use — `qupath_classify_cells.groovy` shipped without one and
  died with `No such property: CFG` the first time anyone ran a classify task.
  `test_config_block.groovy` guards this now.
- **In Groovy, `replaceAll(pattern) { it }` passes the WHOLE match** when the pattern has no
  capture groups, so `it[0]` is its first *character*, not the match. In a line-preserving
  comment stripper that silently shifts every reported line number.

### Cellpose extension

- **It tiles each annotation's BOUNDING BOX**, not its shape. A scattered tissue outline is
  therefore no cheaper than the whole slide.
- **Training reads each image's SAVED data.** Unsaved rectangles produce
  "Found 0 Training objects" and then `ERROR: no files in --dir folder`.
- **Ground truth = rectangle classed `Training`/`Validation`.** The extension's own exporter
  builds its mask with `useFilter(o -> o.getPathClass() == null)`, so a cell you classify
  "Macrophage" is dropped from the mask while its pixels stay in the image — taught as
  background. **`qupath_export_labels.groovy` avoids this** by writing the masks itself with
  the opposite filter (exclude only the Training/Validation rectangles), so one labelled set
  serves both segmentation and classification. Use it rather than
  `qupath_export_training_data.groovy` whenever cells carry cell-type classes.
- **`moveRenameAndReturnModelFile` returns the FIRST file in `<train>/models`, not the newest.**
  Clear that folder before training or you get a model from days ago.
- **`saveTrainingImages()` only runs when `cleanTrainingDir` is set, and cleaning erases the
  folder first.** That is why export and training are separate steps for the corpus.
- **A QC prediction runs INSIDE `train()`** and overwrites `~/.cellpose/run.log`. Capture
  losses while training runs; never rely on reading the log afterwards.

### Cellpose itself

- **Three different default sets, and the learning rate must match the optimiser:**

  | entry point | optimiser | lr | weight decay | epochs |
  |---|---|---|---|---|
  | Python API | RAdam/AdamW | 0.005 | 1e-5 | 2000 |
  | CLI (what the extension uses) | SGD | 0.2 | 1e-5 | 500 |
  | GUI training dialog (fine-tuning) | SGD | 0.1 | 1e-4 | 100 |

- **cellpose 4 (cpsam) is different again**: lr **1e-5**, weight decay **0.1**, AdamW.
  It is a 304M-parameter transformer; cyto3 is a 6.6M UNet. Using cyto3's rate on cpsam is
  10,000x too high and the loss climbs from epoch 1.
- **`diameter` rescales the image** by `diam_mean/diameter` (30 for cyto models). cpsam is
  scale-free and ignores it. A fine-tuned model stores its own `diam_labels` — pass 0 to use it.
- **Only the LAST epoch is saved**, never the best; `test_losses` is tracked but unused.
  cellpose 3's CLI has no `--save_each`; cellpose 4's does.
- **Exported `_masks.tif` are non-contiguous** (e.g. 113 labels numbered to 546) and look
  black in a viewer (uint16 labels in a 0-65535 range). `relabel_sequential` before scoring,
  or `cellpose.metrics` counts phantom objects and recall collapses.
- `_cp_masks.tif` is cellpose's own prediction, **not** ground truth.

### Python

- Parquet round-trips as **Arrow-backed columns**; `.values` then breaks sklearn indexing.
  Use `.to_numpy(dtype=float)` / `.astype(str).to_numpy()`.
- Do not name scratch files after stdlib modules (`types.py`, `inspect.py`).

---

## Domain facts that changed decisions

- **Cells are ~58 px (7.8 µm).** That is the diameter for cyto2/cyto3.
- **BAL lymphocyte nuclei fill 85–98% of the cell.** Never reject a nucleus for being large —
  an early "nucleus > 95% of cell is a bad match" rule discarded exactly the lymphocytes.
- **Measure eosin as red − green, not red − blue.** In MGG eosin renders magenta; a red-vs-blue
  test reports zero eosin everywhere and led to a wrong conclusion about the stain.
- **Nuclei come from Cellpose's `nuclei` model, not InstanSeg.** InstanSeg cannot be
  fine-tuned from QuPath, so a bad nucleus mask could be seen and never fixed. Its own
  0.5 µm/px also made nucleus ≈ cell here, gutting `nc_ratio` (IQR 0.18 vs 0.48 at
  0.25 µm/px). `qupath_instanseg_detection.groovy` is still on disk and still runs
  standalone, but nothing in the dialog reaches it.
- **The `nuclei` model has `diam_mean` 17, where the cyto models use 30.** Give it the
  nucleus size, not the cell size, or it rescales by the wrong factor.
- **`Output type = Cells` without a real nucleus is a trap, and the dialog now forbids it.**
  The inner boundary would be the soma and the outer an invented ring, making `nc_ratio`
  a function of diameter that runs *backwards* against the real one: measured 0.445 for a
  7.8 µm cell and 0.779 for a 30 µm one, where real lymphocytes are 0.8+ and macrophages
  0.3–0.5.
- **Class `Nucleus` marks a nucleus annotation; everything else that is not a
  Training/Validation rectangle is a soma.** Soma and nucleus ground truth are separate
  corpora over the same pixels — soma at `<corpus>/train|test`, nuclei at
  `<corpus>/nuclei/train|test`. Letting a nucleus into a soma mask puts a second, smaller
  object inside every cell and poisons the corpus with no error anywhere.
- **Always run the diameter-matched control before claiming a fine-tune helped.** Stock cyto3
  at diameter 98 matched the "improved" fine-tuned model exactly — the gain was scale, not learning.
- **Check which field of view a measurement came from.** The overview series covers the whole
  49 x 25 mm slide; the 40x series is a 7.6 x 7.2 mm crop. Mixing them produced a wrong
  tissue-coverage estimate and a wrong runtime plan.

---

## Conventions

- Comments explain **why**, especially where the code looks odd because an API is odd.
  Write the reason, not the mechanism.
- Console output is for a working scientist: say what happened, what it means, and what to
  do next. Warn about the specific failure that is likely, not generic advice.
- Validate inputs in the GUI and fail with a message naming the fix.
- Prefer failing safe: when information is missing, do nothing rather than guess (the
  checkpoint pruner deletes nothing when it has no loss data).
- Record every measured number in the message to the user; they are the evidence future
  decisions rest on.
