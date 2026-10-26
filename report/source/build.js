// Builds the Neurovox project report (.docx). Usage: node build.js [pages.json]
// pages.json (from find_pages.py) supplies real page numbers for the contents lists.
const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, ImageRun, Table, TableRow, TableCell, WidthType, AlignmentType,
  HeadingLevel, BorderStyle, ShadingType, PageBreak, Footer, Header, PageNumber, NumberFormat, LevelFormat,
  PositionalTab, PositionalTabAlignment, PositionalTabRelativeTo, PositionalTabLeader, TabStopType, VerticalAlign,
  TableLayoutType, LeaderType,
} = require("docx");

const DIR = __dirname;
const FIG = path.join(DIR, "fig");
const SIZES = JSON.parse(fs.readFileSync(path.join(FIG, "sizes.json")));
const PAGES = process.argv[2] && fs.existsSync(process.argv[2]) ? JSON.parse(fs.readFileSync(process.argv[2])) : {};
const CODE = path.join(DIR, "..", "..", "exam-version");
const STATS = JSON.parse(fs.readFileSync(path.join(DIR, "stats.json")));
const OUT = path.join(DIR, "..");

const FONT = "Times New Roman", MONO = "Consolas";
const W = 8666;                 // text width in DXA (A4, 1.25" left, 1" right)
const ACCENT = "4B2A7B";

// ------------------------------------------------------------------ inline markup: **bold**, *italic*, `code`
function runs(text, base = {}) {
  const out = [];
  const re = /(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`)/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(new TextRun({ text: text.slice(last, m.index), ...base }));
    const t = m[0];
    if (t.startsWith("**")) out.push(new TextRun({ text: t.slice(2, -2), bold: true, ...base }));
    else if (t.startsWith("`")) out.push(new TextRun({ text: t.slice(1, -1), font: MONO, size: 20, ...base }));
    else out.push(new TextRun({ text: t.slice(1, -1), italics: true, ...base }));
    last = m.index + t.length;
  }
  if (last < text.length) out.push(new TextRun({ text: text.slice(last), ...base }));
  return out;
}

// ------------------------------------------------------------------ content model
const body = [];                // paragraphs/tables of the main matter
const toc = [];                 // {level, text, key}
const lof = [], lot = [];       // figure / table captions
let ch = 0, sec = 0, sub = 0, figN = 0, tabN = 0;

const P = (t, o = {}) => body.push(new Paragraph({ children: runs(t), alignment: AlignmentType.JUSTIFIED,
  spacing: { after: 120, line: 360 }, ...o }));
const Pn = (t) => P(t, { indent: { firstLine: 0 } });

function chapter(title) {
  ch++; sec = 0; figN = 0; tabN = 0;
  const key = `ch${ch}`;
  toc.push({ level: 0, text: `${ch}. ${title.toUpperCase()}`, key, find: `CHAPTER ${ch}` });
  body.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 600, after: 120 }, pageBreakBefore: true,
    children: [new TextRun({ text: `CHAPTER ${ch}`, bold: true, size: 28, font: FONT, color: ACCENT })] }));
  body.push(new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, spacing: { after: 360 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: ACCENT, space: 8 } },
    children: [new TextRun({ text: title.toUpperCase() })] }));
}
function section(title) {
  sec++; sub = 0;
  const num = `${ch}.${sec}`;
  toc.push({ level: 1, text: `${num}  ${title}`, key: `s${num}`, find: `${num} ${title}` });
  body.push(new Paragraph({ heading: HeadingLevel.HEADING_2, spacing: { before: 280, after: 140 }, keepNext: true,
    children: [new TextRun({ text: `${num} ${title}` })] }));
}
function subsection(title) {
  sub++;
  const num = `${ch}.${sec}.${sub}`;
  body.push(new Paragraph({ heading: HeadingLevel.HEADING_3, spacing: { before: 200, after: 100 }, keepNext: true,
    children: [new TextRun({ text: `${num} ${title}` })] }));
}
function unnumbered(title, findText) {     // References, Appendices
  toc.push({ level: 0, text: title.toUpperCase(), key: title, find: findText || title.toUpperCase() });
  body.push(new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, spacing: { before: 400, after: 360 }, pageBreakBefore: true,
    border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: ACCENT, space: 8 } },
    children: [new TextRun({ text: title.toUpperCase() })] }));
}

let numberingRef = 0;
const bulletConfigs = [];
function bullets(items, numbered = false) {
  const ref = `list${numberingRef++}`;
  bulletConfigs.push({ reference: ref, levels: [{ level: 0, format: numbered ? LevelFormat.DECIMAL : LevelFormat.BULLET,
    text: numbered ? "%1." : "•", alignment: AlignmentType.LEFT,
    style: { paragraph: { indent: { left: 640, hanging: 320 } } } }] });
  for (const it of items) body.push(new Paragraph({ numbering: { reference: ref, level: 0 }, alignment: AlignmentType.JUSTIFIED,
    spacing: { after: 80, line: 336 }, children: runs(it) }));
}

function figure(file, caption, widthIn = 6.0) {
  figN++;
  const id = `${ch}.${figN}`;
  const [pw, ph] = SIZES[file];
  const wpx = Math.round(widthIn * 96), hpx = Math.round(wpx * ph / pw);
  body.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 160, after: 60 }, keepNext: true,
    children: [new ImageRun({ type: file.endsWith(".jpg") ? "jpg" : "png", data: fs.readFileSync(path.join(FIG, file)),
      transformation: { width: wpx, height: hpx }, altText: { title: caption, description: caption, name: file } })] }));
  body.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 240 },
    children: [new TextRun({ text: `Figure ${id}: `, bold: true, size: 20 }), new TextRun({ text: caption, size: 20, italics: true })] }));
  lof.push({ text: `Figure ${id}: ${caption}`, find: `Figure ${id}:` });
  return id;
}

const border = { style: BorderStyle.SINGLE, size: 4, color: "8C8C99" };
const borders = { top: border, bottom: border, left: border, right: border };
function table(caption, header, rows, widths, opts = {}) {
  tabN++;
  const id = `${ch}.${tabN}`;
  const total = widths.reduce((a, b) => a + b, 0);
  const scale = W / total;
  const cw = widths.map((w) => Math.round(w * scale));
  cw[cw.length - 1] += W - cw.reduce((a, b) => a + b, 0);
  body.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 200, after: 80 }, keepNext: true,
    children: [new TextRun({ text: `Table ${id}: `, bold: true, size: 20 }), new TextRun({ text: caption, size: 20, italics: true })] }));
  lot.push({ text: `Table ${id}: ${caption}`, find: `Table ${id}:` });
  const mk = (cells, head, shade) => new TableRow({ tableHeader: head, cantSplit: true, children: cells.map((c, i) => new TableCell({
    borders, width: { size: cw[i], type: WidthType.DXA }, verticalAlign: VerticalAlign.CENTER,
    shading: head ? { fill: "E9E2F3", type: ShadingType.CLEAR, color: "auto" } : shade ? { fill: "F7F5FA", type: ShadingType.CLEAR, color: "auto" } : undefined,
    margins: { top: 50, bottom: 50, left: 90, right: 90 },
    children: String(c).split("\n").map((line) => new Paragraph({ spacing: { after: 0, line: 264 },
      alignment: opts.align && opts.align[i] === "c" ? AlignmentType.CENTER : AlignmentType.LEFT,
      children: runs(line, { size: opts.size || 20, bold: head || undefined }) })) })) });
  body.push(new Table({ width: { size: W, type: WidthType.DXA }, columnWidths: cw, layout: TableLayoutType.FIXED,
    rows: [mk(header, true), ...rows.map((r, k) => mk(r, false, k % 2 === 1))] }));
  body.push(new Paragraph({ spacing: { after: 160 }, children: [] }));
  return id;
}

function equation(text, label) {
  body.push(new Paragraph({ spacing: { before: 120, after: 160 },
    tabStops: [{ type: TabStopType.CENTER, position: Math.round(W / 2) }, { type: TabStopType.RIGHT, position: W }],
    children: [new TextRun({ text: "\t" + text, italics: true, font: "Cambria Math", size: 23 }),
      ...(label ? [new TextRun({ text: `\t(${label})`, size: 22 })] : [])] }));
}

function code(lines, size = 17) {
  lines.forEach((l, i) => body.push(new Paragraph({ spacing: { after: 0, line: 240 }, keepLines: true,
    shading: { fill: "F4F2F7", type: ShadingType.CLEAR, color: "auto" },
    border: i === 0 ? { top: { style: BorderStyle.SINGLE, size: 2, color: "C9C2D6", space: 2 } } :
      i === lines.length - 1 ? { bottom: { style: BorderStyle.SINGLE, size: 2, color: "C9C2D6", space: 2 } } : undefined,
    children: [new TextRun({ text: l.replace(/\t/g, "    ") || " ", font: MONO, size })] })));
  body.push(new Paragraph({ spacing: { after: 120 }, children: [] }));
}
function listing(file, title) {
  const lines = fs.readFileSync(path.join(CODE, file), "utf8").replace(/\n$/, "").split("\n");
  body.push(new Paragraph({ heading: HeadingLevel.HEADING_2, spacing: { before: 240, after: 120 }, keepNext: true,
    children: [new TextRun({ text: title })] }));
  toc.push({ level: 1, text: title, key: title, find: title });
  const w = String(lines.length).length;
  code(lines.map((l, i) => `${String(i + 1).padStart(w, " ")}  ${l}`), 15);
}

// ================================================================== CONTENT
// ---------------- Chapter 1
chapter("Introduction");
section("Background");
P("Parkinson’s disease (PD) is a progressive disorder of the nervous system in which the dopamine-producing neurons of the substantia nigra are gradually lost. It is best known for its motor signs — tremor at rest, slowness of movement (bradykinesia), rigidity and unstable posture — but it also affects sleep, mood, smell and speech. The World Health Organization estimates that more than 8.5 million people were living with Parkinson’s disease in 2019, and that disability and deaths due to PD are increasing faster than for any other neurological disorder [1].");
P("There is no blood test or routine scan that confirms Parkinson’s disease in a living patient. Diagnosis is clinical: a neurologist examines the patient, takes a history and rules out conditions that look similar, using criteria such as those of the International Parkinson and Movement Disorder Society [2]. By the time the classic tremor appears, a large share of the relevant neurons has already been lost, so any cheap, non-invasive signal that could prompt an earlier visit to a specialist is valuable.");
P("Speech is one such signal. The same loss of motor control that slows the hands also affects the muscles of the larynx, tongue and lips. In a classic study of 200 patients, Logemann and colleagues found voice (laryngeal) problems in 89% of them [3], and later acoustic studies showed measurable changes even in early, untreated patients [4]. Typical changes include a softer voice (hypophonia), reduced pitch variation (monopitch), a breathy or hoarse quality and irregular vibration of the vocal folds. Many of these changes are too small for a listener to notice, but they can be computed from a recording.");

