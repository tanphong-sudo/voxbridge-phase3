# VoxBridge Development Workflow

VoxBridge keeps code and model artifacts in separate places. A normal code change never needs a
model export or a ZIP upload.

| What | Location |
| --- | --- |
| Source code | private GitHub repository `tanphong-sudo/voxbridge` |
| Current validated model cache | `MyDrive/VoxBridge/models/` |
| Immutable model archives | `MyDrive/VoxBridge/artifacts/models/` |
| APKs | `MyDrive/VoxBridge/artifacts/apk/` |
| Benchmark output | `MyDrive/VoxBridge/artifacts/benchmarks/` |

## Normal Code Change

1. Edit VoxBridge locally.
2. Commit and push `main`.
3. In the already-running Colab runtime, run **UPDATE SOURCE + TEST** in
   `colab/00_dev_bootstrap.ipynb`.
4. The cell performs a fast-forward-only pull and runs `bash tools/e2e-jvm/run.sh models`.

The Drive model cache stays untouched. Do not re-upload the repository, recreate a model archive,
or run model export for ordinary Kotlin, test, notebook, or documentation changes.

## New Colab Session

1. Open `colab/00_dev_bootstrap.ipynb` and run all cells.
2. It mounts Drive, securely reads a GitHub token from Colab Secrets (or asks without displaying it),
   clones the private repository to `/content/voxbridge`, restores models into
   `/content/voxbridge/models`, and executes real JVM E2E.
3. If E2E fails, fix the runtime contract before building an APK. Do not replace or re-export models
   merely to hide the failure.

## Model Change

Run `colab/01_models_pipeline.ipynb` only when artifacts change: a Whisper model/export,
quantization, OPUS-MT model/tokenizer, Piper voice, or Qualcomm/QNN artifact.

The notebook exports into `/content/voxbridge/models`, validates the complete tree, runs real E2E,
then promotes it atomically to `MyDrive/VoxBridge/models/`. It creates a timestamped archive only
after that promotion. A failed or incomplete export never replaces the known-good Drive cache.

## APK Build

Run `colab/02_build_apk.ipynb` only after E2E passes. It uses `/content/voxbridge` and its restored
`models/` directory; it never starts the model pipeline. APKs go to
`MyDrive/VoxBridge/artifacts/apk/`.

## Local Commands

```bash
bash tools/dev/status.sh models          # required JVM E2E model inputs
bash tools/dev/validate-model-cache.sh models  # complete APK model set
bash tools/dev/test.sh models            # source checks + local/JVM validation
```

`restore-models.sh` and `sync-models.sh` use staging directories and only switch the target after
the copied tree validates. They require `rsync`, which is available in Google Colab.
