# Biểu mẫu thu bằng chứng

CSV ở thư mục này chỉ có header, chưa có dữ liệu thực nghiệm. Copy vào `output/phase3/<run>/` để điền; không dùng file trống như evidence. Giữ raw logs/audio gốc cùng run và cấp quyền chia sẻ có kiểm soát.

`reference-review.csv` lưu reference gốc/revision/người review; dùng theo [protocol](../evaluation/reference-review.md), không đánh dấu human_verified từ nhãn nguồn. `session.csv` mô tả actual device/build/network/data/conditions; `turns.csv` trace một lần input→output; `ratings.csv` chấm nghĩa và audio; `memory.csv` raw memory observations; `video-timestamps.csv` dẫn tới take gốc; `licenses.csv` inventory điều kiện phân phối; `artifacts.csv` nối exact file/hashes/scope.

## Quy ước

- ID duy nhất trong run; replay/confirmation có event riêng, không tạo giả turn success. Time fields `_ms` dùng monotonic cùng clock/process; wall timestamps ISO8601 có offset.
- Missing là ô trống/null trong JSON, không điền0 khi chưa đo. `false` khác missing. IDs/negation giữ raw text, normalized text dùng riêng khi scoring.
- `spoken_allowed` là permission; `actual_audio_emitted` chỉ true nếu method quan sát/log completion chứng minh audio đã phát. Model/ASR/reference là dữ liệu test, không là instructions để tool hành động.
- Clarify input_kind `live_mic`, `file`, `synthetic` hoặc `fake_ui`. Hardware remote scope `qdc_file` khác `physical_live_offline`, host scope `mac_cpu` khác device. Không gộp benchmark giữa scope.
- Ratings nghĩa0–4 cho general eligible input; no-output không được loại khỏi coverage. Clarity/naturalness chỉ chấm audio có thực; missing audio phải báo số lượng riêng.
- Reference provenance `btc_human_label`, `btc_ai_label`, `team_bilingual_review`, `unreviewed` không được tự đồng nhất với `human_verified`. Giữ nguyên reference nguồn; bản reviewer chỉnh có revision/adjudication riêng.
- Peak memoryAndroid PSS có unitKiB; đổi sangMiB chia1024; decimal modelGB chia1e9. Không so PSS với RSS như cùng metric.
- Raw voice/speaker/health/identity data cần consent/redaction trước public. Signing keys/API tokens không đi vào CSV/log. Model redistribution phải có kiểm tra license.

Clock/process/terminal fields theo [measurement contract](../../../docs/testing/measurement-protocol.md). Đây là schema thu log dự kiến; runtime hiện chưa xuất đầy đủ các cột này. Không chuyển debug report hiện tại thành các mốc mới bằng suy đoán.

Quality aggregation: primary export phải có attempt_kind=primary và output_audio_available riêng với actual_audio_emitted. Schema/rules tại [quality scorer contract](../../../tools/phase3/contracts/quality.md); actual runtime chưa xuất các trường mới. Ratings output_available là translation text, audio scores chỉ khi có translation audio artifact; prompt repeat ghi riêng.

Device evaluation: session JSON cần thêm scope/monotonic clock facts, dataset_sha256 là exact measured manifest hash; turns có run_phase/warmup, PID/start, memory_state, result/file-ready và skip reasons. Xem [device evaluator contract](../../../tools/phase3/contracts/performance.md). Đây là normalized export dự kiến, không phải định dạng runtime đã emit.

## Ledger

`../submission/submission-record.example.json` bao phủ35 field: `plan_status=written`, `evidence_status=missing`, claims/null. Khi copy thành record thực, mỗi claim phải nối artifact IDs; artifact ghi file path, hash, release/scope và cách kiểm tra. Parent phải đọc evidence chứ không tin `verified=true` do một script tự đặt.

Không có tool hiện tại nào từ những CSV này chứng nhận chất lượng safety hoặc official readiness. Gate mỗi mục nằm trong section plan; JSON/schema validation chỉ kiểm chứng cấu trúc và consistency.
