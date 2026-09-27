# Neurovox — Project Checklist

Progress record for the build. Each sprint is marked done only when its acceptance checks pass.
The three planning documents (`neurovox-01…03`) are reference material and are not edited.

| Sprint | Scope | Status |
|---|---|---|
| 0 | Particle website and visual system | **Done** — 2026-09-26 |
| 1 | Runnable Java foundation (Maven, HttpServer, static hosting) | **Done** — 2026-09-26 |
| 2 | Sessions and SQLite | **Done** — 2026-09-26 |
| 3 | Four-task recorder and API | Not started |
| 4 | Audio preprocessing and quality gate | Not started |
| 5 | Pitch detection and vocal pulses | Not started |
| 6 | Acoustic features (16 UCI columns + MFCC) | Not started |
| 7 | Feature calibration against Praat, go/no-go | Not started |
| 8 | Offline classifier training and evaluation | Not started |
| 9 | Java inference and scientific gate | Not started |
| 10 | Results and history frontend | Not started |
| 11 | Autocorrelation vs YIN study | Not started |
| 12 | Hardening, documentation, hand-in | Not started |

---

## Sprint 0 — Particle website and visual system

### Delivered
- [x] Video background removed: `<video>` element, `app.js` (video-driven), and `assets/orb-reference.mp4`.
      The original recording remains at `~/Downloads/Recording_2026-09-10_142850.mp4` (byte-identical copy was deleted from the project).
- [x] Procedural particle orb in raw WebGL 1 — no Three.js or other dependency, nothing loaded from a CDN.
  - Dense Fibonacci-sphere shell (64 000 desktop / 16 000 mobile) plus inner volume and great-circle trace filaments with travelling pulses.
  - Three noise octaves for a slowly breathing, fluid shape; fresnel rim and key light for 3D depth.
- [x] Ambient particle field with depth, drift, pointer displacement and tap ripple.
- [x] Interaction: pointer tilt + surface attraction; tap/click sends a travelling wave; keyboard (focus orb → arrows tilt, Enter/Space wave); touch.
- [x] Microphone preview: Web Audio RMS level + 12 log-spaced bands reshape the particles (spectrum ribbons, noise amplitudes, speed). Nothing recorded, stored or sent.
- [x] `AudioReactor` is source-agnostic so the Sprint 3 recorder can drive the same orb during a take (`window.neurovox.orb.setAudioSource`).
- [x] States: microphone denied / missing / busy / insecure context, WebGL context loss + restore, WebGL unavailable (static Canvas 2D orb), `prefers-reduced-motion`, hidden tab.
- [x] Mobile: particle budget reduced to about a quarter of desktop, pixel ratio capped at 1.5 (2 on desktop), adaptive resolution drop when frames are slow; orb scrolls away with the hero so it never sits behind text.
- [x] Page: header, hero, session preview (four tasks), method (with periodicity illustration), boundaries (non-diagnostic), footer.

### Acceptance evidence (2026-09-26)
- `node --test src/test/js/` → 18/18 pass (audio feature maths, geometry, layout, static contract: no video, no CDN, no record/store/upload APIs in the preview code, shader precision).
- `node --experimental-websocket scripts/visual-check.mjs <out>` (headless Chrome, ANGLE Metal / Apple M4):
  - WebGL mode, 68 000 particles; 16.7 ms/frame at 1440×900, including at DPR 2.
  - No console errors; no network requests while listening (synthetic voice and Chrome fake microphone).
  - Chrome fake microphone through real `getUserMedia`: peak level ≈ 0.8–0.9.
  - Context loss → still image + message → restore → WebGL again.
  - `?nowebgl` → static fallback with message. Reduced motion → animation runs at 0.18× speed; waves and drift are damped.
  - 390×844, 320×640 and 820×1180: no horizontal overflow; 390×844 primary action ends at 709 px (above the fold).
- `python3 scripts/orb_shape_metric.py <out>` silhouette of the orb, quiet vs loud synthetic voice:
  equivalent radius 1.07 → 1.21 (× layout radius), solidity 0.967 → 0.922 (lower means a spikier outline). The shape changes, not just the brightness.

