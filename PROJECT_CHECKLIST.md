# Neurovox — Project Checklist

Progress record for the build. A sprint is marked done only when its checks pass; evidence is listed under each.
The three planning documents (`neurovox-01…03`) are reference material and are not edited. The completion request
(2 Oct 2026) organised the remaining work as sprints **A–I**; the table maps them to the planning document’s numbers.

| Sprint | Scope | Status |
|---|---|---|
| 0 | Particle website and visual system | **Done** — 2026-09-26 |
| 1 | Runnable Java foundation (Maven, HttpServer, static hosting) | **Done** — 2026-09-26 |
| 2 | Sessions and SQLite | **Done** — 2026-09-26 |
| 3 / **A** | Four-task recorder, upload API, guided website flow | **Done** — 2026-10-02 (was marked “not started” although most recorder code existed; audited and finished) |
| 4 / **B** | WAV/PCM, anti-aliased resampling, framing, windows, quality gate | **Done** — 2026-10-02 |
| 5 / **C** | Autocorrelation and YIN pitch, voicing, glottal pulses | **Done** — 2026-10-02 |
| 6 / **D** | 16 UCI features (fixed order) + MFCC | **Done** — 2026-10-02 |
| 7 / **E** | Java-vs-Praat comparison and go/no-go | **Done on synthetic vowels**; human recordings missing → decision **NO-GO** |
| 8 / **F** | Offline training, subject-grouped evaluation, export | **Done** — 2026-10-02 |
| 9 / **G** | Java inference and scientific gate | **Done** — inference verified; live indicator withheld by the gate |
| 10 / **H** | Results, history, trends, deletion, all frontend states | **Done** — 2026-10-02 |
| 11–12 / **I** | Autocorrelation-vs-YIN study, regression, security/privacy, demo, docs | **Done** — 2026-10-02 |

Totals at completion: **174 Java tests, 31 frontend tests, all passing**; end-to-end browser journey passing.

---

## Sprint 0 — Particle website and visual system *(2026-09-26, unchanged)*

Procedural WebGL particle orb (no video, no CDN), microphone-reactive, with fallbacks for denied/missing microphone,
WebGL loss, reduced motion and phone layouts. Evidence: `scripts/visual-check.mjs`, `scripts/orb_shape_metric.py`
(quiet → loud: equivalent radius 1.07 → 1.21, solidity 0.967 → 0.922). The video background stays removed.

## Sprint 1 — Runnable Java foundation *(2026-09-26, unchanged)*

Java 17 Maven build with wrapper, shaded JAR, JDK `HttpServer` on 127.0.0.1, static hosting, JSON errors, request
limits, path-traversal and Host-header protection, CSP and security headers.

## Sprint 2 — Sessions and SQLite *(2026-09-26; one latent defect fixed on 2026-10-02)*

Domain model, SQLite with versioned migrations, foreign keys and cascades, triggers restricting research results to
the sustained vowel, repositories, atomic recording files with SHA-256, owner-only permissions.
**Fixed 2026-10-02:** concurrent first start-ups could fail with `SQLITE_BUSY` because switching to WAL does not wait
for the busy timeout (it failed in about 5 of 6 runs once a second migration existed). `Database` now retries the
switch; 15 of 15 stress runs pass.

## Sprint 3 / A — Recorder, upload API, guided flow

**Audit (2 Oct):** the four-task recorder (AudioWorklet capture at the real sample rate, review/playback/retake,
interruption and microphone-error states) and `PUT /api/sessions/{id}/recordings/{task}` already existed with 7 API
tests. Missing: in-site guidance, stop-early, quality checks on upload, analysis, results, history, deletion, resume.

### Delivered
- [x] Landing page **“How to use Neurovox”**: the eight steps (what it measures and the non-diagnostic limit; room;
      microphone; the four tasks; start/stop/replay/retry/save; Java processing; reading results; history/deletion).
- [x] Recorder with an **8-step indicator** (About → Room → Microphone → Vowel → Syllables → Reading → Free speech →
      Analysis), **Back** on every step, labels such as “Continue to microphone check” and “Review this recording”.
- [x] Microphone check with a live level meter (green zone) and the orb reacting; per-browser and per-OS recovery
      instructions when access is blocked, missing or busy.
- [x] **Stop recording** after the task’s minimum (vowel 3 s, pa-ta-ka 4 s, reading 10 s, free speech 10 s); the
      worklet ends the take early and keeps it.
- [x] Server-side **quality gate on upload**: unusable takes get `422 audio_quality` with what was wrong and how to fix
      it, and are not stored; warnings are saved and shown.
