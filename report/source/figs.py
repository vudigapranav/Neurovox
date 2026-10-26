"""Builds every figure for the Neurovox report into fig/."""
import json, subprocess, textwrap
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker
from scipy.io import wavfile
from PIL import Image, ImageDraw, ImageFont

R = Path(__file__).parent
F = R / "fig"
F.mkdir(exist_ok=True)
REPO = R.parent.parent
S = R / "data"

INK, ACC, ACC2, GRID, MUTED = "#1f2430", "#6b3fa0", "#d0457b", "#d9d9e3", "#6b6f80"
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "axes.edgecolor": "#888",
                     "axes.titleweight": "bold", "axes.titlesize": 11, "axes.spines.top": False,
                     "axes.spines.right": False, "axes.grid": True, "grid.color": "#e6e6ee", "savefig.dpi": 200})

# ---------------------------------------------------------------- diagrams (graphviz)
def dot(name, src):
    (F / f"{name}.dot").write_text(src)
    subprocess.run(["dot", "-Tpng", "-Gdpi=200", str(F / f"{name}.dot"), "-o", str(F / f"{name}.png")], check=True)

common = 'fontname="Helvetica"; node [fontname="Helvetica", fontsize=11, style="rounded,filled", fillcolor="#f4effa", color="#6b3fa0", penwidth=1.2]; edge [fontname="Helvetica", fontsize=9, color="#555555"];'

dot("architecture", f"""digraph G {{ {common} rankdir=LR; nodesep=0.35; ranksep=0.45;
  user [label="User\\n(says 'aaah')", shape=box, fillcolor="#fde9f1", color="#d0457b"];
  subgraph cluster_audio {{ label="package audio"; style="rounded,dashed"; color="#999999"; fontsize=11;
    vs [label="VoiceSource\\n«abstract»", shape=box];
    mic [label="Microphone", shape=box]; wav [label="WavFile\\n«final»", shape=box]; demo [label="Demo", shape=box]; }}
  subgraph cluster_analysis {{ label="package analysis"; style="rounded,dashed"; color="#999999"; fontsize=11;
    va [label="VoiceReport\\n«Runnable, Comparable»", shape=box]; pt [label="PitchTracker\\nfinds every cycle", shape=box]; }}
  subgraph cluster_main {{ label="default package"; style="rounded,dashed"; color="#999999"; fontsize=11;
    main [label="Main\\nmenu · thread · history", shape=box, fillcolor="#e8f1fb", color="#2e6aa8"]; }}
  hist [label="history.csv", shape=cylinder, fillcolor="#fff7e0", color="#b48a00"];
  rec  [label="recording.wav", shape=cylinder, fillcolor="#fff7e0", color="#b48a00"];
  user -> main [label="menu choice"]; main -> vs [label="load()"];
  vs -> mic [dir=back, arrowtail=empty, label="extends"]; vs -> wav [dir=back, arrowtail=empty]; vs -> demo [dir=back, arrowtail=empty];
  mic -> rec [style=dashed]; main -> va [dir=both, label="  thread  "]; va -> pt [label="new"]; pt -> vs [label="getSamples()", style=dashed];
  main -> hist [label="BufferedWriter"]; }}""")

dot("dataflow", f"""digraph G {{ {common} rankdir=TB; nodesep=0.3; ranksep=0.28; node [shape=box, width=3.6];
  a [label="1. Voice input\\nmicrophone (5 s) · WAV file · built-in demo", fillcolor="#fde9f1", color="#d0457b"];
  b [label="2. Decode 16-bit PCM bytes → samples in [-1, +1]"];
  c [label="3. Checks: length ≥ 2 s · keep middle 60 % · remove DC · loudness"];
  d [label="4. Rough pitch period: autocorrelation over 75–500 Hz"];
  e [label="5. Cycle tracking: cross-correlation of neighbouring cycles\\n+ parabolic interpolation → exact cycle lengths and peak heights"];
  f [label="6. Measures: F0 · jitter · shimmer · HNR"];
  g [label="7. Compare with reference limits → markers (0–3)"];
  h [label="8. Result: ≥ 2 markers → FURTHER CHECK SUGGESTED\\notherwise NO CONCERN INDICATED", fillcolor="#e8f1fb", color="#2e6aa8"];
  i [label="9. Report on screen + row appended to history.csv", fillcolor="#fff7e0", color="#b48a00"];
  a->b->c->d->e->f->g->h->i; }}""")