### Known limitations
- Automated checks use a synthetic Web Audio voice and Chrome's fake microphone device. A real voice through a real microphone has not been exercised automatically. Pranav should do one manual check in a desktop browser and on a phone.
- The mic-denied UI state was tested by forcing `getUserMedia` to reject with `NotAllowedError`, not through a real browser permission prompt.
- Frame timings come from headless Chrome on an M4 and are not measurements from a phone. On slow devices the adaptive resolution drop is the safeguard.
- The "Start recording" button is intentionally disabled until Sprint 3 provides the recorder.
- ~~Until Sprint 1, the page is previewed with `python3 -m http.server`.~~ Since Sprint 1, the Java server serves it (`.claude/launch.json` runs the JAR).

---

## Sprint 1 — Runnable Java foundation

### Delivered
- [x] `pom.xml`: Java 17 (`maven.compiler.release=17`), Gson 2.14.0, JUnit Jupiter 6.1.3, pinned stable plugins, enforcer (JDK ≥ 17), shaded runnable JAR `target/neurovox.jar` with `Main-Class` and `Implementation-Version`.
- [x] Maven Wrapper (`./mvnw`, `mvnw.cmd`) pinned to Maven 3.9.16 with `distributionSha256Sum`.
- [x] Package `com.neurovox`: `Main`, and `server/` with `AppServer`, `ServerConfig`, `RequestGuardFilter`, `SafeHandler`, `StaticFileHandler`, `StaticPath`, `MimeTypes`, `RequestBodies`, `ApiHandlers`, `Json`, `HttpError`, `SecurityHeaders`.
- [x] JDK `HttpServer` bound to 127.0.0.1 only; serves the particle site from classpath `static/`.
- [x] JSON errors with one shape; unexpected exceptions become a generic 500 with no internals in the response (details only in the local log).
- [x] Correct Content-Type per file; unknown types, directories and class files are never served; `nosniff`.
- [x] Request limits: URI length (414), declared body size (413, checked before reading), `RequestBodies.readLimited` for chunked bodies in later upload endpoints.
- [x] Path-traversal protection: character whitelist on the raw path, no `.`/`..`/hidden or empty segments.
- [x] Also: Host-header check against DNS rebinding (403), Content-Security-Policy and related headers, GET/HEAD only on static routes (405 + `Allow`), `--port` / `NEUROVOX_PORT`, clear messages for a bad port or a port already in use.
- [x] README with build, run, test and layout instructions.

### Acceptance evidence (2026-09-26)
- `./mvnw clean test package` → **BUILD SUCCESS, 59 tests, 0 failures** (also run with Maven removed from `PATH`, so the wrapper alone is enough).
- `./mvnw test -Pfrontend-tests` → 59 Java + 18 frontend tests pass.
- `java -jar target/neurovox.jar` → `lsof` shows it listening on `127.0.0.1:8080` only. curl checks:
  health 200 JSON; `/` 200 HTML; `/js/orb.js` 200 `text/javascript`; missing 404 JSON; `/../../pom.xml` and `/%2e%2e/pom.xml` 400 JSON;
  `Host: evil.example` 403; 17 MB POST 413; DELETE 405 with `Allow: GET, HEAD`.
- Headless Chrome visual check against `http://localhost:8080/` with the CSP active: WebGL orb, voice (synthetic + Chrome fake mic),
  mic denied, context loss, WebGL fallback, reduced motion and mobile layouts all pass; **no console errors**; only same-origin requests.
- `--help` exits 0; `--port=abc` exits 2 with a message; a second server on the same port exits 1 with "Port … is already in use".

### Known limitations
- A malformed `Content-Length` header (for example `abc`) is rejected by the JDK server itself with a plain-text 400 before Neurovox code runs, so that one error is not JSON.
- When a client declares an oversized body, the 413 is sent immediately, but the JDK then drains up to 64 KB of the unread body before closing the connection.
- Local Maven for this machine lives in `~/.local/opt/apache-maven-3.9.16` (used only to generate the wrapper). The project itself needs only `./mvnw`.

