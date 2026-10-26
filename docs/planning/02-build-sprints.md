# Neurovox — Build Sprints

**Document 2 of 3 — Implementation Plan**
Written to be handed to Claude Code one sprint at a time.

---

## How to use this document

Each sprint is self-contained. Give Claude Code **one sprint at a time**, not the whole document — the context stays tight and the acceptance criteria stay enforceable.

Suggested opening prompt for each sprint:

> Here is Sprint N of the Neurovox project. Implement exactly what is described. Do not add features beyond the scope listed. Write the unit tests specified in Acceptance. Show me the acceptance test output when done.

**Standing rules for every sprint:**

- Java 17, Maven, package root `com.neurovox`
- No library may compute an acoustic feature. FFT via JTransforms is permitted; pitch detection, jitter, shimmer, HNR, and MFCC must be hand-written.
- Every DSP class gets JUnit tests against synthetic signals with known answers before it touches real audio.
- Every public method that implements a formula carries a Javadoc comment stating the formula and its source. The team must be able to explain each one.
- No `System.out.println` in library code — use `java.util.logging`.

---

## Repository layout

```
neurovox/
├── pom.xml
├── README.md
├── src/main/java/com/neurovox/
│   ├── Main.java
│   ├── model/          Patient, VoiceSample, SpeechFeatures,
│   │                   ScreeningResult, TaskType
│   ├── audio/          PcmDecoder, Resampler, AudioProcessor, AudioFrame
│   ├── dsp/            FftProvider, WindowFunction, PitchDetector,
│   │                   AutocorrelationPitchDetector, YinPitchDetector,
│   │                   PitchContour
│   ├── features/       JitterExtractor, ShimmerExtractor, HnrExtractor,
│   │                   F0StatsExtractor, MfccExtractor, FeatureVector,
│   │                   FeatureExtractionService
│   ├── ml/             ModelCoefficients, ModelLoader,
│   │                   LogisticRegressionModel, PredictionService,
│   │                   FeatureReportBuilder
│   ├── db/             Database, PatientRepository,
│   │                   VoiceSampleRepository, ResultRepository
│   └── server/         AppServer, StaticFileHandler, RecordHandler,
│                       ResultsHandler, HistoryHandler, Json
├── src/main/resources/
│   ├── static/         index.html, record.html, results.html,
│   │                   style.css, recorder.js, results.js
│   └── model/          coefficients.txt, scaling.txt
├── src/test/java/com/neurovox/
├── training/           Python — offline only, not shipped
│   ├── train_classifier.py
│   ├── train_updrs.py
│   ├── export_model.py
│   └── data/
└── docs/
```

---

## Sprint 0 — Scaffolding

**Goal:** A project that builds and runs.

**Tasks**
1. `pom.xml` — Java 17, `maven-compiler-plugin`, `maven-shade-plugin` for a runnable fat JAR
2. Dependencies: `com.github.wendykierp:JTransforms:3.1`, `org.xerial:sqlite-jdbc:3.45.1.0`, `com.google.code.gson:gson:2.10.1`, `org.junit.jupiter:junit-jupiter:5.10.2` (test scope)
3. Full package skeleton
4. `Main.java` prints a startup banner and exits
5. `.gitignore` — `target/`, `*.db`, `recordings/`, `__pycache__/`

**Acceptance**
- `mvn clean package` succeeds
- `java -jar target/neurovox.jar` prints the banner

**Gotcha:** JTransforms' Maven coordinates changed across versions. If `com.github.wendykierp` fails to resolve, fall back to `com.github.rwl:jtransforms:2.4.0` and adjust the import.

---

## Sprint 1 — Domain model and persistence

**Goal:** Data can be stored and retrieved.

**Model classes** (plain Java, private fields, constructors, getters, `equals`/`hashCode`, `toString`)

