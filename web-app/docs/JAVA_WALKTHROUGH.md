# Java walkthrough

A plain-language tour of the Java code, in the order a request travels through it. For each important class:
**responsibility**, **input → output**, **key method**, and **why it exists**. Formulas are given where they are
implemented; the Javadoc in each class repeats them next to the code and names the source.

---

## 1. Start-up

### [`Main`](../src/main/java/com/neurovox/Main.java)
- **Responsibility:** starts the application and keeps it running until Ctrl+C.
- **Input → output:** command-line arguments → a running server on `http://localhost:<port>/`.
- **Key method:** `main(String[] args)` — parses options, `LocalData.open(dataDir)`, creates `SessionService`,
  `AppServer.start(...)`, registers a shutdown hook that closes the server.
- **Why:** one obvious entry point; errors such as “port in use” become one clear message and an exit code.

### [`AppOptions`](../src/main/java/com/neurovox/AppOptions.java)
- `--port`/`--data-dir` (or `NEUROVOX_PORT`/`NEUROVOX_DATA_DIR`) → validated values. Exists so option parsing is
  testable without starting a server.

## 2. HTTP layer (`server`)

### [`AppServer`](../src/main/java/com/neurovox/server/AppServer.java)
- **Responsibility:** creates the JDK `com.sun.net.httpserver.HttpServer` bound to **127.0.0.1 only**, registers
  every route, and gives it a fixed pool of 8 worker threads (the default is one thread, so a seconds-long analysis
  would block everything).
- **Input → output:** `ServerConfig`, `SessionService`, `AnalysisService` → a started server.
- **Key method:** `start(ServerConfig, SessionService, AnalysisService)` — the whole routing table in eight lines:
  `/api/health`, `/api/tasks`, `/api/sessions`, `/api/model`, `/api/data`, `/api/` (JSON 404), `/` (website).
- **Why:** routing is explicit and explainable — no framework, no annotations.

### [`RequestGuardFilter`](../src/main/java/com/neurovox/server/RequestGuardFilter.java)
- Runs before every handler. Rejects a `Host` that is not localhost (DNS rebinding), any non-GET request whose
  `Origin` or `Sec-Fetch-Site` shows it came from another website (cross-site request forgery), over-long URLs (414)
  and declared bodies over the limit (413).
- **Why:** a local server is still reachable by any web page the person visits; this keeps other sites out.

### [`SafeHandler`](../src/main/java/com/neurovox/server/SafeHandler.java)
- Base class of every handler: adds security headers, turns an `HttpError` into its JSON response and any unexpected
  exception into a generic 500 (details only in the local log). **Why:** one place guarantees no stack trace reaches
  the browser.

### [`SessionApi`](../src/main/java/com/neurovox/server/SessionApi.java)
- **Responsibility:** the session endpoints (list, create, get, delete, upload, WAV download, analyse, report).
- **Input → output:** HTTP request → JSON (via `JsonViews`).
- **Key method:** `uploadRecording(...)` — checks `Content-Type`, parses `sampleRate`, enforces a per-task size limit
  *before* reading (`maxSeconds × sampleRate × 4` bytes), decodes the PCM, runs the **quality gate**, and only then
  stores the take; a failing take gets `422 audio_quality` with the message and the recovery advice.
- **Why:** all input validation sits at the boundary, so the services below can trust their inputs.

### [`JsonViews`](../src/main/java/com/neurovox/server/JsonViews.java), [`Json`](../src/main/java/com/neurovox/server/Json.java), [`HttpError`](../src/main/java/com/neurovox/server/HttpError.java)
- Hand-written JSON shapes (stable field names; internal file paths never exposed), the Gson wrapper, and the one
  error shape `{"error": {status, code, message}}`.

## 3. Sessions, files and the database

### [`SessionService`](../src/main/java/com/neurovox/session/SessionService.java)
- **Responsibility:** profiles, sessions and recordings, keeping files and rows consistent.
- **Key method:** `attachRecording(sessionId, task, sampleRate, bytes)` — write the file first (temp + atomic rename),
  then insert the row in a transaction (replacing an earlier take of the same task); if the database step fails, the
  new file is deleted; the old file is deleted only after the commit. The session becomes `complete` at the fourth task.
- Also: `readRecording` (checks SHA-256), `deleteSession`, `deleteAllData`.
- **Why:** a crash at any moment cannot leave a database row without its file or a file without its row.

### [`Database`](../src/main/java/com/neurovox/db/Database.java) and [`Migrations`](../src/main/java/com/neurovox/db/Migrations.java)
- `Database.open(file)` creates/opens the SQLite file, switches it to WAL, and runs pending migrations.
  `transaction(work)` commits all or nothing. Every connection sets `PRAGMA foreign_keys = ON`.
