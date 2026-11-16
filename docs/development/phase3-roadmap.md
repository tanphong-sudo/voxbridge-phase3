# VoxBridge Phase 3: hiện trạng, cách tối ưu pipeline và lộ trình tới lúc nộp

Cập nhật: 11/11/2026. Hạn nộp: **hết ngày 16/11/2026 (23:59, UTC+7)**.

File này dành cho cả đội. Nó trả lời bốn câu hỏi:

1. Cuộc thi chấm gì.
2. Hệ thống của mình hiện đang chạy ra sao và yếu ở đâu.
3. Tối ưu **cả pipeline** như thế nào: thuật toán ở từng khâu và giữa các khâu, không chỉ là chọn model.
4. Từ giờ tới lúc nộp làm gì, theo thứ tự nào, và **mỗi việc đo ở đâu**.

Các con số ghi "ước tính" là chưa đo. Các con số ghi "đã đo" có nguồn kèm theo.

---

## 0. Đọc nhanh

- Pipeline đã có đủ các khâu và có một lớp an toàn riêng, đây là điểm khác biệt của đội. Nhưng **chưa có số đo nào trên điện thoại Snapdragon**, mà gần như mọi tiêu chí chấm đều cần số đo đó.
- Hai điểm yếu lớn nhất về chất lượng:
  - **ASR tiếng Việt nghe sai.** Trên 10 câu thử, 5 câu bị sai nghĩa ngay từ bước nghe.
  - **Không có thuật toán chống ồn.** Thiết kế hiện tại chỉ "phát hiện ồn thì bắt nói lại", không cố nghe đúng hơn.
- Điểm yếu lớn nhất về hiệu năng: **mọi model đang chạy CPU**. Code chạy NPU đã có nhưng đang bị tắt, nên khó giữ cam kết **≤ 2 giây từ lúc nói xong tới lúc phát âm thanh đầu tiên**.
- Rủi ro bị loại: **ML Kit phải có mạng để tải model lần đầu**, trong khi BGK phải tự cài và chạy được khi không có mạng.
- Chia việc theo nơi chạy:
  - **Mac** để sửa code.
  - **VPS** cho việc nặng: build APK, đo chất lượng.
  - **AI Hub** đo từng model trên chip thật.
  - **QDC** đo cả app trên điện thoại 8 Gen 3 thật (đội có 1000 phút).
  - **Một máy Android cầm tay** để quay video.
- Cách tối ưu: **mỗi lần chỉ thay một thứ, đo trước và sau trên cùng bộ dữ liệu, có lợi mới giữ.** Cách đo xem mục 5.

---

## 1. Cuộc thi chấm gì

Nguồn: [Brief Phase 3](../../submission/phase-3/templates/1V_P3_Prototype_Brief.pdf), [tổng hợp email BTC](../../submission/phase-3/announcements.md).

### Bắt buộc (thiếu là có thể bị loại)

| Yêu cầu | Ý nghĩa với mình |
|---|---|
| Chạy trên phần cứng thật | APK chạy trên điện thoại thật, không phải emulator hay Mac |
| Toàn bộ inference trên máy, không gọi cloud lúc demo | ML Kit chỉ hợp lệ nếu model đã nằm sẵn trên máy |
| Có giao diện để BGK tự bấm | App push-to-talk là đủ |
| Ít nhất 1 cặp ngôn ngữ, kịch bản khoảng 30 phút, có tạp âm nhẹ | VI↔EN, kịch bản nhà máy |
| Video một take, thấy cảnh **tắt mạng ngay trước khi chạy**, thấy thời gian xử lý | Cần máy cầm tay, nói trực tiếp |
| Gói cài offline kèm hướng dẫn để BGK tự chạy | APK đã ký, kèm model và hướng dẫn |
| Benchmark đo khi ngắt mạng, có log gốc | Số tự khai không có log thì không được tính |
| Source trên GitHub **public** | Repo đang private, cần quyết định |
| Báo cáo theo template, ghi rõ thay đổi so với Phase 2 | Bản V3 đã có khung |

### Thang điểm

| Tiêu chí | Tỉ trọng | BGK nhìn vào đâu |
|---|---|---|
| A. Chất lượng dịch giọng nói | **45%** | Đúng nghĩa, rõ, tự nhiên, trong hội thoại thật |
| B. Hiệu năng on-device | 20% | Kích thước model, thời gian xử lý, RAM đỉnh |
| C. Tích hợp và UX | 20% | Mức hoàn thiện trên máy, thiết kế cho công nhân |
| D. Hồ sơ demo | 15% | Video, tài liệu, khả năng tái lập, roadmap |
| Điểm cộng phần cứng | tối đa +0,5 | Phần cứng tự thiết kế và tối ưu |

Workshop 23/10 xác nhận: **số end-to-end là cơ sở chấm chính**, số từng model chỉ để giải thích. Được đổi model hoặc thiết bị so với Phase 2, nhưng phải nói rõ lý do.

### Cam kết của chính đội (Phase 2)

Đây là những gì đội đã hứa. BGK sẽ đối chiếu, nên coi như luật:

- **Từ lúc nói xong tới lúc phát âm thanh đầu tiên ≤ 2,0 giây** (mục tiêu thiết kế 0,7–1,05 giây).
- Không gọi cloud khi chạy.
- Model đang hoạt động < 0,8 GB, bộ nhớ AI < 1,6 GB trên máy 8 GB.
- 0 lượt cho qua câu nguy hiểm trên bộ test an toàn.
- Dùng được trong môi trường **85–95 dB**, có găng tay và bảo hộ tai.