```java
Patient           id, name, age, sex, createdAt
VoiceSample       id, patientId, taskType, sampleRate, durationSeconds,
                  filePath, recordedAt
SpeechFeatures    id, voiceSampleId, and one field per acoustic feature
                  (see the 22-feature list in Sprint 6)
ScreeningResult   id, patientId, sessionId, probability, elevated,
                  modelVersion, computedAt
TaskType          enum: SUSTAINED_VOWEL, DIADOCHOKINETIC,
                  READING_PASSAGE, FREE_SPEECH
```

**Database**
- `Database.java` — connection management, `initSchema()` creating tables if absent
- Four tables mirroring the model classes, with foreign keys
- `PatientRepository`, `VoiceSampleRepository`, `ResultRepository` — each with `save`, `findById`, `findAll`, and where relevant `findByPatientId`
- Use `PreparedStatement` throughout. No string concatenation in SQL.

**Acceptance**
- JUnit test: create a patient, save, retrieve by ID, assert field equality
- JUnit test: save a `VoiceSample` linked to a patient, retrieve by patient ID
- Schema initialisation is idempotent — running twice does not error

**Note:** Store timestamps as ISO-8601 strings. SQLite has no native date type and this avoids a class of confusing bugs.

---

## Sprint 2 — HTTP server and static hosting

**Goal:** A browser can load a page served by Java.

**Tasks**
1. `AppServer` — wraps `com.sun.net.httpserver.HttpServer`, binds to port 8080, registers handlers, `start()` and `stop()`
2. `StaticFileHandler` — serves from `src/main/resources/static/`, correct MIME types for `.html`, `.css`, `.js`, path-traversal guard rejecting `..`
3. `Json` — thin Gson wrapper: `toJson(Object)`, `fromJson(String, Class)`, and a helper writing a JSON response with the correct `Content-Type` and status
4. A placeholder `index.html` with the Neurovox title
5. `Main` starts the server and logs the URL

**Acceptance**
- `java -jar target/neurovox.jar`, then `http://localhost:8080` shows the page
- Requesting `/../../etc/passwd` returns 400, not a file
- A test handler returning a POJO produces valid JSON with `Content-Type: application/json`

**Gotcha:** `HttpServer` uses a single-threaded executor by default. Set `server.setExecutor(Executors.newFixedThreadPool(4))` or concurrent requests will queue. Audio processing takes seconds — this matters.

---

## Sprint 3 — Browser capture and PCM upload

**Goal:** Audio recorded in the browser arrives in Java as raw samples.

**Frontend** (`recorder.js`)
1. `navigator.mediaDevices.getUserMedia({ audio: true })`
2. `AudioContext` with an `AudioWorkletNode` (not the deprecated `ScriptProcessorNode`) to collect Float32 samples
3. Task-driven UI: display the instruction, count down, record for the task's fixed duration, stop automatically
4. Concatenate the Float32 buffers, convert to a `Float32Array`, POST the raw bytes as `application/octet-stream` to `/api/record`
5. Send `taskType`, `sampleRate`, and `patientId` as query parameters — the body is audio only

**Backend**
1. `RecordHandler` — reads the request body into a byte array, parses query parameters
2. `PcmDecoder` — converts little-endian Float32 bytes to `double[]`. Also provide a WAV file reader for the calibration study in Sprint 10.
3. Write the raw samples to `recordings/{patientId}/{sampleId}.pcm`, save a `VoiceSample` row, return the sample ID as JSON

**Acceptance**
- Recording a 5-second vowel in the browser produces a `.pcm` file on disk
- File size ≈ `sampleRate × 5 × 4` bytes
- A `VoiceSample` row exists with the correct duration and task type
- Decoding the file and computing RMS gives a plausible non-zero value

**Gotchas**
- Microphone access requires `localhost` or HTTPS. Opening the HTML as a `file://` URL will fail silently — always go through the server.
- `AudioContext.sampleRate` is whatever the hardware provides, commonly 48000 but not guaranteed. Read it at runtime; never assume.
- Byte order: JavaScript `Float32Array` is little-endian on all common platforms. Java's `ByteBuffer` defaults to **big-endian**. Set `ByteOrder.LITTLE_ENDIAN` explicitly or every sample will be garbage.

---

## Sprint 4 — Preprocessing

**Goal:** Raw samples become clean, framed, windowed analysis data.

