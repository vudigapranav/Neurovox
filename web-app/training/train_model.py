#!/usr/bin/env python3
"""Offline training and evaluation of the Neurovox research logistic-regression model.

Python is used here only, offline. The shipped application loads the exported JSON and does inference in Java.

    python3 training/train_model.py            # from the project root

What it does
  1. Loads the UCI Oxford Parkinson's dataset (training/data/parkinsons.data) and checks its SHA-256.
  2. Takes the subject code from the `name` column (phon_R01_S01_1 -> subject 1).
  3. Uses only the 16 columns Neurovox computes, in the fixed Java order (UciFeature.ORDER).
     The six nonlinear columns (RPDE, D2, DFA, spread1, spread2, PPE) are never used.
  4. Evaluates StandardScaler + LogisticRegression with SUBJECT-GROUPED cross-validation:
     StratifiedGroupKFold(5) (primary, with real fold composition), the same repeated over 20 seeds, and
     leave-one-subject-out. It asserts that no subject is in both training and test data in any fold.
  5. Runs a naive recording-level StratifiedKFold(5) only to show how much subject leakage inflates results.
  6. Refits on all data and exports src/main/resources/model/research-model.json (feature order, scaler,
     coefficients, intercept, threshold, version, evaluation summary) and Java reference predictions.
  7. Writes docs/results/model_evaluation.{json,md}.

Re-running gives byte-identical outputs (fixed seeds, no timestamps).
"""

import hashlib
import json
import platform
import re
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
import sklearn
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (accuracy_score, balanced_accuracy_score, confusion_matrix, f1_score,
                             precision_score, recall_score, roc_auc_score)
from sklearn.model_selection import LeaveOneGroupOut, StratifiedGroupKFold, StratifiedKFold
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "training" / "data" / "parkinsons.data"
DATA_SHA256 = "a36116c2deac07895b79ce97a2f99a1b3720ac4892995ff5c3b9ccb397ed2bc5"
MODEL_OUT = ROOT / "src" / "main" / "resources" / "model" / "research-model.json"
REFERENCE_OUT = ROOT / "src" / "test" / "resources" / "model" / "reference-predictions.csv"
RESULTS_DIR = ROOT / "docs" / "results"

# Must equal com.neurovox.features.UciFeature.ORDER (checked by the Java model loader and its tests).
FEATURES = [
    "MDVP:Fo(Hz)", "MDVP:Fhi(Hz)", "MDVP:Flo(Hz)", "MDVP:Jitter(%)", "MDVP:Jitter(Abs)", "MDVP:RAP", "MDVP:PPQ",
    "Jitter:DDP", "MDVP:Shimmer", "MDVP:Shimmer(dB)", "Shimmer:APQ3", "Shimmer:APQ5", "MDVP:APQ", "Shimmer:DDA",
    "NHR", "HNR",
]
NOT_USED = ["RPDE", "D2", "DFA", "spread1", "spread2", "PPE"]
SEED = 42
REPEATS = 20
C = 1.0
THRESHOLD = 0.5
MODEL_VERSION = "uci16-logreg-v1"


def make_model():
    return make_pipeline(StandardScaler(), LogisticRegression(C=C, solver="lbfgs", max_iter=10_000))


def load():
    raw = DATA.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != DATA_SHA256:
        sys.exit(f"parkinsons.data has SHA-256 {digest}, expected {DATA_SHA256}. Re-download it (see training/README.md).")
    df = pd.read_csv(DATA)
    df["subject"] = df["name"].map(lambda n: int(re.search(r"_S(\d+)_", n).group(1)))
    per_subject = df.groupby("subject")["status"].nunique()
    assert (per_subject == 1).all(), "every subject must have a single label"
    return df


def metrics(y, prob):
    pred = (prob >= THRESHOLD).astype(int)
    tn, fp, fn, tp = confusion_matrix(y, pred, labels=[0, 1]).ravel()
    return {
        "n": int(len(y)),
        "accuracy": float(accuracy_score(y, pred)),
        "balanced_accuracy": float(balanced_accuracy_score(y, pred)),
        "precision": float(precision_score(y, pred, zero_division=0)),
        "recall_sensitivity": float(recall_score(y, pred, zero_division=0)),
        "specificity": float(tn / (tn + fp)) if (tn + fp) else None,
        "f1": float(f1_score(y, pred, zero_division=0)),
        "roc_auc": float(roc_auc_score(y, prob)) if len(set(y)) == 2 else None,
        "confusion_matrix": {"tn": int(tn), "fp": int(fp), "fn": int(fn), "tp": int(tp)},
    }


