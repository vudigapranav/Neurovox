# Neurovox — Presentation Brief

**Document 3 of 3 — Deck specification**
Hand this to Claude Design to generate the slides.

---

## Brief

| | |
|---|---|
| **Deck title** | Neurovox — Voice-Based Parkinson's Screening |
| **Audience** | Faculty reviewer and classmates. Technically literate, not speech-science specialists. |
| **Duration** | 10–12 minutes plus a live demo and Q&A |
| **Slides** | 16 content slides, plus a demo break |
| **Presenters** | 3, each owning their section |
| **Format** | 16:9, projected in a classroom |

**The one thing the audience should leave with:** *Parkinson's disease changes the voice in ways that are measurable before they are audible — and we built the measuring instrument in Java.*

---

## Visual direction

### The idea

The subject's world is **instrumentation** — oscilloscopes, spectrograms, clinical measurement. Not medical-brochure warmth, not startup-pitch gradients. The deck should feel like a well-made measuring device: precise, calm, legible, with data as the ornament.

### Palette

| Role | Hex | Use |
|---|---|---|
| Paper | `#EDF1F0` | Primary background — cool clinical off-white, deliberately not cream |
| Ink | `#0D1B1E` | Body text, near-black with a blue-green cast |
| Signal | `#0F766E` | Primary accent — deep instrument teal. Headings, key data, waveforms on light. |
| Trace | `#5EEAD4` | Waveform on dark section dividers only. Used sparingly. |
| Flag | `#B45309` | Atypical/elevated readings, warnings. Warm amber-brown. |
| Muted | `#64748B` | Captions, axis labels, secondary text |

**Ratio discipline:** roughly 85% of slides are light (Paper ground). Dark slides (Ink ground with Trace waveforms) are reserved for the four section dividers. Inverting this ratio would make the deck look like every other technical presentation; keeping the dark rare makes each divider land.

**Projector note:** classroom projectors wash out dark backgrounds and crush low-contrast text. Light-dominant is a practical choice as much as an aesthetic one.

### Typography

| Role | Face | Treatment |
|---|---|---|
| Display | **Space Grotesk** | Slide titles, 40–52pt, medium weight, tight tracking. Sentence case, never all-caps. |
| Body | **IBM Plex Sans** | 20–24pt regular. Never below 18pt. |
| Data | **IBM Plex Mono** | Numbers, formulas, feature names, code, table values. This is what makes measurements read as measurements. |

All three are free via Google Fonts. The mono face doing real work — carrying every numeric value in the deck — is what gives it its instrument character.

### Signature element

**The periodicity strip.**

A horizontal row of thin vertical bars, each bar representing one glottal cycle of the voice. Bar *spacing* encodes period; bar *height* encodes amplitude.

- **Even spacing, even height** = a healthy voice
- **Irregular spacing** = jitter
- **Irregular height** = shimmer

This is not decoration. It is the disease made directly visible, and it is the single image that explains the entire project.

**Usage:**
- A quiet 4pt version sits as a rule beneath every slide title, in Signal at 30% opacity
- On slides 5, 6, and 7 it becomes the hero — full width, animated or shown as a regular/irregular pair
- On the dark section dividers it runs full-bleed in Trace

Everything else stays disciplined. This is where the deck spends its boldness.

### What to avoid

- Stock photography of elderly people or trembling hands — sentimental and off-message
- Medical clip-art, red crosses, stethoscopes
- Gradient meshes, glassmorphism, drop shadows
- Bullet-point walls. Maximum 5 lines of text per slide.
- Any visual implying the tool diagnoses

---

## Slide plan

Each entry gives content, visual treatment, and speaker notes.

---

### 1 — Title

**Neurovox**
Voice-Based Parkinson's Screening Indicator

Team names · Course · Date

*Visual:* Paper ground. The periodicity strip runs full-width across the lower third — regular on the left, gradually becoming irregular toward the right. No other elements.

*Notes:* Let the strip do the talking. Do not explain it yet.

---

### 2 — The problem

Parkinson's disease is diagnosed **clinically** — by examination, not by any test.

- No blood test or scan confirms it in a living patient
- Early diagnostic accuracy is around **80%**, even for specialists
- By the time tremor appears, **60–80%** of the relevant neurons are already lost

*Visual:* Three statistics in large mono type, generous whitespace. No icons.

*Notes:* Establish that this is a genuinely hard problem, not one already solved.

---

### 3 — Why voice

Speech is affected in about **90%** of patients — often early.

The disease impairs the muscles of the larynx, tongue, and lips exactly as it impairs the hands: reduced amplitude, reduced speed, poor coordination.

*Visual:* A simple two-column diagram. Left: a hand with the finger-tapping test. Right: a larynx cross-section. A connecting line labelled "same motor pathway." Line art in Ink and Signal only.

