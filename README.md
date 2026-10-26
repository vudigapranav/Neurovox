# Neurovox

A local web application, written for the Java Programming course end project by **Pranav, Bhanuteja and Md. Ali**.
Neurovox guides a person through four short voice tasks in the browser, then measures the recordings with signal
processing written in Java on the same computer: pitch, cycle-to-cycle timing (jitter), cycle loudness (shimmer),
voice-to-noise balance (HNR/NHR), spectral shape (MFCC) and speech timing. Results and history stay on the computer.

> **Neurovox is a research prototype, not a medical device.** It does not diagnose Parkinson’s disease or any other
> condition, does not estimate a stage, and gives no treatment advice. An offline research model was trained and
> evaluated, but the validation does not support applying it to live recordings, so the app does not show it
> (see [docs/VALIDATION.md](docs/VALIDATION.md)).

**Exam version:** a short console version (one `Main` + packages `audio` and `analysis` (four short files), no libraries) written to
be reproduced by hand in the internal exam is in [`exam-version/`](exam-version/README.md).
Run it with `javac Main.java audio/*.java analysis/*.java && java Main`.

**Status (2 Oct 2026):** all planned sprints are implemented. 174 Java tests and 31 frontend tests pass, and the
end-to-end browser check passes. The things that remain unproven are listed under [Known limitations](#known-limitations).

---

## 1. What you need

| | Required? | Check with |
|---|---|---|
| **JDK 17 or newer** (Temurin, Oracle or any OpenJDK) | yes | `java -version` |
| A current **Chrome, Edge, Firefox or Safari** | yes | |
| A **microphone** (built-in or headset) | yes, to record | |
| Maven | no — the Maven Wrapper (`./mvnw`) downloads the pinned Maven 3.9.16 and checks its SHA-256 | |
| Node.js 20+ | only for the frontend tests and the browser check | `node --version` |
| Python 3.10+ | only to retrain the model or rerun the validation studies | `python3 --version` |
| Google Chrome | only for the automated browser check | |

Internet access is needed once, for Maven to download dependencies. After that Neurovox runs offline.

## 2. Build and run

```bash
./mvnw clean package
```

This compiles, runs every Java test and builds `target/neurovox.jar` (about 16 MB; it contains everything). On
Windows use `mvnw.cmd clean package`. To build without running the tests: `./mvnw clean package -DskipTests`.

```bash
java -jar target/neurovox.jar
```

The terminal shows `Neurovox is running at http://localhost:8080/ (local only). Press Ctrl+C to stop.`
Open **http://localhost:8080** in your browser. Keep the terminal open while you use the app.

Options:

```bash
java -jar target/neurovox.jar --port=9090                # another port
java -jar target/neurovox.jar --data-dir=/path/to/data   # where the database and recordings are kept
java -jar target/neurovox.jar --help
```

The same settings can come from `NEUROVOX_PORT` and `NEUROVOX_DATA_DIR`. Stop the server with **Ctrl+C**.

## 3. Using it: the exact journey

The website explains every step itself (landing page → **How to use**, and a step indicator at the top of the
recorder). In short:

1. **Home page → “Start a guided session”.**
2. **About** — what is measured and the non-diagnostic limit. Tick the box → **Continue to room setup**.
3. **Room** — quiet room, microphone 20–30 cm away, same microphone every time → **Continue to microphone check**.
4. **Microphone** — **Allow microphone**, accept the browser prompt, say “aah”: the level bar reaches the green zone
   and the orb moves → **Continue to the voice tasks**.
5. **Four tasks**, each with its instructions on screen:
   sustained “aah” (5 s), “pa-ta-ka” (6 s), reading passage (20 s), free speech (30 s).
   **Start recording** → 3-second countdown → recording (the orb reacts to your voice). **Stop recording** becomes
   available after the task’s minimum time; otherwise the timer stops it. **Review this recording**: **Play**,
   **Record again** or **Save this take**. Java checks each saved take; a take that is too quiet, clipped, too short or
   without voice is not stored, and the page says what to change. **Back** returns to the previous step.
6. **Analysis** — after the fourth take, Java resamples, tracks pitch, finds every vocal cycle in the steady vowel and
   computes the measurements (a few seconds).
7. **Report** — each value with a plain name, its technical name and a one-sentence explanation; recording quality;
   descriptive measures of the other three tasks; the spectral shape; and why the research model is not applied.
   No “normal ranges” are shown, because Neurovox has none that are validated.
8. **History** — every session, a trend chart of any of the 16 vowel measurements, **Continue recording** for an
   unfinished session, **Delete** one session, or **Delete all local data**.

An unfinished session can be continued later from History (or `record.html?session=<id>`). Leaving the page during
a take cancels only that take.

## 4. Microphone troubleshooting

| What you see | What to do |
|---|---|
| “Microphone access is blocked” | **Chrome/Edge:** click the icon left of the address bar → Site settings → Microphone → Allow, then **Try again**. **Safari:** Safari → Settings → Websites → Microphone → localhost → Allow. **Firefox:** click the microphone icon in the address bar and remove the block. |
| Still blocked on macOS | System Settings → Privacy & Security → Microphone → turn on your browser, then restart the browser. |
| Still blocked on Windows | Settings → Privacy & security → Microphone → turn on “Let desktop apps access your microphone”. |
| Linux: no microphone / wrong one | Choose the input device in your desktop’s sound settings (PulseAudio/PipeWire), then reload the page. |
| “No microphone was found” | Plug one in or enable the built-in one, then **Try again**. |
| “The microphone is busy” | Close the other app using it (video calls), then **Try again**. |
| “only allows the microphone on http://localhost” | Open `http://localhost:8080`, not the HTML file and not the computer’s network address. |
| Level bar stays at “Waiting for sound…” | Wrong input device selected, or the microphone is muted in system settings. |
| “This take can’t be used … too quiet / too loud / no voice” | Follow the advice shown (distance, loudness, input volume) and record again. |
| “The Neurovox server is not responding” | The terminal running the JAR was closed or stopped. Start it again and press **Try again**; your take is kept in the page. |

## 5. Your local data

Everything lives in the data folder (default `./data`, relative to where you start the JAR; ignored by Git):

```
data/
  neurovox.db                                         SQLite database (plus -wal / -shm files while running)
  recordings/session-<id>/<task>-<utc time>-<rand>.f32le   raw takes: mono 32-bit float, little-endian
```

| Table | Holds |
|---|---|
| `profiles` | one local label (no name, age, sex or contact details are ever collected) |
| `recording_sessions` | one guided sitting; `complete` once all four tasks are saved |
| `voice_samples` | one row per task: file path, real sample rate, sample count, size, SHA-256 |
| `speech_features` | one row per measurement: value **or** the reason it could not be measured, unit, extractor version |
| `sample_analyses` | quality-gate result and analysis facts (steady region, cycle count) per recording |
| `screening_results` | research-model output — only ever written if the validation gate says “go” (it currently does not) |

- The server listens on 127.0.0.1 only; no other device can reach it. Nothing is uploaded anywhere.
- On macOS and Linux the data folder and database are readable by your user account only.
- **Delete one session:** its report or History → **Delete**. **Delete everything:** History → **Delete all local
  data** (type `delete all` to confirm). Or stop the server and delete the `data` folder.
- **Export a take for Praat:** report → **Download vowel WAV** (16-bit WAV at the original sample rate).
- To look inside: `sqlite3 data/neurovox.db ".tables"`. Schema upgrades run automatically at start-up (`PRAGMA user_version`).

## 6. Tests

| Command | What it runs |
|---|---|
| `./mvnw test` | 174 Java tests: WAV/PCM, resampling and alias rejection, framing, windows, quality gate, both pitch detectors, pulses, every jitter/shimmer/HNR/MFCC formula on signals with known answers, Java-vs-scikit-learn model agreement (to 1e-9), database, migrations, HTTP API end to end, security guards |
| `./mvnw test -Pfrontend-tests` | the above plus 31 frontend tests (`node --test src/test/js/`) |
| `node --experimental-websocket scripts/recorder-check.mjs out http://localhost:8096/` | the whole guided journey in headless Chrome with a synthetic voice as the microphone, plus every failure path and phone layouts (start the JAR with `--port=8096 --data-dir=target/e2e-data` first; needs `validation/synthetic/demo-voice.wav`, see §7) |
| `node --experimental-websocket scripts/visual-check.mjs out http://localhost:8080/` | the particle orb’s states and layouts (Sprint 0 check) |

## 7. Retraining the model and rerunning the validation

Python is used offline only; the app never runs Python.

```bash
python3 -m venv .venv
.venv/bin/pip install -r training/requirements.txt
.venv/bin/python training/train_model.py                 # grouped CV, exports src/main/resources/model/research-model.json
./mvnw -q package -DskipTests                            # the validation tools are inside the JAR
.venv/bin/python validation/make_synthetic_vowels.py     # 40 synthetic vowels with known cycle lengths
.venv/bin/python validation/praat_compare.py             # Java vs Praat on the same files and regions
java -cp target/neurovox.jar com.neurovox.tools.PitchStudy --refs validation/synthetic \
     --truth validation/synthetic/truth.csv --out docs/results/pitch_study validation/synthetic/syn*.wav
.venv/bin/python validation/summarise_pitch_study.py
```

Outputs go to `docs/results/`. Training is deterministic: rerunning gives byte-identical files. After retraining,
run `./mvnw test`: `ModelAgreementTest` checks Java against the new scikit-learn reference predictions. To compare
real voices with Praat, put WAV files in `validation/recordings/` (never committed) and rerun `praat_compare.py`.

## 8. Common failures

| Symptom | Cause and fix |
|---|---|
| `Neurovox needs JDK 17 or newer` / `UnsupportedClassVersionError` | Install JDK 17+ and make sure `java -version` shows it (on Windows check `JAVA_HOME`). |
| `./mvnw: Permission denied` (macOS/Linux) | `chmod +x mvnw` |
| `Port 8080 is already in use` | Another program (or a second Neurovox) uses it: stop it, or run with `--port=9090`. |
| Page loads but buttons do nothing | Open it through the server (`http://localhost:8080`), not as a file; JavaScript must be enabled. |
| `Could not open the data folder` | The `--data-dir` path is not writable; choose another folder. |
| Windows: `mvnw` not recognised | Use `mvnw.cmd`, from the project folder. |
| Linux: build fails downloading Maven | Check the proxy/firewall; the wrapper needs one download from repo.maven.apache.org. |
| Browser check captures silence | Chrome flag behaviour; `scripts/lib/browser.mjs` already passes `--disable-features=AudioServiceOutOfProcess`. Update Chrome. |

## 9. Project layout

```
pom.xml                    Maven build: Java 17; Gson, sqlite-jdbc, JTransforms (FFT only); JUnit
src/main/java/com/neurovox/
  Main, AppOptions         start-up and command-line options
  server/                  JDK HttpServer, routes, security guards, JSON
  session/, db/, storage/  sessions, SQLite repositories and migrations, recording files
  audio/                   PCM and WAV, resampling, framing, windows
  dsp/                     autocorrelation and YIN pitch detectors, glottal pulse extraction
  features/                jitter, shimmer, HNR/NHR, F0 statistics, MFCC, the fixed 16-feature order
  analysis/                quality gate, per-recording analysis, session reports and history
  ml/                      research model loading, logistic-regression inference, the validation gate
  tools/                   AnalyzeWav and PitchStudy command-line tools for the validation studies
src/main/resources/static/ the website (HTML, CSS, JavaScript; particle orb in raw WebGL)
src/main/resources/model/  research-model.json (exported) and research-gate.json (the go/no-go decision)
training/                  offline Python training (dataset included, CC BY 4.0)
validation/                synthetic vowel generator, Praat comparison, pitch-study summary
scripts/                   headless-Chrome checks
docs/                      architecture, Java walkthrough, validation, demo script, team hand-off, results
```

## 10. Further documents

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — package/class map and request-to-result data flow
- [docs/JAVA_WALKTHROUGH.md](docs/JAVA_WALKTHROUGH.md) — every important class and formula in plain language
- [docs/VALIDATION.md](docs/VALIDATION.md) — model results, Praat comparison, pitch study, limitations, sources
- [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) — 3-minute demo click path and backup path
- [docs/TEAM_HANDOFF.md](docs/TEAM_HANDOFF.md) — setup for each teammate and how to collaborate without sharing recordings
- [docs/PRESENTATION_INPUTS.md](docs/PRESENTATION_INPUTS.md) — verified numbers, diagram, code excerpts and screenshots for the slides
- [PROJECT_CHECKLIST.md](PROJECT_CHECKLIST.md) — sprint-by-sprint record with evidence

## Known limitations

- Java agrees with Praat on synthetic vowels only; **no human recordings** have been compared yet.
- Agreement with Praat does not show agreement with the dataset’s MDVP-named columns.
- The research model’s subject-grouped accuracy (69.2 %) is below the majority-class baseline (75.4 %).
- The descriptive speech measures (pauses, syllable rate) are simple heuristics, not validated instruments.
- Owner-only file permissions are applied on macOS/Linux only. Windows and Linux were not run by the author;
  the JAR bundles SQLite for both.
