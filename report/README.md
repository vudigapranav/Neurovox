# Neurovox — project report

Course end project report for Java Programming (22ITC02N), covering the exam (console) version in
[`../exam-version/`](../exam-version/README.md) and a summary of the full web prototype.

| File | What it is |
|---|---|
| [`Neurovox_Project_Report.docx`](Neurovox_Project_Report.docx) | The report in Word (53 pages: front matter, 10 chapters, references, appendices) |
| [`Neurovox_Project_Report.pdf`](Neurovox_Project_Report.pdf) | The same report as PDF, for reading on any device |
| `source/` | Everything used to build the report (see below) |

**Contents:** title page, certificate, declaration, acknowledgement, abstract, table of contents, lists of
figures, tables and abbreviations · 1 Introduction · 2 Literature survey · 3 Requirements · 4 System design ·
5 Algorithms · 6 Implementation (with a syllabus map) · 7 Testing and results · 8 Web prototype and research
findings · 9 Limitations, ethics, future scope · 10 Conclusion · References (20) · Appendix A source code ·
Appendix B user manual · Appendix C team contribution.

Before submitting, fill in the date on the declaration page and get the certificate signed.

## Rebuilding the report

Needs Node.js with the `docx` package, Python 3 with numpy, scipy, matplotlib and Pillow, Graphviz (`dot`),
LibreOffice (`soffice`) and Poppler (`pdftotext`, `pdfinfo`).

```bash
cd report/source
python3 figs.py                 # diagrams, plots and console screenshots -> fig/
node build.js pages.json        # writes ../Neurovox_Project_Report.docx
python3 find_pages.py           # renders the PDF and updates pages.json with real page numbers
node build.js pages.json        # rebuild so the contents pages show those numbers
```

`figs.py` reads `validation/synthetic/syn10.wav`; create it first with
`python3 validation/make_synthetic_vowels.py` from the repository root.

| In `source/` | Purpose |
|---|---|
| `build.js` | All report text, tables and layout; reads the code listings straight from `exam-version/` |
| `figs.py` | Builds every figure into `fig/` |
| `find_pages.py` | Finds the page of every heading, figure and table for the contents lists |
| `data/synthetic_results.json` | The exam version's measurements on the 40 synthetic vowels, with the true values |
| `data/console_full.txt` | A full console session used for the output screenshots |
| `stats.json` | Accuracy numbers quoted in Chapter 7 |