- `Migrations` keeps the schema version in `PRAGMA user_version`; version 1 is the original schema (five tables,
  CHECK constraints, a trigger that allows research results only from the session’s sustained vowel), version 2 adds
  `sample_analyses`. **Why:** a teammate’s existing database is upgraded in place, never rebuilt.

### Repositories
One class per table, every query a `PreparedStatement` with bound parameters (no SQL built from strings):
`ProfileRepository`, `SessionRepository`, `VoiceSampleRepository`, `SpeechFeatureRepository`,
`SampleAnalysisRepository`, `ScreeningResultRepository`.

### [`RecordingStore`](../src/main/java/com/neurovox/storage/RecordingStore.java)
- Writes `recordings/session-<id>/<task>-<time>-<random>.f32le`, stores relative paths, computes SHA-256, refuses any
  path outside the recordings folder. **Why:** the voice files are the most sensitive data, so they get the most care.

## 4. Audio processing (`audio`)

### [`PcmDecoder`](../src/main/java/com/neurovox/audio/PcmDecoder.java)
- Bytes → `float[]`, reading **little-endian** explicitly (`ByteBuffer` defaults to big-endian, which silently gives
  garbage). Rejects partial samples, NaN/∞, and values beyond ±4.

### [`WavCodec`](../src/main/java/com/neurovox/audio/WavCodec.java)
- Reads PCM 8/16/24/32-bit and float WAV (any channels, downmixed), skips unknown chunks; writes 16-bit PCM.
  16-bit sample `v` becomes `v / 32768`. Used for Praat export and the validation tools.

### [`Resampler`](../src/main/java/com/neurovox/audio/Resampler.java) — anti-aliased resampling
- **Responsibility:** change the sample rate without aliasing (e.g. 48 kHz → 16 kHz).
- **Formula (windowed-sinc interpolation):** output sample *m* sits at input position `t = m·fsIn/fsOut`;
  `y[m] = Σ_k x[k] · g · sinc(g·(t−k)) · w((t−k)/H)`, with `g = 0.95 · min(1, fsOut/fsIn)` (cutoff 7.6 kHz for
  16 kHz output), `w` a Blackman window, `H = 32/g` samples. Source: J. O. Smith, *Digital Audio Resampling*, CCRMA.
- **Key method:** `resample(clip, targetRate)`; kernels are cached per fractional phase when the rates share a factor.
- **Why:** taking every third sample would fold a 10 kHz sound to a fake 6 kHz tone; the test proves > 60 dB rejection.

### [`SignalOps`](../src/main/java/com/neurovox/audio/SignalOps.java), [`WindowFunction`](../src/main/java/com/neurovox/audio/WindowFunction.java), [`Framer`](../src/main/java/com/neurovox/audio/Framer.java)
- DC removal `y = x − mean(x)`; pre-emphasis `y[n] = x[n] − 0.97·x[n−1]`; RMS and dBFS `20·log10(rms)`.
- Hamming `0.54 − 0.46·cos(2πn/(N−1))`, Hann `0.5 − 0.5·cos(2πn/(N−1))`.
- Frames: `count = 1 + floor((N − frame)/hop)` (16 000 samples, 512/256 → 61).

## 5. Pitch and pulses (`dsp`)

### [`AutocorrelationPitchDetector`](../src/main/java/com/neurovox/dsp/AutocorrelationPitchDetector.java) (Boersma 1993; Praat “To Pitch (ac)”)
- **Input → output:** `AudioClip` → `PitchContour` (F0 or 0 per 10 ms frame, plus the periodicity strength r).
- **Per frame:** subtract the mean, Hann window (3 periods of 75 Hz = 40 ms), autocorrelation
  `r_a(τ) = Σ a[n]·a[n+τ]`, then divide by the window’s own autocorrelation:
  `r(τ) = [r_a(τ)/r_a(0)] / [r_w(τ)/r_w(0)]`. Peaks between 1/600 s and 1/75 s become candidates, refined between
  samples by sinc interpolation; F0 = fs/τ. One “unvoiced” candidate gets strength
  `0.45 + max(0, 2 − (localPeak/globalPeak)/(0.03/1.45))`.
- **Across frames (Viterbi):** choose one candidate per frame maximising Σ strength − Σ cost, where a voiced
  candidate scores `r − 0.01·log2(600/F0)`, a voiced↔unvoiced switch costs 0.14, an F0 jump costs
  `0.35·|log2(F0₁/F0₂)|`. **Why:** this dynamic programming is what prevents isolated octave errors.

