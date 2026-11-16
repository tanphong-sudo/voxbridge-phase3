# Ma trận yêu cầu BTC và bằng chứng

Nguồn: Brief §1–8 (EN/VI tương đương) và toàn template, hash ở `template-field-inventory.json`/current-state. Đây là map coverage, không phải bảng kết quả đạt. Giấy tờ BTC là yêu cầu bài thi, không tự cấp quyền upload/public/dispatch remote.

| Requirement | Mức | Evidence cần | Kế hoạch/đường thu | Hiện trạng |
|---|---|---|---|---|
| Chạy phần cứng thực tế | Bắt buộc §3 | device facts + actual pipeline logs/video | §1, §3.3, §4 | chưa xác nhận target |
| Inference local, không API/cloud demo | Bắt buộc §3 | runtime/backend, APK permission, disconnected live demo | §2, §3.2, §3.4 | source design, thiếu actual |
| Giám khảo tự thao tác | Bắt buộc §3 | installer + independent task run | §3.4, §4 UX | UI emulator historical |
| Ít nhất cặp đăng ký | Bắt buộc §4 | registered-pair confirmation + actual both directions claimed | §1 languages, §4 quality | VI↔EN code, chưa registration check |
| Khoảng30 phút đúng use context | Bắt buộc chuẩn bị §4 | script, actual duration, task completion | §1 scenario/conditions, §2, §4 UX | draft cần rehearsal |
| Tạp âm nền nhẹ | Điều kiện §4/khuyến nghị thử trước | condition-specific outcomes | §1 conditions, §4 quality | old noisy host evidence riêng |
| Hidden1000/cặp, mẫu BTC | Benchmark §2 | provenance/splits/sample IDs/coverage | §4 quality + dataset intake | chưa đọc folder mẫu |
| Quality meaning/clarity/naturalness 45% | Trụ cột A | reference/audio ratings/examples/failures | §4 Translation quality | WER alone chưa đủ |
| Model size/inference/peak memory20% | Trụ cột B | exact selected bytes + timing/PSS raw | §4 ba hàng riêng | Mac timing historical, target missing |
| Integration/worker UX20% | Trụ cột C | live tasks, recovery, interruption log | §4 UX, §3.4 | thiếu physical acceptance |
| Video/docs/reproducibility/roadmap15% | Trụ cột D | same-build package/video/report/links | §2, §3, §4 next steps | chưa có final package |
| Video actual uncut, input→output | Bắt buộc §7.1 | nguyên take/hash, timestamp input/text/audio | §2 | chưa quay |
| Cảnh tắt mạng ngay trước chạy | Bắt buộc §7.1 | setting proof same take/device | §2, §3.4 | QDC control không tự chứng minh |
| Screen/log thấy thời gian thực | Bắt buộc §7.1 | readable display/log, defined timing | §2, §4 timing | cần shot rehearsal |
| Offline installer đầy đủ | Bắt buộc §7.2 | signed APK/models/config/deps/install steps | §3.1, §3.4 | dev scripts không thay final installer |
| Source package có requirements/Dockerfile+run | Theo package format §7.2 | fully offline deps/entrypoint | §3.1 format | APK route ưu tiên, không ép Docker cho APK |
| Actual benchmark ngắt mạng + raw logs/ảnh | Bắt buộc §7.3 | complete raw dataset/run artifacts | §4 | chưa có target offline run |
| Hardware/software reproduction guide | Khuyến khích §7.4; template hỏi rõ | exact versions, compatibility, commands/actions | §3.2–3.4 | plans ready, procedure untested |
| Video fallback nếu BGK không cài được | Quy tắc chấm §7 note | original take/access, no simulation | §2 | cần ưu tiên quay sớm |
| Submit form + Drive/GitHub links | Định dạng §8 | submitted receipt/link permissions | final checklist | chưa nộp; human action |
| Architecture/install/minimum trong technical doc | Yêu cầu §8 | §3.2–3.4 filled with evidence | section04–06 | planned |
| Public GitHub source | Brief §8, template exception wording | BTC clarification or compliant public/exception decision | §3.1 source | private, unresolved |
| Completed template .docx/.pdf đúng tên | Yêu cầu §8 | actual rendered final file + check | all sections | chưa author final |
| Cùng build mọi artifacts | Before submitting | release ledger/source/model/APK/video/log joins | every section | tooling chuẩn bị |
| Team-recorded không official/independent score | Template §4 | methodology and labeling | section07 | không suy BTC score |
| Minimum tested vs estimated | Template §3.3 | labeled validation limits | section05 | target chưa test |
| Measurement unavailable ghi Not measured | Template §4 | explicit gap, no estimated replacement | section07 | enforced review rule |
| Evidence filenames/timestamps ở từng result row | Template §4 | exact artifact locator | section07 | pending measurements |
| Known limitations và demo/package differences | Template §4 | failure log + version comparison | section07 limitations | historical failures có |
| Tối đa3 next steps, cách kiểm tra | Template §4 | three concrete evidence-backed actions | section07 roadmap | choose after run |
| Contact/team change qua form | Template cuối + announcement | người nộp xác nhận, form record | human final checklist | chưa xác nhận, không tự gửi |
| AI Hub/QDC trước hands-on | Khuyến khích §1/5 | supported model/app test artifacts | technical plan | chưa account/session |
| Advanced multi-speaker/dialect/code-switch | Khuyến khích §3 | only claim if measured; otherwise roadmap | scope gate | không mở mới trước stable pair |
| Form factor/enclosure/battery | Khuyến khích §3 | optional actual hardware design | scope gate | app-only hợp lệ |
| Hardware design bonus ≤0.5 | Bonus §6 | only custom optimized hardware evidence | scope gate | không prior hơn85% A+B+C |
| Thêm cặp cho Field Testing/Final | Sau vòng này §4 | next-pair acceptance suite | roadmap | không claim current support |
| Hạn13/11/2026 23:59 UTC+7 | Bắt buộc §1/announcement | form receipt trước hạn | target12/11 submit | chưa nộp |
| Workshop23/10; lịch devices chưa có | Mốc hỗ trợ §1/announcement | confirmed schedule/questions | `workshop-questions.md` | địa điểm/cách đặt còn hỏi |

Coverage check: table row/paragraph fields xem35 ID trong `section-review-queue.md`. Matrix này bổ sung instructions ngoài bracket; không thay thế từng field plan. Các thời điểm sau deadline là dự kiến trong Brief (23/11 kết quả,28/11 field-testing,05/12 final); không tự tạo calendar/reminder.