---

## 2. Thuật ngữ

| Từ | Nghĩa |
|---|---|
| ASR | Nhận dạng giọng nói, đổi âm thanh thành chữ |
| MT | Dịch máy, đổi chữ ngôn ngữ A thành ngôn ngữ B |
| TTS | Tổng hợp giọng nói, đổi chữ thành âm thanh |
| VAD | Phát hiện đoạn nào có tiếng người nói |
| Pipeline | Chuỗi các khâu nối nhau: mic → VAD → ASR → MT → kiểm an toàn → TTS → loa |
| CPU / NPU | Bộ xử lý chung / bộ xử lý AI chuyên dụng (trên Snapdragon gọi là Hexagon). Chạy NPU nhanh và tiết kiệm pin hơn nhiều |
| QNN / QAIRT | Bộ công cụ và runtime của Qualcomm để chạy model trên NPU |
| ONNX / ORT | Định dạng model / thư viện chạy model ONNX (ONNX Runtime) |
| Quantize (lượng tử hóa) | Nén model, ví dụ từ 32-bit xuống 8-bit, để nhẹ và nhanh hơn. Có thể làm giảm chất lượng, nên phải đo lại |
| AI Hub | Web của Qualcomm: compile model cho NPU, chạy thử trên máy thật ở cloud, đo từng model |
| QDC | Thuê điện thoại Snapdragon thật từ xa, điều khiển qua adb |
| Latency first audio | Thời gian từ lúc thả nút tới lúc loa bắt đầu phát |
| p50 / p95 | 50% / 95% số lượt nhanh hơn con số này |
| WER / CER | Tỉ lệ lỗi từ / ký tự của ASR, càng thấp càng tốt |
| chrF / COMET | Điểm chất lượng dịch so với đáp án, càng cao càng tốt |
| ASK_REPEAT | App bắt người nói nói lại thay vì dịch |
| Bộ BTC | 200 câu mẫu BTC phát (100 VI→EN, 100 EN→VI) |

---

## 3. Hệ thống hiện tại: từng khâu và điểm yếu

### 3.1 Sơ đồ một lượt nói

```
Giữ nút → [Mic VOICE_RECOGNITION] → Thả nút
  → [1] Silero VAD: tìm đoạn có tiếng, cắt lặng (giữ 300/400 ms lề)
  → [2] Cổng chất lượng âm thanh: điểm = 0,65 × độ chắc của VAD + 0,35 × chênh lệch tiếng nói so với nền
        thấp / quá ngắn / rè / quá dài → NO_SPEECH hoặc ASK_REPEAT (dừng tại đây)
  → [3] ASR: PhoWhisper Base (VI) hoặc Whisper Small (EN), ONNX FP32, CPU, giải mã greedy
  → [4] Kiểm ASR: chuỗi rỗng / xác suất "không có tiếng nói" > 0,6 → ASK_REPEAT
        (ngưỡng độ tin cậy 0,60 đang TẮT vì điểm chưa được hiệu chỉnh)
  → [5] Trích khung an toàn từ câu nguồn: hành động, đối tượng, số máy, phủ định, mối nguy, khẩn cấp, số đo
  → [6] Bộ nhớ: câu đã được duyệt, khớp mạnh, đủ tin cậy → dùng lại (vẫn kiểm an toàn lại)
  → [7] Dịch: Google ML Kit (CPU, hộp đen)
  → [8] Chặn bản dịch hỏng: rỗng, hoặc một từ lặp ≥ 6 lần → ASK_REPEAT
  → [9] Câu không có tín hiệu an toàn → phát luôn (MODEL)
        Câu có tín hiệu an toàn → so khung nguồn với khung bản dịch:
          giữ đủ → VERIFIED (phát)
          mất slot sửa được → REPAIRED (phải bấm xác nhận mới phát)
          mất slot không sửa được → ASK_REPEAT
  → [10] Chính sách phát: quyết định phát gì, nghe lại thế nào
  → [11] TTS Piper (CPU) → loa
```

Code chính: [DecisionEngine.kt](../../core/src/main/kotlin/com/voxbridge/core/pipeline/DecisionEngine.kt), [PushToTalkViewModel.kt](../../android/app/src/main/java/com/voxbridge/ui/PushToTalkViewModel.kt), [WhisperAsr.kt](../../android/app/src/main/java/com/voxbridge/runtime/WhisperAsr.kt), [CaptureAnalysis.kt](../../core/src/main/kotlin/com/voxbridge/core/audio/CaptureAnalysis.kt).

### 3.2 Đối chiếu với thiết kế Phase 2

| Phase 2 hứa | Code thực tế | Đánh giá |
|---|---|---|
| PTT xác định người nói | Có | ✅ |
| Silero VAD (chọn vì bền nhất ở 0 dB) | Có | ✅ |
| Cổng chất lượng: ồn, rè, **lời chồng nhau** → ASK_REPEAT | Có cổng ồn và rè. **Không có bộ phát hiện lời chồng nhau** | ⚠️ thiếu |
| ASR Reliability Guard có hiệu chỉnh | Chỉ còn kiểm "không có tiếng nói", ngưỡng tin cậy tắt | ⚠️ thiếu |
| Khung an toàn: so, sửa có giới hạn, bắt xác nhận | Có, đã có test | ✅ (5 lỗi ASR lịch sử vẫn mở) |
| Bộ nhớ có kiểm an toàn lại | Có | ✅ |
| Chuẩn hóa số và đơn vị trước TTS | Có | ✅ |
| Cache câu an toàn hay dùng (10–30 ms) | Chỉ cache 2 câu nhắc ("nói lại", "không nghe thấy") | ⚠️ thiếu |
| Chỉ giữ model của chiều đang dùng trong RAM | Có. **Nhưng mỗi lần đổi chiều phải nạp lại ASR** (xem 4.7) | ⚠️ |
| Chạy NPU trên Snapdragon | Code có nhánh QNN, nhưng **bị ép chạy CPU** | ❌ |
| MT OPUS INT8 offline | Đã đổi sang ML Kit (cần mạng lần đầu) | ⚠️ rủi ro offline |
| Dùng được ở 85–95 dB | Chỉ có phát hiện và từ chối, **không có xử lý chống ồn** | ❌ |