def out_of_fold(df, splitter, groups=None):
    X = df[FEATURES].to_numpy()
    y = df["status"].to_numpy()
    prob = np.full(len(df), np.nan)
    folds = []
    for k, (train, test) in enumerate(splitter.split(X, y, groups)):
        train_subjects = set(df["subject"].iloc[train])
        test_subjects = set(df["subject"].iloc[test])
        model = make_model().fit(X[train], y[train])
        prob[test] = model.predict_proba(X[test])[:, 1]
        fold = {
            "fold": k + 1,
            "test_recordings": int(len(test)),
            "test_subjects": sorted(int(s) for s in test_subjects),
            "test_pd_subjects": int(df.iloc[test].groupby("subject")["status"].first().sum()),
            "test_healthy_subjects": int((df.iloc[test].groupby("subject")["status"].first() == 0).sum()),
            "subjects_also_in_training": sorted(int(s) for s in train_subjects & test_subjects),
        }
        fold["accuracy"] = float(accuracy_score(y[test], (prob[test] >= THRESHOLD).astype(int)))
        two_classes = len(set(y[test])) == 2   # a leave-one-subject-out fold, or an all-PD fold, has one class
        fold["balanced_accuracy"] = float(balanced_accuracy_score(y[test], (prob[test] >= THRESHOLD).astype(int))) if two_classes else None
        fold["roc_auc"] = float(roc_auc_score(y[test], prob[test])) if two_classes else None
        folds.append(fold)
    assert not np.isnan(prob).any()
    return prob, folds


def subject_level(df, prob):
    """Average each subject's out-of-fold recording outputs, then score subjects (one vote per person)."""
    s = pd.DataFrame({"subject": df["subject"], "status": df["status"], "prob": prob}).groupby("subject").agg(
        status=("status", "first"), prob=("prob", "mean"))
    return metrics(s["status"].to_numpy(), s["prob"].to_numpy())


