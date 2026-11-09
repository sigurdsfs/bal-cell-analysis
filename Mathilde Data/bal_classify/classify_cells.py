"""Turn cell embeddings into cell-type predictions.

Two modes, matching how much labelling has been done:

  explore  -- no labels yet. Clusters the embeddings and writes a montage per cluster so
              you can see what each one contains and name it. This is the cheap way to
              find out what cell types are actually on the slide before labelling anything.

  train    -- labels exist (set by hand in QuPath, exported with the cells). Fits logistic
              regression on the embeddings, reports honest cross-validated per-class
              scores, saves the classifier, and writes predictions.csv for every
              UNLABELLED cell. Point --cells at corpus.parquet to train on the labels
              pooled from every slide instead of just the open image.

  predict  -- applies a saved classifier. Cells that already carry a hand-set class are
              left alone unless --overwrite: the predictions are applied back in QuPath by
              ID, so predicting a labelled cell would replace your label with a guess.

Training is always a fresh fit, never a resume: nothing is carried over from the saved
file, so adding labels means re-running 'train' over ALL of them. The labels accumulate
(in crops/ via qupath_export_labels.groovy), not the model.

Per-class recall matters more than accuracy here: BAL is macrophage-dominant, so a model
that never predicts "eosinophil" can still look 90% accurate.

    python classify_cells.py explore --cells cells.parquet
    python classify_cells.py train   --cells cells.parquet
"""

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).parent


def embedding_columns(df):
    return [c for c in df.columns if c.startswith("e") and c[1:].isdigit()]


def load(path):
    df = pd.read_parquet(path)
    cols = embedding_columns(df)
    if not cols:
        raise SystemExit(f"{path} has no embedding columns -- run embed_cells.py first")
    return df, cols


# ---------------------------------------------------------------- explore ----
def explore(args):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from sklearn.cluster import KMeans
    from sklearn.preprocessing import StandardScaler

    df, cols = load(args.cells)
    X = StandardScaler().fit_transform(df[cols].to_numpy(dtype=float))
    km = KMeans(n_clusters=args.clusters, n_init=10, random_state=0)
    df["cluster"] = km.fit_predict(X)

    # The montage must show the SAME pixels that were embedded. When the parquet carries a
    # crop path (QuPath cut it from the open slide) read that; only fall back to the zarr,
    # which is one hard-coded slide, when there is nothing else.
    if "crop" in df.columns:
        from PIL import Image
        root = Path(args.crop_root) if args.crop_root else Path(args.cells).parent
        def thumb(i):
            return np.array(Image.open(root / str(df.loc[i, "crop"])).convert("RGB"))
    else:
        import zarr
        z = zarr.open(args.zarr, mode="r")
        half = args.crop // 2
        H, W = z.shape[-2:]
        print(f"no crop paths in {args.cells.name} -- drawing the montage from {args.zarr}")
        def thumb(i):
            x, y = df.loc[i, "x"], df.loc[i, "y"]
            x0 = max(0, min(int(x) - half, W - args.crop))
            y0 = max(0, min(int(y) - half, H - args.crop))
            return np.moveaxis(z[0, :, 0, y0:y0 + args.crop, x0:x0 + args.crop], 0, -1)
    n_show = 8
    fig, axes = plt.subplots(args.clusters, n_show, figsize=(2.0 * n_show, 2.3 * args.clusters))
    summary = []
    for c in range(args.clusters):
        sub = df[df.cluster == c]
        # Show the cells nearest the cluster centre: the most typical members.
        d = np.linalg.norm(X[sub.index] - km.cluster_centers_[c], axis=1)
        pick = sub.index[np.argsort(d)[:n_show]]
        for j in range(n_show):
            ax = axes[c, j] if args.clusters > 1 else axes[j]
            ax.axis("off")
            if j < len(pick):
                ax.imshow(thumb(pick[j]))
        lab = f"cluster {c}\nn={len(sub)}"
        ax0 = axes[c, 0] if args.clusters > 1 else axes[0]
        ax0.set_title(lab, fontsize=9, loc="left")
        row = {"cluster": c, "n": len(sub)}
        for feat in ["equiv_diameter_um", "nc_ratio", "nucleus_circularity", "cyto_r_minus_g", "area_um2"]:
            if feat in df.columns:
                row[feat] = round(float(sub[feat].median()), 3)
        summary.append(row)

    fig.suptitle("Cells nearest each cluster centre -- name these, then label examples in QuPath", fontsize=13)
    plt.tight_layout()
    out_png = HERE / "clusters.png"
    plt.savefig(out_png, dpi=120)
    print(f"wrote {out_png}")
    table = pd.DataFrame(summary)
    print("\nMedian measurements per cluster:")
    print(table.to_string(index=False))
    table.to_csv(HERE / "cluster_summary.csv", index=False)
    # x/y are absent when the crops came from a folder rather than one slide.
    keep = [c for c in ("id", "image", "x", "y", "cluster") if c in df.columns]
    df[keep].to_csv(HERE / "cluster_assignments.csv", index=False)


