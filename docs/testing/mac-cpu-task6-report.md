# Task 6 Mac CPU handoff — 2026-09-27

**Status: partial.** The 1303-sample development run passes coverage, stage, stability within
that process, and clean ASR quality. The separate 60-sample provisional Piper safety holdout
fails its zero-unsafe-output gate (5/40 labeled safety utterances). This is an observed limitation,
not a release safety claim. Model weights, large JSONL traces, and WAVs remain local and ignored
by Git; the committed scripts and manifests reproduce the checks after models are supplied.

## Development run

Run: `reports/mac-cpu/20260927-081123-dev/`; command:

```bash
bash tools/mac-cpu-benchmark/run.sh --tier dev --models models
```

- Apple M2, macOS arm64, JDK 17, ONNX Runtime 1.20.0 CPU. Sequential 1303/1303 rows,
  0 missing, 0 unexpected gate failures, process exit 0 (2450 s). Decisions: 1294 model
  translations, 5 no-speech, 4 ask-repeat. This is also a 1303-turn sequential process run,
  but it did not monitor RSS or apply a per-turn timeout.
- EN clean WER 112/1103 = **10.15%**; VI clean WER 183/1403 = **13.04%**. Clean usable output:
  **50/50 each**. Both meet the predeclared ≤15% WER and ≥95% usable gates. The earlier
  post-calibration run has identical clean counts, so no observed regression.
- Mac CPU total latency p50/p95 **1883/3417 ms**; routed ASR **1787/3154 ms**; MT
  **83/191 ms**; VAD **43/96 ms**. RTF p50/p95 **0.154/0.449**. First sample in each
  direction took 4120 and 17707 ms; this cold estimate has only two observations.
- Reference translation quality has no human-verified parallel target set here; BLEU/chrF
  are deliberately unreported. Usable means a valid pipeline output, not a correct translation.

## Safety holdout

`reports/mac-cpu/task6-safety-holdout-corrected-run/` contains 60/60 synthetic Piper WAV
results with 0 corpus runtime failures. Forty samples carry provisional safety labels. Scorer:
**5 unsafe spoken outputs**, 0 unintended translation audio on reject, 0 repair auto-speech.
The failures are `safety-037-vi-en`, `safety-044-vi-en`, `safety-046-vi-en`,
`safety-047-vi-en`, `safety-050-vi-en`. In these cases Vietnamese ASR altered or lost the
negation, urgency, or hazard *before* source/target safety comparison. The source text supplied
to the safety layer no longer contained the reference command. Focused extractor fixes for
"shutdown" and "press number" passed their Kotlin tests, but cannot reconstruct words lost in
audio recognition. The holdout is exposed to investigation and must not be presented as a
blind validation set. It is synthetic speech with provisional labels; no human/device acceptance
was performed.

The hard safety gate therefore remains **failed**. Repeating the same check or changing audio
thresholds to fit these examples would not establish real-world safety. A future independent
ASR reliability signal or acoustic cross-check needs a fresh, separately labeled holdout.

## Evidence and gaps

- Model tree SHA-256: `f511dc6cd684b335292fb17af569e150cd7b2ae906ab148160a07865fd791aa4`.
- Selected config SHA-256: `b671f8311871119e85181f2f08440f0ebeb23503eca8da62b239d47159437c7c`.
- Dev manifest SHA-256: `072ba53330163c48be61f2c519dbefaa2546f16adc605e4e17f3cb703e8257bb`.
- Safety template SHA-256: `1a3b2fc9012919ae7f8504241522326c8aeebc2c803d2a5108aabbcca34a2fad`.
- Local traces: `results.jsonl`, `score-summary.json`, `failures.jsonl`, `evaluation.json`,
  `metadata.txt`, and model-file hashes in the run directory. The safety WAVs and manifest are
  under `reports/mac-cpu/task6-safety-holdout-synth-corrected/`.
- Task 6 still lacks measured 100-turn RSS trend, three independent smoke confirmations,
  calibrated Mac performance budget, and an implemented `--tier full --offline` command.
- No phone was available. Android mic, speaker, UI touch behavior, JNI inference, QNN,
  battery, and thermals remain untested. See `ANDROID_PARTNER_README.md` for device commands.
