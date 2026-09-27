# Neurovox

A local web application for the Java Programming Course End Project. Neurovox guides a person through
four short voice tasks, analyses the recordings in Java, stores sessions on the same computer and
explains the measurements in plain language.

> **Neurovox is a research prototype, not a medical device.** It does not diagnose Parkinson's disease
> or any other condition, does not estimate disease stage and does not give treatment advice.

**Status:** Sprints 0–2 are complete: the particle website, the Java server, and local storage for
sessions and recordings (SQLite + files). The recorder and analysis arrive in later sprints. See [`PROJECT_CHECKLIST.md`](PROJECT_CHECKLIST.md).

## Requirements

- **JDK 17 or newer.** Check with `java -version`.
- Nothing else. The Maven Wrapper (`./mvnw`) downloads the pinned Maven version on first use and checks its SHA-256.
- Optional: **Node.js 20+** for the frontend unit tests and the headless browser check.

## Build, test and run

```bash
./mvnw clean test package        # compile, run all Java tests, build target/neurovox.jar
java -jar target/neurovox.jar    # start the server
```

Then open **http://localhost:8080**.

On Windows, use `mvnw.cmd` instead of `./mvnw`.

Options:

```bash
java -jar target/neurovox.jar --port=9090               # another port
java -jar target/neurovox.jar --data-dir=/path/to/data  # where the database and recordings live
NEUROVOX_PORT=9090 NEUROVOX_DATA_DIR=/path/to/data java -jar target/neurovox.jar
java -jar target/neurovox.jar --help
```

Stop the server with **Ctrl+C**. If the port is taken, Neurovox says so and exits; choose another with `--port`.

## Privacy and safety by design

- The server listens on **127.0.0.1 only**, so other devices on the network cannot reach it.
- Requests must be addressed to `localhost`, `127.0.0.1` or `[::1]`. This blocks DNS-rebinding attacks from other websites.
- A Content-Security-Policy stops the page from loading code from, or sending data to, any other site.
- The landing-page microphone preview only animates the orb. It does not record, store or upload audio.
- Voice recordings are stored on this computer, in the data folder, and never sent to an external service.
  Folders the app creates, and the database file, are readable by your user account only (macOS/Linux).
- A profile is only a label you choose. Neurovox stores no name, age, sex or contact details.

## Local data

Everything lives in the data folder (default `./data`, ignored by Git):

```
data/
  neurovox.db                                    SQLite database
  recordings/session-<id>/<task>-<time>-<rand>.f32le   raw audio: mono float32, little-endian
```

| Table | Holds |
|---|---|
| `profiles` | id, label, created time |
| `recording_sessions` | one guided sitting; `complete` once all four tasks are recorded |
| `voice_samples` | one row per task per session: file path, real sample rate, sample count, size, SHA-256 |
| `speech_features` | one row per measurement: value **or** an explicit "unavailable" reason, with unit and extractor version |
| `screening_results` | research-model output; a trigger allows only the session's sustained-vowel sample |

- Foreign keys cascade: deleting a profile or session removes its samples, features and results; the service then deletes the audio files.
- Timestamps are UTC text in a fixed format (`2026-09-26T17:03:05.120Z`) and sort chronologically.
- The schema version is kept in `PRAGMA user_version`; migrations run automatically at start-up and are safe to run repeatedly.
- To inspect the database: `sqlite3 data/neurovox.db ".schema"`.
- To delete all local data, stop the server and delete the data folder.

## Tests

| Command | What it runs |
|---|---|
| `./mvnw test` | Java unit and integration tests (JUnit): server, static files, path safety, request limits, JSON errors, schema, repositories, sessions and recording files |
| `./mvnw test -Pfrontend-tests` | The above plus the frontend unit tests (`node --test src/test/js/`) |
| `node --experimental-websocket scripts/visual-check.mjs out http://localhost:8080/` | Headless Chrome check of every orb and page state against the running server, with screenshots in `out/` |
| `python3 scripts/orb_shape_metric.py out` | Measures how much the orb's outline changes between quiet and loud voice in those screenshots (needs OpenCV) |

## Project layout

```
pom.xml                         Maven build (Java 17; Gson; sqlite-jdbc; JUnit)
mvnw, mvnw.cmd, .mvn/           Maven Wrapper
src/main/java/com/neurovox/
  Main.java                     entry point: opens the data folder, starts the server
  AppOptions.java               --port and --data-dir (and environment variables)
  model/                        TaskType, LocalProfile, RecordingSession, VoiceSample,
                                SpeechFeatures, FeatureValue, ScreeningResult
  db/                           Database (connections, transactions), Migrations (schema),
                                Timestamps, one repository per table
  storage/                      LocalData (data folder), RecordingStore (audio files)
  session/                      SessionService: profiles, sessions, attach/replace/delete recordings
  server/
    AppServer.java              JDK HttpServer on 127.0.0.1 and its routes
    ServerConfig.java           port, body/URI limits, threads
    RequestGuardFilter.java     Host check, URI length (414), body size (413)
    SafeHandler.java            base handler: security headers, errors → JSON
    StaticFileHandler.java      serves the frontend from the classpath
    StaticPath.java             request path → resource name; blocks traversal
    MimeTypes.java              allowed file types and their Content-Type
    RequestBodies.java          size-limited body reading for upload endpoints
    ApiHandlers.java            /api/health and JSON 404 for unknown /api paths
    Json.java, HttpError.java   JSON responses and error shape
    SecurityHeaders.java        CSP and other response headers
src/main/resources/static/      frontend: index.html, style.css, js/ (particle orb, mic preview)
src/test/java/                  JUnit tests
src/test/js/                    frontend unit tests (Node)
scripts/                        visual check and orb shape metric
```

## HTTP API (so far)

| Method | Path | Response |
|---|---|---|
| GET | `/api/health` | `{"status":"ok","name":"neurovox","version":"0.1.0"}` |
| GET | `/`, `/style.css`, `/js/*.js` | frontend files |

Every error is JSON with the same shape:

```json
{"error": {"status": 404, "code": "not_found", "message": "There is no page at /x."}}
```

Codes so far: `invalid_path` (400), `bad_content_length` (400), `forbidden_host` (403), `not_found` (404),
`no_such_endpoint` (404), `method_not_allowed` (405), `payload_too_large` (413), `uri_too_long` (414), `internal_error` (500).
One exception: a malformed `Content-Length` header is rejected by the JDK server itself with a plain 400,
before Neurovox code runs.

## Planning documents

`neurovox-01-project-proposal.md`, `neurovox-02-build-sprints.md` and `neurovox-03-presentation-brief.md`
are the original planning references.