### 3.3 Số liệu đã có

| Số | Nguồn | Dùng được để chấm không |
|---|---|---|
| PhoWhisper Base trên S24 qua AI Hub: encoder 32,25 ms, decoder 3,40 ms/token, đỉnh 571 MB | Phase 2 | Chỉ là số từng model |
| Whisper Small trên S24 (Phase 2): 101,79 ms, 9,85 ms/token, đỉnh 1.202 MB | Phase 2 | Chỉ là số từng model |
| Whisper Small W8A16 bản Qualcomm: encoder 45,6 ms, decoder 6,36 ms/token | [qualcomm-graph-profile.json](../../submission/phase-3/evaluation/qualcomm-graph-profile.json) | Chỉ là số từng model |
| Lỗi từ ở 0 dB tăng thêm: PhoWhisper +4,99 điểm %, Whisper Small +10,91 điểm % | Phase 2 | Cho thấy cần chống ồn |
| 10 câu BTC tiếng Việt: 9/78 từ sai, **5/10 câu sai nghĩa** | `output/model-upgrades-20261015/asr-baseline-btc10.jsonl` | Quá ít mẫu |
| Mac/OPUS lịch sử: p50/p95 1,88/3,42 giây (**chưa tính TTS**) | 27/09 | Không phải số Android |
| An toàn: 5/40 câu nguy hiểm vẫn bị cho qua (giả lập) | [safety-failures.md](../testing/safety-failures.md) | Lỗi vẫn mở |

### 3.4 Chấm điểm hiện tại (đánh giá của đội, không phải điểm BGK)

| Tiêu chí | Thiết kế | Nếu nộp hôm nay | Vì sao |
|---|---|---|---|
| A. Chất lượng (45%) | 5/10 | ~1/10 | ASR VI yếu, không chống ồn, ML Kit chưa đo, giọng Việt của Piper khó nghe |
| B. Hiệu năng (20%) | 4/10 | 0/10 | Toàn bộ CPU, FP32, chưa có số Snapdragon |
| C. UX (20%) | 7/10 | ~2/10 | Concept tốt cho công nhân, nhưng chưa chạy máy thật, chưa giải được offline |
| D. Demo (15%) | 6/10 | ~1/10 | Công cụ tốt, nhưng chưa có video, APK, số đo |

### 3.5 Việc đã làm ngày 11/11 (chưa commit)

- **TTS phát dần theo câu:** câu 1 tổng hợp xong là phát, câu 2 tổng hợp trong lúc câu 1 đang phát. Code: [SpeechChunker.kt](../../core/src/main/kotlin/com/voxbridge/core/pipeline/SpeechChunker.kt), [ChunkedSpeech.kt](../../android/app/src/main/java/com/voxbridge/ui/ChunkedSpeech.kt).
- **Đo đúng mốc 2 giây:** đồng hồ chạy tới lúc byte âm thanh đầu tiên vào loa. Màn hình hiện thời gian từng khâu, logcat ghi dòng `VoxTiming` để lọc khi chạy QDC. Code: [TurnTiming.kt](../../core/src/main/kotlin/com/voxbridge/core/pipeline/TurnTiming.kt).
- Host test: **139/139 pass**.
- **Chưa compile bằng Gradle Android**, vì cache plugin trên Mac đã bị dọn. Sẽ kiểm khi build APK trên VPS.
- Lưu ý: **cache model trên Mac đã mất**. Thư mục `models/` không còn, cần export lại trên VPS.

---

## 4. Tối ưu pipeline: thuật toán từng khâu và giữa các khâu

Nguyên tắc: **pipeline đúng quan trọng hơn model to.** Một model tốt đặt trong pipeline cắt mất chữ đầu, không chống ồn, mất dấu hỏi và nạp lại mỗi lần đổi chiều thì vẫn cho kết quả tệ. Phần dưới đi lần lượt từng khâu: vấn đề → thuật toán đề xuất → đo bằng gì → chi phí.

Mức ưu tiên:
- **P1:** rẻ, làm trước 16/11.
- **P2:** trung bình, làm nếu kịp.
- **R:** đưa vào roadmap trong báo cáo.

### 4.1 Thu âm (trước VAD)

**Vấn đề 1: mất chữ đầu và chữ cuối do cách bấm nút.** Người dùng hay vừa bấm vừa nói, và thả nút trước khi nói hết chữ cuối. Hiện app chỉ thu đúng khoảng giữ nút.

