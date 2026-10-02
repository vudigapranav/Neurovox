# Presentation inputs

Verified material for the slides (deck brief: `neurovox-03-presentation-brief.md`). Every number below is copied
from a generated results file named next to it; re-run the scripts and update this file if anything changes.
Screenshots are real captures of the app, made by the automated browser check with a **synthetic voice** as the
microphone — say so if you show them.

## Facts the deck can safely use

| Claim | Number | Source |
|---|---|---|
| Measurements computed in Java | 16 Oxford/UCI voice measures + 13 MFCC (mean, SD) + speech descriptors | `UciFeature`, `RecordingAnalyzer` |
| Java vs Praat, same file and region | all 16 within **0.15 %** relative error, Pearson **r = 1.000**, 40 synthetic vowels | `docs/results/praat_comparison.md` |
| Java vs Praat on human voices | **not measured** (no recordings) | VALIDATION §2 |
| Java inference vs scikit-learn | difference **< 1e-9** on 21 dataset rows | `ModelAgreementTest` |
| Grouped (honest) model accuracy | **69.2 %** (balanced 57.1 %, ROC-AUC **0.62**) | `docs/results/model_evaluation.md` |
| Grouped, 20 shuffles | **72.4 % ± 2.8 %**, ROC-AUC 0.656 ± 0.047 | same |
| Naive (leaky) accuracy | **80.5 %** (ROC-AUC 0.82) | same |
| Majority-class baseline | **75.4 %** | same |
| Dataset | 195 recordings; 32 subject codes (24 PD, 8 healthy); description says 31 people | same |
| Pitch study, octave errors (clean / 6 dB noise) | autocorrelation **0.00 % / 0.00 %**, YIN **1.80 % / 10.61 %** | `docs/results/pitch_study.md` |
| Pitch study, MAE vs truth (clean) | autocorrelation **0.77 Hz**, YIN **3.25 Hz** | same |
| Pitch study, speed | autocorrelation **48.7 ms**, YIN **3.0 ms** per second of audio (M4 laptop) | same |
| Detector choice → jitter | median shift 0.15 % (clean), **max 224 %** on a noisy file | same |
| Anti-aliasing | 10 kHz tone resampled 48 → 16 kHz: alias **> 60 dB** down | `PreprocessingTest` |
| Tests | **174** Java + **31** frontend tests pass; full browser journey passes | `./mvnw test`, `scripts/recorder-check.mjs` |
| Live research indicator | **not shown** — gate decision **NO-GO** (2 Oct 2026) | `research-gate.json`, VALIDATION §6 |

**Do not claim:** that Neurovox detects, screens for, predicts or diagnoses Parkinson’s; any accuracy without saying
it is subject-grouped and below the baseline; “clinical confidence”; normal ranges; validation on patients or on
human voices; a stage or severity.

## Slide-by-slide numbers (replacing the brief’s placeholders)

- **Slide 10 — the trap we avoided:** Naive random split **80.5 %** (struck through) · Patient-level grouped
  **69.2 %**. Add the baseline line: “Always guessing ‘PD’: 75.4 %.” The honest finding is stronger than the brief
  expected: once the same voices are kept apart, 16 classical features barely beat guessing.
- **Slide 11 — calibration against Praat:** Jitter (local) r = 1.000, Shimmer (local) r = 1.000, HNR r = 1.000,
  Mean F0 r = 1.000 — with the caption “40 synthetic vowels; human recordings not yet compared”. Use
  `docs/results/figures/praat_scatter.png`.
- **Slide 12 — autocorrelation vs YIN:** Mean F0 error 0.77 Hz vs 3.25 Hz · Octave errors 0.00 % vs 1.80 % (clean),
  0.00 % vs 10.61 % (6 dB noise) · Time 48.7 ms vs 3.0 ms per second of audio. Finding: on a noisy recording the
  detector choice changed jitter by up to 224 %. Use `docs/results/figures/pitch_study.png`.
- **Slide 13/14 additions:** “We built a classifier, measured it honestly, and decided not to show it.”

## Architecture diagram (verified against the code)