**`Resampler`** — arbitrary rate to 16000 Hz

Correct approach, in order:
1. Design a windowed-sinc low-pass FIR at the new Nyquist (8000 Hz), Hamming-windowed, order 64
2. Convolve the signal with it
3. Decimate

Skipping the low-pass and simply taking every third sample produces aliasing that will corrupt every downstream feature. Do not do it.

For non-integer ratios, use linear interpolation after filtering. Document the choice.

**`AudioProcessor`**

```java
double[] normalize(double[] x)          // peak-normalise to ±1.0
double[] preEmphasis(double[] x, double alpha)   // y[n] = x[n] - a*x[n-1], a = 0.97
double[] removeDcOffset(double[] x)     // subtract the mean
List<AudioFrame> frame(double[] x, int frameSize, int hopSize)
boolean isSilent(double[] frame, double threshold)   // RMS gate
```

Frame size 512 samples (32 ms at 16 kHz), hop 256 (50% overlap).

**`WindowFunction`** — `hamming(int n)` and `hann(int n)`, returning coefficient arrays.

> Hamming: `w[n] = 0.54 − 0.46·cos(2πn / (N−1))`

**Acceptance**
- Resampling a 1000 Hz sine from 48 kHz to 16 kHz preserves the frequency (verify via FFT peak) and introduces no aliasing artefacts
- Resampling a 10000 Hz sine from 48 kHz to 16 kHz yields **attenuation**, not a mirrored ghost tone at 6 kHz — this is the test that proves anti-aliasing works
- Pre-emphasis on a constant-DC signal yields ≈0
- Framing a 16000-sample signal at 512/256 yields 62 frames
- Window coefficients sum to the known Hamming value

---

## Sprint 5 — Pitch detection

**Goal:** An F0 contour and per-cycle period measurements, by two independent methods.

**Interface**

```java
public interface PitchDetector {
    PitchContour detect(double[] signal, int sampleRate);
    String getName();
}
```

`PitchContour` holds: `double[] f0PerFrame`, `boolean[] voiced`, `double[] periodsSeconds` (the consecutive glottal periods — **this is what the jitter and shimmer extractors consume**), and `double[] peakAmplitudes` (per cycle, for shimmer).

**`AutocorrelationPitchDetector`**

For each frame:
1. Window it
2. `r(τ) = Σₙ x[n]·x[n+τ]` for τ in the lag range corresponding to 50–500 Hz (at 16 kHz: lags 32 to 320)
3. Find the maximum peak in that range
4. Parabolic interpolation around the peak for sub-sample precision
5. `F0 = sampleRate / τ_peak`
6. Mark unvoiced if the normalised peak falls below 0.3

**`YinPitchDetector`**

1. Difference function: `d(τ) = Σₙ (x[n] − x[n+τ])²`
2. Cumulative mean normalised difference:
   `d'(0) = 1`, and for τ > 0: `d'(τ) = d(τ) / [ (1/τ) · Σⱼ₌₁..τ d(j) ]`
3. Absolute threshold: take the **first** τ where `d'(τ) < 0.1`, not the global minimum. If none, take the global minimum.
4. Parabolic interpolation
5. `F0 = sampleRate / τ`

**Extracting periods for jitter/shimmer**

The per-frame F0 contour is not sufficient. Jitter needs *consecutive cycle durations*. Locate glottal pulses by finding successive peaks in the waveform within a window guided by the frame's F0 estimate, then take the intervals between them as `periodsSeconds` and their heights as `peakAmplitudes`.

**Acceptance**
- Both detectors on a synthetic 200 Hz sine at 16 kHz return F0 = 200 ± 1 Hz
- Both on a 200 Hz sawtooth (harmonically rich, closer to a real voice) return 200 Hz, not 400
- Silence is marked unvoiced by both
- A real sustained vowel yields a stable contour with fewer than 5% unvoiced frames in the middle 80%
- Timing comparison logged for both detectors — feeds the comparison study

