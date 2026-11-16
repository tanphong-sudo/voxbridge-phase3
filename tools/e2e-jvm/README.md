# tools/e2e-jvm — JVM end-to-end harness (real Kotlin × real ONNX)

Chạy **đúng code Kotlin của app** (`core/` + `runtime/`) với **đúng file ONNX đã export** —
không stub, không mock. Mục đích: chứng minh decode Kotlin và các script export Python thống
nhất về graph contract **trước khi** build APK.

```
models/ (Colab export) ──► run.sh ──► WhisperAsr.kt (decode thật)
                                  ──► OnnxTranslator.kt (past-KV thật)
                                  ──► SileroVad.kt (recurrent state thật)
```

## Chạy

```bash
# Colab (sau cell export của colab/01_models_pipeline.ipynb — notebook tự gọi script này)
!bash tools/e2e-jvm/run.sh models

# Máy cá nhân (sau scripts/fetch_all.sh)
bash tools/e2e-jvm/run.sh            # mặc định: <repo>/models
bash tools/e2e-jvm/run.sh /path/to/models
```

Yêu cầu: **JDK 17** — script tự pin (nếu hệ thống có JDK 21+ thì tự tải Temurin 17 vào
`.cache/`, không đụng JDK hệ thống). Deps (kotlinc 2.0.21 chạy với `-language-version 1.9`
để giữ đúng ngữ nghĩa Kotlin 1.9 của app, onnxruntime 1.20.0, DJL sentencepiece 0.33.0,
org.json, slf4j) tự tải một lần vào `tools/e2e-jvm/.cache/` — offline từ lần chạy thứ hai.

## Nó kiểm chứng gì

| Stage | Hành vi thật được chạy | Assert |
|---|---|---|
| Silero VAD | session recurrent thật, 937 chunk × 512 mẫu silence (≈ 30 s) | prob speech < 0.5 |
| Whisper ASR | `WhisperAsr.load` (encoder + decoder + tokens + mel + BPE) + warm-up 4 bước + decode đủ vòng KV trên 30 s silence | transcript rỗng, `noSpeechProbability > 0.5` |
| OPUS-MT vi→en / en→vi | `OnnxTranslator.load` (encoder + decoder-with-past, KV chaining) + greedy decode | output khác rỗng, `translationScore ∈ (0,1]` |

## Giới hạn trung thực (không lấp liếm)

- **Không có âm thanh speech thật**: harness chỉ tổng hợp silence (zeros). Assert Whisper là
  "silence → rỗng + noSpeech cao" — một speech-in/từ-ra **thật** cần file wav thu âm; chèn
  audio tổng hợp sẽ là kiểm chứng giả tạo. Trên điện thoại, smoke test UI là điểm kiểm speech thật.
- **CPU-only**: desktop ONNX Runtime chạy đường optimum CPU; đường QNN/HTP (`*_ctx.onnx`) chỉ
  nạp được trên Android với EP QNN — GPU/NPU thật được xác nhận bằng `warmUp()` khi mở app.
- Các file runtime **bỏ qua**: `PiperTts.kt` (sherpa-onnx JNI không có trên desktop JVM),
  `ModelInstaller.kt` + `AndroidModelPaths.kt` (cần `android.content.Context`/assets — logic
  extract được test riêng trong mock harness), UI/Room/SecurityCrypto.
- `android.util.Log` được thay bằng shim stdout trong `src/android/util/Log.kt` — **duy nhất**
  một symbol Android mà runtime code tham chiếu; phần còn lại là Kotlin/Java thuần.

## Exit code

`0` = mọi stage PASS · `1` = có FAIL (in chi tiết) · `2` = setup lỗi (thiếu java/models).
`SKIP` (thiếu model cụ thể) không tính là fail — notebook Colab in rõ phần nào bị bỏ qua.