section("Motivation");
P("A sustained vowel — simply saying “aaah” for a few seconds — is the standard recording used in voice research because it removes the effect of words and accent and leaves only the vibration of the vocal folds. From such a recording, three classical measures can be computed: **jitter** (how much the length of each vocal cycle varies), **shimmer** (how much the loudness of each cycle varies) and the **harmonics-to-noise ratio (HNR)** (how much of the sound is regular voice rather than breath noise). Published work has repeatedly linked these measures to Parkinson’s disease [5], [6].");
P("Most tools that compute these measures — Praat [7], the Multi-Dimensional Voice Program (MDVP) [8] — are specialist software. The motivation for Neurovox was to show that the complete chain, from raw microphone bytes to a screening indication, can be written and explained in plain Java by second-year students, without hiding the core work inside an audio library.");

section("Problem Statement");
P("Design and implement a Java application that takes a short sustained-vowel recording (live from a microphone or from a WAV file), measures the pitch, jitter, shimmer and harmonics-to-noise ratio of the voice using algorithms written in Java, compares them with published reference limits, and reports a **binary screening indication** — “further check suggested” or “no concern indicated” — together with the measurements, while keeping a history of results. The application must be small enough to be written by hand in an examination, must use packages, and must not claim to diagnose the disease.");

section("Objectives");
bullets([
  "To capture a voice sample from the microphone (Java Sound API), from a 16-bit WAV file, or from a built-in synthetic voice for demonstration.",
  "To implement pitch detection (autocorrelation) and cycle-by-cycle tracking (cross-correlation) in Java from first principles.",
  "To compute jitter, shimmer and HNR and compare each with a published reference limit.",
  "To produce a clear binary screening result with the measurements and the limits used.",
  "To demonstrate the object-oriented concepts of the Java Programming course (22ITC02N): classes, inheritance, abstract classes, interfaces, inner classes, packages, exceptions, multithreading, generics, collections and file I/O.",
  "To validate the measurements against recordings whose true values are known.",
], true);

section("Scope and Non-Goals");
P("Neurovox is deliberately limited to **binary screening**. The following are explicitly out of scope:");
bullets([
  "**Diagnosis.** The program never states that a person has or does not have Parkinson’s disease. A flagged voice only means “see a doctor”; many other conditions (a cold, smoking, vocal strain, age) change the same measures.",
  "**Disease staging** (for example the Hoehn and Yahr scale). Published error margins are too wide to separate neighbouring stages, so a stage would suggest a precision the signal does not have.",
  "**Treatment advice.** This is medical practice and depends on information not present in a voice recording.",
]);
P("The project exists in two forms. The **exam (console) version**, described in Chapters 3 to 7, is the subject of this report: one `Main` class and two packages in four short files (about 395 lines), with no external libraries. The **extended web prototype**, built earlier in the semester and summarised in Chapter 8, adds a browser interface, a database and a research classifier; its validation results explain several design decisions of the console version.");

section("Organisation of the Report");
P("Chapter 2 reviews the literature on voice changes in Parkinson’s disease and on the measures used. Chapter 3 lists the requirements, and Chapter 4 presents the design: architecture, packages, classes, threads and exceptions. Chapter 5 explains the signal-processing algorithms and their formulas. Chapter 6 walks through the implementation and maps it to the course syllabus. Chapter 7 presents the test cases, output screens and accuracy results. Chapter 8 summarises the extended web prototype and its research findings. Chapter 9 discusses limitations, ethics and future work, and Chapter 10 concludes. The complete source code is given in Appendix A.");

// ---------------- Chapter 2
chapter("Literature Survey");
section("Voice and Speech in Parkinson’s Disease");
P("Speech problems in Parkinson’s disease are grouped under the term *hypokinetic dysarthria*. Logemann et al. [3] examined 200 patients and found laryngeal (voice) dysfunction in 89%, often as the first speech sign to appear. Rusz et al. [4] studied 46 Czech speakers — 23 with newly diagnosed, untreated Parkinson’s disease and 23 healthy controls — and found that voice and prosody measures already differed in this early group, with reduced pitch variation being one of the most sensitive signs. These findings support the use of a sustained vowel as a simple, language-independent recording task.");
table("Voice signs of Parkinson’s disease and how they can be measured", ["Clinical sign", "What happens", "Acoustic measure"], [
  ["Hypophonia", "Softer, unstable loudness", "Shimmer, intensity"],
  ["Vocal-fold rigidity", "Irregular cycle-to-cycle timing", "Jitter"],
  ["Incomplete closure of the glottis", "Breathiness, added noise", "HNR / NHR"],
  ["Monopitch", "Narrow pitch range", "F0 range, F0 standard deviation"],
  ["Imprecise articulation", "Blurred consonants, slower syllables", "MFCC, syllable rate (web prototype only)"],
], [2.2, 2.6, 2.6]);

section("Acoustic Measures: Jitter, Shimmer and HNR");
P("Teixeira et al. [6] review the definitions of the classical perturbation measures and their use in detecting voice pathology. **Local jitter** is the average absolute difference between consecutive cycle lengths divided by the average cycle length; **local shimmer** is the same quantity computed on the peak amplitude of each cycle. Kay Elemetrics’ MDVP [8] — the program whose output columns appear in the UCI Parkinson’s dataset — suggests thresholds of 1.04% for jitter and 3.81% for shimmer, above which a voice is considered potentially pathological. A harmonics-to-noise ratio below about 20 dB is commonly taken to indicate a hoarse or breathy voice [6].");
P("Boersma [9] showed that both the fundamental frequency and the harmonics-to-noise ratio can be estimated accurately from the normalised autocorrelation of the signal. If *r* is the correlation between the signal and itself shifted by one period, the harmonic part of the energy is *r* and the noise part is 1 − *r*, giving HNR = 10·log₁₀(*r* / (1 − *r*)). This is the method used by Praat [7] and by Neurovox.");

section("Pitch Detection Algorithms");
P("The fundamental frequency (F0) is the rate at which the vocal folds vibrate, typically 85–180 Hz for adult men and 165–255 Hz for adult women. The **autocorrelation** method [9] compares the signal with delayed copies of itself and selects the delay at which they match best. The **YIN** algorithm of de Cheveigné and Kawahara [10] uses a cumulative-mean-normalised difference function and is much faster. In the extended prototype (Chapter 8) both were implemented and compared on 40 test vowels: autocorrelation had a mean error of 0.77 Hz and no octave errors on clean audio, while YIN had 3.25 Hz and 1.8% octave errors, and the gap widened with added noise. The console version therefore uses autocorrelation.");

section("Machine Learning on Voice Measures");
P("Little et al. [11] introduced nonlinear voice measures (recurrence period density entropy and detrended fluctuation analysis) for detecting general voice disorders. They then applied these together with the classical MDVP measures to Parkinson’s disease [5]: 195 sustained vowels from 31 people (23 with Parkinson’s disease), 22 measures per recording, published as the widely used **UCI Parkinson’s dataset** [12]. Using a support-vector machine they reported 91.4% accuracy and argued that such dysphonia measurements are suitable for remote monitoring. Tsanas et al. [13] used 5,875 recordings from 42 patients to estimate the UPDRS symptom score remotely, and Sakar et al. [14] compared many signal-processing feature sets for PD classification.");
P("An important caution comes from how these models are tested. Each person in the UCI dataset contributes about six recordings. If recordings are split randomly into training and test sets, the same person appears in both, and the model partly learns to recognise *voices* rather than *disease*. When the extended prototype retrained a logistic-regression model with the split grouped by person, accuracy fell from 80.5% (random split) to 69.2% — below the 75.4% obtained by always predicting the majority class (Section 8.5). This finding is the main reason the console version uses transparent reference limits instead of a trained classifier.");

section("Summary and Research Gap");
table("Summary of related work", ["Work", "Data", "Method", "Key result"], [
  ["Little et al. 2007 [11]", "General voice-disorder recordings", "Nonlinear measures (RPDE, DFA)", "New measures for disordered voices"],
  ["Little et al. 2009 [5]", "195 vowels, 31 people (UCI)", "Feature selection + SVM", "91.4% accuracy (recording-level split)"],
  ["Tsanas et al. 2010 [13]", "5,875 recordings, 42 patients", "Regression on voice measures", "UPDRS estimated within about 7.5 points"],
  ["Rusz et al. 2011 [4]", "23 early PD, 23 controls", "Acoustic analysis of several tasks", "Changes present before treatment"],
  ["Boersma 1993 [9]", "Synthetic and real voices", "Autocorrelation pitch and HNR", "Basis of Praat’s method"],
  ["de Cheveigné & Kawahara 2002 [10]", "Speech databases", "YIN pitch detector", "Low error, fast"],
  ["This project", "40 vowels with known truth", "Java autocorrelation + cross-correlation", "F0 error 0.22 Hz; jitter r = 0.92"],
], [2.1, 1.8, 2.1, 2.3], { size: 18 });
P("The gap this project addresses is not a new detection method but a **transparent, explainable implementation**: every number in the report is computed by Java code that the authors wrote and can explain line by line, and every number is checked against recordings whose correct answer is known.");

