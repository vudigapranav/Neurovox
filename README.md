# Neurovox — Voice-Based Parkinson’s Screening

Java Programming (22ITC02N) course end project · CSE (IoT and CSBCT), CBIT ·
**Pranav (160125749063), Bhanuteja (160125749048), Md. Ali (160125749055)** · Faculty: Mrs. Sujatha Guptha

Neurovox measures a sustained “aaah” — pitch, jitter (cycle timing), shimmer (cycle loudness) and
harmonics-to-noise ratio — with signal processing written in Java, and says whether the voice should be checked
further.

> **Not a medical device.** Neurovox does not diagnose Parkinson’s disease, estimate a stage or give treatment
> advice. A flagged result only means “see a doctor”.

## What is in this repository

| Folder | Contents |
|---|---|
| [`exam-version/`](exam-version/README.md) | Short console program for the internal exam: `Main` + packages `audio` and `analysis` (4 files, no libraries) |
| [`web-app/`](web-app/README.md) | Full web application: Java backend, website, database, validation studies, all documentation |
| [`report/`](report/README.md) | Project report in Word and PDF, with the scripts and figures that build it |

## Run the exam version (console)

Needs only a JDK, version 11 or newer (`java -version` to check).

```bash
cd exam-version
javac Main.java audio/*.java analysis/*.java
java Main
```

On Windows you can double-click `exam-version/run.bat` instead. Type a name, then pick from the menu:

| Option | What it does |
|---|---|
| 1 | Record 5 seconds of “aaah” from the microphone (also saved as `recording.wav`) |
| 2 | Analyse a 16-bit WAV file |
| 3 / 4 | Demo with a steady / shaky built-in voice — works without a microphone |
| 5 | Compare every voice analysed in this run, steadiest first |
| 6 | Show the saved history (`history.csv`) |
| 0 | Exit |

## Run the web application

Needs JDK 17 or newer and a browser. Maven is downloaded automatically by the wrapper the first time.

```bash
cd web-app
./mvnw clean package        # Windows: mvnw.cmd clean package
java -jar target/neurovox.jar
```

Then open **http://localhost:8080** and follow the guided recording. Full instructions, options and
troubleshooting are in [`web-app/README.md`](web-app/README.md).

## Read the report

[`report/Neurovox_Project_Report.pdf`](report/Neurovox_Project_Report.pdf) (or the
[`.docx`](report/Neurovox_Project_Report.docx) to edit). Other documents — proposal, sprint plan, architecture,
validation results, demo script — are listed in [`web-app/docs/`](web-app/docs/README.md).