- [x] Resume an unfinished session (`record.html?session=<id>`; History → Continue recording).
- [x] Orb reacts during the mic check, countdown, recording and playback; the card sits on a translucent panel so
      instructions stay readable.

### Evidence
- `SessionApiTest` (7), `AnalysisApiTest` (7): full four-task flow through HTTP, rejected takes (silence, clipping,
  too short, noise) leave no files, analysis requires all four tasks.
- `scripts/recorder-check.mjs` (headless Chrome, synthetic vowel as microphone): about → room → mic (“Good level.”) →
  vowel stopped early at 3.1 s → retake → replay → save → three tasks → analysis → report; rejected take, dropped
  connection then retry, page hidden during a take, resume, blocked microphone, phone layouts (no overflow at 390 px),
  delete session, delete all. **No console errors; all 172 requests to the local server only.**

## Sprint 4 / B — Audio preprocessing and quality gate

### Delivered
- [x] `WavCodec` (PCM 8/16/24/32-bit and float, multichannel downmix, extensible format; 16-bit writer), `AudioClip`.
- [x] `Resampler`: windowed-sinc band-limited interpolation (Blackman, 32 zero crossings, cutoff 0.95 × lower Nyquist),
      exact for non-integer ratios, kernels cached per phase.
- [x] `SignalOps` (DC removal, pre-emphasis 0.97, peak normalisation, RMS/dBFS), `WindowFunction` (Hamming, Hann),
      `Framer` (512/256 → 61 frames for 16 000 samples; the plan’s “62” would need padding — documented).
- [x] `QualityGate` + `LevelProfile`: sample rate, duration, loudness, clipping, voicing, steady vowel stretch,
      background noise — each with a recovery message.

### Evidence
`PreprocessingTest` (11) and `QualityGateTest` (8): 1 kHz kept through 48→16 kHz; **10 kHz alias > 60 dB down**
(naive decimation leaves it at full strength); 44.1→16 kHz within 1e-3 of the analytic sine; Hamming sum exact;
WAV round trip, float/stereo/24-bit decoding, invalid files rejected; silence, very quiet, clipped, short, noise-only
and 8 kHz takes rejected; steady background noise gives a warning.

## Sprint 5 / C — Pitch detection and vocal pulses

### Delivered
- [x] `AutocorrelationPitchDetector` (Boersma 1993: window-corrected autocorrelation, sinc peak refinement, Viterbi
      path with octave and voicing costs; Praat’s default settings).
- [x] `YinPitchDetector` (de Cheveigné & Kawahara 2002, steps 1–5).
- [x] `PulseExtractor` (Praat’s cross-correlation pulse tracking) → `PulseTrain` with Praat’s period rule.

### Evidence
`PitchDetectorTest` (17): 200 Hz sine and sawtooth within 1 Hz (both detectors); 90–400 Hz within 0.5 %; strong
second harmonic → no octave error; silence unvoiced; noise < 10 % voiced; periodic vowel → one pulse per cycle,
mean period exact to 1e-7 s. **Defect found and fixed:** pulse tracking dropped the fractional sample between cycles
(periods came out as whole samples); now fixed as in Praat.

## Sprint 6 / D — Acoustic features

### Delivered
- [x] `UciFeature`: the 16 computable columns in one fixed order and units (jitter/shimmer as fractions, the dataset’s
      convention). The six nonlinear columns are listed as not computed and never filled.
- [x] `JitterMeasures`, `ShimmerMeasures`, `HarmonicityMeasures`, `F0Statistics`, `MfccExtractor` (JTransforms FFT only).
- [x] Unmeasurable values stored as “unavailable” with a reason; the vowel’s period analysis runs at the native rate
      (≤ 48 kHz), MFCC and speech measures at 16 kHz.
- [x] Descriptive measures for the other three tasks (never classifier inputs).

### Evidence
`FeatureFormulaTest` (16): hand-worked jitter and shimmer values exact; **DDP = 3·RAP and DDA = 3·APQ3 exactly**;
injected jitter recovered within 20 %; HNR of sine + noise within 1 dB of the SNR at 5/10/20 dB; MFCC 13-dimensional
and finite; silent vowel → all 16 unavailable with a reason.

## Sprint 7 / E — Praat comparison and go/no-go

### Delivered
- [x] `tools.AnalyzeWav` (same code as the app), `validation/make_synthetic_vowels.py` (40 vowels with known cycle
      timing), `validation/praat_compare.py` (Praat 6.1.38 via Parselmouth on the same files and regions; paired values,
      MAE, bias, relative error, Pearson r, truth comparison, scatter plot).
- [x] Gate decision recorded in `src/main/resources/model/research-gate.json`: **NO-GO**.