dot("classes", """digraph G { fontname="Helvetica"; rankdir=BT; nodesep=0.4; ranksep=0.55;
  node [shape=plain, fontname="Helvetica", fontsize=10]; edge [fontname="Helvetica", fontsize=9, color="#555555"];
  vs [label=<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" color="#6b3fa0" bgcolor="#f4effa">
   <tr><td><i><b>VoiceSource</b></i> «abstract»</td></tr><hr/>
   <tr><td align="left">+ RATE : float {static final}<br align="left"/># samples : double[]<br align="left"/># rate : float<br align="left"/></td></tr><hr/>
   <tr><td align="left">+ <i>load() : void</i> throws VoiceException<br align="left"/>+ <i>getName() : String</i><br align="left"/>+ getSamples(), getRate()<br align="left"/>+ toString() : String<br align="left"/># toSamples(byte[], int) : double[]<br align="left"/></td></tr></table>>];
  mic [label=<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" color="#6b3fa0" bgcolor="#ffffff">
   <tr><td><b>Microphone</b></td></tr><hr/><tr><td align="left">- seconds : int<br align="left"/></td></tr><hr/>
   <tr><td align="left">+ Microphone(), Microphone(int)<br align="left"/>+ load()<br align="left"/></td></tr></table>>];
  wav [label=<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" color="#6b3fa0" bgcolor="#ffffff">
   <tr><td><b>WavFile</b> «final»</td></tr><hr/><tr><td align="left">- path : String<br align="left"/></td></tr><hr/>
   <tr><td align="left">+ WavFile(String)<br align="left"/>+ load()<br align="left"/></td></tr></table>>];
  demo [label=<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" color="#6b3fa0" bgcolor="#ffffff">
   <tr><td><b>Demo</b></td></tr><hr/><tr><td align="left">- shaky : boolean<br align="left"/></td></tr><hr/>
   <tr><td align="left">+ Demo(), Demo(boolean)<br align="left"/>+ load()<br align="left"/></td></tr></table>>];
  ex [label=<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" color="#b0413e" bgcolor="#fdeeee">
   <tr><td><b>VoiceException</b></td></tr><hr/><tr><td align="left">+ VoiceException(String)<br align="left"/></td></tr></table>>];
  exc [label=<<table border="0" cellborder="1" cellspacing="0" cellpadding="3" color="#999999"><tr><td>java.lang.Exception</td></tr></table>>];
  va [label=<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" color="#2e6aa8" bgcolor="#e8f1fb">
   <tr><td><b>VoiceReport</b></td></tr><hr/>
   <tr><td align="left">+ F0, JITTER, SHIMMER, HNR : String<br align="left"/>+ JITTER_LIMIT, SHIMMER_LIMIT, HNR_LIMIT<br align="left"/>- features : Map&lt;String, Double&gt;<br align="left"/>- error : String ; - done : boolean<br align="left"/></td></tr><hr/>
   <tr><td align="left">+ run() ; + isDone() {synchronized}<br align="left"/>~ mean(List&lt;Double&gt;), change(List&lt;Double&gt;)<br align="left"/>+ markers() : int ; + needsFurtherCheck()<br align="left"/>+ report() ; + toCsv() ; + compareTo()<br align="left"/></td></tr></table>>];
  pt [label=<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" color="#2e6aa8" bgcolor="#e8f1fb">
   <tr><td><b>PitchTracker</b></td></tr><hr/>
   <tr><td align="left">~ periods, peaks : List&lt;Double&gt;<br align="left"/>~ similarity : double<br align="left"/></td></tr><hr/>
   <tr><td align="left">+ PitchTracker(VoiceSource) throws VoiceException<br align="left"/>~ corr(double[], int, int, int) : double<br align="left"/></td></tr></table>>];
  ifs [label=<<table border="0" cellborder="1" cellspacing="0" cellpadding="3" color="#999999"><tr><td>«interface» Runnable</td></tr><tr><td>«interface» Comparable&lt;VoiceReport&gt;</td></tr></table>>];
  main [label=<<table border="1" cellborder="0" cellspacing="0" cellpadding="3" color="#555555" bgcolor="#f6f6f6">
   <tr><td><b>Main</b></td></tr><hr/><tr><td align="left">- session : List&lt;VoiceReport&gt;<br align="left"/></td></tr><hr/>
   <tr><td align="left">+ main(String[])<br align="left"/>~ screen(), compare()<br align="left"/>~ saveHistory(), showHistory()<br align="left"/>~ &lt;T&gt; printAll(String, List&lt;T&gt;)<br align="left"/></td></tr></table>>];
  mic -> vs [arrowhead=empty]; wav -> vs [arrowhead=empty]; demo -> vs [arrowhead=empty];
  ex -> exc [arrowhead=empty]; va -> ifs [arrowhead=empty, style=dashed, label="implements"];
  va -> pt [arrowhead=vee, style=dashed, label="creates"]; pt -> vs [arrowhead=vee, style=dashed, label="uses"]; main -> va [arrowhead=vee, style=dashed, label="creates"];
  main -> vs [arrowhead=vee, style=dashed]; }""")

