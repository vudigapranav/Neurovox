"""Render the report to PDF and find the printed page number of every contents entry -> pages.json."""
import json, re, subprocess, sys
from pathlib import Path

D = Path(__file__).parent
docx = D.parent / "Neurovox_Project_Report.docx"
subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", str(D.parent), str(docx)],
               check=True, capture_output=True)
pdf = D.parent / "Neurovox_Project_Report.pdf"
n = int(re.search(r"Pages:\s+(\d+)", subprocess.run(["pdfinfo", str(pdf)], capture_output=True, text=True).stdout).group(1))
norm = lambda s: re.sub(r"\s+", " ", s).strip()
pages = [norm(subprocess.run(["pdftotext", "-layout", "-f", str(i), "-l", str(i), str(pdf), "-"],
                             capture_output=True, text=True).stdout) for i in range(1, n + 1)]

def roman(k):
    out = ""
    for v, s in [(10, "x"), (9, "ix"), (5, "v"), (4, "iv"), (1, "i")]:
        while k >= v: out += s; k -= v
    return out

entries = json.load(open(D / "toc_entries.json"))
body_start = next(i for i, p in enumerate(pages) if re.search(r"CHAPTER 1\b", p) and "INTRODUCTION" in p and "TABLE OF CONTENTS" not in p)
result, missing = {}, []
front_start = 1                                           # page index 1 = certificate = roman i
nxt = front_start
for t in entries["front"]:                                # in page order, so search after the previous hit
    for i in range(nxt, body_start):
        if norm(t).upper() in pages[i].upper():
            result["front:" + t] = roman(i - front_start + 1); nxt = i + 1; break
    else: missing.append(t)
for f in entries["body"]:
    pat = re.escape(norm(f)) + (r"\b" if re.match(r"CHAPTER \d+$", f) else "")
    for i in range(body_start, n):
        if re.search(pat, pages[i]):
            result["body:" + f] = i - body_start + 1; break
    else: missing.append(f)
json.dump(result, open(D / "pages.json", "w"), indent=1)
print(f"{n} pages total; front matter {body_start - 1} pages; body {n - body_start} pages; missing: {missing}")