- **(P1) Thu nốt sau khi thả nút:** khi thả, nếu VAD thấy khung cuối vẫn là tiếng nói thì thu thêm tối đa khoảng 300 ms cho tới khi hết tiếng. Nếu đã im lặng thì dừng ngay, không tốn thêm thời gian.
- **(P2) Bộ đệm trước khi bấm:** giữ 300–500 ms âm thanh gần nhất khi app đang ở trạng thái sẵn sàng, để không mất âm tiết đầu. Đổi lại phải mở mic liên tục, tốn pin và cần cân nhắc quyền riêng tư.
- **Đo:** tỉ lệ câu mất chữ đầu/cuối trên bản thu thật. Chạy trên máy cầm tay hoặc tự thu.

**Vấn đề 2: chế độ mic.** Đang dùng `VOICE_RECOGNITION`, gần như không xử lý gì. Android có `VOICE_COMMUNICATION`, chế độ này bật bộ lọc ồn và beamforming nhiều mic (Fluence) có sẵn trên máy Snapdragon.

- **(P1) Thử 3 chế độ** `VOICE_RECOGNITION`, `VOICE_COMMUNICATION` và `UNPROCESSED` trong cùng điều kiện ồn. Chọn theo WER và tỉ lệ bắt nói lại.
- **Đo:** **chỉ đo được trên máy cầm tay**, vì QDC không có mic. Chi phí: chỉ đổi một dòng cấu hình.

**Vấn đề 3: âm lượng không đều.** Người nói xa hoặc gần mic thì mức âm khác nhau.

- **(P1) Chuẩn hóa mức âm** của đoạn đã cắt trước khi đưa vào ASR, có giới hạn để không khuếch đại ồn.
- **Đo:** WER trên bộ BTC khi giảm âm lượng giả lập (−10 dB, −20 dB). Chạy trên VPS.

### 4.2 VAD và cổng chất lượng: đổi từ "từ chối sớm" sang "thử rồi kiểm"

**Vấn đề:** cổng hiện tại từ chối ngay khi chênh lệch tiếng nói/nền thấp. Ở 85–95 dB, chênh lệch luôn thấp, nên app sẽ bắt nói lại liên tục.

- **(P1) Ước lượng mức ồn nền** từ các khung VAD không có tiếng nói (code đã có số liệu này) để biết đang ở mức ồn nào.
- **(P1) Cổng phân tầng:**
  - Không có tiếng nói → NO_SPEECH. Giữ nguyên.
  - Ồn vừa → **vẫn cho chạy ASR**, rồi để các bước kiểm sau ASR (4.3) quyết định có tin kết quả hay không.
  - Chỉ từ chối ngay khi gần như chắc chắn không dùng được (rè liên tục, quá ngắn).
- **(P1) Hiệu chỉnh lại ngưỡng** (0,15 / 0,20) trên bộ dữ liệu ồn, cân bằng giữa "bắt nói lại nhầm" và "cho qua câu sai". Không chỉnh ngưỡng để làm đẹp điểm. Phải có tập hiệu chỉnh và tập kiểm tra riêng.
- **(P2) Lọc ồn bằng model có chọn lọc:** chỉ bật khi ồn vượt ngưỡng, dùng model rất nhỏ (GTCRN, DeepFilterNet) và compile lên NPU. **Phải đo kỹ**: lọc ồn đôi khi làm Whisper nghe tệ hơn, vì Whisper đã được huấn luyện trên dữ liệu ồn.
- **(R) Phát hiện lời chồng nhau** (hai người cùng nói). Phase 2 có hứa nhưng chưa có. Ghi vào roadmap.
- **Đo:** bộ ồn (mục 5.2), xem WER, tỉ lệ bắt nói lại và tỉ lệ cho qua câu sai ở 0/5/10/20 dB. Chạy trên VPS.

### 4.3 ASR: giải mã và kiểm tra sau giải mã

**Vấn đề 1: Whisper "ảo giác" khi gặp ồn.** Gặp tiếng ồn, Whisper hay sinh ra câu không ai nói (ví dụ các câu kiểu "cảm ơn các bạn đã xem" học từ video). Hiện chỉ có kiểm "không có tiếng nói".

- **(P1) Bộ chặn ảo giác chuẩn của Whisper:**
  - Tỉ lệ nén văn bản quá cao (lặp nhiều) → nghi ngờ.
  - Logprob trung bình quá thấp → nghi ngờ.
  - Danh sách câu ảo giác hay gặp → chặn.
  - Bị nghi ngờ thì giải mã lại với nhiệt độ cao hơn một lần. Vẫn tệ thì ASK_REPEAT.
- **Đo:** số câu ảo giác trên đoạn chỉ có tiếng ồn (không có người nói) và trên bộ ồn. Chạy trên VPS.

**Vấn đề 2: nghe sai từ chuyên ngành** ("máy ép", "rò rỉ khí", "xe nâng", "băng chuyền").

- **(P1) Gợi ý từ vựng qua prompt của Whisper:** đưa glossary công trường (hiện chỉ có 10 dòng trong `domain-packs/factory_en_vi/glossary.csv`, cần bổ sung) vào phần ngữ cảnh trước khi giải mã. Không cần huấn luyện lại.
- **(P2) Thêm câu trước cùng ngôn ngữ vào prompt** để giữ nhất quán trong hội thoại.
- **Rủi ro:** prompt có thể làm Whisper "nghe ra" từ trong glossary dù người ta không nói. Phải đo trên câu **không** chứa từ chuyên ngành để chắc không có hại.
- **Đo:** WER trên bộ BTC và bộ câu nhà máy, có và không có prompt. Chạy trên VPS.

**Vấn đề 3: greedy chọn sai ở chỗ khó nghe.**

