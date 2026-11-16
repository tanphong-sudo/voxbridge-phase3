# Android App

The VoxBridge Android app. It supplies only the platform edges (microphone, model runners,
TTS playback, encrypted store) around the **verified pure-Kotlin core** (`:core` — the decision
engine + Safety Preservation Layer that the harnesses exercise at 70/70 and UNSAFE-PASS 0/38).

> **Current translation backend:** Google ML Kit VI↔EN, with a Wi-Fi model download during first
> setup and on-device translation afterward. ASR remains PhoWhisper Base (VI)/Whisper Small (EN)
> on the current CPU path; Piper supplies TTS. OPUS runtime sources remain for historical Mac
> benchmarks, but the Android ViewModel no longer creates OPUS sessions. See
> [`../docs/development/mlkit-device.md`](../docs/development/mlkit-device.md).

## Layout (implemented)

```text
android/app/src/main/
  AndroidManifest.xml                  microphone, vibration, network for ML Kit model setup
  java/com/voxbridge/
    ui/MainActivity.kt                 assembles models -> DecisionEngine -> screen
    ui/MainScreen.kt                   push-to-talk, decision badge, REPAIRED confirm-tap, offline pin
    ui/PushToTalkViewModel.kt          orchestrates one utterance through the core, maps 6 states
    ui/AudioOut.kt                     PCM playback (Piper voices play at their native rate)
    audio/AudioRecordSource.kt         mic capture + streaming Silero VAD (512-frame chunks)
    runtime/ModelPaths.kt              resolves models/ layout (mirrors MODEL_MANIFEST.md + export outputs)
    runtime/WhisperAsr.kt              Whisper decode: AI-Hub QNN contract or HF optimum contract (past-KV)
    runtime/WhisperBpeDecoder.kt       byte-level BPE (verified vs HF on VI diacritics) + token ids
    runtime/MelSpectrogram.kt          Whisper log-mel (80x3000) from the exported filterbank
    runtime/MlKitTranslator.kt         single active VI/EN client, preparation gate, core adapter
    runtime/GoogleTranslationClient.kt SDK downloads and worker-thread translation
    runtime/SileroVad.kt               streaming VAD; v5 (state/sr) and v4 (h/c/sr) auto-detected
    runtime/PiperTts.kt                cached PCM bank + live Piper (sherpa-onnx)
    store/RoomLearningStore.kt         LearningStore port (Room + SQLCipher, Keystore passphrase)
```

## How to bring it up

From the repository root, connect one physical arm64 phone with USB debugging and Wi-Fi, then run
`bash tools/dev/install-mlkit-device.sh`. It builds, installs, syncs ASR/VAD/Piper from the existing
local cache, and waits for ML Kit setup. The translation model is managed privately by the SDK;
no OPUS export or Google Cloud API key is needed. Preserve app data to keep downloaded models.
