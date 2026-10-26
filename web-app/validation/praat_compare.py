#!/usr/bin/env python3
"""Java-versus-Praat comparison of the 16 sustained-vowel measurements (offline validation).

    ./mvnw -q package -DskipTests                     # builds target/neurovox.jar
    .venv/bin/python validation/make_synthetic_vowels.py
    .venv/bin/python validation/praat_compare.py      # synthetic set + any WAVs in validation/recordings/

For each WAV file:
  1. Neurovox (Java, com.neurovox.tools.AnalyzeWav) analyses it exactly as the app does and reports the steady region.
  2. Praat (via Parselmouth, which embeds Praat's own C++ code) analyses the SAME file and the SAME region with the
     same settings: To Pitch (ac) [time step 0.01, floor 75, ceiling 600, 15 candidates, silence 0.03, voicing 0.45,
     octave 0.01, octave-jump 0.35, voiced/unvoiced 0.14], To PointProcess (cc), Voice report [75, 600, 1.3, 1.6].
  3. Paired values, absolute error, bias (Java - Praat), relative error and Pearson r are computed per feature.
For synthetic files, both are also compared with the known truth (F0 and jitter).

Outputs docs/results/praat_comparison.{csv,json,md}, docs/results/figures/praat_scatter.png and, per file,
<name>.praat_f0.csv next to the WAV (used by the pitch study).

Praat agreement is evidence that our code implements Praat's definitions. It is NOT evidence that the values are on
the same scale as the dataset's MDVP-named columns; see docs/VALIDATION.md.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
import parselmouth
from parselmouth.praat import call
from scipy import stats

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "target" / "neurovox.jar"
SETS = [("synthetic", ROOT / "validation" / "synthetic"), ("human", ROOT / "validation" / "recordings")]
RESULTS = ROOT / "docs" / "results"
FIGURES = RESULTS / "figures"

# (UCI key, Praat Voice-report label, factor to convert Praat's printed unit to the dataset convention)
FEATURES = [
    ("MDVP:Fo(Hz)", "Mean pitch", 1), ("MDVP:Fhi(Hz)", "Maximum pitch", 1), ("MDVP:Flo(Hz)", "Minimum pitch", 1),
    ("MDVP:Jitter(%)", "Jitter (local)", 0.01), ("MDVP:Jitter(Abs)", "Jitter (local, absolute)", 1),
    ("MDVP:RAP", "Jitter (rap)", 0.01), ("MDVP:PPQ", "Jitter (ppq5)", 0.01), ("Jitter:DDP", "Jitter (ddp)", 0.01),
    ("MDVP:Shimmer", "Shimmer (local)", 0.01), ("MDVP:Shimmer(dB)", "Shimmer (local, dB)", 1),
    ("Shimmer:APQ3", "Shimmer (apq3)", 0.01), ("Shimmer:APQ5", "Shimmer (apq5)", 0.01),
    ("MDVP:APQ", "Shimmer (apq11)", 0.01), ("Shimmer:DDA", "Shimmer (dda)", 0.01),
    ("NHR", "Mean noise-to-harmonics ratio", 1), ("HNR", "Mean harmonics-to-noise ratio", 1),
]
PITCH_ARGS = dict(time_step=0.01, pitch_floor=75, max_number_of_candidates=15, very_accurate=False,
                  silence_threshold=0.03, voicing_threshold=0.45, octave_cost=0.01, octave_jump_cost=0.35,
                  voiced_unvoiced_cost=0.14, pitch_ceiling=600)


def parse_report(text):
    values = {}
    for line in text.splitlines():
        m = re.match(r"\s*(.+?):\s+(--undefined--|[-0-9.E]+)\s*(%|E-6 seconds|E-3 seconds|seconds|dB|Hz)?", line)
        if not m:
            continue
        label, raw, unit = m.group(1), m.group(2), m.group(3) or ""
        if raw == "--undefined--":
            values[label] = None
            continue
        v = float(raw)
        if unit.startswith("E-6"):
            v *= 1e-6
        elif unit.startswith("E-3"):
            v *= 1e-3
        values[label] = v
    return values


def praat_voice_report(path, start, end):
    sound = parselmouth.Sound(str(path))
    if sound.n_channels > 1:
        sound = sound.convert_to_mono()
    pitch = sound.to_pitch_ac(**PITCH_ARGS)
    pulses = call([sound, pitch], "To PointProcess (cc)")
    report = parse_report(call([sound, pitch, pulses], "Voice report", start, end, 75, 600, 1.3, 1.6, 0.03, 0.45))
    out = {}
    for key, label, factor in FEATURES:
        v = report.get(label)
        out[key] = None if v is None else v * factor
    return out, pitch


def write_praat_contour(pitch, path):
    times = pitch.xs()
    f0 = pitch.selected_array["frequency"]
    with path.open("w") as fh:
        fh.write("time,f0\n")
        for t, f in zip(times, f0):
            fh.write(f"{t:.4f},{'' if f <= 0 else f'{f:.5f}'}\n")


def run_java(paths):
    out = RESULTS / "java_features.json"
    subprocess.run(["java", "-cp", str(JAR), "com.neurovox.tools.AnalyzeWav", "--out", str(out), *map(str, paths)],
                   check=True)
    return {r["file"]: r for r in json.loads(out.read_text())}


def summarise(pairs):
    j = np.array([p[0] for p in pairs])
    q = np.array([p[1] for p in pairs])
    diff = j - q
    r = float(stats.pearsonr(j, q)[0]) if len(pairs) > 2 and np.std(j) > 0 and np.std(q) > 0 else None
    return {
        "n": len(pairs),
        "java_mean": float(j.mean()), "praat_mean": float(q.mean()),
        "mae": float(np.mean(np.abs(diff))), "bias": float(diff.mean()),
        "relative_mae": float(np.mean(np.abs(diff) / np.abs(q))) if np.all(q != 0) else None,
        "max_abs_error": float(np.max(np.abs(diff))),
        "pearson_r": r,
    }


def verdict(s):
    """The planning document's reading of r, plus a relative-error check so a constant offset is not missed."""
    if s["n"] < 3 or s["pearson_r"] is None:
        return "insufficient data"
    r, rel = s["pearson_r"], s["relative_mae"] or 0
    if r > 0.95 and rel < 0.10:
        return "agrees"
    if r > 0.90 and rel < 0.20:
        return "acceptable"
    return "investigate"