- **(P2) Beam search theo điều kiện:** chạy greedy trước. Chỉ khi câu có tín hiệu an toàn hoặc có từ độ tin cậy thấp thì mới giải mã thêm 2–3 phương án. Trên NPU mỗi bước decoder khoảng 3–10 ms, nên khả năng nằm trong ngân sách thời gian.
- **Đo:** WER và thời gian trên bộ BTC (VPS), thời gian trên AI Hub và QDC.

**Vấn đề 4: dấu câu và chữ hoa.** PhoWhisper trả về chữ thường, dấu chấm thay cho dấu hỏi, nên câu hỏi bị dịch thành câu khẳng định.

- **(P1) Khôi phục dấu hỏi tiếng Việt:** câu kết thúc bằng "không / chưa / nào / gì / đâu / bao giờ / bao lâu / mấy / sao…" thì gắn "?". Viết hoa chữ đầu câu.
- **(P2)** Tách câu dài thành nhiều câu theo dấu hiệu ngắt.
- **Đo:** chrF/COMET của bản dịch **có và không có** khôi phục dấu câu, trên bộ BTC. Chạy trên VPS, kèm ML Kit trên QDC.

### 4.4 Lớp an toàn: bịt đúng lỗ hổng đang mở

**Vấn đề:** lớp an toàn chỉ thấy chữ ASR trả về. ASR nghe sai ("dừng" thành "rừng", "khẩn cấp" thành "cần cất") thì lớp an toàn tin nhầm. 5 lỗi lịch sử đều thuộc loại này.

- **(P1) Cổng độ tin cậy cho từ an toàn:** lấy xác suất token của **đúng các từ tạo nên khung an toàn** (hành động, số máy, mối nguy, khẩn cấp). Từ nào thấp thì không phát thẳng mà bắt xác nhận ("Có phải: DỪNG máy ép số 6?").
- **(P2) Cổng bất đồng giữa các phương án:** lấy 2–3 phương án ASR, trích khung an toàn cho từng phương án. Các phương án **cho khung khác nhau** thì bắt xác nhận. Đây là cách trực tiếp nhất để bắt lỗi "dừng" và "rừng".
- **(P2) Dịch ngược để kiểm nghĩa:** với câu an toàn, dịch bản dịch quay lại ngôn ngữ gốc rồi so khung. Cách này bắt được mất nghĩa nằm ngoài từ điển, tốn thêm 1 lượt dịch và chỉ chạy với câu an toàn.
- **(P1) Đo số câu bắt xác nhận nhầm** trên bộ BTC (chủ yếu câu đời thường). Lớp an toàn không được làm phiền câu bình thường.
- **Đo:**
  - Tỉ lệ cho qua câu nguy hiểm: 5 câu lịch sử, cộng bộ câu an toàn mới thu thật.
  - Tỉ lệ bắt nói lại hoặc xác nhận nhầm trên câu bình thường.
  - Chạy trên VPS bằng audio thật, rồi xác nhận lại trên QDC.
- **Ràng buộc:** không thêm từ ASR nghe sai làm từ đồng nghĩa, không đưa đáp án vào lúc chạy.

### 4.5 Dịch (MT)

**Vấn đề 1: ML Kit chưa từng được đo, và vướng yêu cầu offline.**

- **(P1) Đo ML Kit** trên 100 câu EN→VI (có đáp án), trên QDC. So với 1–2 model dịch mở chạy ONNX trên VPS.
- **Quyết định:**
  - Model mở đạt gần ML Kit → đổi. Lý do: offline hoàn toàn, đóng gói được, sau này còn lên NPU.
  - Không model nào đạt → giữ ML Kit, ghi rõ bước chuẩn bị cần mạng, và để video làm bằng chứng chính.

**Vấn đề 2: mất vế thứ hai của câu** (đã thấy ở OPUS).

- **(P1) Kiểm độ phủ:** tỉ lệ độ dài bản dịch so với câu nguồn nằm ngoài khoảng bình thường thì nghi ngờ mất ý. Khi đó thử dịch từng câu riêng theo dấu ngắt câu thật (không cắt mù giữa câu). Vẫn bất thường thì ASK_REPEAT hoặc hiện cảnh báo.
- **(R)** Dịch có ràng buộc theo glossary, chỉ làm được khi dùng model của mình.
- **Đo:** chrF/COMET, số câu mất vế, trên bộ BTC. Chạy trên VPS và QDC.

### 4.6 TTS và phát âm thanh trong môi trường ồn

- **(Đã làm)** Phát dần theo câu, đo tới âm thanh đầu tiên.
- **(P1) Cache âm thanh:**
  - Câu an toàn hay dùng ("Dừng máy!", "Có rò rỉ khí!", "Rời khỏi khu vực!") được tổng hợp sẵn, phát gần như tức thì (Phase 2 có hứa).
  - Câu lấy từ bộ nhớ thì cache luôn âm thanh lần đầu.
- **(P1) Phát theo mức ồn:** chuẩn hóa độ to, tăng âm lượng theo mức ồn nền đo được, đọc chậm hơn khoảng 10% với câu an toàn.
- **(P2) Giọng Việt tốt hơn:** VieNeu (đội nghe thích hơn Piper) cần port lên Android, khối lượng 2–3 ngày.
- **Đo:**
  - Thời gian tới âm thanh đầu tiên: trên QDC.
  - Độ rõ: cho ASR nghe lại bản TTS rồi tính WER (VPS), kèm một vài người nghe chấm điểm.

### 4.7 Ghép nối giữa các khâu (runtime)

