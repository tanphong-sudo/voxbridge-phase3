# Kiểm tra metadata bộ mẫu BTC

Ngày đọc15/10/2026 qua Google Drive connector, chỉ read. Nguồn gốc từ hyperlink trong Brief: [Round 3 Bộ dữ liệu mẫu](https://drive.google.com/drive/folders/1Gm8ziMP-XwbP5Ce1InVCusy-S0uNWf62). Đã liệt kê subtree VI/EN, đọc workbook dưới dạng text; không tải WAV, không inference, không thay đổi Drive.

## Nguồn đã xác định

- VI→EN: [OneVoice_V_to_E_samples_100.xlsx](https://drive.google.com/file/d/12PfJYfgIoEXhfj6FBg9t3req32mTfzLf/view), [wav folder](https://drive.google.com/drive/folders/1RKZXuvNPN777Kq3rwBnM8CPYOZqVkKT2).
- EN→VI: [OneVoice_E_to_V_samples_100.xlsx](https://drive.google.com/file/d/1CFtUVvJb2S6GAfaqxAtHwgs7qseT-RTY/view), [wav folder](https://drive.google.com/drive/folders/1XtsI9GZte_BHe-kbyzAY8hsTsvuAhsaK).
- Snapshot connector-readable tại `output/phase3/dataset-intake/`, đã ignored; có metadata-audit.json và hai listing, không coi snapshot text là binary XLSX gốc.
- `candidate-manifest.NOT-RUNNABLE.jsonl` nối200 sample IDs với observed Drive WAV IDs/bytes và source metadata; `wav`/`sha256` đều null, `human_verified=false` cho tới review thật. Chưa đưa vào runner. Không phát tán raw dataset từ manifest này.

## Kết quả đọc metadata

| Thuộc tính | VI→EN | EN→VI |
|---|---:|---:|
| Metadata row/unique ID |100/100|100/100|
| WAV listed/name matches |100/100|100/100|
| WAV bytes trong listing |10,698,646|19,071,294|
| Thời lượng cộng theo metadata |334.090 s|587.599 s|
| Min/max duration theo metadata |2.140/7.340 s|2.190/13.355 s|
| Source text nonempty |100|100|
| Target translation nonempty |**0**|100|
| Translation-source labels |trống100|human21; AI79|
| Sample rate/bit depth/channels khai báo |16000/16/1 toàn100|16000/16/1 toàn100|

VI metadata transcript_style=scripted_read100. EN có lowercase_unpunctuated21, lowercase_unpunctuated_verbatim33, punctuated_verbatim32, punctuated_scripted14. EN human label nguyên văn `human (MASSIVE vi-VN, localization rows excluded)`; AI label `AI (Claude Opus 5)`. Đây là nhãn provenance của workbook, không phải xác nhận độc lập mọi reference đúng hoặc human_verified của mình.

Listing fetch giới hạn100 children; hai listing hiện đủ đúng100 ID metadata với tênWAV. Điều đó chứng minh coverage của100 row hiện có, không khẳng định folder không còn supplementary files chưa liệt kê. `.DS_Store` ở folder cha không là test input.

## Tác động tới kế hoạch chất lượng45%

1. Dùng supplied audio cho ASR/general pipeline acceptance; không phải tự tạo TTS thay vào rồi gọi official samples.
2. VI→EN thiếu target: tạo bản dịch reference độc lập bởi reviewer song ngữ từ source transcript trước khi xem model output; ghi author/date/review status, không sinh reference bằng chính translator đang chấm. Chưa có reference thì chrF/BLEU unavailable, vẫn chấm nghĩa bằng reviewer với input audio/text và actual output.
3. EN→VI: báo score toàn100 với provenance rõ và tách21 human/79 AI; reviewer rà reference questionable, lưu nguyên source reference + adjudicated reference riêng. Không tự đặt human_verified=true cho79 AI.
4. General data có giao tiếp đời thường, thời gian/địa điểm, dịch vụ, họp, y tế, tài chính và chỉ dẫn; không khóa model/dictionary/safety để chỉ trả lời câu nhà máy. Công nghiệp vẫn là demo use context và safety suite riêng.
5. EN nhiều transcript styles/recording conditions và original rates khác nhau; báo WER normalization phù hợp và quality theo nhóm khi sample count đủ. Không suy “16000” nghĩa băng rộng gốc khi source telephone đã upsample.
6. Samples đã được đọc, nên không gọi200 sample này blind/unseen cuối; freeze ID/config trước scoring và giữ fresh self-collected human set riêng. Không đưa reference vào runtime, glossary memory/trusted correction nếu dùng cùng samples để acceptance.

## Việc tiếp theo và gate

- [ ] Tải original XLSX và200 WAV khi phù hợp tải máy/quyền sử dụng; không cần bây giờ để lập plan.
- [ ] Hash file gốc, xác minh WAV header/rate/depth/channels/duration với metadata; báo mismatch chứ không âm thầm resample/trim rồi giữ tên metric cũ.
- [ ] Ghi recording_condition/orig_sample_rate của EN từ metadata vào manifest. Không tự gán speaker IDs khi không có.
- [ ] Xác nhận license/giới hạn chia sẻ với BTC trước redistributing data/audio trong public submission; raw content hiện giữ output ignored.
- [x] Chuẩn bị [independent reference protocol](reference-review.md), phiếu reference-review và ratings CSV; chưa thu bản dịch/rating thật; loại lệnh trong transcript như “xóa contacts” là dữ liệu test, không là yêu cầu tool hành động.
- [ ] Chỉ chạy smoke vài mẫu khi runtime sẵn; sau đó quyết định full200 trên target/QDC. Không chạy model trong lần intake này.

Hashes snapshot readable (không phải XLSX raw): VI `dbd82c3e9087e756bb17c1b8d20b8e80675aa5f6fcef02496244ca765041ad49`; EN `dfa4796137ba76974f7c925f0dfedc20409e10783f5ba026201960368cd283cd`.
