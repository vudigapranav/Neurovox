# Neurovox
## Voice-Based Parkinson's Screening Indicator

**Document 1 of 3 — Project Proposal**
For team discussion and faculty approval

| | |
|---|---|
| **Project name** | Neurovox |
| **Course** | Java Programming — Semester Project |
| **Team size** | 3 |
| **Primary language** | Java 17 (all analysis logic) |
| **Deliverable** | Local web application + validation report |
| **Status** | Proposed — pending faculty approval |

---

## 1. Abstract

Neurovox is a Java application that analyses a short voice recording and produces a screening indicator for the acoustic signs associated with Parkinson's disease, along with a detailed report of the underlying speech measurements.

A user records four short vocal tasks through a web page. The Java backend performs all signal processing itself — resampling, pitch tracking, and the computation of clinically established acoustic markers such as jitter, shimmer, and harmonics-to-noise ratio — then feeds those measurements into a logistic regression classifier to produce a risk indicator. Results are stored so a user can track their own measurements over time.

The project's technical centre of gravity is the digital signal processing layer, which is written from first principles in Java rather than delegated to an audio library.

---

## 2. Problem statement

Parkinson's disease affects roughly 1% of people over 60 and is currently diagnosed clinically — through neurological examination, patient history, and the exclusion of conditions that mimic it. There is no blood test or scan that confirms it in a living patient. Even in specialist hands, early diagnostic accuracy is around 80%.

By the time the characteristic tremor appears, an estimated 60–80% of the relevant dopamine-producing neurons are already lost.

Speech is affected in approximately 90% of patients, and often early. The disease impairs the muscles controlling the larynx, tongue, and lips in the same way it impairs the hands: reduced amplitude, reduced speed, poor coordination. This produces measurable acoustic changes:

| Clinical sign | Acoustic consequence | Measurable as |
|---|---|---|
| Hypophonia | Reduced and unstable loudness | Amplitude variability, shimmer |
| Vocal fold rigidity | Irregular cycle-to-cycle timing | Jitter |
| Incomplete glottal closure | Breathiness, added noise | HNR / NHR |
| Monopitch | Collapsed pitch range | F0 standard deviation |
| Imprecise articulation | Blurred consonants, slower syllables | MFCC, diadochokinetic rate |

These changes are subtle enough that a listener may not notice them, but they are directly computable from an audio signal. That gap — between what is audible and what is measurable — is what this project addresses.

---

## 3. Scope and non-goals

### In scope

- Capture of four structured vocal tasks
- Full signal processing pipeline implemented in Java
- Computation of jitter, shimmer, HNR, F0 statistics, and MFCCs
- Binary screening indicator (elevated / not elevated) with a confidence value
- Per-feature report comparing each measurement against reference ranges, in plain language
- Longitudinal tracking of a user's own measurements across sessions
- A validation study comparing Neurovox's feature values against Praat, the reference tool in speech science
- Comparison of two pitch-detection algorithms for accuracy and cost

### Explicitly not in scope

**Neurovox does not diagnose Parkinson's disease.** It is a screening and educational tool. This is stated on every results screen.

The following are deliberately excluded:

| Excluded | Reason |
|---|---|
| Disease stage classification (Hoehn–Yahr) | Published error margins are too wide to distinguish adjacent stages. Displaying a stage implies a precision the signal does not have. |
| Treatment recommendation | This is medical practice and would place the project in medical-device regulatory territory. Treatment decisions depend on medication response, non-motor symptoms, comorbidities, and cognition — none of which are present in the audio. |
| Clinical validation | We have no access to patients. The model is validated against a public research dataset only. |

One experimental module — a UPDRS motor score regression — is included as a **research output with its error explicitly reported**, clearly separated from the main screening result and never expressed as a clinical stage.

---

## 4. Proposed solution

### 4.1 The four recording tasks

Task design matters as much as the model. Free speech alone is not usable; the tasks are chosen because each isolates a different aspect of motor control.

| # | Task | Duration | What it isolates |
|---|---|---|---|
| 1 | Sustained vowel `/a/` | 5 s | Pure phonation — clean jitter, shimmer, HNR with no articulatory interference |
| 2 | Diadochokinetic `/pa-ta-ka/` repeated as fast as possible | 6 s | Articulatory agility. The direct vocal analogue of the finger-tapping bradykinesia test. `/pa/`, `/ta/`, `/ka/` exercise lips, tongue tip, and tongue back respectively |
| 3 | Standard reading passage | 20 s | Controls lexical content across users so recordings are comparable |
| 4 | Free monologue | 30 s | Prosody, pause structure, speech rate |