def main():
    # One grouped fold contains only PD subjects (8 healthy subjects cannot fill 5 folds evenly); sklearn warns that
    # the predictions contain a class missing from that fold. The fold is reported with ROC-AUC "n/a".
    warnings.filterwarnings("ignore", message="y_pred contains classes not in y_true")
    df = load()
    y = df["status"].to_numpy()
    groups = df["subject"].to_numpy()
    subjects = df.groupby("subject")["status"].first()

    # Primary: stratified, subject-grouped 5-fold.
    primary = StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=SEED)
    prob_grouped, folds_grouped = out_of_fold(df, primary, groups)
    for f in folds_grouped:
        assert f["subjects_also_in_training"] == [], "subject leakage in a grouped fold"

    # Stability: the same grouped CV with 20 different shuffles.
    repeated = []
    for seed in range(REPEATS):
        p, folds = out_of_fold(df, StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=seed), groups)
        assert all(f["subjects_also_in_training"] == [] for f in folds)
        m = metrics(y, p)
        repeated.append({"seed": seed, "accuracy": m["accuracy"], "balanced_accuracy": m["balanced_accuracy"], "roc_auc": m["roc_auc"]})

    # Leave one subject out.
    prob_loso, _ = out_of_fold(df, LeaveOneGroupOut(), groups)

    # Naive recording-level split: shown only to demonstrate leakage.
    prob_naive, folds_naive = out_of_fold(df, StratifiedKFold(n_splits=5, shuffle=True, random_state=SEED))
    leaked = sum(1 for f in folds_naive for _ in f["subjects_also_in_training"])

    final = make_model().fit(df[FEATURES].to_numpy(), y)
    scaler = final.named_steps["standardscaler"]
    lr = final.named_steps["logisticregression"]

    def summary(values):
        a = np.array(values, dtype=float)
        return {"mean": float(a.mean()), "sd": float(a.std(ddof=1)), "min": float(a.min()), "max": float(a.max())}

    evaluation = {
        "dataset": {
            "file": "training/data/parkinsons.data",
            "sha256": DATA_SHA256,
            "recordings": int(len(df)),
            "subject_codes": int(subjects.size),
            "pd_subjects": int(subjects.sum()),
            "healthy_subjects": int((subjects == 0).sum()),
            "pd_recordings": int(y.sum()),
            "healthy_recordings": int((y == 0).sum()),
            "note": "The dataset description says 31 people (23 with PD); the name column contains 32 distinct subject "
                    "codes (24 PD). Grouping uses the codes as given.",
        },
        "features": FEATURES,
        "not_used": NOT_USED,
        "model": {"type": "StandardScaler + LogisticRegression", "C": C, "solver": "lbfgs", "threshold": THRESHOLD},
        "baselines": {
            "majority_class_recording_accuracy": float(max(y.mean(), 1 - y.mean())),
            "majority_class_subject_accuracy": float(max(subjects.mean(), 1 - subjects.mean())),
        },
        "grouped_5fold": {
            "splitter": "StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=42)",
            "recording_level": metrics(y, prob_grouped),
            "subject_level": subject_level(df, prob_grouped),
            "folds": folds_grouped,
        },
        "grouped_5fold_repeated": {
            "repeats": REPEATS,
            "accuracy": summary([r["accuracy"] for r in repeated]),
            "balanced_accuracy": summary([r["balanced_accuracy"] for r in repeated]),
            "roc_auc": summary([r["roc_auc"] for r in repeated]),
        },
        "leave_one_subject_out": {"recording_level": metrics(y, prob_loso), "subject_level": subject_level(df, prob_loso)},
        "naive_recording_5fold_LEAKY": {
            "splitter": "StratifiedKFold(n_splits=5, shuffle=True, random_state=42) - ignores subjects",
            "recording_level": metrics(y, prob_naive),
            "test_folds_containing_subjects_seen_in_training": sum(1 for f in folds_naive if f["subjects_also_in_training"]),
            "subject_overlaps_total": leaked,
        },
        "environment": {"python": platform.python_version(), "scikit_learn": sklearn.__version__, "numpy": np.__version__,
                        "pandas": pd.__version__},
    }

    model = {
        "modelVersion": MODEL_VERSION,
        "description": "Research logistic regression on 16 Oxford/UCI voice features. Not a diagnostic model. "
                       "Output is a research score, not a probability of disease and not clinical confidence.",
        "featureOrder": FEATURES,
        "scalerMean": [float(v) for v in scaler.mean_],
        "scalerScale": [float(v) for v in scaler.scale_],
        "coefficients": [float(v) for v in lr.coef_[0]],
        "intercept": float(lr.intercept_[0]),
        "threshold": THRESHOLD,
        "positiveClass": "status = 1 (recording from a person with Parkinson's disease in the dataset)",
        "evaluation": {
            "grouped_5fold_recording": {k: evaluation["grouped_5fold"]["recording_level"][k]
                                        for k in ("accuracy", "balanced_accuracy", "roc_auc", "recall_sensitivity", "specificity")},
            "grouped_5fold_subject": {k: evaluation["grouped_5fold"]["subject_level"][k]
                                      for k in ("accuracy", "balanced_accuracy", "roc_auc")},
            "grouped_repeated_accuracy_mean": evaluation["grouped_5fold_repeated"]["accuracy"]["mean"],
            "grouped_repeated_accuracy_sd": evaluation["grouped_5fold_repeated"]["accuracy"]["sd"],
            "naive_leaky_accuracy": evaluation["naive_recording_5fold_LEAKY"]["recording_level"]["accuracy"],
            "majority_class_accuracy": evaluation["baselines"]["majority_class_recording_accuracy"],
            "dataset_sha256": DATA_SHA256,
            "scikit_learn": sklearn.__version__,
        },
    }

    MODEL_OUT.parent.mkdir(parents=True, exist_ok=True)
    MODEL_OUT.write_text(json.dumps(model, indent=2) + "\n")

    # Reference rows for the Java agreement test: every 10th recording plus the first and last.
    idx = sorted(set(range(0, len(df), 10)) | {len(df) - 1})
    probs = final.predict_proba(df[FEATURES].to_numpy()[idx])[:, 1]
    REFERENCE_OUT.parent.mkdir(parents=True, exist_ok=True)
    with REFERENCE_OUT.open("w") as fh:
        fh.write("name," + ",".join(FEATURES) + ",sklearn_probability\n")
        raw = pd.read_csv(DATA, dtype=str)
        for i, p in zip(idx, probs):
            fh.write(raw.loc[i, "name"] + "," + ",".join(raw.loc[i, FEATURES]) + "," + repr(float(p)) + "\n")

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / "model_evaluation.json").write_text(json.dumps(evaluation, indent=2) + "\n")
    (RESULTS_DIR / "model_evaluation.md").write_text(markdown(evaluation, model))
    g = evaluation["grouped_5fold"]["recording_level"]
    n = evaluation["naive_recording_5fold_LEAKY"]["recording_level"]
    print(f"grouped 5-fold: accuracy {g['accuracy']:.3f}, balanced {g['balanced_accuracy']:.3f}, AUC {g['roc_auc']:.3f}")
    print(f"naive (leaky):  accuracy {n['accuracy']:.3f}, balanced {n['balanced_accuracy']:.3f}, AUC {n['roc_auc']:.3f}")
    print(f"wrote {MODEL_OUT.relative_to(ROOT)}, {REFERENCE_OUT.relative_to(ROOT)}, docs/results/model_evaluation.*")