# ------------------------------------------------------------------ train ----
def train(args):
    from sklearn.linear_model import LogisticRegression
    from sklearn.model_selection import StratifiedKFold, cross_val_predict
    from sklearn.metrics import classification_report, confusion_matrix
    from sklearn.preprocessing import StandardScaler

    df, cols = load(args.cells)
    if "qupath_class" not in df.columns:
        raise SystemExit("no 'qupath_class' column -- label cells in QuPath and re-export")
    labelled = df[df.qupath_class.notna() & (df.qupath_class != "") & (df.qupath_class != "Clump/debris")]
    if len(labelled) < 20:
        raise SystemExit(f"only {len(labelled)} labelled cells -- label ~40-60 per type first")

    counts = labelled.qupath_class.value_counts()
    print("training set:")
    print(counts.to_string())
    # Which slides the labels came from. One slide's staining and focus is not the next
    # one's, so a model fitted on a single slide usually does not transfer -- and that is
    # invisible in the cross-validated scores, which resample the same slide.
    if "image" in labelled.columns and labelled.image.notna().any():
        per_slide = labelled.image.value_counts()
        print(f"\nfrom {len(per_slide)} slide(s):")
        print(per_slide.to_string())
        if len(per_slide) == 1:
            print("  NOTE: one slide only. The scores below say how well this model works on"
                  "\n  THIS slide; expect worse on a new one until you add labels from others.")
    too_few = counts[counts < 5]
    if len(too_few):
        print(f"\nWARNING: {list(too_few.index)} have under 5 examples; their scores will be meaningless.")

    feats = labelled[cols].to_numpy(dtype=float)
    scaler = StandardScaler().fit(feats)
    Xl = scaler.transform(feats)
    yl = labelled.qupath_class.astype(str).to_numpy()

    # Honest estimate: every prediction comes from a fold that never saw that cell.
    folds = min(5, int(counts.min()))
    if folds >= 2:
        cv = StratifiedKFold(n_splits=folds, shuffle=True, random_state=0)
        pred = cross_val_predict(
            LogisticRegression(max_iter=2000, class_weight="balanced"), Xl, yl, cv=cv)
        print(f"\ncross-validated ({folds}-fold) -- per-class recall is what matters:")
        print(classification_report(yl, pred, zero_division=0))
        print("confusion matrix (rows = truth, columns = predicted):")
        print(pd.DataFrame(confusion_matrix(yl, pred),
                           index=sorted(set(yl)), columns=sorted(set(yl))).to_string())
    else:
        print("\ntoo few examples in the smallest class for cross-validation -- skipping scores")

    clf = LogisticRegression(max_iter=2000, class_weight="balanced").fit(Xl, yl)

    # Keep the scaler with the model: predictions must be standardised the same way, and a
    # scaler refitted on different cells would shift every feature.
    import joblib
    # The crop style is part of the measurement, not a detail: a model fitted on crops with
    # the neighbouring cells painted out, applied to crops that still contain them, is being
    # shown a different picture of the same cell. Record it so predict can refuse.
    style = (str(labelled.crop_style.iloc[0]) if "crop_style" in labelled.columns
             and labelled.crop_style.notna().any() else "unknown")
    bundle = {"clf": clf, "scaler": scaler, "columns": cols, "classes": list(clf.classes_),
              "n_training_cells": int(len(labelled)), "crop_style": style}
    joblib.dump(bundle, args.model)
    print(f"\nclassifier saved to {args.model}")

    rest = df[~df.index.isin(labelled.index)]
    if len(rest) == 0:
        print("\nevery cell is labelled; nothing to predict")
        return
    probs = clf.predict_proba(scaler.transform(rest[cols].to_numpy(dtype=float)))
    out = pd.DataFrame({
        "id": rest["id"].astype(str).to_numpy(),
        "prediction": clf.classes_[probs.argmax(axis=1)],
        "probability": probs.max(axis=1).round(4),
    })
    out_csv = HERE / "predictions.csv"
    out.to_csv(out_csv, index=False)
    print(f"\nwrote {out_csv}: {len(out)} predictions")
    print(out.prediction.value_counts().to_string())
    low = (out.probability < 0.6).sum()
    print(f"\n{low} predictions below 0.6 confidence -- correcting those first is the "
          f"quickest way to improve the model")


