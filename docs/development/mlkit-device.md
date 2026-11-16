# Cài VoxBridge với Google ML Kit trên điện thoại

Kết nối **một điện thoại Android arm64 thật** bằng USB, mở khóa, cho phép USB debugging và bật Wi-Fi có Internet. Giữ app ở foreground trong lúc thiết lập lần đầu. Máy Mac cần Android SDK/adb, JDK 17 và model ASR/VAD/Piper đã tải trước đó.

Từ thư mục repo, chạy:

```bash
bash tools/dev/install-mlkit-device.sh
```

Script tự tìm model ở `~/Downloads/models`, sau đó `models/` trong repo. Nếu model ở chỗ khác:

```bash
VOXBRIDGE_MODEL_CACHE=/duong/dan/models bash tools/dev/install-mlkit-device.sh
```

Script kiểm tra cache theo `config/models/dev-vi-en-mlkit.json`, build `debugCpu`, cài APK một lần bằng `adb install -r`, đồng bộ ASR/VAD/Piper và chờ app sẵn sàng. **OPUS-MT không còn cần cho profile này.** Script không tải model về Mac, không uninstall/clear app data và không xóa model OPUS cũ trên điện thoại. Gradle vẫn cần Internet nếu thiếu dependency build, gồm thư viện ML Kit.

ML Kit tải model dịch trực tiếp trên điện thoại lần đầu; phải có Wi-Fi Internet. Sau khi script in `READY`, có thể rút USB và dùng dịch offline. Cấp quyền microphone khi app hỏi. Xóa dữ liệu app hoặc model dịch sẽ yêu cầu tải lại. Kết quả `READY` xác nhận startup thành công; chưa phải benchmark chất lượng, tốc độ hay kiểm tra nói thực tế.

Nếu quá 15 phút chưa xong, kiểm tra thông báo trong app, Wi-Fi và log file script in ra. Có thể chạy lại hoặc tăng thời gian chờ:

```bash
VOXBRIDGE_SETUP_TIMEOUT_SEC=1800 bash tools/dev/install-mlkit-device.sh
```

Script chỉ đọc log của process vừa mở và dừng nếu startup lỗi/crash; không lấy trạng thái Ready từ lần mở trước. Cấu hình tùy chọn: `ADB=/path/to/adb`, `VOXBRIDGE_JAVA_HOME=/path/to/jdk17`, `VOXBRIDGE_MODEL_CACHE=/path/to/models`. Xem `--help` để tra nhanh.

## Bản sửa audit 05/10/2026

Chạy lại cùng script để cập nhật APK và tạo `integrity.sha256` cho bộ model đã có. App kiểm SHA-256 lần đầu, sau đó cache kết quả theo manifest/kích thước/mtime; nếu thiếu manifest, setup yêu cầu sync lại thay vì báo model sẵn sàng. File mới chuyển được kiểm hash trên điện thoại trước khi thay thế. Muốn kiểm lại cả file đã skip, chạy riêng:

```bash
VOXBRIDGE_MODEL_CACHE="$HOME/Downloads/models" bash tools/dev/sync-models-device.sh vi-en-mlkit --verify-full
```

Sync riêng để app ở trạng thái dừng; mở app lại sau khi hoàn tất. Dữ liệu học được migration Room v1→v2, không xóa DB để nâng version. Capture WAV/CSV mặc định tắt; chỉ bật phục vụ chẩn đoán bằng `./gradlew :android:app:assembleDebugCpu -PvoxbridgeCaptureArtifacts=true` rồi cài APK đó. Không dùng cấu hình ghi artifacts làm benchmark mặc định.

ASR được nạp/warm-up trước khi báo Ready và sau đổi chiều; chỉ giữ một ASR và một voice Piper. Vì vậy lần setup/đổi chiều có thể chờ, thay vì dồn toàn bộ load vào lượt nói đầu. Piper vẫn sinh cả câu, chưa phải TTS streaming end-to-end.

Xem trạng thái từng lỗi trong [báo cáo HTML](../testing/code-audit-2026-10-01.html) và [quy trình kiểm chứng thiết bị](../testing/device-profiling.md). Build/test host không chứng minh WER, PSS hay hiệu năng Snapdragon.

## Giới hạn và phân phối

ML Kit translation xử lý trên thiết bị sau khi tải model. Độ chính xác có thể khác OPUS; cần đánh giá lại VI↔EN trước khi dùng các kết quả benchmark cũ cho bản mới. Xem [tài liệu Android](https://developers.google.com/ml-kit/language/translation/android), [hướng dẫn sử dụng](https://developers.google.com/ml-kit/language/translation/translation-terms) và [yêu cầu attribution](https://cloud.google.com/translate/attribution) của Google. App đã có badge Google Translate cạnh bản dịch và thông tin về bản dịch tự động; câu sửa bởi VoxBridge được ghi rõ.

APK có quyền Internet để SDK tải model; không còn cam kết “không có quyền Internet” của bản cũ. Nội dung dịch được xử lý trên thiết bị. Không cần API key hay bật Google Cloud Translation. ASR vẫn dùng PhoWhisper Base/Whisper Small, TTS vẫn dùng Piper. Bộ nhớ học và dữ liệu app cũ được giữ lại; các bản dịch đã duyệt vẫn có thể được tái sử dụng.

## Kiểm tra sau cài

Sau khi app báo Ready, từ repo chạy:

```bash
bash tools/dev/accept-device.sh vi-en-mlkit
bash tools/dev/runtime-instrumented-test.sh vi-en-mlkit
```

Dùng đúng profile ML Kit; các helper cũ vẫn có default OPUS. Các lệnh có thể build/cài lại APK nên giữ máy kết nối. Sau đó thử mic/loa cả hai chiều, xác nhận bản sửa trước phát, phát lại và tắt Wi-Fi/dữ liệu di động để kiểm tra offline. Instrumentation không thay cho thử hội thoại thật.

Tình trạng tích hợp 15/10 và giới hạn kiểm tra: [integration review](../../submission/phase-3/STATUS.md).