---

## Sprint 2 — Sessions and SQLite

### Delivered
- [x] Model (`com.neurovox.model`): `TaskType` (4 tasks, order, target seconds, only the sustained vowel feeds the classifier), `LocalProfile` (label only — no name/age/sex, since nothing uses them), `RecordingSession` (in progress / complete), `VoiceSample` (+ `AudioFormat.PCM_F32LE`), `SpeechFeatures` / `FeatureValue` (finite value **or** explicit unavailable reason, unit, extractor version), `ScreeningResult` (`researchScore` in [0,1], documented as not a diagnosis or clinical confidence).
- [x] SQLite via sqlite-jdbc 3.53.4.0. `Database` opens short-lived connections with `PRAGMA foreign_keys = ON` and `busy_timeout`; WAL journal; `transaction()` commits all or nothing.
- [x] `Migrations`: versioned with `PRAGMA user_version`, each step in its own `BEGIN IMMEDIATE` transaction; idempotent; refuses a database from a newer version.
- [x] Schema: 5 tables with foreign keys (`ON DELETE CASCADE`), CHECK constraints (task codes, sample-rate range, `byte_size = sample_count × 4`, value XOR reason, score range, timestamp shape, status ↔ completed time), `UNIQUE(session_id, task_type)`, and triggers that allow screening results **only** from the same session's sustained vowel.
- [x] Timestamps: UTC, fixed-width millisecond text (`2026-09-26T17:03:05.120Z`) — sorts chronologically, readable in `sqlite3`; injected `Clock` for tests.
- [x] Repositories (all prepared statements): `ProfileRepository`, `SessionRepository`, `VoiceSampleRepository`, `SpeechFeatureRepository`, `ScreeningResultRepository`.
- [x] Local files: `RecordingStore` writes `recordings/session-<id>/<task>-<utc>-<random>.f32le` atomically (temp + rename), stores relative paths, SHA-256 per file, refuses paths outside the recordings folder; owner-only permissions for created folders, files and the database file.
- [x] `SessionService`: profiles, start session, attach/replace recording (file first, row in a transaction, new file removed if the DB step fails, old file removed after commit), auto-complete after the fourth task, integrity-checked reads, delete session / profile (rows then files).
- [x] `--data-dir` / `NEUROVOX_DATA_DIR` (default `./data`, Git-ignored); `Main` opens and migrates the database at start-up.

### Acceptance evidence (2026-09-26)
- `./mvnw clean test package` → **BUILD SUCCESS, 96 tests, 0 failures**, no warnings. With `-Pfrontend-tests`, the 18 frontend tests also pass.
- Acceptance tests (`SessionServiceTest`, `RepositoryTest`, `DatabaseTest`):
  - create a session, attach four recordings (out of order, 48 kHz and 44.1 kHz) → stored in guided order, bytes identical, session `complete` at the fourth;
  - history for a profile is newest first with a stable tie-break, and excludes other profiles;
  - schema initialised repeatedly (same object, reopened, and 6 threads at once) → no error, version 1, data preserved;
  - foreign keys reject orphans; cascades clear every table; triggers block non-vowel screening results even via raw SQL.
- Mutation checks: turning foreign keys off → 4 tests fail; sorting history oldest-first → 2 tests fail. The originals were restored and the suite passes.
- Real JAR run: data folder `drwx------`, `neurovox.db` `-rw-------`, `user_version = 1`, WAL, both triggers present; restart keeps version 1.

### Known limitations
- No HTTP endpoints use the storage yet; the upload API arrives in Sprint 3.
- Audio is stored as raw float32 (`.f32le`) plus metadata in the database. WAV export for research validation is Sprint 4.
- The shaded JAR is now about 12 MB because sqlite-jdbc bundles native SQLite for all operating systems (so teammates on Windows/Linux can run it).
- On Windows, owner-only permissions are not applied; operating-system defaults are used.
