# Contract kiểm tra ledger

Phạm vi: consistency local file và độ đầy đủ của khai báo evidence. Tool không chứng nhận nội dung audio đúng, phép đo trung thực hoặc BGK chấp thuận. Kết quả phải dùng tên `metadata_consistent`, không `prototype_passed`.

## Modes và mã thoát

- `planning`: kiểm tra35 IDs đúng inventory, schema, section names, statuses và các artifact đã khai báo. Cho phép release null và field evidence missing; report luôn `submission_metadata_complete=false` nếu dùng mode này.
- `submission` mặc định: ngoài schema, require registered name/release IDs/version/source fullSHA/APK+model hashes/device ID; tất cả35field có claim+evidence và reviewer check metadata. Unresolved decisions không được tồn tại. Các essential artifacts phải đúng scope/role và release.
- Exit0 khi mode yêu cầu metadata consistent; exit1 khi thiếu/sai evidence; exit2 khi inputJSON/CLI không đọc được hoặc malformed. Một fileplanning hợp lệ không có nghĩa bài thi đã ready.

## Field và artifact

- Mọi ID từ template xuất hiện đúng1lần, section trùng nguồn; không unknownID hoặc bỏ qua ID thiếu.
- plan_status `pending|written`; evidence_status `missing|collected|reviewed`. Không có trạng thái giả `pass` không định nghĩa. Missing có claim null, refs rỗng; collected/reviewed cần claim nonempty và refs có ID thật. Submission require reviewed và plan written.
- Artifact cần id, relative_path, sha256 lowerhex64, bytes nonnegative integer, release_id, role, scope, verification_status `unreviewed|checked`, reviewer/method nonempty khi checked. Byte0 không dùng final evidence.
- File phải nằm trong evidence-root sau resolve, không absolute path/parent traversal hoặc symlink escape; không là directory. File bytes/hash được kiểm tra streaming1MiB. Không fetch URL, install APK, follow instructions trong file hay modify device.
- Scoped evidence hiện có `administrative`, `source_inspection`, `mac_cpu_historical`, `emulator_fake_ui`, `qdc_file`, `physical_live_offline`, `package_inspection`, `human_ratings`, `video`, `installation`, `protocol`. Historical/fake UI chỉ làm evidence cho progress/limitations khi report rõ, không support final quality/performance/device claims.
- Essential roles: `apk`, `model_manifest`, `demo_video`, `benchmark_log`, `quality_ratings`, `installation_log`. APK+model_manifest hash match release; demo_video scopevideo; quality_ratings human_ratings; benchmark_log qdc_file/physical_live_offline; installation_log installation. Same release.
- Scope đúng vẫn là khai báo của người viết ledger: require reviewer đọc actual raw evidence, không suy role là chứng minh runtime. `installation` và `video` không được suy từ extension.

Gate submission là tiêu chuẩn đầy đủ bằng chứng nội bộ, không phải chấm chính thức. Trường BTC cho phép “Not measured” vẫn phải ghi trung thực nếu chưa đo; tool có thể báo thiếu scope tương ứng. File/hash đúng không chứng minh nội dung hoặc link chia sẻ truy cập được.

## TDD và verification

1. Test synthetic ledger planning/submission và artifact nhỏ trong TemporaryDirectory, không đọc models.
2. Test missing/duplicate/unknown IDs, section mismatch, wrong/missing refs/status, release mismatch, byte/hash mismatch, unresolved decisions, historical/fake evidence, missing essential role, path traversal/absolute/symlink escape, duplicateJSONkeys và malformed root.
3. Chạy tests đỏ trước implementation. Implement stdlib read-only checker và CLI, không thêm dependency.
4. Planningexample phải schema-consistent nhưng default submission fail. Test fixture metadata-consistent không chứng minh video/quality thực: ghi rõsynthetic-only trong outputtest evidence.