```
 Browser (HTML/CSS/JS)                         Java 17 — one process, 127.0.0.1 only
 ─────────────────────                         ──────────────────────────────────────
 guided pages, particle orb                    JDK HttpServer  (AppServer, RequestGuardFilter)
 AudioWorklet: float32 PCM  ── PUT ──▶            │
   at real sample rate                            ▼
                                               Quality gate ── fail ──▶ 422 + how to fix
                                                  │ pass
                                                  ▼
                                               SQLite + recording files (SessionService)
                                                  │  POST /analysis
                                                  ▼
                                               Resample 16 kHz (anti-alias)  │  vowel at native rate
                                               Autocorrelation pitch (Viterbi)
                                               Glottal pulses (cross-correlation)
                                               Jitter · Shimmer · HNR/NHR · F0 · MFCC
                                                  │
                                               Research model ◀── gate: NO-GO (not applied)
                                                  │
 report, history, trends  ◀── JSON ────────────  stored report
```
Colour split for slide 8: browser column muted, Java column in Signal teal — everything analytical is Java.
A Mermaid version is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Code excerpts (copied from the source, unchanged)

Little-endian decoding — `audio/PcmDecoder.java`:
```java
FloatBuffer floats = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
```

The routing table — `server/AppServer.java`:
```java
route(server, "/api/health", new ApiHandlers.Health(), guard);
route(server, "/api/tasks", new ApiHandlers.Tasks(), guard);
route(server, SessionApi.PREFIX, new SessionApi(sessions, analysis, config.maxBodyBytes()), guard);
route(server, "/api/model", new ApiHandlers.Model(analysis.indicator()), guard);
route(server, "/api/data", new ApiHandlers.LocalDataApi(sessions), guard);
route(server, "/api/", new ApiHandlers.NotFound(), guard);
route(server, "/", new StaticFileHandler(), guard);
```

Window-corrected autocorrelation — `dsp/AutocorrelationPitchDetector.java`:
```java
for (int lag = 0; lag <= lagLimit; lag++) {
    double sum = 0;
    for (int n = 0; n + lag < nW; n++) {
        sum += a[n] * a[n + lag];
    }
    r[lag] = sum / (r0 * windowR[lag]);
}
```

The bug we found — keeping the fraction of a sample between cycles — `dsp/PulseExtractor.java`:
```java
double shift = lo - 1 + refined.position() - centre;
double position = t * fs + shift;
```

Local jitter — `features/JitterMeasures.java`:
```java
for (int i = 0; i + 1 < intervals; i++) {
    if (inRange(p[i], pmin, pmax) && inRange(p[i + 1], pmin, pmax) && ratio(p[i], p[i + 1]) <= factor) {
        sum += Math.abs(p[i] - p[i + 1]);
        terms++;
    }
}
```

Logistic-regression inference — `ml/ResearchModel.java`:
```java
double s = intercept;
for (int i = 0; i < x.length; i++) {
    if (!Double.isFinite(x[i])) {
        throw new IllegalArgumentException(featureOrder.get(i) + " is missing; the model cannot be applied.");
    }
    s += coefficients[i] * (x[i] - mean[i]) / scale[i];
}
return s;
```

## Screenshots (`docs/screenshots/`, real captures, synthetic voice)

| File | Shows |
|---|---|
| `01-about.jpg` | Step 1 of 8, the non-diagnostic statement, the orb |
| `02-room.jpg` | Room preparation |
| `03-microphone-check.jpg` | Level meter in the green zone, orb reacting |
| `04-task-instructions.jpg` | Sustained-vowel instructions and what the task is used for |
| `05-recording-orb.jpg` | Recording with timer; orb reacting beside the instructions |
| `06-review-this-recording.jpg` | Review this recording: waveform, Play, Record again, Save |
| `07-quality-gate-rejection.jpg` | A silent take rejected by Java with recovery advice |
| `08-microphone-blocked.jpg` | Blocked microphone with per-browser instructions |
| `09-report-top.jpg`, `10-report-full.jpg` | The report: guidance, quality, 16 measurements, descriptive tasks, MFCC, why no indicator |
| `11-history-trend.jpg` | History table and trend chart (two sessions) |
| `12-delete-dialog.jpg` | Deleting a session |
| `13-phone-recorder.jpg`, `14-phone-report.jpg` | Phone layouts (390 px wide) |

Figures: `docs/results/figures/praat_scatter.png`, `docs/results/figures/pitch_study.png`.

## Pre-presentation checklist (from the brief, updated)

- [x] Every placeholder number has a measured value (above)
- [ ] Demo rehearsed three times on the presenting laptop ([DEMO_SCRIPT.md](DEMO_SCRIPT.md))
- [ ] Screen recording of a rehearsal session exported (must be made by the team)
- [ ] Microphone permission pre-granted for `localhost` on the presenting machine
- [ ] Demo data folder contains one earlier session so the trend is not empty
- [ ] Deck checked on the projector; fonts embedded or exported to PDF