// ---------------- Chapter 3
chapter("System Requirements and Analysis");
section("Functional Requirements");
table("Functional requirements", ["ID", "Requirement"], [
  ["FR1", "Ask for the patient’s name and show a menu until the user chooses Exit."],
  ["FR2", "Record 5 seconds of voice from the microphone and save it as recording.wav."],
  ["FR3", "Read a 16-bit PCM WAV file (mono or stereo, any sample rate)."],
  ["FR4", "Generate a steady or a shaky synthetic voice for demonstration without a microphone."],
  ["FR5", "Reject recordings that are too short, too quiet or contain no clear voice, with a message saying what to change."],
  ["FR6", "Measure F0, jitter, shimmer and HNR of a sustained vowel."],
  ["FR7", "Compare each measure with its reference limit and report the binary result."],
  ["FR8", "Append every result to history.csv and show the saved history on request."],
  ["FR9", "Compare all voices analysed in the current session, steadiest first."],
], [0.7, 6.6]);
section("Non-Functional Requirements");
bullets([
  "**Simplicity:** only the Java standard library; compiles with a single `javac` command on JDK 11 or newer.",
  "**Writability:** the core (Main, VoiceSource, PitchTracker, VoiceReport) must fit in about 250 lines, and no file may be longer than about 120 lines, so it can be written by hand in three hours.",
  "**Responsiveness:** analysis of a 5-second recording completes in under one second on a laptop.",
  "**Robustness:** no input — wrong menu choice, missing file, noise, silence — may crash the program.",
  "**Honesty:** results must state the limits used and must not use the word “diagnosis”.",
  "**Privacy:** all data stays on the computer; nothing is sent over a network.",
]);
section("Hardware and Software Requirements");
table("Hardware and software requirements", ["Item", "Minimum", "Used in development"], [
  ["Processor", "Any x86-64 or ARM64", "Apple M4 / Intel Core i5"],
  ["Memory", "2 GB RAM", "8–16 GB"],
  ["Microphone", "Optional (demo works without)", "Built-in laptop microphone"],
  ["Operating system", "Windows, Linux or macOS", "macOS 15, Ubuntu 24.04, Windows 11"],
  ["Java", "JDK 11 or newer", "JDK 17 and 21"],
  ["Editor", "Any text editor", "VS Code, IntelliJ IDEA"],
  ["Validation tools", "—", "Python 3, Praat (via Parselmouth)"],
], [1.8, 2.6, 2.9]);
section("Feasibility Study");
P("**Technical feasibility.** The Java Sound API (`javax.sound.sampled`) is part of the JDK and can both record from a microphone and read WAV files, so no library is needed. The algorithms need only arrays, loops and the `Math` class. **Operational feasibility.** The program is a text menu that runs in any command prompt; options 3 and 4 need no microphone, which matters in a college lab. **Economic feasibility.** All tools used are free; the cost is limited to development time.");
section("Use Case Diagram");
P("Figure 3.1 shows the actions available to the user. Every way of obtaining a voice — recording, loading a file or running a demo — leads to the same screening report.");
figure("usecase.png", "Use case diagram of the console application", 5.6);

// ---------------- Chapter 4
chapter("System Design");
section("System Architecture");
P("The application is divided into three parts (Figure 4.1). The **audio** package knows where a voice comes from; the **analysis** package knows how to measure it; the `Main` class in the default package handles the user, runs the analysis on a separate thread and keeps the history file. Neither package depends on `Main`, so each can be tested or reused on its own.");
figure("architecture.png", "Architecture of the console version: packages, classes and files", 6.2);
section("Data Flow");
P("Figure 4.2 shows the path of one recording from input to result. Steps 2 and 3 belong to the audio package and the start of the analysis; steps 3 to 5 are in `PitchTracker`, steps 6 to 8 in `VoiceReport`, and step 9 is in `Main`.");
figure("dataflow.png", "Data flow from voice input to stored result", 4.3);
section("Package Structure");
table("Packages and their responsibilities", ["Package", "File", "Responsibility"], [
  ["audio", "VoiceSource.java", "Abstract voice source; three subclasses (Microphone, WavFile, Demo); byte-to-sample conversion; the VoiceException class"],
  ["analysis", "PitchTracker.java", "Pre-processing checks, rough pitch by autocorrelation, cycle-by-cycle tracking; gives cycle lengths, peak heights and similarity"],
  ["analysis", "VoiceReport.java", "F0, jitter, shimmer, HNR, reference limits, report text, sorting by jitter; runs on a thread"],
  ["(default)", "Main.java", "Menu, thread management, session list, history file, comparison"],
], [1.2, 1.8, 4.3]);
P("Packages group related classes, prevent name clashes and control access. The default package can import named packages, but not the reverse, which is why the reusable code is in `audio` and `analysis` while only `Main` stays in the default package. Because the folders `audio/` and `analysis/` sit next to `Main.java`, the current directory is the CLASSPATH root and no `-cp` option is needed.");
section("Class Design");
P("Figure 4.3 is the class diagram. `VoiceSource` is abstract: it holds the samples and sample rate and declares the abstract method `load()`. The three subclasses override `load()` in different ways, so `Main` can hold any of them in a `VoiceSource` variable and call `voice.load()` — the correct version is chosen at run time (dynamic method dispatch). `WavFile` is declared `final` to show how inheritance can be prevented. The analysis is split into two small classes. `PitchTracker` does the signal processing in its constructor and exposes its results (`periods`, `peaks`, `similarity`) with *default (package-private) access*, so only classes in the `analysis` package can read them. `VoiceReport` turns those results into the four measures and the decision; it implements `Runnable`, so that it can run on its own thread, and `Comparable`, so that a list of results can be sorted by jitter.");
figure("classes.png", "Class diagram (UML)", 6.2);
section("Thread Design");
P("Analysis is run on a separate thread so that the main thread stays free to show progress (Figure 4.4). `Main` creates `new Thread(analyzer)` and calls `start()`. While the worker runs, the main thread prints a dot every 50 ms and checks `isDone()`. Both `isDone()` and `finish()` are `synchronized`, so the flag written by the worker is guaranteed to be visible to the main thread. Finally `join()` waits for the worker to terminate before the report is read.");
figure("thread.png", "Life cycle of the analysis thread and the main thread", 6.2);
P("An exception thrown inside `run()` cannot propagate to the main thread, because `run()` is not allowed to declare checked exceptions. The worker therefore stores the message in the field `error`; when the main thread later calls `report()`, that method re-throws it as a `VoiceException`. The `finally` block guarantees that `done` is set even if analysis fails, so the progress loop always ends.");
section("Exception Design");
table("Exceptions and the messages shown to the user", ["Situation", "Where detected", "Message"], [
  ["No microphone or access denied", "Microphone.load()", "Microphone not available: …"],
  ["File missing or not a WAV", "WavFile.load()", "Cannot read <file>: …"],
  ["WAV not 16-bit PCM", "WavFile.load()", "Please use a 16-bit PCM WAV file"],
  ["Recording shorter than 2 s", "PitchTracker()", "Too short. Record at least 2 seconds."],
  ["RMS level below 0.01", "PitchTracker()", "Too quiet. Say ‘aah’ louder."],
  ["Fewer than 10 cycles found", "PitchTracker()", "No steady voice. Hold one ‘aah’ for 3–5 s."],
  ["Mean cycle similarity below 0.5", "PitchTracker()", "No clear voice found (only noise)…"],
  ["Any unexpected runtime error", "run()", "Analysis failed: …"],
  ["Menu input not 0–6", "main()", "Invalid choice."],
], [2.6, 1.8, 3.0], { size: 18 });
P("All recoverable problems are reported through one user-defined checked exception, `VoiceException`, declared as a static nested class of `VoiceSource`. Using a checked exception forces every caller either to handle it or to declare it with `throws`, so no failure can be silently ignored.");
section("File Formats");
P("**recording.wav** is a standard RIFF WAV file (44,100 samples per second, 16-bit, mono) written by `AudioSystem.write`, so it can be opened in Praat or any audio player. **history.csv** is a plain text file with one line per analysis, in the order: name, date and time, F0 (Hz), jitter (%), shimmer (%), HNR (dB), result. For example:");
code(["Pranav,Mon Oct 26 13:45:35 IST 2026,139.9,0.30,2.35,35.1,OK"], 18);

