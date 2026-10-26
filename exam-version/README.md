# Neurovox — exam version (console, 4 files)

A short, hand-writable version of Neurovox for the Java Programming (22ITC02N, R22A) internal exam.
Same idea as the full app — screen a sustained "aah" for voice irregularities linked to Parkinson's — but in
**plain Java with no libraries**, one `Main` class and **two packages**, with no file longer than about 120 lines.

> Not a medical device. It does not diagnose; it only flags a voice that should be checked by a doctor.

## Files

```
exam-version/
├── Main.java                    menu, thread, collections, file history     (default package, 119 lines)
├── audio/VoiceSource.java       package audio — where the voice comes from (118 lines)
└── analysis/
    ├── PitchTracker.java        package analysis — finds every vocal cycle   (69 lines)
    └── VoiceReport.java         package analysis — jitter, shimmer, HNR, result (89 lines)
```

## Run it (any JDK 11 or newer)

```bash
cd exam-version
javac Main.java audio/*.java analysis/*.java
java Main
```

Windows: double-click `run.bat`. Mac/Linux: `./run.sh`.

Menu: `1` microphone (5 s "aah", saved as `recording.wav`) · `2` analyse a WAV file · `3` demo steady voice ·
`4` demo shaky voice · `5` compare this session · `6` saved history (`history.csv`) · `0` exit.
Options 3 and 4 need no microphone, so they always work in a lab.

## What it measures

| Measure | How (`PitchTracker` finds the cycles, `VoiceReport` computes) | Flag if |
|---|---|---|
| Pitch F0 | autocorrelation finds the rough period; then each cycle is matched to the next (cross-correlation) | — |
| Jitter | average change in cycle length from one cycle to the next ÷ mean cycle length | > 1.04 % |
| Shimmer | average change in cycle peak height ÷ mean peak height | > 3.81 % |
| HNR | how alike neighbouring cycles are (r): 10·log10(r / (1 − r)) | < 20 dB |

Two or more flags → **FURTHER CHECK SUGGESTED**, otherwise **NO CONCERN INDICATED**. The limits are the MDVP
"threshold of pathology" values for jitter and shimmer, and the usual 20 dB for HNR.

Checked against 40 synthetic vowels with known answers (`validation/make_synthetic_vowels.py`):
F0 within 0.22 Hz (r = 1.000), jitter within 0.22 %-points (r = 0.92), all 40 analysed, about 0.3 s each.

## Syllabus map (22ITC02N)

| Unit | Topic | Where |
|---|---|---|
| I | classes, objects, constructors, access modifiers | all four files |
| I | constructor overloading, `this(...)` | `Microphone()`, `Demo()` |
| I | `static`, `final` | `RATE`, limits, `public static final class WavFile` |
| I | arrays, Strings, `StringTokenizer`, `Scanner` | `PitchTracker`, `showHistory()`, `main()` |
| II | abstract class and abstract methods | `VoiceSource` |
| II | inheritance, `super`, method overriding | `Microphone`, `WavFile`, `Demo` |
| II | preventing inheritance | `final class WavFile` |
| II | `Object` class (`toString`) | `VoiceSource`, `VoiceReport` |
| II | dynamic method dispatch | `voice.load()` in `Main.screen()` |
| II | inner (static nested) classes | `VoiceException`, `Microphone`, `WavFile`, `Demo` |
| II | interfaces | `VoiceReport implements Runnable, Comparable` |
| II | packages, import, CLASSPATH | `audio`, `analysis` |
| I | default (package-private) access | `PitchTracker.periods`, `peaks`, `similarity` read by `VoiceReport` |
| III | user-defined exception, `throw`, `throws` | `VoiceException` |
| III | `try`, multiple `catch`, multi-catch, `finally`, try-with-resources | `run()`, `load()`, `screen()` |
| III | threads, `start`, `join`, synchronization | `Main.screen()`, `finish()` / `isDone()` |
| III | generics | `List<Double>`, `Map<String, Double>`, `mean(List<Double>)`, `printAll<T>()` |
| IV | `ArrayList`, `LinkedHashMap`, `Iterator`, `Comparable` | `PitchTracker`, `VoiceReport`, `compare()` |
| IV | Java 8: lambda, streams | `session.stream().filter(v -> ...)` |
| V | `FileWriter`, `BufferedWriter`, `FileReader`, `BufferedReader` | `saveHistory()`, `showHistory()` |

## Writing it in the exam

Must write (about 250 lines): `Main.main()` and `screen()`, `VoiceSource` with `VoiceException` and `Demo`,
`PitchTracker` and `VoiceReport`. Can be left out if time is short, and the program still compiles if you also
remove their menu lines: the `Microphone` class, `WavFile`, `compare()`, `saveHistory()` / `showHistory()`.
