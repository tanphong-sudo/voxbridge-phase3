# VoxBridge — JVM mock harness

Unit/behavioral tests for the Android runtime WITHOUT Android or real ONNX graphs: the Android
framework + ONNX Runtime + DJL APIs are stubbed here, and the test files script every session's
tensor behavior (`OrtSession.handler`) so the assertions check the DECODE LOGIC, tensor identity,
and lifecycle contracts — not the models.

**The runtime files under test are compiled DIRECTLY from the repo** (`android/app/.../runtime/*.kt`,
no copies — this harness can never drift from the app code) **against the core-contract stubs in
`src/`** (Language, TranslationResult/Translator, TranscriptResult, … — minimal replicas of the
core ports the runtime consumes; the core's own logic is verified by `tools/self-verify/`).

## What is covered (and with what fidelity)

| Suite | Verifies | Fidelity notes |
|---|---|---|
| TestMain.kt | Mel spectrogram DSP (bit-exact vectors), Silero VAD state machine, Whisper QNN contract (KV identity, single-token feeds, EOS stop, no-speech prob), core pipeline | QNN graph mocked per the AI Hub `app.py` reference; real math runs host-side |
| TestOptimum.kt / TestTranslator.kt | optimum Marian contract (past-free bootstrap + with-past KV chain tensor IDENTITY, degenerate past-free path), ChainedTranslator pivot semantics (sourceText correction, min-score, wiring guards, no-op close), tokenizer round-trips | optimum v1.17.1 OnnxConfigWithPast shape/name contract, reflected in the stubs |
| TestInstaller.kt | ModelInstaller extract/resume/refresh over a synthetic asset bundle (6 MT hops + whisper + 4 voices), completeness gates | real `java.io` file walking; AssetManager stubbed |
| Count as of the ZH/KO expansion | **707 checks (TestMainKt) + 22 checks (TestInstallerKt)** | |

## Run

```bash
bash tools/mock-jvm/run.sh            # compile repo code + stubs, run both suites
bash tools/mock-jvm/run.sh --main     # just the 707-check main suite
bash tools/mock-jvm/run.sh --installer
```

Same env handling as `tools/e2e-jvm/run.sh` (pins JDK 17 when the system JDK is 21+), and it
**reuses the same `tools/e2e-jvm/.cache`** (JDK 17, kotlinc 2.0.21, ORT/DJL jars — one download
serves both harnesses; gitignored). Works on macOS and Linux/Colab.

## Relationship to the other harnesses

- `tools/self-verify/` — pure-core (no Android, no ONNX) decision/safety verification.
- `tools/e2e-jvm/` — the REAL ONNX Runtime + real exported models (contract proof).
- `tools/mock-jvm/` — this: no models needed, runs anywhere, catches logic/lifecycle
  regressions instantly (used by every review round).
