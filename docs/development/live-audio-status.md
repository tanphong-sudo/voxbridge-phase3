# Live Audio Status Indicator — prototype 15/11/2026

Indicator chỉ hướng dẫn user trong lúc giữ PTT. Không gọi ASR để phân loại chất lượng; không nối vào Reliability Guard, không reject/ASK_REPEAT, không sửa transcript/translation. Không đổi model, thêm network call, model hoặc runtime dependency.

## Bật và sử dụng

- Debug/debugCpu/debugUi: mặc định bật. Có thể tắt bằng `-PvoxbridgeLiveAudioStatus=false` khi build. Release/competition luôn tắt.
- Trong lúc giữ PTT, meter cũ đổi màu và hiện một status, tối đa một hint: `Good`, `Move closer`, `Too noisy`, `Too loud`, `Listening...`. Khi thả nút, phần live biến mất, pipeline tiếp tục như cũ.
- Mở **Diagnostics** để xem current RMS/noise floor/contrast proxy/VAD/state, summary của turn vừa xong và công tắc **Live audio vibration**. Rung mặc định tắt, được lưu riêng trong app preferences.
- Haptic là một pulse riêng 25 ms, tối đa một lần/utterance khi từ GOOD sang warning đã ổn định; không dùng pattern ASK_REPEAT/safety. Bật setting khi đang lỗi không tự rung.
- `debugUi` dùng fake pipeline nên không tạo PCM/VAD thật; indicator sẽ không giả lập GOOD. Dùng `debugCpu` với model đã cài để thử microphone thực.

APK local đã build: `android/app/build/outputs/apk/debugCpu/app-debugCpu.apk`. Không kèm model weights mới; runtime vẫn dùng cấu hình/model dev hiện tại.

## Luồng xử lý

```text
AudioRecord (mic cũ, normalized PCM16 float)
  → StreamingVadAnalysis: cùng một Silero call / 512 samples
      → giữ nguyên VAD trace/boundaries
      → observer read-only → LiveAudioQualityCapture → AudioQualityMonitor
          → immutable scalar snapshot (~100 ms, ngay khi state ổn định đổi)
          → generation guard → UiState → meter/status cũ
  → release: summary + một JSON log, rồi pipeline/gate/ASR hiện tại
```

Một monitor được tạo riêng mỗi capture, không giữ PCM. Một bounded scan/frame tính RMS, peak, tỷ lệ sample có `abs(value) >= 0.99`; các bước sau chỉ là scalar math/EMA/hysteresis. Capture tap không I/O, chờ, tạo coroutine từng frame, chạy lại VAD hay gọi model khác. Consumer chỉ publish StateFlow; JSON/logging chạy sau `job.await()`.

Native 16 kHz dùng đúng frame 512 samples/32 ms đã có. Nếu recorder trả rate khác 16 kHz, app hiện chỉ resample/VAD sau release. Khi đó live monitor chỉ quan sát amplitude với VAD `null`: không học noise, không claim GOOD hoặc đoán SNR; clipping vẫn có thể đo độc lập. Không thêm stream resampler hoặc pipeline thứ hai.

Noise floor là EMA **power** từ confident non-speech, loại frame clipping. Không cập nhật bằng speech, VAD chưa rõ hoặc VAD unavailable. Tự học trong các khoảng không nói khi PTT đang mở; không yêu cầu đứng im/calibrate riêng. Chưa đủ non-speech → noise floor/SNR là `null`; GOOD khi đó chỉ dựa trên speech probability và mức tín hiệu, không khẳng định background đã được đo.

Speech/background contrast là `frame_rms_dbfs - estimated_noise_dbfs`, gồm noise trong speech frame. Đây là **proxy**, không phải SNR tách nguồn, dB SPL hoặc ASR confidence.

## Threshold — prototype values, NOT calibrated

Tất cả quality threshold nằm ở `core/.../audio/AudioQualityConfig.kt`; UI không giữ threshold phân loại.

| Tham số | Giá trị |
|---|---:|
| Speech VAD probability | ≥ 0.60 |
| Confident non-speech | ≤ 0.20 |
| Weak speech RMS | < −38 dBFS |
| Loud non-speech noise floor | ≥ −28 dBFS |
| Low speech/background contrast | < 6 dB proxy |
| Non-speech evidence trước khi noise ready | 320 ms |
| Noise-power EMA time constant | 1.000 ms |
| Clipped sample amplitude | ≥ 0.99 full scale |
| Clipping frame ratio | ≥ 0,5% samples |
| Warning liên tục trước khi hiện | 250 ms |
| Stable GOOD trước khi khôi phục | 160 ms |
| NO_SPEECH hangover | 200 ms |
| Numerical dB floor | −120 dBFS |
| UI publication interval | 100 ms; stable state change publish ngay |