### Evidence
`docs/results/praat_comparison.md`: **all 16 features agree, relative MAE ≤ 0.15 %, r = 1.000** (40 synthetic
vowels). Against the truth, Java and Praat share the same small under-estimate of large jitter.

### Missing evidence (stated precisely)
No human sustained-vowel recordings were available, so Java-vs-Praat agreement on real voices is untested; and the
dataset provides no audio or MDVP settings, so compatibility with its columns cannot be shown. To close the first gap:
record ≈ 20 consenting volunteers, put the WAVs in `validation/recordings/`, rerun `praat_compare.py`.

## Sprint 8 / F — Offline training and evaluation

### Delivered
- [x] `training/train_model.py`: SHA-256-checked UCI data, subject codes from `name`, 16 features, StandardScaler +
      LogisticRegression; StratifiedGroupKFold(5) with fold composition and an assertion of zero subject overlap;
      20 repeated shuffles; leave-one-subject-out; per-subject scoring; naive split for leakage contrast; export of
      feature order, scaler, coefficients, intercept, threshold, version; 21 reference rows for Java.

### Evidence
`docs/results/model_evaluation.md`: grouped **69.2 %** (balanced 57.1 %, AUC 0.622); repeated 72.4 % ± 2.8 %;
naive (leaky) **80.5 %**; majority baseline 75.4 %. Re-running gives byte-identical outputs. Dataset discrepancy
recorded: 32 subject codes vs 31 people in the description.

## Sprint 9 / G — Java inference and scientific gate

### Delivered
- [x] `ResearchModel` (standardise → linear score → logistic), `ModelLoader` (JSON; checks feature order),
      `CompatibilityGate`, `ResearchIndicator` (gate → model → all 16 vowel values; vowel only).
- [x] Live indicator shown only if the gate says go — currently withheld, and the report explains why.

### Evidence
`ModelAgreementTest` (6): **Java = scikit-learn to < 1e-9** on 21 rows; feature-order mismatch rejected; packaged gate
is no-go. `AnalysisApiTest`: with a “go” gate the indicator is shown and stored for the vowel only; with “no-go”
nothing is stored and the reason is returned.

## Sprint 10 / H — Results, history, deletion, frontend states

### Delivered
- [x] `results.html`: how-to-read guidance, recording quality, 16 measurements with plain and technical names and
      explanations, change since the previous session, descriptive tasks, MFCC chart, research-model status with real
      offline results, WAV download, delete session. No invented normal ranges.
- [x] `history.html`: session list (analyse / continue / open / delete), trend chart of any of the 16 measures with a
      data table, delete all local data (typed confirmation).
- [x] API: list, delete, analysis, report, WAV export, model info, delete all; migration v2 (`sample_analyses`).
- [x] Every state handled: loading, not analysed, not found, server down, analysis failure.

### Evidence
`AnalysisApiTest`, frontend contract tests (no `innerHTML` with server text, disclaimers present, no confidence or
staging wording), end-to-end screenshots `docs/screenshots/`.

## Sprints 11–12 / I — Pitch study, hardening, documentation, demo

### Delivered
- [x] `tools.PitchStudy` + `validation/summarise_pitch_study.py`: 40 vowels × 3 conditions × 2 detectors.
- [x] Security/privacy: cross-origin guard for state-changing requests (Origin / Sec-Fetch-Site), Host check, size
      limits, CSP, owner-only files, no external requests, recordings and databases ignored by Git.
- [x] Documentation: README (rewritten), `docs/ARCHITECTURE.md`, `docs/JAVA_WALKTHROUGH.md`, `docs/VALIDATION.md`,
      `docs/TEAM_HANDOFF.md`, `docs/DEMO_SCRIPT.md`, `docs/PRESENTATION_INPUTS.md`, `training/README.md`.

### Evidence
`docs/results/pitch_study.md`: autocorrelation 0.00 % octave errors (clean and 6 dB noise) vs YIN 1.80 % / 10.61 %;
YIN ≈ 16× faster; detector choice shifts jitter by up to 224 % on a noisy file. `AnalysisApiTest.changesFromOtherSitesAreRefused`.

## Known limitations (all sprints)

- No human voices in the validation; MDVP compatibility unknown; the model is weak under honest evaluation.
- Speech heuristics (pauses, syllable rate) and quality thresholds are engineering choices, not validated.
- A real voice through a real microphone was not exercised by the automated checks (they use a synthetic vowel);
  each teammate should run one manual session (TEAM_HANDOFF §1).
- Windows and Linux were not run by the author; the JAR bundles SQLite for both. Owner-only permissions apply on
  macOS/Linux only.
- Analysis takes about 1 s per vowel take on an Apple M4 (direct autocorrelation at 48 kHz); slower laptops will be slower.