// ---------------- Chapter 5
chapter("Algorithms and Signal Processing");
section("Digital Audio Basics");
P("A microphone produces a voltage that follows the air pressure. The sound card measures this voltage 44,100 times per second (the **sample rate**) and stores each measurement as a 16-bit signed integer between −32,768 and 32,767 (**PCM**, pulse-code modulation). A five-second recording is therefore 220,500 numbers, or 441,000 bytes. In a WAV file each sample is stored *little-endian*: the low byte first, then the high byte. The method `toSamples()` rebuilds each sample and scales it to the range −1 to +1:");
equation("x[i] = ((b[2i+1] << 8) | (b[2i] & 0xFF)) / 32768", "5.1");
P("The mask `& 0xFF` is needed because Java’s `byte` is signed: without it, a low byte such as 0xF0 would be sign-extended to a negative number and corrupt the sample. For stereo files only the first channel is used, by stepping 2 × channels bytes per sample.");
section("Pre-processing");
P("Three steps prepare the signal. (1) The first and last 20% of the recording are dropped, because the start and end of a sustained vowel are unstable. (2) The mean value is subtracted from every sample to remove any DC offset introduced by the microphone. (3) The root-mean-square (RMS) level is computed; if it is below 0.01 (about −40 dB below full scale), the recording is rejected as too quiet.");
equation("RMS = √( (1/N) Σ x[n]² )", "5.2");
section("Pitch Detection by Autocorrelation");
P("A voiced sound repeats itself once per vocal cycle. If the signal is compared with a copy of itself shifted by τ samples, the match is best when τ equals one period. The normalised correlation between two segments of length *L* starting at positions *a* and *b* is:");
equation("r(a, b, L) = Σ x[a+i]·x[b+i] / √( Σ x[a+i]² · Σ x[b+i]² ),   i = 0 … L−1", "5.3");
P("*r* is 1 when the segments are identical and near 0 when they are unrelated. To find the rough period, `PitchTracker` computes r(0, τ, L) for every lag τ between the sample rate ÷ 500 and the sample rate ÷ 75 — that is, for every pitch between 75 Hz and 500 Hz — and keeps the τ with the highest *r*. Figure 5.1 shows this on a test vowel: the curve peaks at a lag of 295 samples, which is 44,100 ÷ 295 = 149.5 Hz. Note the second-highest peak near 75 Hz (twice the period) with r ≈ 0.9: choosing it would be an *octave error*, which is why the method selects the global maximum and the search range stops at 75 Hz.");
figure("autocorr.png", "Left: 40 ms of a test vowel with the detected period marked. Right: correlation r for each candidate pitch", 6.2);
section("Cycle-by-Cycle Tracking");
P("Jitter and shimmer need the length and height of *every* cycle, not just the average. Starting at time *t* with the current period estimate *T*, the analyser takes one period of signal and slides a copy forward by every shift *s* from 0.8·T to 1.2·T, keeping the shift with the highest correlation. That best shift is the length of this cycle. The search window adapts to each new period, so slow changes of pitch are followed.");
P("Because the best shift is a whole number of samples, it can be up to half a sample (11 µs at 44.1 kHz) wrong. This is comparable to the timing differences that jitter measures, so the position of the peak is refined by fitting a parabola through the correlation values at s − 1, s and s + 1 (values *a*, *b*, *c*):");
equation("T = s + 0.5·(a − c) / (a − 2b + c)", "5.4");
P("The refined period is kept inside the 75–500 Hz range, *t* is advanced by *T*, and the largest sample value inside the cycle is stored as its peak height *A*. The correlation *b* is also added to a running sum for the HNR. The same exact time *t* is carried forward (only the window start is rounded), so the fractional part of each period is never lost — a defect that was found and fixed in the extended prototype (Section 8.3).");
section("Jitter, Shimmer and F0");
P("With N cycles of lengths T₁, T₂, … and peak heights A₁, A₂, …:");
equation("F0 = N / Σ Tᵢ", "5.5");
equation("Jitter (%) = 100 · [ (1/(N−1)) Σ |Tᵢ − Tᵢ₋₁| ] / [ (1/N) Σ Tᵢ ]", "5.6");
equation("Shimmer (%) = 100 · [ (1/(N−1)) Σ |Aᵢ − Aᵢ₋₁| ] / [ (1/N) Σ Aᵢ ]", "5.7");
P("These are the “local” jitter and shimmer of MDVP and Praat [6], [8]. Both are relative measures, so they do not depend on the voice’s pitch or the recording level.");
section("Harmonics-to-Noise Ratio");
P("If r̄ is the mean of the best correlations found while tracking the cycles, then following Boersma [9]:");
equation("HNR (dB) = 10 · log₁₀( r̄ / (1 − r̄) )", "5.8");
P("A perfectly periodic voice has r̄ → 1 and a very high HNR; noise lowers r̄. For example r̄ = 0.99 gives 20 dB. If r̄ is below 0.5 the neighbouring cycles hardly resemble each other and the recording is rejected as noise.");
section("Decision Rule");
table("Reference limits used for the screening decision", ["Measure", "Normal if", "Source"], [
  ["Jitter (local)", "≤ 1.04 %", "MDVP threshold [8]"],
  ["Shimmer (local)", "≤ 3.81 %", "MDVP threshold [8]"],
  ["HNR", "≥ 20 dB", "Commonly used limit [6]"],
], [2.2, 1.8, 3.3], { align: ["", "c", ""] });
P("Each measure outside its limit counts as one **marker** (0 to 3). If two or more markers are present the result is **FURTHER CHECK SUGGESTED (see a neurologist)**; otherwise **NO CONCERN INDICATED**. Requiring two markers makes the decision less sensitive to a single noisy measurement. The rule is fully transparent: the report prints every value and every limit, so the user can see exactly why a result was given.");
section("Synthetic Demo Voice");
P("Options 3 and 4 build an artificial vowel so the program can be demonstrated anywhere. Each cycle at 140 Hz is the sum of three harmonics with amplitudes 1, 0.5 and 0.25. The length of each cycle is multiplied by (1 + j·g) and its amplitude by (1 + s·g), where *g* is a random Gaussian number, and small Gaussian noise is added to every sample (Figure 5.2). The steady voice uses j = 0.4%, s = 2% and noise 0.005; the shaky voice uses j = 2.5%, s = 12% and noise 0.05. A fixed random seed (7) makes the demo give the same numbers every time it is run.");
figure("demo_waves.png", "60 ms of the steady and shaky demo voices", 6.0);
section("Complexity Analysis");
P("Let *n* be the number of samples analysed, *P* the period in samples and *K* the number of lags searched (about 500 at 44.1 kHz). The rough pitch search computes K correlations over about n samples: O(K·n). Cycle tracking computes 0.4·P correlations of length P for each of the n/P cycles: O(0.4·P·n). Jitter, shimmer and F0 are O(N) in the number of cycles. The overall time is therefore O((K + P)·n), and the memory is O(n) for the sample array plus O(N) for the two lists. For a 5-second recording this is a few hundred million multiplications, which a laptop completes in well under a second (Section 7.6).");

// ---------------- Chapter 6
chapter("Implementation");
section("Development Environment");
P("The code was written in Java 17 and tested on JDK 17 and 21; it uses no feature newer than Java 11 (`readAllBytes`, multi-catch, try-with-resources, lambdas). It was developed in VS Code and IntelliJ IDEA, version-controlled with Git and hosted on GitHub (github.com/vudigapranav/neurovox, folder `exam-version/`).");
section("Package audio — class VoiceSource");
P("`VoiceSource` is the abstract base class. It declares the constant `RATE` as `public static final`, holds the samples in a `protected double[]` so that subclasses can fill it, and provides the shared helper `toSamples()`. It overrides `Object.toString()` to describe the recording. The abstract methods force every subclass to say how it loads samples and what it is called:");
code([
  "public abstract class VoiceSource {",
  "    public static final float RATE = 44100f;",
  "    protected double[] samples;",
  "    protected float rate;",
  "    protected VoiceSource(float rate) { this.rate = rate; }",
  "    public abstract void load() throws VoiceException;",
  "    public abstract String getName();",
  "    ...",
  "}",
]);
P("The three subclasses are **static nested classes** of `VoiceSource`. A Java file may contain only one public top-level class, so nesting them is how all four public types can live in one file and still be used from outside the package. Each constructor calls `super(RATE)`, and `Microphone()` and `Demo()` show constructor overloading with `this(...)`:");
code([
  "public static class Microphone extends VoiceSource {",
  "    private final int seconds;",
  "    public Microphone() { this(5); }",
  "    public Microphone(int seconds) { super(RATE); this.seconds = seconds; }",
  "    public void load() throws VoiceException {",
  "        try {",
  "            AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);",
  "            TargetDataLine mic = AudioSystem.getTargetDataLine(fmt);",
  "            ...",
  "        } catch (LineUnavailableException | IOException | IllegalArgumentException e) {",
  "            throw new VoiceException(\"Microphone not available: \" + e.getMessage());",
  "        }",
  "    }",
  "}",
]);
P("`Microphone.load()` opens a `TargetDataLine` for 44.1 kHz, 16-bit, signed, little-endian mono audio, reads exactly seconds × 88,200 bytes, saves them with `AudioSystem.write` and converts them to samples. `WavFile.load()` uses try-with-resources so the `AudioInputStream` is always closed, and checks that the file is 16-bit little-endian PCM. Library exceptions are caught and *re-thrown* as `VoiceException`, so callers deal with only one exception type.");
section("Package analysis — classes PitchTracker and VoiceReport");
P("The analysis package has two short classes with one job each. **PitchTracker** does all the signal processing in its constructor: the length and loudness checks, the rough pitch search and the cycle-by-cycle tracking of Chapter 5. If anything is wrong it throws `VoiceException`, so a `PitchTracker` object only ever exists for a usable recording. Its results are stored in two `ArrayList<Double>` (cycle lengths and peak heights) and one `double` (the mean similarity), declared without an access modifier so that `VoiceReport`, in the same package, can read them directly. The heart of the class is the tracking loop:");
code([
  "while (t + 2.5 * period < x.length) {",
  "    int pos = (int) Math.round(t), n = (int) period, s = (int) (0.8 * period);",
  "    double b = corr(x, pos, pos + s, n);",
  "    for (int k = s + 1; k <= 1.2 * period; k++) {",
  "        double c = corr(x, pos, pos + k, n);",
  "        if (c > b) { b = c; s = k; }",
  "    }",
  "    double a = corr(x, pos, pos + s - 1, n), c = corr(x, pos, pos + s + 1, n), d = a - 2 * b + c;",
  "    period = d < 0 ? s + 0.5 * (a - c) / d : s;",
  "    period = Math.max(minLag, Math.min(maxLag, period));",
  "    ...  // store period, peak height and b",
  "    t += period;",
  "}",
]);
P("**VoiceReport** turns those lists into the four measures. Because jitter and shimmer use the same formula on different lists (equations 5.6 and 5.7), two small helper methods, `mean(List<Double>)` and `change(List<Double>)`, cover both, which keeps the class short. Its `run()` method shows try, two catch blocks and finally:");
code([
  "public void run() {",
  "    try {",
  "        PitchTracker p = new PitchTracker(voice);",
  "        features.put(F0, 1 / mean(p.periods));",
  "        features.put(JITTER, 100 * change(p.periods) / mean(p.periods));",
  "        features.put(SHIMMER, 100 * change(p.peaks) / mean(p.peaks));",
  "        features.put(HNR, 10 * Math.log10(p.similarity / (1 - p.similarity)));",
  "    } catch (VoiceException e) {",
  "        error = e.getMessage();",
  "    } catch (RuntimeException e) {",
  "        error = \"Analysis failed: \" + e;",
  "    } finally {",
  "        finish();                    // synchronized: done = true",
  "    }",
  "}",
]);
P("The measures are kept in a `LinkedHashMap<String, Double>`, which keeps insertion order so the report always lists F0, jitter, shimmer and HNR in the same order. `compareTo()` orders two reports by jitter, so `Collections.sort(session)` lists the steadiest voice first; `toString()` gives a one-line summary for the comparison screen and `toCsv()` a comma-separated line for the history file.");
section("Class Main");
P("`main()` reads the patient’s name with a `Scanner` and runs a `do-while` menu loop with a `switch` statement. The variable `voice` has the superclass type `VoiceSource`, and is assigned a `Microphone`, `WavFile` or `Demo` object depending on the choice. `screen()` then loads the voice, starts the analysis thread, waits for it and prints the report:");
code([
  "VoiceReport a = new VoiceReport(voice);",
  "Thread worker = new Thread(a, \"analyzer\");",
  "worker.start();",
  "System.out.print(\"Analysing\");",
  "while (!a.isDone()) {",
  "    System.out.print(\".\");",
  "    Thread.sleep(50);",
  "}",
  "worker.join();",
  "System.out.println(\"\\n\" + a.report());",
]);
P("History is written with a `BufferedWriter` wrapped round a `FileWriter` opened in append mode, and read back with a `BufferedReader`; each line is split with a `StringTokenizer` on commas. `compare()` sorts the session list, walks it with an explicit `Iterator`, and counts flagged voices with a stream and a lambda expression. The generic method `<T> printAll(String, List<T>)` prints any list.");
section("Mapping to the Java Programming Syllabus (22ITC02N)");
P("Table 6.1 shows where each syllabus topic [15] appears in the code, so that the project can serve as a revision aid.");
table("Syllabus topics and where they are used", ["Unit", "Topic", "Where in the code"], [
  ["I", "Classes, objects, fields, methods, access modifiers", "All four files (private / protected / default / public)"],
  ["I", "Constructors and constructor overloading", "Microphone(), Microphone(int), Demo(), Demo(boolean)"],
  ["I", "static and final", "RATE, the limits, final class WavFile, final fields"],
  ["I", "Arrays, Strings, StringTokenizer, Scanner", "double[] samples; String.format; showHistory(); main()"],
  ["I", "Flow control", "switch, do-while, while, for, for-each, ternary operator"],
  ["II", "Inheritance and super", "Microphone, WavFile, Demo extend VoiceSource; super(RATE)"],
  ["II", "Preventing inheritance", "public static final class WavFile"],
  ["II", "Object class, method overriding", "toString() in VoiceSource and VoiceReport"],
  ["II", "Dynamic method dispatch", "voice.load() on a VoiceSource reference"],
  ["II", "Abstract classes and methods", "VoiceSource, load(), getName()"],
  ["II", "Interfaces", "VoiceReport implements Runnable, Comparable"],
  ["II", "Inner classes", "Four static nested classes in VoiceSource"],
  ["II", "Packages, import, CLASSPATH", "package audio; package analysis; import audio.VoiceSource.*"],
  ["III", "User-defined exception, throw, throws", "VoiceException; PitchTracker(…) throws VoiceException"],
  ["III", "try, multiple catch, multi-catch, finally", "run(), screen(), Microphone.load(), showHistory()"],
  ["III", "Threads, life cycle, synchronization", "new Thread, start(), sleep(), join(); synchronized isDone()"],
  ["III", "Generics", "List<Double>, Map<String, Double>, Comparable<VoiceReport>, mean(List<Double>), <T> printAll()"],
  ["IV", "ArrayList, LinkedHashMap, Iterator", "periods, peaks, features, session; compare()"],
  ["IV", "Comparable interface", "compareTo() + Collections.sort()"],
  ["IV", "Java 8: lambda expressions, streams", "session.stream().filter(v -> v.needsFurtherCheck())"],
  ["V", "FileWriter, BufferedWriter, FileReader, BufferedReader", "saveHistory(), showHistory()"],
  ["V", "Byte streams", "AudioInputStream, ByteArrayInputStream, readAllBytes()"],
], [0.6, 3.0, 3.8], { size: 18, align: ["c", "", ""] });
section("Compiling and Running");
P("From the folder that contains `Main.java`:");
code(["javac Main.java audio/*.java analysis/*.java", "java Main"], 18);
P("`javac` compiles the four files and places `VoiceSource.class` (and its nested classes, such as `VoiceSource$Demo.class`) in `audio/` and `PitchTracker.class` and `VoiceReport.class` in `analysis/`, matching the package declarations. `java Main` runs the program with the current folder as the CLASSPATH. On Windows the paths are written `audio\\*.java`; the file `run.bat` does both steps.");