Ở frame 32 ms, hold 250 ms được lượng tử thành 256 ms. Classifier ưu tiên clipping, sau đó speech yếu, speech thiếu contrast, hoặc nền non-speech lớn đã đo đủ; còn lại GOOD/NO_SPEECH theo evidence. Weak speech bị VAD bỏ hoàn toàn vẫn có thể chỉ hiện Listening — không suy ra speech từ RMS một cách giả tạo.

Hysteresis dùng số samples, không phụ thuộc clock/UI scheduling. Candidate khác reset thời gian chờ. Telemetry vẫn đếm classification tức thời để không che các khoảng lỗi ngắn, còn UI chỉ dùng state đã ổn định.

## Lifecycle và fault isolation

- Callback update meter/live quality chỉ được publish khi `held && captureGen == myGen && ui.listening`, kiểm tra **trong** StateFlow transform. Callback cuối của capture cũ không được hồi sinh indicator sau release/turn mới.
- Mỗi start/end reset live state và haptic policy; policy nằm trong retained ViewModel để không rung lại do recomposition/rotation.
- `TalkButton` dùng `runPushToTalkHold` với `finally`: coroutine gesture bị disposal/cancel cũng kết thúc hold. Existing release/cancel-return behavior giữ nguyên; không để mic tiếp tục đến cap30s vì `tryAwaitRelease()` bị cancel.
- Monitoring/consumer RuntimeException chỉ disable tap, xoá snapshot về unavailable và lưu error class. VAD/capture/gate không đổi hoặc bị abort bởi guidance.
- Teardown vẫn chờ active capture trước khi đóng VAD. Không thêm worker/job cần teardown riêng.

## Telemetry theo utterance

Debug log tag `AudioQuality` xuất một JSON cho mỗi real capture. Có `utterance_id`, `duration_ms` thực capture, `monitored_duration_ms`, `actual_sample_rate`, `speech_rms_mean`, `noise_rms_mean`, `snr_proxy_mean`, `clipping_ratio`, `good_ratio`, `quiet_ratio`, `noisy_ratio`, `clipping_state_ratio`, `no_speech_ratio`, `final_audio_quality`, `vad_available`, `monitoring_error`, `prototype_uncalibrated`, `guidance_only`.

- RMS means là **linear full-scale amplitude**, sample-weighted frame RMS trên speech/non-speech evidence tương ứng. Noise mean không phải mean dB của EMA.
- Contrast mean tính dB trên speech frames có noise-ready; thiếu evidence là JSON `null`, không fill bằng zero.
- Các state ratio là classification tức thời, denominator là tất cả samples đã monitor. Không phải thời gian màu UI sau hysteresis.
- `final_audio_quality` là state UI đã smooth ở frame cuối, không phải kết luận chất lượng ASR cả câu; có thể NO_SPEECH nếu kết thúc bằng pause. Dùng thêm ratios/means để calibration.
- Native16k bỏ phần VAD frame chưa đủ512 ở cuối (dưới32ms), nên monitored duration có thể ngắn hơn capture duration. Không báo chúng là cùng denominator.
- `vad_available` mô tả **live evidence**, không phủ nhận VAD post-resampling của pipeline cũ.
- Không log PCM/transcript hoặc tạo raw WAV mới. `CAPTURE_ARTIFACTS` opt-in cũ giữ nguyên; summary cũng được ghi vào debug report khi tính năng bật.

Thu log để benchmark: `adb logcat -v raw -s AudioQuality:I '*:S'`. Chưa dùng các metric này để hiệu chỉnh confidence, WER hoặc chặn output.

## Validation và giới hạn