**Gotcha:** Autocorrelation's classic failure is octave error, reporting 400 Hz for a 200 Hz signal. If the sawtooth test fails this way, the lag search range is wrong or the peak-picking is selecting a harmonic.

---

## Sprint 6 — Acoustic features

**Goal:** A complete, correctly scaled feature vector.

**Every formula below must match Praat's published definitions.** The classifier is trained on Praat-derived values; a different definition produces silent failure.

### `JitterExtractor`

Given periods `T₁..Tₙ`:

| Feature | Formula |
|---|---|
| Jitter (local), % | `[ (1/(N−1)) · Σᵢ₌₁..ₙ₋₁ \|Tᵢ − Tᵢ₊₁\| ] / [ (1/N) · Σ Tᵢ ] × 100` |
| Jitter (absolute), s | `(1/(N−1)) · Σ \|Tᵢ − Tᵢ₊₁\|` |
| RAP | `[ (1/(N−2)) · Σᵢ₌₂..ₙ₋₁ \|Tᵢ − mean(Tᵢ₋₁, Tᵢ, Tᵢ₊₁)\| ] / mean(T) ` |
| PPQ5 | Same as RAP with a 5-point moving average |
| DDP | `3 × RAP` (by definition — do not compute separately) |

### `ShimmerExtractor`

Given peak amplitudes `A₁..Aₙ`:

| Feature | Formula |
|---|---|
| Shimmer (local) | `[ (1/(N−1)) · Σ \|Aᵢ − Aᵢ₊₁\| ] / [ (1/N) · Σ Aᵢ ]` |
| Shimmer (dB) | `(1/(N−1)) · Σ \|20·log₁₀(Aᵢ₊₁ / Aᵢ)\|` |
| APQ3 | 3-point amplitude perturbation quotient |
| APQ5 | 5-point |
| APQ11 | 11-point |
| DDA | `3 × APQ3` |

### `HnrExtractor`

Per frame, from the normalised autocorrelation peak `r'(τ_max)`:

> `HNR(dB) = 10 · log₁₀( r'(τ_max) / (1 − r'(τ_max)) )`

Report the mean across voiced frames. `NHR` is the linear noise-to-harmonics ratio: `(1 − r') / r'`.

Clamp `r'` to `[0.001, 0.999]` before the logarithm, or a perfectly periodic frame produces infinity.

### `F0StatsExtractor`

Mean F0, maximum, minimum, standard deviation, and range across voiced frames.

### `MfccExtractor`

Per frame: pre-emphasis → Hamming window → FFT (JTransforms) → power spectrum → mel filterbank → log → DCT-II → keep coefficients 1–13.

- Mel scale: `m = 2595 · log₁₀(1 + f/700)`
- 26 triangular filters spanning 0–8000 Hz, equally spaced on the mel scale
- DCT-II, coefficients 1 through 13 (discard the 0th — it is overall energy)

Report the mean and standard deviation of each coefficient across frames.

### `FeatureVector`

Must expose the 22 features the UCI Oxford dataset provides, under these exact keys, in this order:

```
MDVP:Fo(Hz)      MDVP:Fhi(Hz)     MDVP:Flo(Hz)     MDVP:Jitter(%)
MDVP:Jitter(Abs) MDVP:RAP         MDVP:PPQ         Jitter:DDP
MDVP:Shimmer     MDVP:Shimmer(dB) Shimmer:APQ3     Shimmer:APQ5
MDVP:APQ         Shimmer:DDA      NHR              HNR
RPDE             D2               DFA              spread1
spread2          PPE
```

**On the last six:** RPDE, D2, DFA, spread1, spread2, and PPE are nonlinear dynamical measures. Implementing them correctly is a substantial project in itself.

**Recommended approach:** train the primary model on the **16 features we can compute correctly**, and document the omission. A model on 16 well-validated features is scientifically stronger than one on 22 where six are wrong. If time allows, add DFA (the most tractable of the six) in a later sprint.

**Acceptance**
- A perfectly periodic synthetic signal yields jitter ≈ 0 and shimmer ≈ 0
- A signal with deliberately perturbed periods yields jitter matching the injected perturbation
- HNR on a pure sine is high (> 20 dB); on white noise it is low or negative
- MFCC output is 13-dimensional with finite values
- `DDP == 3 × RAP` and `DDA == 3 × APQ3` exactly