Tasks 1 and 2 drive the classifier. Tasks 3 and 4 feed the descriptive report.

### 4.2 Processing pipeline

```
  Browser                          Java Backend
  ───────                          ────────────

  Web Audio API captures
  microphone, sends raw
  Float32 PCM over HTTP     ──▶    HttpServer receives PCM
                                          │
                                          ▼
                                   Resample 48 kHz → 16 kHz
                                   (anti-alias FIR + decimation)
                                          │
                                          ▼
                                   Pre-emphasis, framing,
                                   Hamming windowing
                                          │
                                          ▼
                                   Pitch tracking
                                   (autocorrelation / YIN)
                                          │
                                          ▼
                        ┌─────────────────┴─────────────────┐
                        ▼                                   ▼
                 Period-based features              Spectral features
                 jitter, shimmer, HNR,              MFCC (via FFT)
                 F0 mean / SD / range
                        └─────────────────┬─────────────────┘
                                          ▼
                                   Feature vector (22-dim)
                                          │
                                          ▼
                                   Standardise, then
                                   logistic regression
                                          │
                        ┌─────────────────┴─────────────────┐
                        ▼                                   ▼
                 Screening indicator                 Feature report
                        └─────────────────┬─────────────────┘
                                          ▼
                                   SQLite persistence
                                          │
  JS renders dashboard      ◀──────  JSON response
```

### 4.3 Why the analysis stays in Java

The project must demonstrate Java competence, so the boundary between "our code" and "library code" is drawn deliberately:

**Written by the team, in Java:**

- Resampling (48 kHz → 16 kHz)
- Pre-emphasis, framing, Hamming windowing
- Autocorrelation pitch detection
- YIN pitch detection
- Jitter — local, RAP, PPQ5
- Shimmer — local, dB, APQ3, APQ5
- Harmonics-to-noise ratio
- Mel filterbank and DCT for MFCC
- Logistic regression inference
- Domain model, repository layer, HTTP handlers

**Third-party libraries, used only as numerical or plumbing primitives:**

| Library | Purpose | Justification |
|---|---|---|
| JTransforms | FFT | A numerical primitive, comparable to `Math.sqrt`. Hand-writing a radix-2 butterfly demonstrates nothing about this project. |
| sqlite-jdbc | Database driver | Standard JDBC driver. |
| Gson | JSON serialisation | Boilerplate only. |

**Deliberately avoided:**

| Avoided | Reason |
|---|---|
| TarsosDSP | Provides ready-made YIN pitch detection and MFCC — precisely the components that constitute this project's contribution. |
| Spring Boot | Request routing happens invisibly through annotations. We use the JDK's built-in `com.sun.net.httpserver.HttpServer`, roughly 40 lines of setup, all of it explainable. |
| Weka / DL4J at runtime | Model training may use external tooling offline; the inference math in the shipped application is ours. |

The rule: *if a library computes an acoustic feature, we write it ourselves. If it computes a number, we use it.*

---

## 5. Technology stack

| Layer | Technology | Notes |
|---|---|---|
| Core logic | Java 17 (LTS) | All DSP, feature extraction, inference |
| Build | Maven | Standard layout, reproducible dependency resolution |
| HTTP server | `com.sun.net.httpserver` | Built into the JDK |
| Database | SQLite via JDBC | Single-file, zero configuration |
| Frontend | HTML, CSS, vanilla JavaScript | Web Audio API for capture; no framework |
| FFT | JTransforms 3.1 | Numerical primitive only |
| Offline training | Python 3, scikit-learn, pandas | Produces a coefficient file; not shipped with the app |
| Validation reference | Praat | Used to check our feature values |

The frontend is intentionally minimal: record, POST, render JSON. All logic stays in Java, so there is a single place to debug and a single body of code to defend.

---

## 6. Datasets

| Dataset | Contents | Use |
|---|---|---|
| **Oxford Parkinson's Disease Detection Dataset** (Little et al., 2008) — UCI ML Repository | 195 sustained-vowel recordings from 31 subjects (23 with PD), 22 pre-computed acoustic features plus a binary `status` label | Trains the primary classifier |
| **Parkinson's Telemonitoring Dataset** (Tsanas et al., 2010) — UCI ML Repository | 5,875 recordings from 42 patients with `motor_UPDRS` and `total_UPDRS` scores | Trains the experimental severity regression |

