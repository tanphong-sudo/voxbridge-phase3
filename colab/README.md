# Colab — model pipeline + Google Drive model cache

Normal Android development now runs on the Mac. Do not use Colab to rebuild APKs for ordinary
Kotlin/UI/runtime edits. See [`docs/development/mac-android.md`](../docs/development/mac-android.md) for the local Gradle →
USB phone → Logcat loop.

Colab remains for model export/download, quantization, merged-decoder experiments, Qualcomm/QNN
artifacts, and heavy model benchmarking. Successful model artifacts are published to
`Google Drive/VoxBridge/models`; normal development syncs only the selected profile to the phone.

Notebook 00 remains useful for model-side validation in a fresh Colab runtime, but it is no longer
the normal source-code edit loop. Source comes from the private GitHub repository and model
artifacts stay in Google Drive.

| Notebook | Làm gì | Thời gian | Yêu cầu |
|----------|--------|-----------|---------|
| [`00_dev_bootstrap.ipynb`](00_dev_bootstrap.ipynb) | Mount Drive → clone/pull private source → restore `models/` cache → JVM E2E | ~5–10 phút first run | Colab Secret `GITHUB_TOKEN` |
| [`01_models_pipeline.ipynb`](01_models_pipeline.ipynb) | Build/validate the full selected cache: PhoWhisper Base + Whisper Small + MT/VAD/TTS → one JVM E2E → atomically promote Drive cache → archive | depends on missing exports | CPU runtime |
| [`02_build_apk.ipynb`](02_build_apk.ipynb) | Restore/reuse cache, create either selected ASR only if missing → exact asset staging → JVM E2E → build + verify APK → Drive artifact | depends on cache | CPU runtime |

## Cách chạy (chỉ 1 thao tác chuẩn bị)

1. Add a private-repository `GITHUB_TOKEN` to Colab Secrets. The notebook reads it at runtime and
   never stores it in the checkout, notebook, or Git config.
2. Run **Notebook 00** for every new Colab runtime. It restores
   `MyDrive/VoxBridge/models/` into `/content/voxbridge/models/` and runs E2E.
3. Run **Notebook 01** only when the model artifacts themselves change. It promotes only a complete,
   E2E-validated tree to Drive and saves a versioned archive in `VoxBridge/artifacts/models/`.
4. Run **Notebook 02** only for competition/release packaging after E2E passes. It restores the Drive cache only if models are absent,
   bundles them, and writes APKs to `VoxBridge/artifacts/apk/`.

Kết quả: `voxbridge-debug-<tag>.apk` ở `/content` và Drive → `VoxBridge/`.
APK này **đã chứa toàn bộ mô hình** trong assets.

## Lên điện thoại — không cần adb

1. Mở **Drive trên điện thoại** (hoặc chép file APK bằng cáp USB — chỉ chép file, không phải
   adb), tải file `voxbridge-debug-<tag>.apk`.
2. Cài đặt — Android sẽ hỏi cho phép **"Cài từ nguồn không xác định"** cho app Files/Drive: cho.
3. Mở app. **Lần đầu** app tự giải nén bộ mô hình từ APK ra bộ nhớ riêng (~1.3–1.7 GB, vài phút —
   màn hình hiển thị "Đang chuẩn bị mô hình…"). Từng file ghi atomic: tắt app giữa chừng không
   hỏng gì, lần mở lại tự tiếp tục.
4. Giữ nút nói là dịch. **Bật airplane mode** trước demo — app không khai báo quyền INTERNET,
   100% on-device được đảm bảo ở mức build.

*Đường adb thay thế (không bắt buộc): `adb install app-debug.apk` + đẩy `models/` vào
`/sdcard/Android/data/com.voxbridge/files/models/` — app nhận cả hai nguồn, ưu tiên bộ đã
có sẵn; thiếu file nào hiện màn hình setup liệt kê đúng file đó, không crash.*

## Môi trường model tooling

- `tooling` — `transformers==4.42.4` + `optimum[onnxruntime]==1.21.0`: export Whisper ONNX,
  OPUS-MT, quantize INT8. Kết hợp đã verify (naming `present.*`/`past_key_values.*` đúng
  contract mà Kotlin decoder introspect).

Notebook 01 tạo cache đầy đủ với đúng hai ASR sản phẩm: `vinai/PhoWhisper-base` cho VI và
`openai/whisper-small` cho EN. Notebook 02 reuse cache đó và chỉ export lại ASR nào còn thiếu,
trong Python 3.11 riêng; không có đường fallback sang model ASR khác.

## Bảo đảm khi build xong

Notebook 02 chạy `aapt2 dump badging` và **fail nếu APK khai báo INTERNET** — ràng buộc
"100% on-device" được kiểm chứng tự động ở bước build, không phải chỉ cam kết trong code.
Nó cũng fail nếu APK không chứa **đúng** `phowhisper-base-onnx` + `whisper-small-onnx`, nếu Tiny/Base
lọt vào assets, hoặc nếu cặp native ONNX Runtime Java/core không cùng phiên bản 1.20.0.
Việc bundle models vào assets không ảnh hưởng bảo đảm đó: assets là nội dung cục bộ của APK,
`ModelInstaller` chỉ ghi file trong thư mục riêng của app, không hề chạm mạng.
