# Trạng thái Phase 3 — 15/10/2026

Android hiện dùng ML Kit VI↔EN. Source đã hợp nhất commit partner `9a69ad1` với safety/tooling của local tại `0eb1c6b`; các file Android đã đầy đủ.

## Đã kiểm tra

- 119 test JUnit host PASS: core, benchmark CSV, Android logic chọn lọc và playback/callback trên boundary fake. SDK GoogleTranslationClient dùng stub. Gradle với SDK/AGP thật cũng đã PASS **69 core + 36 Android debugCpu unit tests**, compile ViewModel/Compose/Java đầy đủ. Chưa có test thiết bị. [Kết quả host mới](../../docs/testing/host-validation.md).
- 82 Phase3 tool tests,42 benchmark-tool tests,19 device-tool tests và4 SQL migration tests PASS. Tách riêng các suite logic này với các lượt inference có ghi dưới đây.
- Safety logic giả lập:51/51 decisions khớp,0/38 unsafe-pass. Không chứng minh acoustic pipeline đã hết lỗi.
- Exporter kết hợp immutable revision + ONNX checker/staging; model bundle giữ selected files/notices/integrity và rollback. Chưa export/stage model thật.
- Đã tải/chạy audition VieNeu Turbo và Nano CPU trên Mac, tách riêng production. Turbo Trúc Ly có ASR proxy 2/71 word edits trên 6 câu so với Piper hiện tại 18/71; Nano 8 bước thử 2 câu cho 0/36. Chưa có human listening score, chưa thay TTS Android. [Model và giới hạn](../../docs/development/tts-models.md).
- Đã thêm host adapter Nano VI/Piper EN, inventory/hash checks, seed 42 và engine reuse; 9 requests render thật. Người dùng nghe mẫu và thích VieNeu hơn Piper, chưa phải MOS/blind study.
- Đã sửa lỗi tokenizer OPUS tự lowercase: 6 câu có token IDs khớp HF, hai câu uppercase hết No lặp; một câu khác vẫn mất vế sau. Chưa thay MT Android/ML Kit.
- Hai graph Whisper Small W8A16 đã profile thành công trên NPU Galaxy S24 Ultra/Android 14: encoder median 45,6435 ms, decoder 6,364 ms/bước token, 100 lượt/graph. [Evidence/job links](evaluation/qualcomm-graph-profile.json). Không phải latency toàn app hay điểm chất lượng.

## Kết quả thực nghiệm lịch sử

Mac/OPUS ngày27/09:1303/1303 mẫu hoàn tất; clean EN WER10,1541%, VI13,0435%; latency p50/p95 1883/3417ms, chưa tính phát hết TTS. Không phải kết quả Android/ML Kit. Manifest dev cục bộ hiện thiếu, chưa tái chạy.

Safety:5/40 mẫu có nhãn vẫn cho phép dịch trái kỳ vọng; chưa có evidence sửa xong. [Chi tiết](../../docs/testing/safety-failures.md). Tập nhãn provisional/synthetic không thay human acceptance.

## Cần làm theo thứ tự

1. Giải phóng thêm dung lượng rồi hoàn tất `assembleDebugCpu`. SDK/AGP/AAR đã có, compile và unit tests đã qua. Assemble đã vượt lỗi heap sau tăng lên 2 GiB, hiện dừng `mergeDebugCpuNativeLibs` vì hết chỗ; chưa có APK mới. [Hướng dẫn partner](../../docs/development/mlkit-device.md).
2. Replay chờ xác nhận và Speaking đến khi phát xong đã sửa, có test host tái hiện trước/sau. Còn kiểm Android mic/loa, lifecycle, correction→reuse UI và offline thật.
3. Xử lý5ASR safety failures bằng test nhỏ, rồi đánh giá MT đủ cả câu. Trên 10 WAV BTC, PhoWhisper Base 9/78 lỗi từ; encoder INT8 10/78, Whisper Small VI 19/78: không promote hai candidate làm giảm chất lượng. Chưa tải PhoWhisper Small gần 1 GB do thiếu chỗ; không đổi ngưỡng để làm đẹp điểm.
4. Tải/kiểm header/hash200WAV BTC; VI→EN cần references, EN→VI có21nhãn human/79AI cần review. Thu ratings thật, pilot rồi freeze budget và đo thiết bị/memory/chạy lâu. QDC Mobile có thể giúp file test trên máy thật, chưa thay bằng chứng mic/loa và tắt mạng live.
5. Chốt nguồn/giấy phép đúng model phát hành, signed offline package và thử cài sạch. ML Kit setup đầu cần tải model; cài mới hoàn toàn offline chưa được chứng minh.
6. Quay video liên tục theo yêu cầu, điền báo cáo bằng số thật, kiểm hash/build/link rồi nộp.

TTS Android mới vẫn cần port Nano ONNX + phonemizer. Đã xác nhận SEA-G2P 0.10.0 có C ABI không cần Python, license Apache-2.0; chưa build/integrate Rust Android hoặc validate graph trên runtime điện thoại. Gói QNN tham chiếu cần ORT 1.30/QAIRT 2.50, app hiện 1.20; gate từ chối mismatch. Còn phải đồng bộ runtime hoặc compile lại cho runtime đã chọn, không chỉ đổi tên/copy weights.

Audit Python exporter cũ:83advisories trực tiếp trong4packages (torch2.5.1,transformers4.42.4,onnx1.17.0,sentencepiece0.2.0); chưa kiểm transitive hoặc nâng pin vì cần kiểm tương thích export riêng. Đây không phải kết quả audit Android dependencies.

Chưa có signed competition APK, video cuối, bộ số đo target hoặc báo cáo Phase3 hoàn tất. Raw evidence/log cục bộ nằm trong `output/phase3/`, không đưa vào source Git.