dot("usecase", f"""digraph G {{ {common} rankdir=LR; nodesep=0.25;
  u [label="User / Patient", shape=box, fillcolor="#fde9f1", color="#d0457b"];
  subgraph cluster_s {{ label="Neurovox (console)"; style="rounded"; color="#6b3fa0";
    node [shape=ellipse, fillcolor="#ffffff"];
    r1 [label="Record voice"]; r2 [label="Analyse WAV file"]; r3 [label="Run demo voice"];
    r4 [label="View screening report"]; r5 [label="Compare session"]; r6 [label="View saved history"]; }}
  u -> r1; u -> r2; u -> r3; u -> r5; u -> r6;
  r1 -> r4 [style=dashed, label="«include»"]; r2 -> r4 [style=dashed]; r3 -> r4 [style=dashed]; }}""")

dot("thread", f"""digraph G {{ {common} rankdir=LR; nodesep=0.3; node [shape=box];
  n [label="NEW\\nnew Thread(a)"]; r [label="RUNNABLE\\nstart()"]; ru [label="RUNNING\\nrun() → new PitchTracker()"];
  w [label="main: TIMED_WAITING\\nThread.sleep(50) + isDone()", fillcolor="#e8f1fb", color="#2e6aa8"];
  t [label="TERMINATED\\nfinish(): done = true"]; j [label="main: join()\\nthen report()", fillcolor="#e8f1fb", color="#2e6aa8"];
  n->r->ru->t->j; ru->w [style=dashed, label="meanwhile"]; w->j [style=dashed]; }}""")

# ---------------------------------------------------------------- demo waveforms (same formula as Demo.load)
def demo(shaky, rate=44100, secs=3.0):
    r = np.random.default_rng(7)
    jit, shim, noise = (0.025, 0.12, 0.05) if shaky else (0.004, 0.02, 0.005)
    x = np.zeros(int(secs * rate)); start = 0
    while start < len(x):
        n = int(round(rate / 140 * (1 + jit * r.standard_normal())))
        amp = 0.5 * (1 + shim * r.standard_normal())
        k = np.arange(min(n, len(x) - start)); ph = 2 * np.pi * k / n
        x[start:start + len(k)] = amp * (np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.25 * np.sin(3 * ph)) + noise * r.standard_normal(len(k))
        start += n
    return x

