#!/usr/bin/env python3
"""Generates synthetic sustained /a/ vowels with KNOWN cycle lengths and amplitudes (offline validation only).

    .venv/bin/python validation/make_synthetic_vowels.py

Writes validation/synthetic/<name>.wav (16-bit, 44.1 kHz), <name>.truth_f0.csv and truth.csv, plus
validation/synthetic/demo-voice.wav (48 kHz) used as Chrome's fake microphone in the end-to-end browser check.

Signal model (independent of the Java code, which never sees this file's numbers):
  * Glottal flow derivative per cycle (Rosenberg 1971 shape: rising half-cosine over 40 % of the period, falling
    quarter-cosine over 16 %), each cycle ending in glottal closure. Closures are exactly the given periods apart.
  * Period T_k = T0 * (1 + jitter * N(0,1)) and amplitude A_k = 1 + shimmer * N(0,1) (seeded), with a slow
    +/-0.5 % pitch drift so F0 is not perfectly flat.
  * Synthesised at 8x the output rate, filtered by three formant resonators (/a/), then band-limited and
    decimated with scipy.signal.resample_poly.
  * White noise added at a chosen signal-to-noise ratio, then a 0.3 s silent lead-in and tail.
These are NOT human voices: they test whether two implementations agree and whether known perturbations are
recovered, not how either behaves on pathological human phonation.
"""

import csv
import json
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import lfilter, resample_poly

OUT = Path(__file__).resolve().parent / "synthetic"
FS = 44_100
OVERSAMPLE = 8
SEED = 2026
FORMANTS = [(730, 90), (1090, 110), (2440, 170)]


def resonator(x, fs, hz, bw):
    r = np.exp(-np.pi * bw / fs)
    a1 = 2 * r * np.cos(2 * np.pi * hz / fs)
    a2 = -r * r
    return lfilter([1 - a1 - a2], [1, -a1, -a2], x)


def synthesise(f0, jitter, shimmer, snr_db, seconds, fs, rng):
    hi = fs * OVERSAMPLE
    lead = 0.3
    periods, amps = [], []
    t = 0.0
    while t < seconds:
        drift = 1 + 0.005 * np.sin(2 * np.pi * 0.7 * t)
        p = (1 / (f0 * drift)) * (1 + jitter * rng.standard_normal())
        periods.append(p)
        amps.append(max(0.2, 1 + shimmer * rng.standard_normal()))
        t += p
    periods = np.array(periods)
    amps = np.array(amps)
    closures = lead + periods[0] + np.concatenate([[0], np.cumsum(periods[1:])])
    total = closures[-1] + lead + 0.05
    source = np.zeros(int(np.ceil(total * hi)))
    for c, (tc, p, a) in enumerate(zip(closures, periods, amps)):
        tp, tn = 0.40 * p, 0.16 * p
        start = tc - tp - tn
        i0, i1 = int(np.ceil(start * hi)), int(np.floor(tc * hi))
        tt = np.arange(i0, i1 + 1) / hi - start
        d = np.where(tt < tp, 0.5 * np.pi / tp * np.sin(np.pi * tt / tp),
                     -np.pi / (2 * tn) * np.sin(np.pi * (tt - tp) / (2 * tn)))
        source[i0:i1 + 1] += a * d * p * 0.02
    y = source
    for hz, bw in FORMANTS:
        y = resonator(y, hi, hz, bw)
    y = resample_poly(y, 1, OVERSAMPLE)                   # band-limited decimation to fs
    voiced = slice(int(lead * fs), int((closures[-1]) * fs))
    signal_power = np.mean(y[voiced] ** 2)
    noise = rng.standard_normal(len(y)) * np.sqrt(signal_power / 10 ** (snr_db / 10))
    y[voiced] += noise[voiced]                             # noise only while voicing; the lead-in stays silent
    y = 0.5 * y / np.max(np.abs(y))
    return y, periods, amps, closures


def perturbation(values, points):
    """Praat-style perturbation quotient (all terms accepted), divided by the mean."""
    v = np.asarray(values)
    if points == 1:
        return np.mean(np.abs(np.diff(v))) / np.mean(v)
    h = points // 2
    terms = [abs(v[i] - v[i - h:i + h + 1].mean()) for i in range(h, len(v) - h)]
    return np.mean(terms) / np.mean(v)


def truth_f0_contour(closures, periods, step=0.01):
    """F0 at 10 ms steps: 1 / (length of the cycle that contains t), NaN outside voicing."""
    times = np.arange(0, closures[-1] + 0.3, step)
    f0 = np.full(len(times), np.nan)
    starts = closures - periods
    for k, t in enumerate(times):
        i = np.searchsorted(closures, t)
        if 0 <= i < len(closures) and starts[i] <= t <= closures[i]:
            f0[k] = 1 / periods[i]
    return times, f0


def write_wav(path, y, fs):
    wavfile.write(path, fs, np.round(np.clip(y, -1, 1) * 32767).astype(np.int16))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(SEED)
    plan = []
    # A spread of voices: 40 files sampled across pitch, jitter, shimmer and noise.
    for i in range(40):
        plan.append({
            "name": f"syn{i + 1:02d}",
            "f0": float(rng.choice([95, 110, 125, 150, 180, 210, 240])) * float(rng.uniform(0.97, 1.03)),
            "jitter": float(rng.choice([0.002, 0.004, 0.008, 0.015, 0.025])),
            "shimmer": float(rng.choice([0.01, 0.03, 0.06, 0.10])),
            "snr_db": float(rng.choice([35, 25, 18, 12])),
        })
    rows = []
    for item in plan:
        y, periods, amps, closures = synthesise(item["f0"], item["jitter"], item["shimmer"], item["snr_db"], 3.5, FS, rng)
        write_wav(OUT / f"{item['name']}.wav", y, FS)
        times, f0 = truth_f0_contour(closures, periods)
        with (OUT / f"{item['name']}.truth_f0.csv").open("w") as fh:
            fh.write("time,f0\n")
            for t, f in zip(times, f0):
                fh.write(f"{t:.4f},{'' if np.isnan(f) else f'{f:.5f}'}\n")
        rows.append({**item,
                     "true_mean_f0": float(np.mean(1 / periods)),
                     "true_jitter_local": float(perturbation(periods, 1)),
                     "true_jitter_rap": float(perturbation(periods, 3)),
                     "true_jitter_ppq5": float(perturbation(periods, 5)),
                     "true_source_shimmer_local": float(perturbation(amps, 1)),
                     "cycles": int(len(periods))})
    with (OUT / "truth.csv").open("w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    # Fake-microphone input for the browser check: 6 s of vowel, 0.6 s of silence (Chrome loops the file).
    demo_rng = np.random.default_rng(7)
    y, *_ = synthesise(130, 0.005, 0.03, 30, 6.0, 48_000, demo_rng)
    y = np.concatenate([y, np.zeros(int(0.6 * 48_000))])
    write_wav(OUT / "demo-voice.wav", 0.6 * y / np.max(np.abs(y)), 48_000)
    (OUT / "README.txt").write_text("Generated by make_synthetic_vowels.py (seed %d). Synthetic, not human voices.\n" % SEED)
    print(json.dumps({"files": len(rows), "out": str(OUT)}))


if __name__ == "__main__":
    main()