**An important characteristic of both datasets:** they contain *extracted features*, not audio files. There is no `.wav` to train on. This directly shapes the architecture — see Risk R1 below.

---

## 7. Algorithm comparison component

To provide a clear analytical core suitable for evaluation, Neurovox includes a controlled comparison of two pitch-detection algorithms, since every period-based feature depends on pitch tracking accuracy.

| | Autocorrelation | YIN |
|---|---|---|
| Method | Peak of the autocorrelation function within a lag range | Cumulative mean normalised difference function with absolute threshold |
| Known weakness | Octave errors — may lock onto a harmonic | More robust, higher computation cost |
| Complexity | O(N·L) per frame | O(N·L) plus normalisation pass |

Both are implemented. They are compared on:

1. Agreement with Praat's F0 contour on the same recordings
2. Downstream effect on jitter and shimmer values
3. Execution time per second of audio

This gives a genuine empirical result rather than an assertion, and it mirrors the structure of a classic algorithm-comparison study.

---

## 8. Validation plan

Because we cannot recruit patients, validation operates on two independent axes.

**Axis 1 — Model validity (does the classifier work?)**

Evaluated on the UCI dataset with **patient-level splits** using `GroupKFold`.

> This is critical. The dataset contains roughly six recordings per subject. A naive random split by *recording* places the same person's voice in both training and test sets, producing accuracy figures above 95% that reflect voice memorisation rather than disease detection. Nearly every published student project on this dataset makes this mistake. We report grouped-split figures, and we also report the naive figure alongside it to demonstrate the size of the effect.

Metrics: accuracy, precision, recall, F1, ROC-AUC, and confusion matrix — all under grouped cross-validation.

**Axis 2 — Feature validity (do our Java measurements match the reference?)**

This addresses the project's central technical risk. We record a set of samples, process each through both Praat and Neurovox, and report per-feature Pearson correlation and mean absolute error.

This calibration study is a deliverable in its own right. A high correlation demonstrates that our from-scratch DSP is correct; a low correlation on any given feature is a finding we report honestly rather than conceal.

---

## 9. Risks and mitigations

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| **R1** | **Feature scale mismatch.** The UCI dataset's features were produced by Praat, which uses specific definitions (jitter local vs. RAP vs. PPQ5 vs. DDP). If our implementation differs even slightly, the trained coefficients receive inputs on a different scale and the model fails **silently** — still emitting a confident-looking number that is meaningless. | **High** | Implement Praat's exact published definitions. Validate through the Axis 2 calibration study *before* connecting the classifier. Treat any feature with correlation below 0.9 as unresolved and report it. |
| R2 | Patient-level data leakage | High | `GroupKFold` on subject ID, parsed from the dataset's `name` column. Report both grouped and naive scores. |
| R3 | Confounds — microphone quality, room noise, distance, age, sex. With 31 subjects a model can trivially learn "cheap microphone" instead of "Parkinson's." | High | Amplitude normalisation, a signal-quality gate that rejects noisy recordings, and an explicit statement of this limitation in the report. |
| R4 | Medication state. The same patient's voice differs substantially on and off levodopa within a single day. | Medium | Acknowledged as a limitation. Reinforces why the longitudinal view is framed as trend-tracking, not staging. |
| R5 | Browser audio format. Browsers deliver 48 kHz Float32; the datasets assume ~16–25 kHz. | Medium | Resampling stage with proper anti-aliasing, implemented and unit-tested first. |
| R6 | Microphone permission requires `localhost` or HTTPS | Low | Demo runs on `localhost`. Documented in the setup guide. |
| R7 | Scope. This is materially larger than a typical semester project. | Medium | Sprint plan with a defined minimum viable core (Sprints 0–8). Sprints 9–12 are enhancements that can be dropped without breaking the deliverable. |

---

## 10. Team allocation

Three members, split along the seams where the work genuinely separates.

**Member 1 — Signal processing**
`audio/`, `dsp/`
Resampling, pre-emphasis, framing, windowing, both pitch detectors, the FFT wrapper. Owns the pitch-detection comparison study.