---

## Sprint 7 — Offline training and model export

**Goal:** Trained coefficients Java can load. Python is used here only; it is not shipped.

**`training/train_classifier.py`**

1. Load `parkinsons.data` (UCI Oxford)
2. **Extract subject ID from the `name` column** — format `phon_R01_S01_1`; regex `S(\d+)`
3. Select the 16 implemented features
4. `StandardScaler` → `LogisticRegression`
5. Cross-validate with **`GroupKFold(n_splits=5)` grouped on subject ID**
6. Also run a naive `KFold` split, and print both

> Expect roughly 95%+ from the naive split and materially lower from the grouped split. **Report the grouped number.** The gap between them is itself a result worth presenting.

7. Report accuracy, precision, recall, F1, ROC-AUC, confusion matrix
8. Refit on all data, export

**`training/export_model.py`** writes two plain-text files into `src/main/resources/model/`:

`scaling.txt` — one line per feature: `featureName mean stddev`
`coefficients.txt` — one line per feature: `featureName weight`, plus a final line `__intercept__ value`

Plain text, not pickle or ONNX — it must be readable and explainable.

**`training/train_updrs.py`** — the experimental module. Linear or ridge regression on the telemonitoring dataset predicting `motor_UPDRS`, grouped on `subject#`. **Report MAE and RMSE prominently.** This model exists to demonstrate the limits of voice-based severity estimation, not to claim it works.

**Acceptance**
- Both text files generated with 16 feature lines plus intercept
- Grouped and naive metrics both printed and recorded in `docs/`
- Rerunning with a fixed random seed reproduces identical output

---

## Sprint 8 — Java inference

**Goal:** End-to-end. Record, get a screening result.

**Classes**

- `ModelLoader` — parses the two text files from the classpath into `ModelCoefficients`
- `LogisticRegressionModel`:
  - `standardise`: `xᵢ' = (xᵢ − μᵢ) / σᵢ`
  - `z = intercept + Σ wᵢ·xᵢ'`
  - `p = 1 / (1 + e^(−z))`
- `PredictionService` — orchestrates feature vector → standardise → predict → build `ScreeningResult`. Threshold at 0.5, configurable.
- `FeatureReportBuilder` — for each feature, produces the measured value, a reference range, a status (`typical` / `borderline` / `atypical`), and a one-sentence plain-language explanation

**Acceptance**
- Feed a known row from `parkinsons.data` directly into the Java model; the probability matches scikit-learn's output for that row to within 1e-6
- That test is the whole point of this sprint. If it fails, the coefficient parsing or the standardisation order is wrong.
- A recorded vowel produces a `ScreeningResult` saved to the database

---

## Sprint 9 — Results dashboard

**Goal:** A user can see and understand their results.

**API**
```
GET  /api/results/{sessionId}    full result plus feature report
GET  /api/history/{patientId}    all sessions, chronological
```

**Frontend** (`results.html`, `results.js`)
1. Screening indicator with confidence — visually clear, never alarming
2. **Persistent disclaimer:** *Neurovox is a screening tool, not a diagnosis. Consult a neurologist about any health concern.*
3. Feature report table: measurement, your value, typical range, status
4. Longitudinal chart — the user's own key features across sessions. Plain `<canvas>`, no charting library.
5. Session list with dates

**Copy guidance:** name things by what the user recognises. "Voice steadiness," with the technical term shown alongside, not "Jitter (local) %" alone. Explain what each measurement means in one sentence. Never use language that implies a diagnosis.

**Acceptance**
- Completing all four tasks produces a full results page
- Recording twice produces two points on the trend chart
- The disclaimer is present on every results view

---

## Sprint 10 — Praat calibration study

**The most important sprint in the project.** This validates every DSP claim.