fig, axs = plt.subplots(2, 1, figsize=(7.2, 3.6), sharex=True)
for ax, sh, title, col in [(axs[0], False, "Steady voice (jitter 0.4 %, shimmer 2 %)", ACC), (axs[1], True, "Shaky voice (jitter 2.5 %, shimmer 12 %, more noise)", ACC2)]:
    x = demo(sh); t = np.arange(len(x)) / 44100 * 1000
    m = (t >= 1000) & (t < 1060)
    ax.plot(t[m], x[m], color=col, lw=1.1); ax.set_title(title, loc="left"); ax.set_ylabel("amplitude"); ax.set_ylim(-1.2, 1.2)
axs[1].set_xlabel("time (ms)")
fig.tight_layout(); fig.savefig(F / "demo_waves.png"); plt.close(fig)

# ---------------------------------------------------------------- autocorrelation & cycle tracking on a real test vowel
rate, w = wavfile.read(REPO / "validation/synthetic/syn10.wav")
x = w.astype(float) / 32768
x = x[len(x)//5: len(x) - len(x)//5]; x -= x.mean()
lags = np.arange(int(rate/500), int(rate/75) + 1); L = len(x) - lags[-1]
def corr(a, b, n):
    p, q = x[a:a+n], x[b:b+n]; return (p @ q) / np.sqrt((p @ p) * (q @ q))
rc = np.array([corr(0, l, L) for l in lags])
best = lags[rc.argmax()]
fig, axs = plt.subplots(1, 2, figsize=(7.4, 2.9), gridspec_kw={"width_ratios": [1.15, 1]})
tt = np.arange(int(0.04 * rate)) / rate * 1000
axs[0].plot(tt, x[:len(tt)], color=ACC, lw=1)
for k in range(1, 7): axs[0].axvline(k * best / rate * 1000, color=ACC2, lw=0.8, ls="--")
axs[0].set_title("Test vowel syn10 (40 ms)", loc="left"); axs[0].set_xlabel("time (ms)"); axs[0].set_ylabel("amplitude")
axs[1].plot(rate / lags, rc, color=INK, lw=1.1)
axs[1].scatter([rate / best], [rc.max()], color=ACC2, zorder=3)
axs[1].annotate(f"best lag {best} samples\n= {rate/best:.1f} Hz", (rate / best, rc.max()), xytext=(230, 0.55),
                fontsize=9, arrowprops=dict(arrowstyle="->", color="#555555"))
axs[1].set_xscale("log"); axs[1].xaxis.set_minor_formatter(matplotlib.ticker.NullFormatter()); axs[1].set_xticks([75, 100, 150, 200, 300, 500]); axs[1].set_xticklabels(["75", "100", "150", "200", "300", "500"])
axs[1].set_title("Autocorrelation vs candidate F0", loc="left"); axs[1].set_xlabel("candidate F0 (Hz)"); axs[1].set_ylabel("r")
fig.tight_layout(); fig.savefig(F / "autocorr.png"); plt.close(fig)

# ---------------------------------------------------------------- validation scatter (exam version vs known truth)
res = json.load(open(S / "synthetic_results.json"))
a = np.array([r[1:9] for r in res], dtype=float)
fig, axs = plt.subplots(1, 3, figsize=(7.6, 2.7))
for ax, (m, t, name, unit) in zip(axs, [(0, 1, "F0", "Hz"), (2, 3, "Jitter", "%"), (4, 5, "Shimmer", "%")]):
    ax.scatter(a[:, t], a[:, m], s=14, color=ACC, alpha=0.85, edgecolor="white", lw=0.4)
    lo, hi = min(a[:, t].min(), a[:, m].min()), max(a[:, t].max(), a[:, m].max())
    ax.plot([lo, hi], [lo, hi], color="#999999", lw=0.8, ls="--")
    r = np.corrcoef(a[:, t], a[:, m])[0, 1]; mae = np.mean(abs(a[:, m] - a[:, t]))
    ax.set_title(f"{name}: r = {r:.3f}", loc="left"); ax.set_xlabel(f"true ({unit})"); ax.set_ylabel(f"measured ({unit})")
    ax.text(0.97, 0.05, f"MAE {mae:.2f} {unit}", transform=ax.transAxes, ha="right", fontsize=8.5, color=MUTED)
fig.tight_layout(); fig.savefig(F / "validation.png"); plt.close(fig)

fig, ax = plt.subplots(figsize=(5.4, 2.8))
ax.scatter(a[:, 7], a[:, 6], s=16, color=ACC2, alpha=0.85, edgecolor="white", lw=0.4)
ax.set_xlabel("added noise: signal-to-noise ratio (dB)"); ax.set_ylabel("measured HNR (dB)")
ax.set_title(f"HNR follows the added noise (r = {np.corrcoef(a[:,7], a[:,6])[0,1]:.2f})", loc="left")
ax.axhline(20, color="#999999", ls="--", lw=0.8); ax.text(12.3, 20.4, "20 dB limit", fontsize=8, color=MUTED)
fig.tight_layout(); fig.savefig(F / "hnr.png"); plt.close(fig)

# per-file stats for the report text
stats = {}
for name, (m, t) in {"f0": (0, 1), "jitter": (2, 3), "shimmer": (4, 5)}.items():
    stats[name] = dict(mae=float(np.mean(abs(a[:, m] - a[:, t]))), bias=float(np.mean(a[:, m] - a[:, t])), r=float(np.corrcoef(a[:, m], a[:, t])[0, 1]))
stats["hnr_snr_r"] = float(np.corrcoef(a[:, 6], a[:, 7])[0, 1])
stats["flagged"] = int(sum(1 for r in res if r[9] == "CHECK"))
json.dump(stats, open(R / "stats.json", "w"), indent=1)

# ---------------------------------------------------------------- console screenshots
def terminal(name, lines, width=86):
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", 26)
    bold = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf", 26)
    lh, pad, bar = 34, 30, 50
    W = int(pad * 2 + width * 15.7); H = bar + pad * 2 + lh * len(lines)
    img = Image.new("RGB", (W, H), "#16131d"); d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, bar], fill="#2a2533")
    for i, c in enumerate(["#ff5f57", "#febc2e", "#28c840"]): d.ellipse([20 + i * 34, 16, 38 + i * 34, 34], fill=c)
    d.text((W // 2 - 120, 12), "Command Prompt - java Main", font=font, fill="#b9b3c6")
    for i, (txt, kind) in enumerate(lines):
        col = {"in": "#7fd4ff", "ok": "#7ee2a8", "bad": "#ff8fa8", "head": "#e6c2ff", "dim": "#8d879a"}.get(kind, "#e8e6ee")
        d.text((pad, bar + pad + i * lh), txt, font=bold if kind in ("ok", "bad", "head") else font, fill=col)
    img.save(F / f"{name}.png")

def L(s, k=""): return (s, k)
menu = [L("1. Record my voice (5 s 'aah')"), L("2. Analyse a WAV file"), L("3. Demo - steady voice"),
        L("4. Demo - shaky voice"), L("5. Compare this session"), L("6. View saved history"), L("0. Exit")]
terminal("console_start", [L("C:\\neurovox\\exam-version> javac Main.java audio\\*.java analysis\\*.java", "dim"),
    L("C:\\neurovox\\exam-version> java Main", "dim"),
    L("===== NEUROVOX : Voice-based Parkinson's Screening =====", "head"), L("Patient name: Pranav", "in")] + menu + [L("Choice: 3", "in")])
terminal("console_steady", [L("Choice: 3", "in"), L("Analysing...."), L("Voice : Demo steady voice (3.0 s at 44100 Hz)"), L("Cycles: 250"),
    L("Pitch F0 (Hz) :  139.95"), L("Jitter (%)    :    0.30"), L("Shimmer (%)   :    2.35"), L("HNR (dB)      :   35.06"),
    L("Limits: jitter <= 1.04 %, shimmer <= 3.81 %, HNR >= 20 dB"), L("Markers outside limits: 0 of 3"),
    L("RESULT: NO CONCERN INDICATED", "ok"), L("--------------------------------------------------", "dim")])
terminal("console_shaky", [L("Choice: 4", "in"), L("Analysing..."), L("Voice : Demo shaky voice (3.0 s at 44100 Hz)"), L("Cycles: 250"),
    L("Pitch F0 (Hz) :  139.90"), L("Jitter (%)    :    2.21"), L("Shimmer (%)   :   13.57"), L("HNR (dB)      :   16.58"),
    L("Limits: jitter <= 1.04 %, shimmer <= 3.81 %, HNR >= 20 dB"), L("Markers outside limits: 3 of 3"),
    L("RESULT: FURTHER CHECK SUGGESTED (see a neurologist)", "bad"), L("--------------------------------------------------", "dim")])
terminal("console_compare", [L("Choice: 5", "in"), L("This session, steadiest voice first:"),
    L("  Demo steady voice  jitter  0.30 %  shimmer  2.35 %  HNR 35.1 dB -> OK"),
    L("  WAV syn10.wav      jitter  0.75 %  shimmer  5.41 %  HNR 21.2 dB -> OK"),
    L("  Demo shaky voice   jitter  2.21 %  shimmer 13.57 %  HNR 16.6 dB -> CHECK"),
    L("Flagged for further check: 1 of 3"), L(""), L("Choice: 6", "in"), L("Saved history:"),
    L("  Pranav  Mon Oct 26 13:45:35 IST 2026 F0 139.9 J 0.30% S  2.35% HNR 35.1 OK"),
    L("  Pranav  Mon Oct 26 13:45:35 IST 2026 F0 139.9 J 2.21% S 13.57% HNR 16.6 CHECK"),
    L("  Pranav  Mon Oct 26 13:45:35 IST 2026 F0 149.3 J 0.75% S  5.41% HNR 21.2 OK")], width=92)
terminal("console_errors", [L("Choice: 1", "in"), L("Say 'aaaah' steadily now..."),
    L("Check failed: Microphone not available: No line matching interface", "bad"), L("  TargetDataLine supporting format PCM_SIGNED 44100.0 Hz ...", "bad"),
    L("Choice: 2", "in"), L("WAV file path: noise.wav", "in"),
    L("Check failed: No clear voice found (only noise). Try again in a quiet room.", "bad"),
    L("Choice: 2", "in"), L("WAV file path: silence.wav", "in"), L("Check failed: Too quiet. Say 'aah' louder.", "bad"),
    L("Choice: 2", "in"), L("WAV file path: float.wav", "in"), L("Check failed: Please use a 16-bit PCM WAV file", "bad"),
    L("Choice: 9", "in"), L("Invalid choice.", "bad")], width=92)

# copy prototype figures
for src, dst in [("docs/screenshots/05-recording-orb.jpg", "web_record.jpg"), ("docs/screenshots/09-report-top.jpg", "web_report.jpg"),
                 ("docs/screenshots/11-history-trend.jpg", "web_history.jpg"), ("docs/results/figures/praat_scatter.png", "praat_scatter.png"),
                 ("docs/results/figures/pitch_study.png", "pitch_study.png")]:
    Image.open(REPO / src).convert("RGB").save(F / dst)
print(json.dumps(stats, indent=1))
