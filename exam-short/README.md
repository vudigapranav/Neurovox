# Neurovox — short exam version (2 files)

The smallest version of Neurovox: **one main file + one important file**, written to be learned and
hand-written in the exam. Plain Java, no libraries, no packages.

| File | What it does |
|---|---|
| `Main.java` | Main file. Step 1: gets the features from `FeatureExtractor`. Step 2: predicts risk with logistic regression (weighted sum → sigmoid). Step 3: prints the result. |
| `FeatureExtractor.java` | Important file. `findPeriod()` finds the pitch period with autocorrelation, `pitch()` gives F0, `variation()` gives jitter (on periods) and shimmer (on amplitudes). |
| `Main_OneFile.txt` | Both classes in one file, for online compilers that allow only one file (e.g. Programiz **Java**). Paste it as `Main.java`. |
| `Neurovox_Exam_Notes.pdf` | Exam notes: aim, description (10 points), formulas, both programs, output, conclusion. |
| `Neurovox_Viva_QA.pdf` | 40 likely viva questions with short answers. |

## Run it (any JDK)

```bash
cd exam-short
javac Main.java FeatureExtractor.java
java Main
```

Output:

```
---- NEUROVOX SCREENING ----
F0      : 146.0 Hz
Jitter  : 1.49 %
Shimmer : 5.35 %
Risk    : 0.96 -> HIGH RISK - consult a neurologist
```

To show a healthy voice, change the values in `main()` to
`periods = {300, 301, 300, 301, 300}`, `amps = {0.80, 0.80, 0.79, 0.80, 0.80}`, `hnr = 25` — it prints **LOW RISK**.

> The weights in `Main.java` (4.0, 2.0, 0.5, −0.3) are example values so the demo gives a sensible answer;
> the real weights come from training on the UCI Oxford Parkinson's dataset. Not a medical device.
