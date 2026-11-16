# FLEURS safety-activation regression

This harness scans the public Google FLEURS `vi_vn` and `en_us` **test transcripts** through the
real `SafetyFrameExtractor`. FLEURS is read-speech from general Wikipedia-style text, so this is a
useful negative corpus for detecting lexical collisions that would make ordinary speech enter the
industrial Safety Preservation Layer.

Run:

```bash
bash tools/fleurs-regression/run.sh
```

The script downloads only `test.tsv` for each locale, compiles the shipping Kotlin core, scans the
full splits, and fails when any row activates the safety gate. It does **not** measure ASR WER and
does not replace the Phase-2 audio benchmarks; its purpose is source-side false-activation
regression.

Source: `google/fleurs` on Hugging Face, `data/{vi_vn,en_us}/test.tsv`.
