# Tested VPS audio pipeline with rejected-output drafts

This package preserves the integration used for the completed 200-case VPS run on
2026-11-15. It connects the existing Kotlin capture analysis and newer VPS guard
to existing PhoWhisper-small (VI), native NeMo Parakeet TDT v2 (EN), EnViT5 and
Piper. It contains source and aggregate provenance only. Audio, models, corpus
references, raw results, logs, credentials and server checkout copies are excluded.

The VPS core was newer than this feature branch's original app core. Therefore
`server-core.patch` adds the tentative-output feature to exactly two existing VPS
files, preserving its calibrated-score flag, punctuation option, token-limit and
translation-collapse checks. The two new helper files are read from the canonical
feature checkout. This is a separate functional harness; applying this patch to the
Android app or claiming an Android/QNN run is outside its scope.

## Reproduce in an isolated directory on the existing VPS

Use a reviewed feature checkout containing this package and the canonical new
`TentativeOutput.kt` / `RejectedCaptureDraft.kt`. The existing server checkout is
read-only input. Choose a new work directory for each reproduction:

```sh
FEATURE_REPO=/path/to/reviewed/feature-checkout
PACKAGE="$FEATURE_REPO/experiments/asr_reliability_guard/vps_pipeline"
WORK=/home/opc/voxbridge-experiments/retry-drafts-reproduction-20261115

python3 "$PACKAGE/stage.py" --repo-root "$FEATURE_REPO" \
  --server-root /home/opc/voxbridge-current --work-dir "$WORK"
python3 "$WORK/compile-vps.py"
python3 -u "$WORK/launch-vps.py" pilot --label pilot-envit5
```

Inspect the six-case pilot metadata/results and audio audit before the full run.
The launcher refuses existing output directories; it never overwrites earlier
pilot failures or results. A complete run and its offline scoring use:

```sh
python3 -u "$WORK/launch-vps.py" full --label full-envit5
/home/opc/voxbridge-mt3-20261113/venv/bin/python "$WORK/score_vps.py" \
  --manifest "$WORK/full-envit5/manifest.jsonl" \
  --results "$WORK/full-envit5/results.jsonl" \
  --out-dir "$WORK/full-envit5/score"
```

Scoring requires exactly sacrebleu 2.5.1 and whisper-normalizer 1.0.1; it fails if
the selected existing environment differs. Optional `--baseline <results.jsonl>`
adds a separately labeled comparison. It does not turn backend changes into a
guard-only experiment.

`stage.py` checks both original VPS file SHA256 values before creating the work
directory, applies the patch only inside the copied source tree, checks resulting
hashes, and copies the exact tested harness/adapters/scripts. It also pins the two
canonical helper hashes and canonical `tools/mac-cpu-benchmark/monitor_process.py`.
It rejects an existing work directory and any destination containing or nested in
the server checkout. Runtime/core copies include Kotlin/Java source only. No models,
WAVs or native inference are touched during staging.

## Existing dependencies and runtime protocol

These scripts deliberately reuse the existing VPS installation without installing
packages, downloading models, replacing a decoder or changing shared environments:

| Component | Existing path / pin |
| --- | --- |
| JDK | `/usr/lib/jvm/java-17-openjdk` |
| Kotlin/JVM jars | `/home/opc/voxbridge/tools/e2e-jvm/.cache` |
| Model bundle | `/home/opc/voxbridge-mt3-20261113/pipeline-current-models` |
| Frozen 200-case manifest | `/home/opc/voxbridge-mt3-20261113/full-audio-qnn-ready-20261114/manifest200.jsonl` |
| Persistent MT/Piper worker | `/home/opc/voxbridge-current/tools/phase3/audio_pipeline_worker.py` |
| MT/Piper Python | `/home/opc/voxbridge-mt3-20261113/venv/bin/python` |
| Local HF cache | `/home/opc/voxbridge-mt3-20261113/hf-cache` |
| EnViT5 revision | `840bc88104d5a4277af740eaedb024df8c3093e7` (beam 2, max 192 new tokens) |
| Parakeet Python | `/home/opc/en-asr-new3-20261113/nemo/venv/bin/python` |
| Existing native Parakeet runtime | `/home/opc/parakeet-next-20261114/inference/{run.py,contract.py}` |
| Parakeet checkpoint pin | `/home/opc/en-asr-new3-20261113/nemo/parakeet-v2/model-pin.json` |
| Parakeet revision | `ae9ad07059c7c739ffaf932226a8fe64ae2620b0` |
| Parakeet checkpoint SHA256 | `d99e39955c9d3d0350d8fb7c75e40c64a2b2eaeb003883d7c941fd2e8747b28c` |
| Native environment | NeMo 2.5.3, Torch 2.8.0+cpu, NumPy 1.26.4, CPU FP32 |
| Piper voices | VI `vais1000-medium`; EN `lessac-medium`; existing cached repeat/no-speech prompts |