// ---------------- Chapter 7
chapter("Testing and Results");
section("Testing Strategy");
P("Testing was done at three levels: (1) **functional tests** of every menu option and error path, (2) **known-answer tests** with synthetic voices whose jitter and shimmer were chosen in advance, and (3) an **accuracy study** on 40 realistic test vowels with exactly known cycle lengths, generated by the validation script of the extended prototype.");
section("Test Cases");
table("Functional test cases", ["ID", "Input", "Expected", "Actual", "Status"], [
  ["TC01", "Option 3 (steady demo)", "0 markers, NO CONCERN", "Jitter 0.30 %, shimmer 2.35 %, HNR 35.1 dB, 0 markers", "Pass"],
  ["TC02", "Option 4 (shaky demo)", "≥ 2 markers, FURTHER CHECK", "Jitter 2.21 %, shimmer 13.57 %, HNR 16.6 dB, 3 markers", "Pass"],
  ["TC03", "Option 2, test vowel syn10.wav", "F0 ≈ 149.5 Hz", "F0 149.3 Hz, NO CONCERN", "Pass"],
  ["TC04", "Option 2, stereo WAV", "First channel analysed", "Same values as mono file", "Pass"],
  ["TC05", "Option 2, 4 s of silence", "Rejected: too quiet", "“Too quiet. Say ‘aah’ louder.”", "Pass"],
  ["TC06", "Option 2, white noise only", "Rejected: no voice", "“No clear voice found (only noise)…”", "Pass"],
  ["TC07", "Option 2, 0.1 s clip", "Rejected: too short", "“Too short. Record at least 2 seconds.”", "Pass"],
  ["TC08", "Option 2, 32-bit float WAV", "Rejected: format", "“Please use a 16-bit PCM WAV file”", "Pass"],
  ["TC09", "Option 2, text file named .wav", "Rejected: unreadable", "“Cannot read …: File of unsupported format”", "Pass"],
  ["TC10", "Option 2, missing file", "Rejected: not found", "“Cannot read … (No such file or directory)”", "Pass"],
  ["TC11", "Option 1 on a machine without microphone", "Clear message, no crash", "“Microphone not available: …”", "Pass"],
  ["TC12", "Menu input 9, −3, “abc”", "Invalid choice, menu again", "“Invalid choice.”", "Pass"],
  ["TC13", "Option 5 after three analyses", "Sorted by jitter, count of flags", "Steady, syn10, shaky; 1 of 3 flagged", "Pass"],
  ["TC14", "Option 6", "All saved rows shown", "Rows parsed with StringTokenizer", "Pass"],
  ["TC15", "Option 5 / 6 before any analysis", "Friendly message", "“Nothing analysed yet.” / “No history yet.”", "Pass"],
  ["TC16", "Empty patient name", "Default name used", "Saved as “Patient”", "Pass"],
], [0.6, 1.9, 1.7, 2.6, 0.6], { size: 17, align: ["c", "", "", "", "c"] });
section("Output Screens");
P("Figures 7.1 to 7.5 show the console during a typical session.");
figure("console_start.png", "Compiling and starting the program; the main menu", 5.6);
figure("console_steady.png", "Result for the steady demo voice: no marker outside the limits", 5.6);
figure("console_shaky.png", "Result for the shaky demo voice: all three markers outside the limits", 5.6);
figure("console_compare.png", "Session comparison (sorted by jitter) and the saved history file", 5.9);
figure("console_errors.png", "Error handling: no microphone, noise, silence, wrong format and invalid menu input", 5.9);
section("Accuracy on Test Vowels with Known Values");
P("Real patient recordings with known jitter do not exist — jitter is defined by the measuring program. To check correctness, the 40 synthetic sustained /a/ vowels of the extended prototype’s validation suite were used. They are not simple sine waves: each cycle is a glottal pulse (Rosenberg shape [16]) passed through three formant resonators at 730, 1090 and 2440 Hz, as in a real /a/, with F0 between 94 and 245 Hz, injected jitter of 0.2–2.5%, shimmer of 1–10% and added noise at 12–35 dB signal-to-noise ratio. The script records the exact length and amplitude of every cycle, so the true jitter and shimmer are known. All 40 files were analysed by the console version through menu option 2.");
figure("validation.png", "Console version against the known true values for 40 test vowels (dashed line = perfect agreement)", 6.2);
table("Accuracy of the console version on 40 test vowels", ["Measure", "Mean absolute error", "Mean bias", "Correlation r"], [
  ["F0", `${STATS.f0.mae.toFixed(2)} Hz`, `${STATS.f0.bias.toFixed(2)} Hz`, STATS.f0.r.toFixed(3)],
  ["Jitter", `${STATS.jitter.mae.toFixed(2)} %-points`, `${STATS.jitter.bias.toFixed(2)} %-points`, STATS.jitter.r.toFixed(3)],
  ["Shimmer", `${STATS.shimmer.mae.toFixed(2)} %-points`, `+${STATS.shimmer.bias.toFixed(2)} %-points`, STATS.shimmer.r.toFixed(3)],
  ["HNR vs added SNR", "—", "—", STATS.hnr_snr_r.toFixed(2)],
], [1.8, 2.0, 1.8, 1.6], { align: ["", "c", "c", "c"] });
P("**F0** is essentially exact (r = 1.000, error 0.22 Hz). **Jitter** follows the truth closely (r = 0.92) with a small tendency to read low, especially for the three files with the highest jitter (about 2.8%). This is expected: the correlation window is one full period and overlaps its neighbours, which smooths the largest perturbations. Praat shows the same behaviour — in the extended prototype, Praat and the full Java implementation both under-read jitter by about 0.14 percentage points against the same truth (Section 8.3).");
figure("hnr.png", "Measured HNR against the amount of added noise", 4.6);
P("**HNR** decreases as more noise is added (r = 0.85 with the signal-to-noise ratio; Figure 7.7). **Shimmer** is the weakest measure (r = 0.78) and reads high on noisy files, because noise is added on top of every peak. Table 7.3 shows that this over-reading disappears in quiet recordings, which is why the program and the user manual ask for a quiet room.");
table("Shimmer over-reading against the noise level", ["Added noise (SNR)", "Files", "Mean shimmer error"], [
  ["12 dB (very noisy)", "13", "+3.29 %-points"],
  ["18 dB", "9", "+1.84 %-points"],
  ["25 dB", "9", "+0.52 %-points"],
  ["35 dB (quiet)", "9", "−0.17 %-points"],
], [2.6, 1.2, 2.4], { align: ["", "c", "c"] });
section("Agreement of the Final Decision");
P("For each test vowel, the *reference* decision was computed by applying the same rule to the true jitter, the true shimmer and the added signal-to-noise ratio (as a stand-in for HNR). The console version agreed with the reference on **33 of 40 files (82.5%)**. It never missed a file that the reference flagged (19 of 19), and gave 7 extra flags. All 7 extra flags were noisy files where over-read shimmer pushed the count of markers to two; the jitter marker agreed on all 40 files and the HNR marker on 35. In a screening setting, an extra flag (a needless visit) is a smaller harm than a miss, but the result again shows that recording conditions matter.");
table("Decision agreement on 40 test vowels", ["", "Reference: flag", "Reference: no flag"], [
  ["Console: FURTHER CHECK", "19", "7"],
  ["Console: NO CONCERN", "0", "14"],
], [2.6, 2.2, 2.2], { align: ["", "c", "c"] });
section("Performance");
P("All 40 files (3.5–4.5 s each) were analysed in a single run in 10.8 seconds including Java start-up — about 0.27 s per file — on a cloud Linux machine. A 3-second demo voice is analysed in under 0.2 s, which is why only a few progress dots appear. Memory use is dominated by the sample array: 8 bytes × 220,500 samples ≈ 1.8 MB for a 5-second recording.");

