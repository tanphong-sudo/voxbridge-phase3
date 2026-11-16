# Dữ liệu Vòng3

Thư mục source chỉ giữ protocol/schema và manifest đã kiểm tra quyền chia sẻ. Chưa có audio hoặc runnable BTC manifest ở đây. Không gọi các ví dụ/tập synthetic là dataset BTC.

Nguồn và hiện trạng: [dataset intake](../../submission/phase-3/evaluation/dataset.md). Quy trình: [device/test contract](../../docs/testing/measurement-protocol.md), [reference review](../../submission/phase-3/evaluation/reference-review.md), [measurement](../../docs/testing/measurement-protocol.md).

## Intake trước runner

1. Lưu workbook/WAV gốc trong `output/phase3/<dataset_revision>/`, không vào Git; giữ nguồn Drive ID và thời điểm lấy. Connector-readable text không phải binary XLSX gốc.
2. Xác minh từng original ID duy nhất, direction/source transcript; actual WAV tồn tại, hash, PCM/header/rate/channels/duration và metadata khớp. File thiếu/corrupt ghi issue, không âm thầm thay bằng TTS/resample.
3. Giữ target reference gốc và provenance. VI→EN cần reference độc lập; EN→VI giữ labels21human/79AI, human_verified chỉ sau review có bằng chứng. Không đưa reference vào runtime input/glossary.
4. Khóa manifest revision/hash, split/group và exposure. Candidate200 ở output/phase3/dataset-intake/candidate-manifest.NOT-RUNNABLE.jsonl hiện có WAV/hash null; không gửi thẳng vào runner.
5. Khi runtime/runner sẵn: smoke vài sample hai chiều, kiểm tra ID join và raw artifacts rồi mới full supplied set. Không chạy model trong chặng chuẩn bị này.

## Mỗi record cần giữ

- ID nội bộ duy nhất và original ID; dataset/source revision, direction, split/group và provenance.
- Local WAV path tương đối với dataset root, exact SHA256/bytes/header/duration; actual recording condition/original sample rate nếu có. Unknown giữ null.
- Source reference, target reference ID/revision/provenance/review status. Original và adjudicated reference tách riêng.
- Rights/sharing status; reviewer/date và bằng chứng. Dữ liệu có thể dùng nội bộ không tự động được public hoặc upload sang dịch vụ bên thứ ba.

Khi thêm runner Android, chốt schema theo contract trước và validate input; không tự gán fields chưa được runner hỗ trợ. Host CorpusRunner hiện đọc một schema riêng, không có nghĩa Android instrumentation tự nhận được cùng manifest.

## Thu dữ liệu người nói thật

Dùng forms trong submission/phase-3/forms, copy vào output. Ghi consent/phạm vi chia sẻ, speaker alias, session và điều kiện thật. Chọn development và acceptance trước khi tune; group theo người/phiên/paraphrase, giữ unknown metadata đúng trạng thái. Script demo chỉ là prompt hội thoại, không phải gold references hoặc đáp án nhúng sẵn.

Với kết quả: lưu một row/turn, lỗi cũng có row; output text/audio đúng turn ID, timestamp/method và terminal status. Không ghi đè WAV theo một tên duy nhất. Không công bố metric trước khi có đủ output/ref/ratings/coverage tương ứng.