- **182/182 host tests pass**: existing core/decision/playback +22 monitor,5 capture-tap,1 observer parity,7 presentation/haptic,5 gesture cleanup,3 telemetry tests mới.
- **55/55 Android debugCpu unit tests pass**; compile toàn bộ `AudioRecordSource`, ViewModel, Compose UI và build APK thành công. `verifyDebugCpuNativeLibs` pass.
- JaCoCo scoped core monitor/config/summary/tap/stream observer, pure UI presentation/haptic/gesture helpers và telemetry: **99,73% line, 81,62% branch, 96,51% instruction**. Không phải coverage toàn app hoặc coverage mic/Compose instrumentation.
- Host math microbenchmark (Mac ARM64, JBR21):100 synthetic captures×30,016s sau20 warmup; p50≈1,76µs/frame, p95≈6,86µs/frame. Không gồm VAD, StateFlow/UI, capture hoặc ASR; không suy ra Android/end-of-speech latency.
- Logs/report local: `build/live-audio-status-host-final.log`, `build/live-audio-status/android-cpu-build.log`, `build/live-audio-status/coverage/summary.json`, `build/live-audio-status/monitor-isolated/benchmark.log`.
- Máy hiện không có JDK17, nên Android validation dùng JBR21 qua init script local ignored `build/live-audio-status/android-jbr21.init.gradle` để emit JVM17 bytecode. Không sửa project toolchain/dependencies. Với JDK17 chuẩn có thể chạy Gradle như bình thường.
- `debugUi` build offline thiếu QNN AAR đã khai báo từ trước; đã dùng variant `debugCpu` có dependencies cached để compile/build/test đầy đủ. Không tải QNN/model weights để kiểm thử feature này.
- Không có device/emulator trong môi trường hiện tại: chưa thử mic thực, background/rotation vật lý hoặc cảm nhận haptic. Unit test cancellation không thay thế device lifecycle smoke.

Lệnh kiểm tra (đặt `JAVA_HOME` tới JDK17+ phù hợp):

```bash
python3 tools/host-tests/run.py --output build/live-audio-status/host-final
./gradlew :android:app:testDebugCpuUnitTest :android:app:assembleDebugCpu
```

Trước khi calibration/đưa vào release: thu audio qua mic/PTT thật với noise/accent/device mục tiêu, đánh nhãn quality và ASR errors/WER độc lập, kiểm true/false warning cùng time-to-warning; kiểm sample rate fallback và clipping, rotation/background/repress, tốc độ UI và haptic. VAD nhầm noise thành speech hoặc không có non-speech interval vẫn là điểm mù. GOOD không đảm bảo ASR đúng.

## Files thêm/thay đổi

Core thêm:

- `core/src/main/kotlin/com/voxbridge/core/audio/AudioQualityConfig.kt`
- `core/src/main/kotlin/com/voxbridge/core/audio/AudioQualitySummary.kt`
- `core/src/main/kotlin/com/voxbridge/core/audio/AudioQualityMonitor.kt`
- `core/src/main/kotlin/com/voxbridge/core/audio/LiveAudioQualityCapture.kt`
- `core/src/test/kotlin/com/voxbridge/core/AudioQualityMonitorTest.kt`
- `core/src/test/kotlin/com/voxbridge/core/LiveAudioQualityCaptureTest.kt`

Core sửa: `core/src/main/kotlin/com/voxbridge/core/audio/StreamingVadAnalysis.kt`, `core/src/test/kotlin/com/voxbridge/core/StreamingAudioTest.kt`.

Android thêm:

- `android/app/src/main/java/com/voxbridge/audio/AudioQualityTelemetry.kt`
- `android/app/src/main/java/com/voxbridge/ui/LiveAudioStatus.kt`
- `android/app/src/main/java/com/voxbridge/ui/PushToTalkHold.kt`
- `android/app/src/test/java/com/voxbridge/audio/AudioQualityTelemetryTest.kt`
- `android/app/src/test/java/com/voxbridge/ui/LiveAudioStatusTest.kt`
- `android/app/src/test/java/com/voxbridge/ui/PushToTalkHoldTest.kt`

Android sửa: `android/app/build.gradle.kts`, `android/app/src/main/java/com/voxbridge/audio/AudioRecordSource.kt`, `android/app/src/main/java/com/voxbridge/ui/PushToTalkViewModel.kt`, `android/app/src/main/java/com/voxbridge/ui/MainScreen.kt`, `android/app/src/main/java/com/voxbridge/ui/Haptics.kt`, `android/app/src/main/java/com/voxbridge/ui/components/TalkButton.kt`.

Harness/docs: `tools/host-tests/run.py`, `tools/host-tests/README.md`, `docs/development/live-audio-status.md`.