// ---------------- Chapter 8
chapter("Extended Web Prototype and Research Findings");
section("Overview");
P("Before the exam version, the team built a full local web application, also called Neurovox (about 10,000 lines of Java and JavaScript). A Java 17 process uses the JDK’s built-in `HttpServer` (deliberately not Spring Boot, so that every route is visible in code), stores data in SQLite, and does all signal processing in Java. The browser guides the user through four voice tasks — a sustained vowel, the syllables “pa-ta-ka”, a reading passage and free speech — while a particle orb reacts to the microphone (Figure 8.1). Java checks each recording’s quality before storing it, then computes the 16 measures of the UCI dataset (jitter, shimmer and HNR variants, F0 statistics) plus MFCCs and speech-timing measures, and shows a report and a history of trends (Figures 8.2 and 8.3). The prototype has 174 Java unit tests and 31 frontend tests.");
figure("web_record.jpg", "Web prototype: recording the sustained vowel; the orb reacts to the voice", 5.6);
figure("web_report.jpg", "Web prototype: top of the measurement report, with recording quality", 5.0);
figure("web_history.jpg", "Web prototype: history page with the trend of one measurement", 4.0);
section("Java Versus Praat");
P("The full implementation was compared with Praat 6.1.38, the reference program in speech science, on the same 40 synthetic vowels, the same analysis region and the same settings. All 16 measurements agreed within 0.15% relative error with a correlation of 1.000 (Table 8.1, Figure 8.4). Two defects were found and fixed during this study: pulse positions had been rounded to whole samples, losing the fractional part of each period, and cycle timing had been measured on a 16 kHz copy of the signal instead of at the original rate. The console version avoids both by design (Section 5.4).");
table("Full Java implementation against Praat on 40 vowels (selected measures)", ["Measure", "Java mean", "Praat mean", "Relative MAE", "r"], [
  ["F0 (Hz)", "151.669", "151.669", "0.00 %", "1.000"],
  ["Jitter (local)", "0.0089", "0.0089", "0.12 %", "1.000"],
  ["RAP", "0.0051", "0.0050", "0.15 %", "1.000"],
  ["Shimmer (local)", "0.0678", "0.0679", "0.11 %", "1.000"],
  ["NHR", "0.0725", "0.0725", "0.12 %", "1.000"],
  ["HNR (dB)", "14.549", "14.543", "0.07 %", "1.000"],
], [2.0, 1.4, 1.4, 1.4, 0.9], { align: ["", "c", "c", "c", "c"] });
figure("praat_scatter.png", "Full Java implementation against Praat for each measurement", 6.0);
section("Autocorrelation Versus YIN");
P("Both pitch detectors were implemented and run on the 40 vowels under three conditions: as generated, with the fundamental removed by a 300 Hz high-pass filter (the classic trap for octave errors), and with noise at 6 dB SNR (Table 8.2, Figure 8.5). Autocorrelation was more accurate in every condition; YIN was about 16 times faster. The choice also matters downstream: on individual noisy files, jitter computed with YIN-guided pulses differed from autocorrelation-guided jitter by up to 224%, because YIN’s octave jumps break the cycle tracking.");
table("Pitch detector comparison (40 vowels)", ["Condition", "Detector", "Mean error", "Octave errors", "ms per s audio"], [
  ["Clean", "Autocorrelation", "0.77 Hz", "0.00 %", "48.7"],
  ["Clean", "YIN", "3.25 Hz", "1.80 %", "3.0"],
  ["High-pass 300 Hz", "Autocorrelation", "1.06 Hz", "0.29 %", "46.2"],
  ["High-pass 300 Hz", "YIN", "3.74 Hz", "1.96 %", "3.0"],
  ["Noise 6 dB", "Autocorrelation", "0.79 Hz", "0.00 %", "30.9"],
  ["Noise 6 dB", "YIN", "15.27 Hz", "10.61 %", "3.5"],
], [1.8, 1.7, 1.3, 1.3, 1.3], { align: ["", "", "c", "c", "c"] });
figure("pitch_study.png", "Pitch error and speed of the two detectors", 4.8);
section("The Research Model and the Go/No-Go Decision");
P("A logistic-regression model was trained in Python (scikit-learn) on the 16 measures of the UCI dataset — 195 recordings from 32 subject codes — and exported to Java, where inference matched scikit-learn to within 10⁻⁹. The crucial question was how to test it. Table 8.3 shows that the leaky random split, which lets the same person appear in training and testing, gives 80.5% accuracy, but grouping the folds by person reduces it to 69.2%, *below* the 75.4% baseline of always answering “Parkinson’s”. Specificity — correctly clearing healthy people — was only 33%.");
table("Research model evaluation (positive class = Parkinson’s)", ["Evaluation", "Accuracy", "Balanced acc.", "Sensitivity", "Specificity", "ROC-AUC"], [
  ["Majority-class baseline", "75.4 %", "50.0 %", "100 %", "0 %", "—"],
  ["Random split (leaky)", "80.5 %", "69.5 %", "91.2 %", "47.9 %", "0.820"],
  ["Grouped by person, 5-fold", "69.2 %", "57.1 %", "81.0 %", "33.3 %", "0.622"],
  ["Leave one person out", "73.8 %", "61.6 %", "85.7 %", "37.5 %", "0.660"],
], [2.5, 1.0, 1.15, 1.1, 1.1, 1.0], { size: 18, align: ["", "c", "c", "c", "c", "c"] });
P("In addition, the dataset contains measurements only — no audio and no extraction settings — so there is no way to confirm that values computed by our code (or by Praat) are on the same scale as the MDVP values the model was trained on. The team therefore recorded a **no-go** decision: the model is reported as an offline research result and is never applied to live recordings.");
section("Consequences for the Console Version");
bullets([
  "The console version uses **transparent reference limits** instead of the trained model, prints the limits with every result, and gives a **binary** indication, since no probability from this data would be trustworthy.",
  "It uses **autocorrelation** (not YIN), and tracks cycles at the **original sample rate** with **fractional** cycle lengths.",
]);

// ---------------- Chapter 9
chapter("Limitations, Ethics and Future Scope");
section("Limitations");
bullets([
  "**No human validation.** Accuracy was measured on synthetic vowels with known answers. Real voices are less regular, and the program has not been tested on recordings from people with and without Parkinson’s disease.",
  "**Thresholds from another program.** The 1.04% and 3.81% limits were defined for MDVP’s own algorithms. Our jitter and shimmer follow the same definitions, but small differences in method can shift values near the limits.",
  "**Shimmer is sensitive to noise** (Table 7.3), so a noisy room can produce a false flag.",
  "**One task only.** The console version analyses a sustained vowel; the speech tasks of the web prototype (syllable rate, pauses, monopitch in reading) are not included.",
  "**Not specific to Parkinson’s disease.** A cold, smoking, vocal fatigue, ageing and many voice disorders raise jitter and shimmer too.",
]);
section("Ethical Considerations");
P("A health-related tool must not cause harm through false confidence. Neurovox never uses the word “diagnosis”, always prints the limits behind its result, and phrases a positive result as a suggestion to see a neurologist. All recordings and history stay on the user’s computer; nothing is uploaded. Voice is personal data, so the history file contains only the name typed by the user and the measurements, not the audio. Any future clinical use would require ethical approval, informed consent and regulatory review as a medical device.");
section("Future Scope");
bullets([
  "Record 20 or more human vowels with consent and compare the console version with Praat on them.",
  "Add the syllable-repetition task (“pa-ta-ka”) to measure articulation rate, the vocal equivalent of the finger-tapping test.",
  "Replace the fixed limits with each user’s own baseline, so that changes over months are tracked rather than single values.",
  "Add a simple JavaFX or web front end to the console core, reusing the same two packages.",
  "Collect a properly consented dataset with recordings (not only features) so that a classifier can be validated by person.",
]);

// ---------------- Chapter 10
chapter("Conclusion");
P("Neurovox shows that a complete voice-screening pipeline — microphone capture, pitch detection, cycle-by-cycle tracking, jitter, shimmer, harmonics-to-noise ratio and a transparent decision rule — can be written in about 395 lines of standard Java, organised into a `Main` class and two packages in four short files, and small enough to be written by hand in an examination. The design uses the object-oriented features taught in the Java Programming course in a natural way: an abstract class with overridden methods for the different voice sources, a user-defined checked exception for every recoverable problem, a worker thread with synchronised state, generic collections for the measurements, and buffered file I/O for the history.");
P("Testing on 40 realistic synthetic vowels with known values showed that the console version measures pitch almost exactly (0.22 Hz error) and jitter closely (r = 0.92), and that its final decision agreed with the reference decision on 33 of 40 files with no missed cases. The earlier web prototype contributed two lessons that shaped this version: a careful implementation can match Praat to within 0.15%, but a classifier trained on the public dataset does not generalise to new people when tested honestly. For that reason Neurovox remains what it set out to be — a screening aid that measures, explains and suggests a doctor’s visit, and never a diagnosis.");