**Vấn đề 1: đổi chiều là phải nạp lại ASR.** Trong hội thoại thật, hai người nói luân phiên, nên **gần như lượt nào cũng đổi chiều**. Hiện mỗi lần đổi, app đóng ASR cũ và nạp ASR mới (mất vài trăm ms tới vài giây). Nếu người dùng bấm nói ngay sau khi đổi chiều thì thời gian nạp này cộng thẳng vào latency.

- **(P1) Đo thời gian nạp lại** trên QDC, cả CPU và NPU. Model NPU đã compile thường nạp nhanh hơn.
- **(P1) Nếu RAM cho phép, giữ cả hai ASR trong bộ nhớ.** Bản nén chạy NPU nhỏ hơn nhiều so với FP32. Đây là thay đổi so với Phase 2, phải ghi lý do trong báo cáo.
- **(P2) UX hai nút nói**, mỗi nút một ngôn ngữ, thay vì nút đổi chiều. Hội thoại tự nhiên hơn, và hợp với việc giữ cả hai ASR.

**Vấn đề 2: mọi thứ chạy CPU.**

- **(P1) Bật NPU cho ASR** (encoder và decoder). Gỡ chỗ ép chạy CPU trong `LanguageRoutedAsr`, compile model bằng AI Hub với `--qairt_version` khớp với runtime của app (hoặc nâng ORT trong app).
- **(P2) VAD lên NPU** (Silero có sẵn trên AI Hub). Lợi ích tốc độ nhỏ, nhưng giúp cả pipeline nằm trong hệ của Qualcomm.
- **(P2) Chỉnh số luồng CPU** cho phần vẫn chạy CPU (dịch, TTS), ưu tiên nhân lớn.

**Vấn đề 3: chưa biết bộ nhớ thực tế.**

- **(P1) Ghi RAM (PSS) theo từng khâu** trên QDC: lúc khởi động, nạp model, ASR, dịch, TTS. So với cam kết < 1,6 GB.

### 4.8 Tổng hợp mức ưu tiên

| # | Thuật toán | Khâu | Tác động chính | Ưu tiên |
|---|---|---|---|---|
| 1 | Bật NPU cho ASR | Runtime | 2 giây, B | P1 |
| 2 | Bộ chặn ảo giác Whisper | ASR | A (ồn) | P1 |
| 3 | Cổng phân tầng, thử rồi kiểm | Cổng âm thanh | A, C (ít bắt nói lại) | P1 |
| 4 | Khôi phục dấu hỏi và chữ hoa | ASR → MT | A | P1 |
| 5 | Prompt từ vựng công trường | ASR | A | P1 |
| 6 | Thu nốt sau khi thả nút | Thu âm | A | P1 |
| 7 | Chọn chế độ mic | Thu âm | A (ồn) | P1 |
| 8 | Chuẩn hóa mức âm | Thu âm | A | P1 |
| 9 | Cổng tin cậy cho từ an toàn | An toàn | An toàn | P1 |
| 10 | Kiểm độ phủ bản dịch | MT | A | P1 |
| 11 | Cache câu an toàn, phát theo mức ồn | TTS | 2 giây, C | P1 |
| 12 | Giữ hai ASR / không nạp lại | Runtime | 2 giây, C | P1 (sau khi đo) |
| 13 | Cổng bất đồng nhiều phương án | An toàn | An toàn | P2 |
| 14 | Dịch ngược kiểm nghĩa | An toàn | An toàn | P2 |
| 15 | Beam theo điều kiện | ASR | A | P2 |
| 16 | Lọc ồn bằng model, có chọn lọc | Thu âm | A (ồn) | P2 |
| 17 | VieNeu TTS | TTS | A (rõ, tự nhiên) | P2 / R |
| 18 | Phát hiện lời chồng nhau, glossary trong MT, tai nghe mic sát miệng | Nhiều khâu | A, điểm cộng | R |

---

## 5. Cách đo để biết tối ưu có thật không

### 5.1 Quy tắc

1. **Đóng băng baseline trước:** ghi rõ model, cấu hình và commit, rồi đo một lần.
2. **Mỗi lần chỉ đổi một thứ**, đo lại trên **cùng bộ dữ liệu**, ghi vào bảng so sánh (ablation).
3. Giữ một thay đổi chỉ khi nó cải thiện chỉ số mục tiêu **mà không làm xấu** các chỉ số khác: an toàn, thời gian, tỉ lệ bắt nói lại.
4. **Tách lỗi theo khâu:** với mỗi câu, chạy MT trên **transcript đúng** và trên **transcript ASR**. Chênh lệch giữa hai kết quả cho biết lỗi do ASR hay do MT. Nhờ vậy biết cần tối ưu khâu nào trước.
5. Số nào chưa đo thì ghi **"Not measured"**. Không dùng số Mac hay VPS làm số Android.

### 5.2 Bộ dữ liệu

| Bộ | Nội dung | Dùng để |
|---|---|---|
| **BTC 200 câu** | 100 VI→EN (chưa có đáp án dịch), 100 EN→VI (21 người dịch, 79 AI dịch) | Bộ chính, giống bộ chấm ẩn |
| **BTC + ồn** | Trộn BTC với ồn nhà máy (DEMAND, như Phase 2) ở 0/5/10/20 dB | Đo chống ồn |
| **Chỉ có ồn** | Đoạn ồn không có người nói | Đo ảo giác |
| **Câu an toàn** | 5 câu lịch sử, cộng câu an toàn mới do đội thu thật | Đo cho qua câu nguy hiểm |
| **FLEURS VI/EN** | Bộ công khai, có audio và bản dịch hai chiều | Thêm mẫu khi so model |
| **Thu thật** | Đội tự thu ở nơi có quạt, máy chạy | Kiểm tra cuối, chọn chế độ mic |