*Notes:* This is the conceptual hinge of the talk. The audience needs to accept that voice is a motor test, not a separate phenomenon.

---

### 4 — What changes, and how we measure it

| Clinical sign | Acoustic effect | Measured as |
|---|---|---|
| Hypophonia | Unstable loudness | Shimmer |
| Vocal fold rigidity | Irregular cycle timing | Jitter |
| Incomplete glottal closure | Added noise | HNR |
| Monopitch | Collapsed pitch range | F0 std. dev. |

*Visual:* Table with feature names in mono. The rightmost column in Signal.

---

### ▸ SECTION DIVIDER — *How it works*

*Visual:* Ink ground, full-bleed periodicity strip in Trace. Section title in Space Grotesk, reversed out.

---

### 5 — Jitter, made visible

**Jitter** — the cycle-to-cycle variation in the length of each vocal fold vibration.

*Visual:* **Hero slide.** Two periodicity strips stacked. Top labelled "regular" — perfectly even spacing. Bottom labelled "irregular" — visibly uneven. The measurement annotated between two adjacent bars.

Formula in mono, small, at the base:
`Jitter(local) = mean|Tᵢ − Tᵢ₊₁| / mean(T) × 100%`

*Notes:* Point at the strip while speaking. This is the slide people will remember.

---

### 6 — Shimmer and HNR

**Shimmer** — cycle-to-cycle variation in amplitude.
**HNR** — how much of the signal is periodic voice versus turbulent noise.

*Visual:* Same strip motif, now varying in *height* rather than spacing. Beside it, a small waveform pair: clean sine versus noise-contaminated sine.

---

### 7 — The four recording tasks

| Task | Duration | Isolates |
|---|---|---|
| Sustained `/a/` | 5 s | Pure phonation |
| `/pa-ta-ka/` repeated | 6 s | Articulatory agility |
| Reading passage | 20 s | Controlled comparison |
| Free speech | 30 s | Prosody, pauses |

*Visual:* Four cards in a row. Under `/pa-ta-ka/`, a small callout: *lips · tongue tip · tongue back.*

*Notes:* Worth saying aloud that `/pa/`, `/ta/`, `/ka/` were chosen because they exercise three different articulators — it shows the task design was deliberate.

---

### 8 — Architecture

*Visual:* The pipeline diagram. Browser on the left in Muted, Java backend on the right in Signal — **the colour split is the point**, showing that everything analytical lives in Java. Boxes: PCM upload → resample → framing → pitch tracking → features → model → result.

*Notes:* Under 30 seconds. Do not narrate every box.

---

### 9 — What we wrote, and what we didn't

| Written in Java by us | Library |
|---|---|
| Resampling, framing, windowing | FFT (JTransforms) |
| Autocorrelation + YIN pitch detection | SQLite driver |
| Jitter, shimmer, HNR, F0 statistics | JSON serialisation |
| Mel filterbank, DCT, MFCC | |
| Logistic regression inference | |

**Deliberately not used:** TarsosDSP, which provides ready-made pitch detection and MFCC — the exact components that constitute our work.

*Visual:* Two columns, sharply unequal in weight. Left column in Signal and dense; right in Muted and sparse.

*Notes:* This slide pre-empts the most likely challenge. Say the rule out loud: *if a library computes an acoustic feature, we wrote it ourselves; if it computes a number, we used it.*

---

### ▸ SECTION DIVIDER — *Does it actually work?*

---

### 10 — The trap we avoided

The dataset holds ~6 recordings per person. Splitting randomly by **recording** puts the same voice in training and test.

| Split method | Accuracy |
|---|---|
| Naive random split | `9X.X%` |
| **Patient-level (GroupKFold)** | `8X.X%` |

The first number is voice memorisation, not disease detection.

*Visual:* Two large numbers in mono. The naive one struck through in Flag; the grouped one clean in Signal.

*Notes:* **This is the strongest slide in the deck.** Nearly every student project on this dataset reports the inflated figure. Showing both, and explaining why, demonstrates that we understand our own evaluation.

*Fill in the real numbers from `docs/model-evaluation.md` before presenting.*

---

### 11 — Calibration against Praat

Our features are computed from scratch. Do they match the reference tool used in speech science?

| Feature | Correlation with Praat |
|---|---|
| Jitter (local) | `r = 0.XX` |
| Shimmer (local) | `r = 0.XX` |
| HNR | `r = 0.XX` |
| Mean F0 | `r = 0.XX` |

*Visual:* Scatter plot — Praat on x, Neurovox on y, with the identity line drawn. Points in Signal, identity line dashed in Muted.