def main():
    if not JAR.exists():
        sys.exit("Build the JAR first: ./mvnw -q package -DskipTests")
    RESULTS.mkdir(parents=True, exist_ok=True)
    FIGURES.mkdir(parents=True, exist_ok=True)
    files = []
    for name, folder in SETS:
        if folder.exists():
            files += [(name, p) for p in sorted(folder.glob("*.wav")) if p.name != "demo-voice.wav"]
    java = run_java([p for _, p in files])
    truth = {}
    truth_csv = ROOT / "validation" / "synthetic" / "truth.csv"
    if truth_csv.exists():
        import csv
        truth = {row["name"]: row for row in csv.DictReader(truth_csv.open())}

    rows = []
    for set_name, path in files:
        j = java[path.name]
        details = j["details"]
        if "regionStart" not in details:
            rows.append({"set": set_name, "file": path.stem, "skipped": "no steady region in Java analysis"})
            continue
        start, end = details["regionStart"], details["regionEnd"]
        praat, pitch = praat_voice_report(path, start, end)
        write_praat_contour(pitch, path.with_name(path.stem + ".praat_f0.csv"))
        row = {"set": set_name, "file": path.stem, "region_start": start, "region_end": end}
        for key, _, _ in FEATURES:
            row[f"java:{key}"] = j["features"].get(key)
            row[f"praat:{key}"] = praat[key]
        t = truth.get(path.stem)
        if t:
            row["true_mean_f0"] = float(t["true_mean_f0"])
            row["true_jitter_local"] = float(t["true_jitter_local"])
            row["true_jitter_rap"] = float(t["true_jitter_rap"])
            row["true_jitter_ppq5"] = float(t["true_jitter_ppq5"])
            row["snr_db"] = float(t["snr_db"])
        rows.append(row)

    summary = {}
    for set_name, _ in SETS:
        set_rows = [r for r in rows if r["set"] == set_name and "skipped" not in r]
        if not set_rows:
            summary[set_name] = {"files": 0}
            continue
        per_feature = {}
        for key, _, _ in FEATURES:
            pairs = [(r[f"java:{key}"], r[f"praat:{key}"]) for r in set_rows
                     if r[f"java:{key}"] is not None and r[f"praat:{key}"] is not None]
            s = summarise(pairs) if pairs else {"n": 0}
            if pairs:
                s["verdict"] = verdict(s)
            per_feature[key] = s
        block = {"files": len(set_rows), "features": per_feature}
        if set_name == "synthetic":
            vs_truth = {}
            for key, tkey in [("MDVP:Fo(Hz)", "true_mean_f0"), ("MDVP:Jitter(%)", "true_jitter_local"),
                              ("MDVP:RAP", "true_jitter_rap"), ("MDVP:PPQ", "true_jitter_ppq5")]:
                jt = [(r[f"java:{key}"], r[tkey]) for r in set_rows if r[f"java:{key}"] is not None]
                pt = [(r[f"praat:{key}"], r[tkey]) for r in set_rows if r[f"praat:{key}"] is not None]
                vs_truth[key] = {"java": summarise(jt), "praat": summarise(pt)}
            block["versus_truth"] = vs_truth
        summary[set_name] = block

    environment = {"praat": parselmouth.PRAAT_VERSION, "parselmouth": parselmouth.__version__,
                   "java_extractor": next(iter(java.values()))["extractorVersion"] if java else None}
    (RESULTS / "praat_comparison.json").write_text(json.dumps({"environment": environment, "summary": summary,
                                                                "rows": rows}, indent=2) + "\n")
    columns = sorted({k for r in rows for k in r}, key=lambda k: (k not in ("set", "file"), k))
    with (RESULTS / "praat_comparison.csv").open("w") as fh:
        fh.write(",".join(columns) + "\n")
        for r in rows:
            fh.write(",".join("" if r.get(c) is None else str(r.get(c)) for c in columns) + "\n")
    (RESULTS / "praat_comparison.md").write_text(markdown(summary, environment))
    scatter(rows)
    for set_name, block in summary.items():
        if block.get("files"):
            bad = [k for k, s in block["features"].items() if s.get("verdict") not in ("agrees",)]
            print(f"{set_name}: {block['files']} files; not fully agreeing: {bad or 'none'}")
        else:
            print(f"{set_name}: no files")