**Cần người làm:** viết đáp án dịch cho 100 câu VI→EN. Viết **trước** khi xem output của app. Có thể để AI soạn nháp, nhưng phải ghi rõ là AI soạn và có người duyệt.

### 5.3 Chỉ số

| Chỉ số | Ý nghĩa |
|---|---|
| WER / CER | ASR nghe đúng bao nhiêu |
| chrF, COMET | Bản dịch đúng nghĩa bao nhiêu |
| Tỉ lệ bắt nói lại / xác nhận | Có làm phiền người dùng không |
| Tỉ lệ cho qua câu nguy hiểm | An toàn (mục tiêu 0) |
| Latency tới âm thanh đầu tiên, p50/p95/max | Cam kết ≤ 2 s |
| RAM đỉnh (PSS) | Cam kết < 1,6 GB |
| Kích thước model | Tiêu chí B |
| Điểm người nghe 0–4 (nghĩa, độ rõ) | Tiêu chí A, BGK chấm kiểu này |

---

## 6. Đo cái gì ở đâu

| Nơi | Đo được | Không đo được |
|---|---|---|
| **Mac** | Sửa code, chạy test nhẹ | Hiệu năng; việc nặng (ổ đĩa đầy) |
| **VPS** (Ubuntu x86, 8 vCPU, 16–32 GB, 100 GB) | Build APK; export model; chất lượng ASR, MT mở, TTS trên bộ BTC và bộ ồn; gửi job AI Hub; điều khiển QDC | Hiệu năng; ML Kit (chỉ chạy trên Android) |
| **AI Hub** | Compile model cho NPU 8 Gen 3; thời gian và RAM **từng model** trên chip thật; chất lượng model **sau khi nén** (inference job) | Cả app; mic, loa |
| **QDC** (8 Gen 3, 1000 phút) | **Cả app end-to-end** từ file WAV: latency từng khâu, tới âm thanh đầu tiên, RAM, nạp model, NPU thật hay không, ML Kit, chạy khi tắt Wi-Fi | Mic, loa thật; video demo |
| **Máy Android cầm tay** | Mic thật, chế độ mic, ồn thật, UX, **video demo** | (máy không phải Snapdragon thì số hiệu năng chỉ để tham khảo) |

---

## 7. Lộ trình từ giờ tới lúc nộp

Mỗi bước ghi: **làm gì · đo ở đâu · ra kết quả gì · khi nào coi là xong.**

### Giai đoạn 0: dựng môi trường (11/11)

| Việc | Ở đâu | Kết quả | Xong khi |
|---|---|---|---|
| Thuê VPS, cài JDK 17, Android SDK, Python, qai-hub, adb | VPS | Môi trường | `./gradlew :core:test` chạy được |
| Clone repo (deploy key), bạn tự cấu hình token AI Hub | VPS | | `qai-hub list-devices` chạy được |
| Export lại model (PhoWhisper Base, Whisper Small, Piper VI/EN) | VPS | Cache model | Hash khớp manifest |
| Build APK `debugCpu` | VPS | APK đầu tiên | Compile qua, kể cả code mới ngày 11/11 |

### Giai đoạn 1: bộ đo và baseline (11–12/11)

| Việc | Ở đâu | Kết quả |
|---|---|---|
| Tải 200 WAV và 2 file Excel BTC, kiểm header, hash | VPS | Bộ BTC đã khóa |
| Tạo bộ ồn (0/5/10/20 dB) và bộ chỉ có ồn | VPS | Bộ ồn |
| Viết đáp án 100 câu VI→EN | Người trong đội | Đáp án |
| Viết script đo: chạy ASR → (MT) → chấm, có tách lỗi theo khâu | VPS | Công cụ đo |
| **Đo baseline:** ASR hiện tại trên BTC sạch và bộ ồn | VPS | Bảng baseline |

### Giai đoạn 2: tối ưu thuật toán P1 (12–13/11)

Mỗi thuật toán trong mục 4.8 nhóm P1: viết code kèm test (Mac), đo trước/sau (VPS), giữ nếu có lợi.

Thứ tự đề xuất, đi theo dòng chảy của pipeline:
1. Chặn ảo giác.
2. Cổng phân tầng.
3. Khôi phục dấu hỏi.
4. Prompt từ vựng.
5. Chuẩn hóa mức âm.
6. Cổng tin cậy cho từ an toàn.
7. Kiểm độ phủ bản dịch.
8. Cache câu an toàn và phát theo mức ồn.

Thu nốt sau khi thả nút và chọn chế độ mic cần máy cầm tay, nên làm khi có máy.

### Giai đoạn 3: chọn model kết hợp NPU (12–13/11, song song giai đoạn 2)

| Việc | Ở đâu | Kết quả |
|---|---|---|
| So ASR tiếng Việt: PhoWhisper Base/Small, Whisper-Large-V3-Turbo-Quantized, Whisper-Medium-Quantized | Chất lượng: VPS. Tốc độ: AI Hub | Bảng chất lượng × thời gian |
| Compile ASR đã chọn (VI và EN) cho 8 Gen 3, chọn `--qairt_version` | AI Hub | Model NPU kèm link job |
| Chạy inference job để kiểm chất lượng sau khi nén | AI Hub (cần cho phép gửi audio BTC) hoặc QDC | WER trước/sau nén |
| So MT: model mở trên VPS, ML Kit trên QDC | VPS + QDC | Quyết định MT |
| Sửa app: gỡ ép CPU, đồng bộ runtime QNN, cho chọn backend | Mac → build trên VPS | APK có NPU |