### [`YinPitchDetector`](../src/main/java/com/neurovox/dsp/YinPitchDetector.java) (de Cheveigné & Kawahara 2002)
- Difference `d(τ) = Σ (x[j] − x[j+τ])²`; cumulative mean normalised `d′(τ) = d(τ)·τ / Σ_{j≤τ} d(j)`; the **first**
  τ with `d′ < 0.10` (then the bottom of that dip), parabolic interpolation, F0 = fs/τ; unvoiced if `d′ ≥ 0.35`.
- **Why:** the second method for the comparison study; it is 16× faster but less robust (see VALIDATION §3).

### [`PulseExtractor`](../src/main/java/com/neurovox/dsp/PulseExtractor.java) (Praat “To PointProcess (periodic, cc)”)
- **Input → output:** native-rate signal + pitch contour → `PulseTrain` (one time per vocal-fold cycle).
- **Key method:** `extract(clip, pitch)` — for each voiced stretch, start at the largest sample near its middle, then
  step one period at a time: in `[t + 0.8T, t + 1.25T]` find where one period of waveform best matches the period
  around `t` (normalised cross-correlation `ρ = Σ x₁x₂ / √(Σx₁² Σx₂²)`), refine with a parabola, and add the
  **shift** to the exact previous time (keeping the fraction of a sample — the bug fixed in validation).
- **Why:** jitter needs the length of every single cycle; a 10 ms F0 contour is smoothed and would hide it.

### [`PulseTrain`](../src/main/java/com/neurovox/dsp/PulseTrain.java)
- `isPeriod(i, …)`: an interval counts as a period if it lies in [0.8/600, 1.25/75] s and is not more than 1.3× different
  from **both** neighbours (Praat’s rule; removes intervals spanning a missed pulse).

## 6. Features (`features`)

### [`UciFeature`](../src/main/java/com/neurovox/features/UciFeature.java)
- The 16 dataset columns in one **fixed order**, with units and plain names. Jitter/shimmer are fractions (the
  dataset’s convention). The six nonlinear columns are listed as not computed. Used by the database, API, model loader
  and Python script alike.

### [`JitterMeasures`](../src/main/java/com/neurovox/features/JitterMeasures.java)
`local = mean|Tᵢ − Tᵢ₊₁| / T̄`, `absolute = mean|Tᵢ − Tᵢ₊₁|` (s), `RAP = mean|Tᵢ − mean(Tᵢ₋₁,Tᵢ,Tᵢ₊₁)| / T̄`,
`PPQ5` = same with 5 periods, `DDP = 3·RAP` (identical by algebra). Unavailable with fewer than 2/3/5 valid terms.

### [`ShimmerMeasures`](../src/main/java/com/neurovox/features/ShimmerMeasures.java)
Cycle amplitude `A = √(Σ(x·w)²/Σw²)`, Hann window from `t − 0.2·T₁` to `t + 0.2·T₂`. Then
`local = mean|Aᵢ − Aᵢ₊₁| / Ā`, `dB = mean|20·log10(Aᵢ₊₁/Aᵢ)|`, `APQ3/5/11` = deviation from 3/5/11-point averages / Ā,
`DDA = 3·APQ3`; pairs with an amplitude ratio above 1.6 are skipped (Praat).

### [`HarmonicityMeasures`](../src/main/java/com/neurovox/features/HarmonicityMeasures.java)
With r the periodicity of a voiced frame: `HNR = 10·log10(r/(1−r))` dB and `NHR = (1−r)/r`, averaged over the voiced
frames of the steady region; r is clamped to [1e-6, 1−1e-6] so a perfect sine gives a finite number.

### [`F0Statistics`](../src/main/java/com/neurovox/features/F0Statistics.java) and [`MfccExtractor`](../src/main/java/com/neurovox/features/MfccExtractor.java)
- Mean, max, min, SD (Hz and semitones `12·log2(f/100)`), 5–95 % range.
- MFCC per 32 ms frame: pre-emphasis → Hamming → FFT (JTransforms; the only library numerics) → `|X|²/N` →
  26 triangular mel filters, `mel(f) = 2595·log10(1 + f/700)` → ln → DCT-II `c_j = Σ L_m cos(πj(m+½)/M)`, j = 1…13;
  mean and SD over frames within 40 dB of the loudest.

## 7. Analysis and reporting (`analysis`)

### [`RecordingAnalyzer`](../src/main/java/com/neurovox/analysis/RecordingAnalyzer.java)
- **Responsibility:** one take → `RecordingAnalysis` (quality report, ordered features, facts).
- **Key methods:** `prepare` (16 kHz copy for MFCC/speech; native-rate copy and pitch for the vowel),
  `check` (quality gate only, used at upload), `analyze` (everything).