// ---------------- References
unnumbered("References");
const refs = [
  "World Health Organization, “Parkinson disease,” Fact sheet, 2023. [Online]. Available: https://www.who.int/news-room/fact-sheets/detail/parkinson-disease",
  "R. B. Postuma *et al.*, “MDS clinical diagnostic criteria for Parkinson’s disease,” *Movement Disorders*, vol. 30, no. 12, pp. 1591–1601, 2015.",
  "J. A. Logemann, H. B. Fisher, B. Boshes and E. R. Blonsky, “Frequency and cooccurrence of vocal tract dysfunctions in the speech of a large sample of Parkinson patients,” *Journal of Speech and Hearing Disorders*, vol. 43, no. 1, pp. 47–57, 1978.",
  "J. Rusz, R. Cmejla, H. Ruzickova and E. Ruzicka, “Quantitative acoustic measurements for characterization of speech and voice disorders in early untreated Parkinson’s disease,” *Journal of the Acoustical Society of America*, vol. 129, no. 1, pp. 350–367, 2011.",
  "M. A. Little, P. E. McSharry, E. J. Hunter, J. Spielman and L. O. Ramig, “Suitability of dysphonia measurements for telemonitoring of Parkinson’s disease,” *IEEE Transactions on Biomedical Engineering*, vol. 56, no. 4, pp. 1015–1022, 2009.",
  "J. P. Teixeira, C. Oliveira and C. Lopes, “Vocal acoustic analysis – jitter, shimmer and HNR parameters,” *Procedia Technology*, vol. 9, pp. 1112–1122, 2013.",
  "P. Boersma and D. Weenink, *Praat: doing phonetics by computer* [Computer program], version 6.1.38. [Online]. Available: https://www.praat.org",
  "Kay Elemetrics Corp., *Multi-Dimensional Voice Program (MDVP), Model 5105: Software Instruction Manual*. Lincoln Park, NJ, USA, 1993.",
  "P. Boersma, “Accurate short-term analysis of the fundamental frequency and the harmonics-to-noise ratio of a sampled sound,” *Proceedings of the Institute of Phonetic Sciences, University of Amsterdam*, vol. 17, pp. 97–110, 1993.",
  "A. de Cheveigné and H. Kawahara, “YIN, a fundamental frequency estimator for speech and music,” *Journal of the Acoustical Society of America*, vol. 111, no. 4, pp. 1917–1930, 2002.",
  "M. A. Little, P. E. McSharry, S. J. Roberts, D. A. E. Costello and I. M. Moroz, “Exploiting nonlinear recurrence and fractal scaling properties for voice disorder detection,” *BioMedical Engineering OnLine*, vol. 6, art. 23, 2007.",
  "M. Little, “Parkinsons,” UCI Machine Learning Repository, 2008. [Online]. Available: https://archive.ics.uci.edu/dataset/174/parkinsons",
  "A. Tsanas, M. A. Little, P. E. McSharry and L. O. Ramig, “Accurate telemonitoring of Parkinson’s disease progression by noninvasive speech tests,” *IEEE Transactions on Biomedical Engineering*, vol. 57, no. 4, pp. 884–893, 2010.",
  "C. O. Sakar *et al.*, “A comparative analysis of speech signal processing algorithms for Parkinson’s disease classification and the use of the tunable Q-factor wavelet transform,” *Applied Soft Computing*, vol. 74, pp. 255–263, 2019.",
  "Chaitanya Bharathi Institute of Technology (A), *Scheme of Instruction and Syllabi (R-22A), Computer Engineering and Technology*, course 22ITC02N Java Programming, 2026.",
  "A. E. Rosenberg, “Effect of glottal pulse shape on the quality of natural vowels,” *Journal of the Acoustical Society of America*, vol. 49, no. 2B, pp. 583–590, 1971.",
  "H. Schildt, *Java: The Complete Reference*, 12th ed. New York: McGraw-Hill, 2020.",
  "E. Balagurusamy, *Programming with Java*, 6th ed. New Delhi: McGraw-Hill Education, 2019.",
  "Oracle, “Java Sound API — Package javax.sound.sampled,” Java SE 17 API Specification. [Online]. Available: https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/sound/sampled/package-summary.html",
  "Y. Jadoul, B. Thompson and B. de Boer, “Introducing Parselmouth: a Python interface to Praat,” *Journal of Phonetics*, vol. 71, pp. 1–15, 2018.",
];
refs.forEach((r, i) => body.push(new Paragraph({ spacing: { after: 100, line: 300 }, alignment: AlignmentType.JUSTIFIED,
  indent: { left: 560, hanging: 560 }, children: [new TextRun({ text: `[${i + 1}]\t` }), ...runs(r)],
  tabStops: [{ type: TabStopType.LEFT, position: 560 }] })));

// ---------------- Appendices
unnumbered("Appendix A: Source Code", "APPENDIX A: SOURCE CODE");
P("The complete source of the console version, exactly as in the repository folder `exam-version/`. Line numbers are for reference only and are not part of the code.");
listing("Main.java", "A.1  Main.java (default package)");
listing("audio/VoiceSource.java", "A.2  audio/VoiceSource.java (package audio)");
listing("analysis/PitchTracker.java", "A.3  analysis/PitchTracker.java (package analysis)");
listing("analysis/VoiceReport.java", "A.4  analysis/VoiceReport.java (package analysis)");

unnumbered("Appendix B: User Manual", "APPENDIX B: USER MANUAL");
bullets([
  "Install JDK 11 or newer and check with `java -version`.",
  "Open a command prompt in the `exam-version` folder (it contains `Main.java`, `audio` and `analysis`).",
  "Compile: `javac Main.java audio/*.java analysis/*.java` (on Windows: `audio\\*.java analysis\\*.java`, or double-click `run.bat`).",
  "Run: `java Main` and type the patient’s name.",
  "For a live test choose **1**, take a breath and say a steady “aaah” at normal loudness for the whole 5 seconds, 20–30 cm from the microphone, in a quiet room. The recording is also saved as `recording.wav`.",
  "To analyse an existing recording choose **2** and type the path of a 16-bit WAV file of at least 2 seconds. Phone recordings can be converted to WAV with any audio editor (for example Audacity: File → Export → WAV, signed 16-bit PCM).",
  "To demonstrate without a microphone choose **3** (steady voice) or **4** (shaky voice).",
  "Choose **5** to compare all voices analysed in this run, and **6** to see every saved result from `history.csv`.",
  "Choose **0** to exit. Delete `history.csv` to clear the history.",
], true);
P("**If a check fails:** “Too quiet” — speak louder or move closer; “No steady voice” — hold one vowel without stopping; “No clear voice found” — move to a quieter room; “Microphone not available” — allow microphone access for the terminal in the operating system settings, or use options 2–4.");

unnumbered("Appendix C: Team Contribution", "APPENDIX C: TEAM CONTRIBUTION");
ch = "C"; tabN = 0;
table("Work division by package", ["Member", "Roll number", "Web prototype", "Console version"], [
  ["Pranav", "160125749063", "Signal processing: audio and dsp packages (resampling, pitch detectors, pulse tracking)", "Package audio (VoiceSource and subclasses); report"],
  ["Bhanuteja", "160125749048", "Features and ML: features, ml, training and validation packages", "Package analysis (PitchTracker, VoiceReport); accuracy study"],
  ["Md. Ali", "160125749055", "Application: Main, server, db, session, storage, analysis packages and the website; live demo", "Main class (menu, thread, history); test cases"],
], [1.2, 1.5, 2.6, 2.1], { size: 18 });

// ================================================================== FRONT MATTER
const front = [];
const C = (t, o = {}) => new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: o.after ?? 120 }, children: [new TextRun({ text: t, font: FONT, size: o.size || 24, bold: o.bold, italics: o.italics, color: o.color })] });
const frontTitle = (t) => front.push(new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, spacing: { before: 200, after: 360 },
  border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: ACCENT, space: 8 } }, children: [new TextRun({ text: t })] }));
const frontToc = [];
function frontPage(title, findText) {
  front.push(new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, spacing: { before: 200, after: 360 }, pageBreakBefore: true,
    border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: ACCENT, space: 8 } }, children: [new TextRun({ text: title })] }));
  frontToc.push({ level: 0, text: title, find: findText || title, roman: true });
}
const FP = (t) => front.push(new Paragraph({ children: runs(t), alignment: AlignmentType.JUSTIFIED, spacing: { after: 160, line: 360 } }));

// Title page (own section, no number)
const titlePage = [
  C("A Course End Project Report on", { size: 26, italics: true, after: 200 }),
  C("NEUROVOX", { size: 56, bold: true, color: ACCENT, after: 60 }),
  C("Voice-Based Parkinson’s Disease Screening using Java", { size: 30, bold: true, after: 400 }),
  C("Submitted in partial fulfilment of the requirements of the course", { size: 22, after: 40 }),
  C("JAVA PROGRAMMING (22ITC02N)", { size: 24, bold: true, after: 40 }),
  C("Bachelor of Engineering — III Semester", { size: 22, after: 40 }),
  C("Computer Science and Engineering (IoT and Cyber Security including Blockchain Technology)", { size: 22, after: 400 }),
  C("Submitted by", { size: 22, italics: true, after: 120 }),
];
const teamRows = [["Pranav", "160125749063"], ["Bhanuteja", "160125749048"], ["Md. Ali", "160125749055"]];
const teamTable = new Table({ width: { size: 5200, type: WidthType.DXA }, columnWidths: [2800, 2400], alignment: AlignmentType.CENTER,
  borders: { top: { style: BorderStyle.NONE }, bottom: { style: BorderStyle.NONE }, left: { style: BorderStyle.NONE }, right: { style: BorderStyle.NONE }, insideHorizontal: { style: BorderStyle.NONE }, insideVertical: { style: BorderStyle.NONE } },
  rows: teamRows.map(([n, r]) => new TableRow({ children: [n, r].map((t, i) => new TableCell({ width: { size: [2800, 2400][i], type: WidthType.DXA },
    borders: { top: { style: BorderStyle.NONE }, bottom: { style: BorderStyle.NONE }, left: { style: BorderStyle.NONE }, right: { style: BorderStyle.NONE } },
    children: [new Paragraph({ alignment: i ? AlignmentType.LEFT : AlignmentType.RIGHT, spacing: { after: 40 }, indent: i ? { left: 300 } : { right: 300 },
      children: [new TextRun({ text: t, size: 24, bold: i === 0 })] })] })) })) });
const titlePage2 = [
  new Paragraph({ spacing: { after: 300 }, children: [] }),
  C("Under the guidance of", { size: 22, italics: true, after: 40 }),
  C("Mrs. Sujatha Guptha", { size: 26, bold: true, after: 20 }),
  C("Faculty, Java Programming", { size: 22, after: 500 }),
  C("DEPARTMENT OF COMPUTER ENGINEERING AND TECHNOLOGY", { size: 24, bold: true, after: 40, color: ACCENT }),
  C("CHAITANYA BHARATHI INSTITUTE OF TECHNOLOGY (A)", { size: 26, bold: true, after: 40 }),
  C("Gandipet, Hyderabad – 500 075, Telangana", { size: 22, after: 40 }),
  C("2026 – 2027", { size: 24, bold: true, after: 0 }),
];