# ---------------------------------------------------------------- predict ----
def predict(args):
    import joblib
    if not args.model.exists():
        raise SystemExit(f"no classifier at {args.model} -- run 'train' first")
    bundle = joblib.load(args.model)
    df, cols = load(args.cells)
    missing = [c for c in bundle["columns"] if c not in df.columns]
    if missing:
        raise SystemExit(f"{len(missing)} embedding columns missing -- were these cells embedded "
                         f"with the same model?")

    trained_style = bundle.get("crop_style", "unknown")
    here_style = (str(df.crop_style.iloc[0]) if "crop_style" in df.columns
                  and df.crop_style.notna().any() else "unknown")
    if "unknown" not in (trained_style, here_style) and trained_style != here_style:
        raise SystemExit(
            f"crop style mismatch: the classifier was trained on '{trained_style}' crops but "
            f"these are '{here_style}'.\nSet cropStyle in bal_config.groovy to "
            f"'{trained_style}' and re-export, or retrain on '{here_style}' crops.")

    # A hand-set class is ground truth and the only thing here a person spent time on. The
    # import applies predictions by ID, so predicting a labelled cell REPLACES that label
    # with a guess, and afterwards nothing distinguishes the two. Leave them out unless
    # asked. ('train' never had this problem -- it only predicts the unlabelled cells.)
    n_all = len(df)
    if "qupath_class" in df.columns:
        has_label = df.qupath_class.notna() & (df.qupath_class.astype(str).str.strip() != "")
        n_labelled = int(has_label.sum())
        if n_labelled and not args.overwrite:
            df = df[~has_label]
            print(f"keeping the {n_labelled} class(es) you set by hand; predicting the other "
                  f"{len(df)} cell(s)")
            print("  (--overwrite, or the dialog's tick box, re-predicts them too)")
        elif n_labelled:
            print(f"OVERWRITING the {n_labelled} class(es) set by hand, as asked")
    if df.empty:
        raise SystemExit(f"all {n_all} cells already carry a hand-set class -- nothing to predict")

    X = bundle["scaler"].transform(df[bundle["columns"]].to_numpy(dtype=float))
    probs = bundle["clf"].predict_proba(X)
    out = pd.DataFrame({
        "id": df["id"].astype(str).to_numpy(),
        "prediction": bundle["clf"].classes_[probs.argmax(axis=1)],
        "probability": probs.max(axis=1).round(4),
    })
    out_csv = HERE / "predictions.csv"
    out.to_csv(out_csv, index=False)
    print(f"classifier trained on {bundle['n_training_cells']} cells, "
          f"classes: {', '.join(bundle['classes'])}, crops: {trained_style}")
    print(f"wrote {out_csv}: {len(out)} predictions")
    print(out.prediction.value_counts().to_string())
    low = int((out.probability < 0.6).sum())
    print(f"\n{low} prediction(s) below 0.6 confidence -- correct those first, then retrain")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("explore", "train", "predict"):
        p = sub.add_parser(name)
        p.add_argument("--cells", type=Path, default=HERE / "cells.parquet")
        p.add_argument("--zarr", default=r"C:\Users\Labsfs\Desktop\Mathilde\SFS\bal2.zarr\2\0",
                       help="fallback for the montage when the parquet has no crop paths")
        p.add_argument("--crop", type=int, default=128)
        p.add_argument("--model", type=Path, default=HERE / "classifier.joblib")
        if name == "explore":
            p.add_argument("--clusters", type=int, default=6)
            p.add_argument("--crop-root", dest="crop_root",
                           help="folder the parquet's crop paths are relative to")
        if name == "predict":
            # Off by default: predicting a cell you labelled by hand destroys that label.
            p.add_argument("--overwrite", action="store_true",
                           help="also re-predict cells that already carry a hand-set class")
    args = ap.parse_args()
    {"explore": explore, "train": train, "predict": predict}[args.cmd](args)


if __name__ == "__main__":
    main()