*Notes:* Report whatever the results actually are. If a feature underperforms, say so and say why. An honest imperfect table is more credible than a suspiciously perfect one.

---

### 12 — Autocorrelation vs YIN

| | Autocorrelation | YIN |
|---|---|---|
| Mean F0 error | `X.X Hz` | `X.X Hz` |
| Octave error rate | `X.X%` | `X.X%` |
| Time per second of audio | `XX ms` | `XX ms` |

**Finding:** the choice of pitch detector propagates measurably into the final jitter values — the algorithm at the bottom of the stack changes the clinical number at the top.

*Visual:* Comparison table plus a small chart showing jitter computed both ways on the same samples.

*Notes:* This is our algorithm-comparison contribution. Land the finding, not just the table.

---

### ▸ DEMO

Live. Rehearsed. Under three minutes.

**Path:** record the sustained vowel → show the waveform → show the extracted features → show the screening result and feature report → show the trend view with a previous session.

**Have a screen recording ready as backup.** Microphone permissions fail at the worst possible moment.

---

### 13 — What we deliberately did not build

| Excluded | Why |
|---|---|
| Disease stage (Hoehn–Yahr) | Published error margins cannot separate adjacent stages. A stage number implies precision the signal does not have. |
| Treatment recommendation | This is medical practice, and it depends on medication response, non-motor symptoms, and cognition — none of which are in the audio. |

**Neurovox is a screening indicator, not a diagnosis.**

*Visual:* Restrained. Ink on Paper, no accent colour. The closing line centred and alone.

*Notes:* Do not rush this slide. Knowing what not to build is part of the engineering, and reviewers notice when a team can articulate its own limits.

---

### 14 — Honest limitations

- Trained on **31 subjects** — a small sample
- Training data is English-speaking; performance in other languages is unknown
- Confounds not fully controlled: microphone quality, room noise, age, sex
- Medication state alters the voice within a single day
- No clinical validation — we had no access to patients

*Visual:* Plain list, Muted. No accent, no icons.

*Notes:* Stating these before the reviewer raises them is much stronger than defending them afterwards.

---

### 15 — What we'd do next

- Extend the feature set to the six nonlinear measures (RPDE, D2, DFA, spread1, spread2, PPE)
- Collect a **Telugu/Hindi healthy-control corpus** — published models are trained almost entirely on English, Italian, and Spanish speakers, so cross-language behaviour is an open question
- Longitudinal study: track the same speakers over months
- Add LSVT LOUD-style guided speech exercises with real-time feedback

*Visual:* Four items with generous spacing. The language corpus item emphasised in Signal.

*Notes:* The language gap is real and worth flagging as a genuine research opportunity, not just future work.

---

### 16 — Close

**Neurovox**

*Parkinson's changes the voice before anyone can hear it. We built the instrument that measures the difference.*

Team names · Repository link

*Visual:* Return to the title-slide periodicity strip, now full-bleed. The regular-to-irregular transition completes across the full width.

---

## Speaker split

| Slides | Presenter | Section |
|---|---|---|
| 1–7 | Member 3 | Problem, clinical basis, task design |
| 8–9, demo | Member 1 | Architecture, signal processing, live demo |
| 10–12 | Member 2 | Evaluation, calibration, algorithm comparison |
| 13–16 | Member 3 | Limitations, future work, close |

---

## Anticipated questions

**"How do you know your jitter calculation is right?"**
Slide 11. The Praat calibration study exists specifically to answer this.

**"Why not use a neural network?"**
31 subjects. A neural network would overfit immediately, and logistic regression coefficients are interpretable — we can point at which acoustic feature drove a given result.

**"Isn't 85% accuracy too low to be useful?"**
It is too low to diagnose, which is why we do not claim to. As a screening indicator that prompts someone to see a neurologist, the relevant comparison is against no screening at all.

**"Could someone game this?"**
Yes. Deliberately unsteady phonation would raise the jitter value. It assumes a cooperative user, as most screening instruments do.

**"Why Java for signal processing rather than Python?"**
Course requirement, and the constraint turned out to be productive: implementing the DSP from scratch is where the actual learning happened.

**"What if the microphone is poor quality?"**
A known confound, listed on slide 14. Sprint 12 adds a signal-quality gate that rejects unusable recordings, but microphone variation remains a real limitation.

---

## Pre-presentation checklist

- [ ] Every `X.X` placeholder replaced with real measured numbers
- [ ] Demo rehearsed end-to-end at least three times
- [ ] Screen recording backup exported and tested
- [ ] Microphone permissions pre-granted on the presenting machine
- [ ] Database seeded with a prior session so the trend chart is not empty
- [ ] Deck tested on the actual projector — check contrast on the dark dividers
- [ ] Fonts embedded or the deck exported to PDF