The tested existing MT/Piper worker SHA256 is
`5cbf3382dc2e3f00a5f908a8721648879abc870f4fe020e15b8324ef1a7bbf25`.
The launcher records current worker hashes, model-worker readiness/provenance,
source receipt, manifest hashes and unchanged input hashes. Reproduction requires
the same existing assets; model bundle contents are not included here.

The English adapter calls the existing `configure(..., 'greedy_trim', ...)` and
native `model.transcribe` / `extract`, without processing the supplied waveform.
The Kotlin harness reconstructs canonical PCM16 from the app-VAD-selected samples
and checks returned sample count/hash. JSONL requests allow only `op`, `id`, `wav`
and optional `sha256`; references and transcript text are rejected. Native scores
remain diagnostics, with calibrated confidence fields null. Explicit Enum names
are JSON-safe; unknown native objects and nonfinite numbers remain errors.

`AskRepeat` remains rejected, keeps its original reason, and can expose a bounded
untrusted source/translation draft. Post-MT rejection reuses the actual MT result
once. `final_text` stays null; speech policy generates only the cached repeat
prompt. Repaired translations await confirmation. Reference fields enter output
only after the terminal policy and speech events.

## Completed run and limits

The run finished at 10:58:48 UTC, 23m57s after launch. Metadata confirmed 200 unique
terminal rows, no changed input hashes and zero runtime/scorer issues.

| Aggregate | Result |
| --- | ---: |
| Decisions | 198 ModelTranslation, 1 AskRepeat, 1 NoSpeech |
| Raw lexical ASR WER, all 200 | 6.73% |
| Raw lexical ASR WER, VI 100 / EN 100 | 3.68% / 8.07% |
| EN normalized WER | 7.07% |
| Final MT chrF, all 200 (rejected output empty) | 47.81 |
| Display-candidate chrF, all 200 | 47.81 |
| AskRepeat drafts | 1 source draft, 0 usable target drafts |
| Verified generated WAVs | 200: 198 translations, 2 cached prompts |
| VPS file-to-WAV latency p50 / p95 | 6.49s / 11.51s |

The sole AskRepeat was a token-limit rejection whose target collapsed; it retained
the source and repeat prompt. There were no translated drafts to improve the
all-200 display score in this run. The existing references are AI/unreviewed drafts
with no independently verified human acceptance set. Scores are diagnostic.

Known blind spot: `EV-0523` still contains an incorrect opening recognized by
Parakeet and is accepted. The guard cannot reliably detect an acoustically wrong
but plausible transcript from these available signals. Native word confidence is
unavailable; this feature does not invent missing-word masks or guarantee semantic
correctness. WAV creation/audit is not speaker playback. VPS CPU timings do not
measure phone recording, Android UI, QNN/HTP parity or Snapdragon latency.

`provenance.json` records base/patched/canonical helper and tested artifact hashes,
plus aggregate metrics and original manifest/result digests, without corpus text.

## Boundary tests

```sh
python3 -m unittest discover -s "$PACKAGE/adapter" -p test_parakeet_jsonl_worker.py
python3 -m unittest discover -s "$PACKAGE" -p test_score_vps.py
python3 -m unittest discover -s "$PACKAGE" -p test_stage.py
```

The packaged suites contain 13 adapter, 8 scorer and 3 staging tests. Python child
protocol fixtures test boundaries only and do not simulate neural quality. The
preserved Kotlin `PipelineBridgeTest.kt` verifies bit-exact PCM16 extrema, invalid
input rejection, real child readiness failure cleanup and JSONL request/close.
`ServerTentativeMergeTest.kt` preserves the four tested newer-server core
regressions. No native inference is rerun by these unit tests.