// Certificate
front.push(new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, spacing: { before: 200, after: 120 },
  children: [new TextRun({ text: "CERTIFICATE" })] }));
front.push(C("CHAITANYA BHARATHI INSTITUTE OF TECHNOLOGY (A)", { bold: true, size: 24, after: 40 }));
front.push(C("Department of Computer Engineering and Technology", { size: 22, after: 400 }));
frontToc.push({ level: 0, text: "Certificate", find: "CERTIFICATE", roman: true });
FP("This is to certify that the course end project report entitled **“Neurovox: Voice-Based Parkinson’s Disease Screening using Java”** is a bonafide record of the work carried out by **Pranav (160125749063)**, **Bhanuteja (160125749048)** and **Md. Ali (160125749055)**, students of B.E. III Semester, CSE (IoT and Cyber Security including Blockchain Technology), in partial fulfilment of the requirements of the course **Java Programming (22ITC02N)** during the academic year 2026–2027.");
FP("The work embodied in this report has not been submitted elsewhere for any other course or degree.");
front.push(new Paragraph({ spacing: { before: 1400, after: 0 }, tabStops: [{ type: TabStopType.RIGHT, position: W }],
  children: [new TextRun({ text: "Mrs. Sujatha Guptha", bold: true }), new TextRun({ text: "\tHead of the Department", bold: true })] }));
front.push(new Paragraph({ tabStops: [{ type: TabStopType.RIGHT, position: W }],
  children: [new TextRun({ text: "Course Faculty, Java Programming" }), new TextRun({ text: "\tComputer Engineering and Technology" })] }));

frontPage("DECLARATION");
FP("We hereby declare that the project report entitled **“Neurovox: Voice-Based Parkinson’s Disease Screening using Java”**, submitted for the course Java Programming (22ITC02N), is a record of our own work carried out under the guidance of Mrs. Sujatha Guptha. The results in this report have been obtained by us from our own code and validation scripts, and the sources we used are acknowledged in the references.");
FP("Software assistance (an AI coding assistant) was used during development and for drafting parts of this report. Every algorithm and line of code in the submitted version has been reviewed, tested and can be explained by the team.");
front.push(new Paragraph({ spacing: { before: 900 }, children: [new TextRun({ text: "Place: Hyderabad" })] }));
front.push(new Paragraph({ spacing: { after: 400 }, children: [new TextRun({ text: "Date:" })] }));
teamRows.forEach(([n, r]) => front.push(new Paragraph({ alignment: AlignmentType.RIGHT, spacing: { after: 60 }, children: [new TextRun({ text: `${n} (${r})`, bold: true })] })));

frontPage("ACKNOWLEDGEMENT");
FP("We express our sincere gratitude to **Mrs. Sujatha Guptha**, our Java Programming faculty, for her guidance, for encouraging us to take up a project that connects programming with a real health problem, and for pushing us to understand and write every part of the code ourselves.");
FP("We thank the **Head of the Department of Computer Engineering and Technology** and the management of **Chaitanya Bharathi Institute of Technology** for providing the facilities and the opportunity to carry out this project.");
FP("We are grateful to the researchers who made their work openly available — in particular Max Little and colleagues for the UCI Parkinson’s dataset, and Paul Boersma and David Weenink for Praat, which served as our reference throughout validation.");
FP("Finally, we thank our families and friends for their support and patience during the semester.");

frontPage("ABSTRACT");
FP("Parkinson’s disease affects more than 8.5 million people worldwide and is diagnosed clinically, often after much of the affected brain tissue has been lost. Changes in the voice — irregular vocal-fold vibration, unstable loudness and breathiness — appear in about nine out of ten patients and can be measured from a few seconds of sustained “aaah”.");
FP("**Neurovox** is a Java application that records or loads such a vowel and measures four classical voice properties with algorithms written from first principles: the fundamental frequency (F0) by normalised autocorrelation, and, after tracking every vocal cycle by cross-correlation with parabolic refinement, the cycle-to-cycle variation in timing (**jitter**), in loudness (**shimmer**) and the harmonics-to-noise ratio (**HNR**). Each measure is compared with a published reference limit (jitter 1.04%, shimmer 3.81%, HNR 20 dB) and a binary screening indication is given: *further check suggested* when two or more limits are exceeded, otherwise *no concern indicated*. The program never claims to diagnose.");
FP("The submitted version is a console program of about 395 lines in four short files — one `Main` class and two packages (`audio`, `analysis`) — with no external libraries. It is designed to be written by hand in an examination and uses the topics of the Java Programming syllabus: abstract classes, inheritance, nested classes, interfaces, packages, user-defined exceptions, multithreading with synchronisation, generics, collections, lambda expressions and buffered file I/O.");
FP("On 40 synthetic vowels with exactly known values, the program measured F0 with a mean error of 0.22 Hz (r = 1.000) and jitter with r = 0.92, and its final decision agreed with the reference decision on 33 of 40 files without any missed case. An earlier web prototype by the same team matched Praat to within 0.15% on 16 measures, but showed that a classifier trained on the public UCI dataset falls below the majority baseline (69.2% against 75.4%) when tested on unseen people — which is why Neurovox uses transparent limits instead.");
FP("**Keywords:** Parkinson’s disease, voice analysis, jitter, shimmer, harmonics-to-noise ratio, autocorrelation, Java, object-oriented programming.");

// Contents lists
function tocLine(text, pageText, level, bold) {
  return new Paragraph({ spacing: { after: level === 0 ? 80 : 40 }, indent: { left: level * 440, right: 500 },
    tabStops: [{ type: TabStopType.RIGHT, position: W, leader: LeaderType.DOT }],
    children: [new TextRun({ text, bold }), new TextRun({ text: "\t" + pageText, bold })] });
}
const pg = (key) => (PAGES[key] !== undefined ? String(PAGES[key]) : "00");

frontPage("TABLE OF CONTENTS");
const allFrontEntries = ["Certificate", "DECLARATION", "ACKNOWLEDGEMENT", "ABSTRACT", "TABLE OF CONTENTS", "LIST OF FIGURES", "LIST OF TABLES", "LIST OF ABBREVIATIONS"];
const tocBlock = [];
for (const t of allFrontEntries) {
  const label = t === "Certificate" ? "Certificate" : t.charAt(0) + t.slice(1).toLowerCase();
  tocBlock.push(tocLine(label, pg("front:" + t), 0, false));
}
for (const e of toc) tocBlock.push(tocLine(e.text, pg("body:" + e.find), e.level, e.level === 0));
front.push(...tocBlock);

frontPage("LIST OF FIGURES");
for (const f of lof) front.push(tocLine(f.text, pg("body:" + f.find), 0, false));
frontPage("LIST OF TABLES");
for (const t of lot) front.push(tocLine(t.text, pg("body:" + t.find), 0, false));

frontPage("LIST OF ABBREVIATIONS");
const abbr = [["API", "Application Programming Interface"], ["CSV", "Comma-Separated Values"], ["DC", "Direct Current (constant offset)"],
  ["F0", "Fundamental frequency (pitch) of the voice"], ["HNR", "Harmonics-to-Noise Ratio"], ["JDK", "Java Development Kit"],
  ["MAE", "Mean Absolute Error"], ["MDVP", "Multi-Dimensional Voice Program (Kay Elemetrics)"], ["MFCC", "Mel-Frequency Cepstral Coefficients"],
  ["NHR", "Noise-to-Harmonics Ratio"], ["PCM", "Pulse-Code Modulation"], ["PD", "Parkinson’s Disease"], ["RAP", "Relative Average Perturbation"],
  ["RMS", "Root Mean Square"], ["ROC-AUC", "Area Under the Receiver Operating Characteristic curve"], ["SNR", "Signal-to-Noise Ratio"],
  ["UCI", "University of California, Irvine (Machine Learning Repository)"], ["UPDRS", "Unified Parkinson’s Disease Rating Scale"],
  ["WAV", "Waveform Audio File Format"], ["YIN", "Pitch-detection algorithm of de Cheveigné and Kawahara"]];
abbr.forEach(([a, b]) => front.push(new Paragraph({ spacing: { after: 60 }, tabStops: [{ type: TabStopType.LEFT, position: 1700 }],
  children: [new TextRun({ text: a, bold: true }), new TextRun({ text: "\t" + b })] })));

// ================================================================== DOCUMENT
const hdr = new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT,
  border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: "BBBBBB", space: 4 } },
  children: [new TextRun({ text: "Neurovox — Voice-Based Parkinson’s Screening", size: 18, italics: true, color: "666666" })] })] });
const ftr = new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ children: [PageNumber.CURRENT], size: 20 })] })] });
const margins = { top: 1440, bottom: 1300, left: 1800, right: 1440, header: 700, footer: 700 };
const page = { size: { width: 11906, height: 16838 }, margin: margins };

const doc = new Document({
  creator: "Pranav, Bhanuteja, Md. Ali", title: "Neurovox — Course End Project Report", description: "Java Programming (22ITC02N) course end project report",
  styles: {
    default: { document: { run: { font: FONT, size: 24 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 32, bold: true, font: FONT, color: "1F1A2E" }, paragraph: { spacing: { before: 240, after: 240 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 27, bold: true, font: FONT, color: ACCENT }, paragraph: { spacing: { before: 240, after: 120 }, outlineLevel: 1 } },
      { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 24, bold: true, italics: true, font: FONT }, paragraph: { spacing: { before: 200, after: 100 }, outlineLevel: 2 } },
    ],
  },
  numbering: { config: bulletConfigs },
  sections: [
    { properties: { page, verticalAlign: "center" }, children: [...titlePage, teamTable, ...titlePage2] },
    { properties: { page: { ...page, pageNumbers: { start: 1, formatType: NumberFormat.LOWER_ROMAN } } },
      footers: { default: ftr }, children: front },
    { properties: { page: { ...page, pageNumbers: { start: 1, formatType: NumberFormat.DECIMAL } } },
      headers: { default: hdr }, footers: { default: ftr }, children: body },
  ],
});

fs.writeFileSync(path.join(DIR, "toc_entries.json"), JSON.stringify({ front: allFrontEntries, body: [...toc, ...lof, ...lot].map((e) => e.find) }, null, 1));
Packer.toBuffer(doc).then((buf) => {
  fs.writeFileSync(path.join(OUT, "Neurovox_Project_Report.docx"), buf);
  console.log("written", buf.length, "bytes;", toc.length, "toc entries,", lof.length, "figures,", lot.length, "tables");
});