- Vowel: `SteadyRegion` (longest voiced stretch minus up to 0.25 s at each end, ≥ 1 s) → pulses → jitter, shimmer,
  HNR/NHR, F0 → 16 values in fixed order (or “unavailable + reason”) → extras → MFCC.
  Speech tasks: voicing share, pitch variability (semitones), loudness variability, pauses ≥ 0.25 s, syllable rate
  (pa-ta-ka) → MFCC. **Why:** one class owns the whole pipeline, so the app, tests and validation tools use identical code.

### [`QualityGate`](../src/main/java/com/neurovox/analysis/QualityGate.java)
- Checks sample rate (≥ 16 kHz), duration (task minimum), loudness (95th-percentile level < −50 dBFS fails),
  clipping (> 1 % of samples at full scale fails), voice present (vowel ≥ 50 % voiced), steady vowel ≥ 1 s, background
  noise (speech tasks; warning). Every failing check carries a message **and** a recovery instruction.

### [`AnalysisService`](../src/main/java/com/neurovox/analysis/AnalysisService.java)
- `analyze(sessionId)`: reads each file (SHA-256 checked), runs the analyzer, stores every feature and analysis fact
  in **one transaction**, evaluates the research indicator from the vowel only. `report(sessionId)` rebuilds the
  stored report; `history(profileId)` lists sessions with vowel values for trend charts.

## 8. Inference (`ml`)

### [`ResearchModel`](../src/main/java/com/neurovox/ml/ResearchModel.java)
`zᵢ = (xᵢ − meanᵢ)/scaleᵢ`, `s = intercept + Σ coefᵢ·zᵢ`, `p = 1/(1 + e^(−s))` (computed without overflow).
The constructor rejects a feature order that differs from `UciFeature.ORDER`, a zero scale or a non-finite value.

### [`ModelLoader`](../src/main/java/com/neurovox/ml/ModelLoader.java), [`CompatibilityGate`](../src/main/java/com/neurovox/ml/CompatibilityGate.java), [`ResearchIndicator`](../src/main/java/com/neurovox/ml/ResearchIndicator.java)
- Load `research-model.json` and `research-gate.json` from the JAR. A missing gate file means no-go.
- `ResearchIndicator.evaluate(vowelFeatures)`: gate must say go → model must exist → all 16 values must be
  available → output and “above/below threshold”. Today it returns “not shown” with the gate’s reason.
- **Why:** the decision whether to show a classifier is a scientific one, recorded as data and enforced in one place.

## 9. Tools

- [`AnalyzeWav`](../src/main/java/com/neurovox/tools/AnalyzeWav.java): WAV files → JSON of region, quality and
  features, using `RecordingAnalyzer` exactly as the app does. Used by `validation/praat_compare.py`.
- [`PitchStudy`](../src/main/java/com/neurovox/tools/PitchStudy.java): runs both detectors under three conditions and
  writes per-file accuracy, octave errors, timing (after JIT warm-up) and the jitter each detector leads to.

## Questions to expect in the viva (and where the answer is)

| Question | Short answer | Code |
|---|---|---|
| Why is little-endian set explicitly? | `ByteBuffer` is big-endian by default; the browser sends little-endian | `PcmDecoder.decodeF32le` |
| How do you avoid aliasing? | Windowed-sinc low-pass at 0.95 × new Nyquist, applied while interpolating | `Resampler.resample` |
| Why not jitter from the pitch contour? | Frames average several cycles; jitter needs each cycle | `PulseExtractor` |
| Why is DDP exactly 3 × RAP? | `|(Tᵢ₊₁−Tᵢ)−(Tᵢ−Tᵢ₋₁)| = 3·|Tᵢ − mean₃|` | `JitterMeasures.compute` |
| What if a value can’t be measured? | Stored as unavailable + reason; never NaN, never guessed | `FeatureValue`, `RecordingAnalyzer.value` |
| How do you know it matches Praat? | Same files, same regions, 16/16 measures within 0.15 % | `tools.AnalyzeWav`, `validation/praat_compare.py` |
| Why isn’t the classifier shown? | Gate is no-go (synthetic-only evidence, MDVP scale unknown, weak model) | `research-gate.json`, `ResearchIndicator` |
| How is leakage avoided? | Subject-grouped CV; asserted no overlap; naive split shown only for contrast | `training/train_model.py` |
| Does Java match scikit-learn? | Yes, < 1e-9 on 21 rows | `ModelAgreementTest` |