**Member 2 — Features and machine learning**
`features/`, `ml/`, `training/`
Jitter, shimmer, HNR, F0 statistics, MFCC. Offline model training, coefficient export, Java inference. Owns the Praat calibration study.

**Member 3 — Application and interface**
`server/`, `db/`, `model/`, frontend
HTTP layer, SQLite persistence, domain model, the recording page and results dashboard. Owns integration and the demo.

**Shared:** domain model definitions are agreed jointly in Sprint 1, since all three depend on them. Members 1 and 2 have the tightest coupling — the pitch contour is the interface between them — and should agree its format early.

---

## 11. Timeline

Ten weeks. Adjust to the actual submission date.

| Week | Focus | Milestone |
|---|---|---|
| 1 | Setup, domain model, database schema | Project builds; a `Patient` can be saved and retrieved |
| 2 | HTTP server, static file serving, browser recorder | A recording reaches the server and is stored as PCM |
| 3 | Resampling, pre-emphasis, framing, windowing | Preprocessing verified against synthetic test signals |
| 4 | Autocorrelation and YIN pitch detection | F0 contour extracted from a real vowel |
| 5 | Jitter, shimmer, HNR, F0 statistics | Full feature vector produced from a recording |
| 6 | **Praat calibration study** | Correlation table complete — the go/no-go checkpoint for R1 |
| 7 | MFCC; offline model training and export | Trained coefficients loading into Java |
| 8 | Java inference; results API | End-to-end: record → screening result |
| 9 | Dashboard, longitudinal view, feature report UI | Demo-ready application |
| 10 | Pitch-detection comparison, UPDRS module, documentation, deck | Submission |

Week 6 is the decision point. If calibration fails, there is still time to correct the feature implementations before the classifier depends on them.

---

## 12. Deliverables

1. Neurovox application — source, build instructions, runnable demo
2. Trained model with exported coefficients and a reproducible training script
3. Praat calibration report — per-feature correlation and error
4. Model evaluation report — grouped cross-validation results
5. Pitch-detection comparison — accuracy and timing
6. Project documentation
7. Presentation

---

## 13. Ethics and limitations

Stated plainly, both in this document and in the application:

- Neurovox is **not a diagnostic tool** and does not detect Parkinson's disease. It measures acoustic properties associated with it.
- It is trained on 31 subjects. That is a small sample, and the model will not generalise reliably beyond it.
- The training data is drawn from English-speaking populations. Performance on other languages is unknown.
- Voice recordings are personal data. All storage is local; nothing is transmitted off the machine. Users can delete their recordings.
- Anyone concerned about their symptoms should consult a neurologist. The application says so on the results screen.

**A note on a genuine research gap:** published models in this area are trained almost exclusively on English, Italian, and Spanish speakers. If time permits, collecting even a small Telugu or Hindi corpus of healthy-control recordings would let us document how features behave across languages — a contribution beyond the scope of a typical semester project.

---

## 14. References

1. Little, M. A., McSharry, P. E., Roberts, S. J., Costello, D. A. E., & Moroz, I. M. (2007). Exploiting nonlinear recurrence and fractal scaling properties for voice disorder detection. *BioMedical Engineering OnLine*, 6:23.
2. Tsanas, A., Little, M. A., McSharry, P. E., & Ramig, L. O. (2010). Accurate telemonitoring of Parkinson's disease progression by non-invasive speech tests. *IEEE Transactions on Biomedical Engineering*, 57(4).
3. Postuma, R. B. et al. (2015). MDS clinical diagnostic criteria for Parkinson's disease. *Movement Disorders*, 30(12).
4. de Cheveigné, A., & Kawahara, H. (2002). YIN, a fundamental frequency estimator for speech and music. *Journal of the Acoustical Society of America*, 111(4).
5. Boersma, P., & Weenink, D. Praat: doing phonetics by computer. Version 6.x.
6. Braak, H. et al. (2003). Staging of brain pathology related to sporadic Parkinson's disease. *Neurobiology of Aging*, 24(2).

---

## Questions for faculty

1. Is the scope appropriate for a semester project given the three-person team?
2. Is the library boundary in §4.3 acceptable — specifically, using JTransforms for FFT while implementing all acoustic features ourselves?
3. Is a browser-based frontend acceptable, given that all analysis logic is in Java?
4. Should the experimental UPDRS regression be included, or excluded entirely for clarity?
