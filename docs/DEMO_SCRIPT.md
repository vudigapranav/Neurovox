# Demo script (3 minutes)

The demo shows the instrument working end to end on a real voice, then the evidence. Rehearse it at least three
times on the presenting laptop.

## Before the session (10 minutes earlier)

1. Build and start with a separate demo data folder, so personal recordings are never on screen:
   ```bash
   ./mvnw -q clean package -DskipTests
   java -jar target/neurovox.jar --data-dir=demo-data
   ```
2. Open `http://localhost:8080/record.html`, complete **one full rehearsal session** with the presenter’s voice
   (so History has a previous point and the report has a “Change” column). Keep it.
3. In the browser, allow the microphone for `localhost` permanently (site settings) so no prompt appears live.
4. Set the input device and level: on the mic step the bar should sit in the green zone for a normal “aah”.
5. Open three tabs: `http://localhost:8080/` (home), the rehearsal session’s report, `docs/VALIDATION.md` (in the
   editor or as a rendered page). Close notifications; set browser zoom to 100 %; full screen.
6. Have the backup ready (below).

## Live click path (≈ 3:00)

| Time | Screen | Click / say |
|---|---|---|
| 0:00 | Home | “Neurovox measures how evenly the voice repeats each cycle — in Java, on this laptop.” Scroll to **How to use**: “The site walks a new person through these eight steps.” Click **Start a guided session**. |
| 0:20 | About | Point at the step indicator (8 steps). Tick “I understand…”, **Continue to room setup** → **Continue to microphone check**. |
| 0:35 | Microphone | **Allow microphone** (already permitted). Say “aah”: bar in the green zone, the orb moves. **Continue to the voice tasks**. |
| 0:50 | Vowel | **Start recording** → countdown → hold “aah” for 5 s (the orb reacts). **Review this recording** → **Play** (2 s) → **Save this take**. “Java just checked loudness, clipping and voicing before storing it.” |
| 1:25 | Syllables / Reading / Free speech | For each: **Start recording**, speak, **Stop recording** as soon as it is enabled (4 s / 10 s / 10 s), **Save this take**. Narrate one line per task: “descriptive only, never a classifier input.” |
| 2:05 | Analysis | “Now the Java server resamples with an anti-aliasing filter, tracks pitch every 10 ms, finds each vocal-fold cycle and computes 16 measurements.” |
| 2:15 | Report | Scroll: **Recording quality** → the 16 values with technical names (point at jitter and HNR) → **Change** vs the rehearsal session → **Research model — not applied**: “Our own validation says no-go; here is why.” |
| 2:40 | History | **View history and trends**: two points on the jitter chart. “No normal range is drawn: we have no validated reference values.” |
| 2:50 | Close | “Every number on this page agrees with Praat to within 0.15 % on our test vowels; the details are in VALIDATION.md.” |

Do **not** say: diagnosis, risk, probability of Parkinson’s, confidence, stage, normal/abnormal.

## Backup path (if the microphone or browser fails)

1. **Mic fails but the app works:** skip recording. Open History → the rehearsal session’s **Open report**, and walk
   the report and the trend exactly as above. Say: “This is the session recorded ten minutes ago.”
2. **The app does not start:** open the screenshots in `docs/screenshots/` in order (01 … 14). They come from the
   automated end-to-end run with a synthetic voice as the microphone; say so.
3. **Screen recording:** before the presentation, record the rehearsal session with the OS screen recorder
   (macOS: Shift-Cmd-5; Windows: Win-Alt-R) and keep the file on the desktop. It has to be made by the team — it is
   not in the repository.
4. **Proof the code runs without a microphone:** in a terminal, `./mvnw -q test` (≈ 1 minute) and show
   “Tests run: 174, Failures: 0”.

## Questions with the strongest answers

- *How do you know your jitter is right?* → VALIDATION §2: same file, same region as Praat; 16/16 within 0.15 %. And
  the fractional-drift bug we found and fixed (VALIDATION §1).
- *Why is there no Parkinson’s score?* → VALIDATION §5–6: grouped accuracy 69.2 % is below the 75.4 % baseline;
  the leaky split would have shown 80.5 %.
- *What did you write yourselves?* → everything acoustic: resampler, both pitch detectors, pulses, all 16 measures,
  MFCC filterbank/DCT, inference. JTransforms does only the FFT.