**Method**
1. Record 20 sustained-vowel samples — team members and volunteers, all healthy controls
2. Save each as 16-bit PCM WAV (the `PcmDecoder` WAV path from Sprint 3)
3. Process each through Praat's built-in Voice Report; log jitter (local, RAP, PPQ5), shimmer (local, dB, APQ3, APQ5), HNR, and F0 statistics
4. Process the same files through Neurovox
5. Compute per-feature Pearson correlation and mean absolute error
6. Produce a scatter plot per feature: Praat on x, Neurovox on y

**Acceptance / interpretation**

| Correlation | Reading |
|---|---|
| r > 0.95 | Implementation is correct |
| 0.90 – 0.95 | Acceptable; note the discrepancy |
| < 0.90 | **Investigate.** Usually a definitional mismatch, not a coding bug. Re-read Praat's documentation for that measure. |

Report all results, including poor ones. A documented, honestly reported failure on one feature is worth more than a suspiciously perfect table.

---

## Sprint 11 — Pitch detector comparison

**Goal:** The empirical algorithm comparison.

1. Run both detectors over the Sprint 10 sample set
2. Compare each against Praat's F0 contour: mean absolute error in Hz, and **octave-error rate** (proportion of frames where F0 is within 5% of half or double the reference)
3. Measure execution time per second of audio, averaged over 10 runs after a JVM warm-up
4. Compute jitter using each detector's periods; report how much the choice of detector shifts the final feature values

**Deliverable:** a table plus two or three plots for the report and the deck. This is the analytical core of the project — the finding that pitch tracking choice propagates measurably into clinical features is the result worth presenting.

**Gotcha:** benchmark after warm-up. The JIT compiler makes the first few iterations misleadingly slow.

---

## Sprint 12 — Hardening and documentation

1. Input validation on every handler — reject malformed PCM, absurd durations, missing parameters
2. Signal-quality gate: reject recordings that are too quiet, clipped, or excessively noisy, with a clear message explaining how to re-record
3. Graceful error responses — the interface states what went wrong and how to fix it, never a stack trace
4. Delete-my-data function
5. `README.md`: prerequisites, build, run, and how to retrain
6. Javadoc on all public APIs
7. `docs/`: calibration report, model evaluation, pitch comparison
8. Demo script — the exact click path for the presentation, rehearsed

---

## Dependency order

```
Sprint 0
  └─ Sprint 1 (model, db)
       ├─ Sprint 2 (server) ─── Sprint 3 (capture)
       │                              └─ Sprint 4 (preprocessing)
       │                                   └─ Sprint 5 (pitch)
       │                                        └─ Sprint 6 (features)
       │                                             ├─ Sprint 7 (training)
       │                                             │    └─ Sprint 8 (inference)
       │                                             │         └─ Sprint 9 (dashboard)
       │                                             ├─ Sprint 10 (calibration)
       │                                             └─ Sprint 11 (comparison)
       └────────────────────────────────────── Sprint 12 (hardening)
```

**Minimum viable submission:** Sprints 0–9.
**Sprints 10 and 11 are what elevate it** from a working application to a project with findings.

Sprint 10 depends only on Sprint 6, so it can run in parallel with 7–9. Given that its outcome determines whether the features are trustworthy at all, **start it as soon as Sprint 6 completes.**

---

## Common failure modes

| Symptom | Likely cause |
|---|---|
| Every audio sample is garbage or near-zero | Byte order — Java `ByteBuffer` defaults to big-endian, JavaScript writes little-endian |
| F0 consistently doubled | Autocorrelation locking onto a harmonic; check the lag range |
| Jitter is absurdly high on a clean recording | Periods are coming from frame-level F0 rather than actual glottal pulse intervals |
| HNR returns infinity | `r'` not clamped below 1.0 before the logarithm |
| Java probability disagrees with scikit-learn | Standardisation applied in the wrong order, or feature ordering mismatched between the coefficient file and the vector |
| Model accuracy above 95% | Data leakage — verify `GroupKFold` is grouped on subject, not recording |
| Microphone permission never prompts | Page opened via `file://` rather than `http://localhost` |
| High-frequency ghost tones after resampling | Anti-alias filter missing or its cutoff is wrong |
