# Phát triển Mac + Android

Bắt đầu từ [README](../../README.md) và [cài ML Kit](mlkit-device.md). Dùng JDK17, Android SDK và cache model ngoài Git; không cần export lại model cho thay đổi Kotlin/UI.

| Công việc | Variant | Model |
|---|---|---|
| UI/state | debugUi | FakePipeline, không model |
| Pipeline thực | debugCpu | vi-en-mlkit, cache ngoài APK |
| Gói thi | competition | cần kiểm signed package/install offline riêng |

```bash
./gradlew :core:test :android:app:testDebugCpuUnitTest :android:app:assembleDebugCpu
```

Dùng debugCpu cho mic/loa trên điện thoại arm64. Mặc định PhoWhisper Base VI, Whisper Small EN, Silero, Google ML Kit và Piper. Helper cũ phải nhận explicit `vi-en-mlkit`; không lấy profile OPUS cũ làm default hiện tại.

- [Đo thiết bị](../testing/device-profiling.md), [quy tắc timing/memory](../testing/measurement-protocol.md).
- [Workflow model cache](workflow.md), [manifest](../../config/models/dev-vi-en-mlkit.json).
- [Trạng thái còn thiếu](../../submission/phase-3/STATUS.md).

Không coi host mock/unit tests hoặc emulator UI là benchmark inference, mic/loa hay offline acceptance. Các script kiểm tra model thật có thể nặng; chỉ chạy khi chủ động chọn đúng cache và thiết bị.