**Tiêu chí chọn:** chất lượng tốt nhất **trong** ngân sách 2 giây và RAM < 1,6 GB.

### Giai đoạn 4: QDC (13–15/11), ngân sách 1000 phút

| Lượt | Phút | Việc | Kết quả |
|---|---|---|---|
| 1. Smoke | 60 | Cài APK, đẩy model, ML Kit tải model, xác nhận **NPU chạy thật** (log QNN) | Ứng dụng chạy được trên 8 Gen 3 |
| 2. So sánh | 150 | 200 WAV: ASR CPU so với NPU; ML Kit trên bộ BTC; thời gian nạp lại khi đổi chiều | Số so sánh |
| 3. Đo chính thức | 250 | Bản đã chốt, **tắt Wi-Fi**, khởi động trước 10 lượt, đủ 200 câu, 100 lượt liên tục, ghi RAM | Số nộp: p50/p95 tới âm thanh đầu tiên, RAM đỉnh |
| 4. Bản phát hành | 100 | Chạy lại trên APK cuối, giữ log và bản quay màn hình của QDC | Bằng chứng cùng một bản build |
| Dự phòng | 440 | Debug, chạy lại | |

### Giai đoạn 5: máy thật và video (14–15/11)

| Việc | Ở đâu |
|---|---|
| Chọn chế độ mic, thử thu nốt sau khi thả nút, thử ở nơi ồn | Máy cầm tay |
| Tập kịch bản nhà máy 30 phút | Máy cầm tay |
| **Quay video một take:** cho thấy máy và bản build, tắt mạng, hội thoại hai chiều, các tình huống phục hồi (nói lại, xác nhận), dòng thời gian hiện trên màn hình | Máy cầm tay |

### Giai đoạn 6: đóng gói, báo cáo, nộp (15–16/11)

| Việc | Kết quả |
|---|---|
| Ký APK, đóng gói model, config, giấy phép, hash | Gói cài offline |
| Viết hướng dẫn cài offline; một người khác trong đội thử cài theo hướng dẫn | Hướng dẫn đã kiểm |
| Điền báo cáo V3 bằng số thật, mỗi số kèm file log hoặc link job; ghi thay đổi so với Phase 2 và lý do; tối đa 3 bước roadmap | Báo cáo .docx/.pdf |
| Kiểm link Drive/GitHub, quyền truy cập, tên file | |
| **Nộp form trước 23:59 ngày 16/11** | Biên nhận |

---

## 8. Rủi ro và phương án dự phòng

| Rủi ro | Dấu hiệu | Phương án |
|---|---|---|
| Không có máy cầm tay để quay video | Tới 14/11 chưa có máy | Mượn bất kỳ máy Android arm64 nào (không bắt buộc Snapdragon), hoặc slot máy tại VNG |
| Model NPU không nạp được trên app | Lượt QDC 1 báo lỗi QNN | Compile lại với `--qairt_version` khác, hoặc nâng ORT. Cuối cùng: nộp bản CPU, ghi rõ, và để link job AI Hub làm bằng chứng NPU từng model |
| Vượt 2 giây | p95 trên QDC > 2 s | Model ASR nhỏ hơn, cache, giữ hai ASR, rút ngắn TTS câu đầu |
| ML Kit không đạt offline, model mở thì kém | Giai đoạn 3 | Giữ ML Kit, chuẩn bị sẵn model trên máy demo, ghi rõ trong hướng dẫn |
| Thiếu đáp án VI→EN | Tới 13/11 chưa có | AI soạn nháp có ghi nhãn, người duyệt một phần, báo cáo tách rõ |
| Hết phút QDC | Còn < 200 phút trước lượt 3 | Ưu tiên lượt 3 và 4 |
| Repo private | Lúc nộp | Public một bản sạch (không token, không dữ liệu BTC) hoặc hỏi BTC ngoại lệ |

---

## 9. Việc cần người quyết định hoặc làm

- [ ] Thuê VPS, đưa alias SSH để Claude chạy lệnh từ xa.
- [ ] Tự cấu hình token AI Hub trên VPS (không commit).
- [ ] Có cho gửi audio BTC lên AI Hub (inference job) không.
- [ ] Ai viết đáp án 100 câu VI→EN.
- [ ] Máy Android cầm tay để thử mic và quay video.
- [ ] Public repo lúc nộp, hay hỏi BTC.
- [ ] Theo dõi email lịch máy tại VNG Campus.
- [ ] Người đại diện nộp form trước 23:59 ngày 16/11.

---

## 10. Tài liệu liên quan

- [Yêu cầu BTC và bằng chứng](../../submission/phase-3/requirements.md)
- [Thông báo BTC](../../submission/phase-3/announcements.md)
- [Bộ dữ liệu BTC](../../submission/phase-3/evaluation/dataset.md), [cách review đáp án](../../submission/phase-3/evaluation/reference-review.md)
- [Bàn giao model và Qualcomm](model-pipeline-handoff.md), [AI Hub tooling](../../model-tools-python/qaihub/README.md)
- [Lỗi an toàn đang mở](../testing/safety-failures.md), [quy trình đo](../testing/measurement-protocol.md)
- [Hướng dẫn điền báo cáo](../../submission/phase-3/report-guide.md), [kịch bản demo](../../submission/phase-3/demo.md)
