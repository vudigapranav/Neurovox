# Offline training

`train_model.py` trains and evaluates the research logistic-regression model and exports it for Java.
Run it from the project root: `.venv/bin/python training/train_model.py` (see the main README, §7).

## Dataset

`data/parkinsons.data` and `data/parkinsons.names` are the **Oxford Parkinson's Disease Detection Dataset**
(Little et al., 2007/2009), from the UCI Machine Learning Repository, https://doi.org/10.24432/C59C74,
licensed CC BY 4.0. Downloaded on 2 Oct 2026 from https://archive.ics.uci.edu/static/public/174/parkinsons.zip;
SHA-256 of `parkinsons.data`: `a36116c2deac07895b79ce97a2f99a1b3720ac4892995ff5c3b9ccb397ed2bc5` (checked by the script).

The dataset contains extracted features only (no audio). Only the 16 columns Neurovox computes are used; the six
nonlinear columns (RPDE, D2, DFA, spread1, spread2, PPE) are not.

## Outputs

- `src/main/resources/model/research-model.json` — feature order, scaler mean/scale, coefficients, intercept,
  threshold, version and an evaluation summary. Loaded by `com.neurovox.ml.ModelLoader`.
- `src/test/resources/model/reference-predictions.csv` — 21 dataset rows with scikit-learn's output, used by
  `ModelAgreementTest` to check the Java inference to 1e-9.
- `docs/results/model_evaluation.{md,json}` — all metrics and fold composition.

The UPDRS regression from the original plan was dropped: the project does not estimate severity or stage.