def fmt(v, digits=4):
    if v is None:
        return "n/a"
    a = abs(v)
    return f"{v:.3e}" if (a != 0 and (a < 1e-3 or a >= 1e4)) else f"{v:.{digits}f}"


def markdown(summary, env):
    lines = ["# Java versus Praat (generated)", "",
             "Generated by `validation/praat_compare.py`. Do not edit by hand.", "",
             f"Praat {env['praat']} via Parselmouth {env['parselmouth']}; Neurovox extractor `{env['java_extractor']}`. "
             "Both analyse the same WAV file over the same steady region with the same settings. "
             "Values are in the dataset's convention (jitter and shimmer as fractions).", ""]
    for set_name, block in summary.items():
        lines.append(f"## {set_name.capitalize()} set ({block.get('files', 0)} files)")
        lines.append("")
        if not block.get("files"):
            lines += ["No files. Real recordings go in `validation/recordings/` (not committed; see docs/VALIDATION.md).", ""]
            continue
        lines += ["| Feature | n | Java mean | Praat mean | MAE | Bias (J−P) | Rel. MAE | Max abs. error | Pearson r | Reading |",
                  "|---|---|---|---|---|---|---|---|---|---|"]
        for key, s in block["features"].items():
            if not s.get("n"):
                lines.append(f"| `{key}` | 0 | | | | | | | | no pairs |")
                continue
            rel = "n/a" if s["relative_mae"] is None else f"{100 * s['relative_mae']:.2f} %"
            lines.append(f"| `{key}` | {s['n']} | {fmt(s['java_mean'])} | {fmt(s['praat_mean'])} | {fmt(s['mae'])} | "
                         f"{fmt(s['bias'])} | {rel} | {fmt(s['max_abs_error'])} | {fmt(s['pearson_r'], 4)} | {s['verdict']} |")
        if "versus_truth" in block:
            lines += ["", "Against the known synthesis truth (closure-to-closure periods):", "",
                      "| Feature | Java MAE | Java bias | Java r | Praat MAE | Praat bias | Praat r |", "|---|---|---|---|---|---|---|"]
            for key, v in block["versus_truth"].items():
                lines.append(f"| `{key}` | {fmt(v['java']['mae'])} | {fmt(v['java']['bias'])} | {fmt(v['java']['pearson_r'])} | "
                             f"{fmt(v['praat']['mae'])} | {fmt(v['praat']['bias'])} | {fmt(v['praat']['pearson_r'])} |")
        lines.append("")
    lines += ["Reading: “agrees” = r > 0.95 and relative MAE < 10 %; “acceptable” = r > 0.90 and < 20 %; otherwise “investigate”.", ""]
    return "\n".join(lines)


def scatter(rows):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    keys = ["MDVP:Fo(Hz)", "MDVP:Jitter(%)", "MDVP:Shimmer", "HNR"]
    fig, axes = plt.subplots(1, 4, figsize=(14, 3.6))
    for ax, key in zip(axes, keys):
        pts = [(r[f"praat:{key}"], r[f"java:{key}"]) for r in rows if "skipped" not in r
               and r.get(f"praat:{key}") is not None and r.get(f"java:{key}") is not None]
        if not pts:
            continue
        x, y = zip(*pts)
        lo, hi = min(x + y), max(x + y)
        ax.plot([lo, hi], [lo, hi], "--", color="#64748B", linewidth=1)
        ax.scatter(x, y, s=16, color="#0F766E")
        ax.set_title(key, fontsize=10)
        ax.set_xlabel("Praat")
        ax.set_ylabel("Neurovox (Java)")
    fig.suptitle("Same WAV, same region: Neurovox vs Praat (synthetic vowels)", fontsize=11)
    fig.tight_layout()
    fig.savefig(FIGURES / "praat_scatter.png", dpi=150)


if __name__ == "__main__":
    main()