def pct(v):
    return "n/a" if v is None else f"{100 * v:.1f} %"


def num(v):
    return "n/a" if v is None else f"{v:.3f}"


def row(name, m):
    c = m["confusion_matrix"]
    return (f"| {name} | {m['n']} | {pct(m['accuracy'])} | {pct(m['balanced_accuracy'])} | {pct(m['recall_sensitivity'])} | "
            f"{pct(m['specificity'])} | {pct(m['precision'])} | {num(m['f1'])} | {num(m['roc_auc'])} | "
            f"{c['tp']} / {c['fn']} / {c['tn']} / {c['fp']} |")


def markdown(e, model):
    d = e["dataset"]
    lines = [
        "# Research model evaluation (generated)",
        "",
        "Generated by `python3 training/train_model.py`. Do not edit by hand; re-run the script.",
        "",
        f"- Dataset: `{d['file']}` (SHA-256 `{d['sha256']}`), {d['recordings']} recordings, {d['subject_codes']} subject codes "
        f"({d['pd_subjects']} PD, {d['healthy_subjects']} healthy). {d['note']}",
        f"- Features: the 16 columns Neurovox computes. Not used: {', '.join(e['not_used'])}.",
        f"- Model: {e['model']['type']}, C = {e['model']['C']}, threshold {e['model']['threshold']}.",
        f"- Majority-class baseline: {pct(e['baselines']['majority_class_recording_accuracy'])} of recordings, "
        f"{pct(e['baselines']['majority_class_subject_accuracy'])} of subjects.",
        "",
        "## Results",
        "",
        "Positive class = recording from a person with PD. Confusion counts are TP / FN / TN / FP.",
        "",
        "| Evaluation | n | Accuracy | Balanced acc. | Sensitivity | Specificity | Precision | F1 | ROC-AUC | TP / FN / TN / FP |",
        "|---|---|---|---|---|---|---|---|---|---|",
        row("Subject-grouped 5-fold (recordings) — **primary**", e["grouped_5fold"]["recording_level"]),
        row("Subject-grouped 5-fold (per subject, mean output)", e["grouped_5fold"]["subject_level"]),
        row("Leave-one-subject-out (recordings)", e["leave_one_subject_out"]["recording_level"]),
        row("Leave-one-subject-out (per subject)", e["leave_one_subject_out"]["subject_level"]),
        row("Naive recording split — **leaky, for comparison only**", e["naive_recording_5fold_LEAKY"]["recording_level"]),
        "",
        f"Grouped 5-fold repeated over {e['grouped_5fold_repeated']['repeats']} shuffles: accuracy "
        f"{pct(e['grouped_5fold_repeated']['accuracy']['mean'])} ± {pct(e['grouped_5fold_repeated']['accuracy']['sd'])} "
        f"(range {pct(e['grouped_5fold_repeated']['accuracy']['min'])}–{pct(e['grouped_5fold_repeated']['accuracy']['max'])}), "
        f"ROC-AUC {num(e['grouped_5fold_repeated']['roc_auc']['mean'])} ± {num(e['grouped_5fold_repeated']['roc_auc']['sd'])}.",
        "",
        f"In the naive split, {e['naive_recording_5fold_LEAKY']['test_folds_containing_subjects_seen_in_training']} of 5 test folds "
        f"contained people whose other recordings were in training ({e['naive_recording_5fold_LEAKY']['subject_overlaps_total']} "
        "subject overlaps in total). In every grouped fold the overlap is zero (asserted by the script).",
        "",
        "## Fold composition (primary grouped 5-fold)",
        "",
        "| Fold | Test recordings | PD subjects | Healthy subjects | Test subject codes | Accuracy | ROC-AUC |",
        "|---|---|---|---|---|---|---|",
    ]
    for f in e["grouped_5fold"]["folds"]:
        lines.append(f"| {f['fold']} | {f['test_recordings']} | {f['test_pd_subjects']} | {f['test_healthy_subjects']} | "
                     f"{', '.join(map(str, f['test_subjects']))} | {pct(f['accuracy'])} | {num(f['roc_auc'])} |")
    lines += [
        "",
        "## Exported model (`src/main/resources/model/research-model.json`)",
        "",
        "| Feature | Scaler mean | Scaler scale | Coefficient |",
        "|---|---|---|---|",
    ]
    for name, m, s, c in zip(model["featureOrder"], model["scalerMean"], model["scalerScale"], model["coefficients"]):
        lines.append(f"| `{name}` | {m:.6g} | {s:.6g} | {c:+.4f} |")
    lines += ["", f"Intercept: {model['intercept']:+.4f}. Environment: " + ", ".join(f"{k} {v}" for k, v in e["environment"].items()) + ".", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    main()
