# Contract kiểm tra performance logs

Phạm vi: evaluate_device_run.py đọc session JSON, measured manifest JSONL, turns/memory CSV và performance-budget.json. Không ADB, profiling, inference hoặc kiểm chứng provider/clock do thiết bị khai báo. Không thay quality/safety acceptance.

Session cần run/release/device, dataset/model/config hashes, clock_id/kind/unit, input_kind, planned_turn_count và warmup_turn_count. Clock phải monotonic/ms. Manifest IDs/directions phải duy nhất VI↔EN; planned count khớp manifest đo. Turn có run/device/release, PID/process_start_id, clock_id/unit, run_phase warmup|measured, attempt_kind primary, memory_state, input_kind và terminal_status. Một measured primary/sample; warm-up báo riêng, không bỏ failure khỏi số attempted.

Timestamp trống là chưa đo. Số âm/NaN/boolean/sai kiểu bị reject. Mốc kết thúc trước bắt đầu là issue, không dùng turn đó để tính latency. Báo ASR/MT/TTS spans riêng, không cộng overlap. Chỉ completed turn có đủ endpoints mới có latency completed; timeout/error/cancelled/no_speech/ask_repeat/awaiting_confirm đều vào status counts, không tự thành0ms hay completed.

Report p50/p95/max bằng nearest-rank (ceil(p*n), index từ1), kèm n. Tách direction×memory_state và boundary. release→first_audio chỉ dùng first_audio_method=measured + actual_audio_emitted=true; estimated/enqueue_proxy không được đổi tên audible latency. File-ready→result riêng cho file-input; cold_start_ms nếu được đo là session field, không trộn warm-turn metrics. Báo missing endpoints cho completed input phù hợp.

Memory đúng run/device/clock/PID-start segment, total_pss_kib≥0, interval>0, monotonic_ms tăng trong từng segment; raw_log_path/tool/version/phase có khai báo. Unknown PID-start không join được phải reject. Tính sampled peak MiB=KiB/1024, n và max observed gap từng segment; không nối restart thành trend một process, không gọi snapshot là instantaneous peak. Native/Java nullable, báo phase coverage. Không đọc raw_log_path để suy số đo đúng.

Data completeness cần đủ measured rows, endpoints phù hợp, memory samples và không issue; không đồng nghĩa benchmark passed. Budget mặc định pilot_required và mọi threshold/binding chưa có evidence để null. Frozen budget chỉ so trên scope/device/release/model/config/manifest/input_kind/memory_state đã khóa; cần pilot ID khác run acceptance, min turn/warmup/phase coverage và numeric p95/peak/failure limits. Mismatch/missing dữ liệu không được PASS. Budget chỉ so metadata/scalars được khai báo; runtime_verified luôn false.

Tất cả group có output latency n và status/coverage, không drop unknown group. Frozen gate kiểm tra mỗi direction×memory group với min_samples_per_group, không lấy group nhanh nhất hoặc trộn hit/miss để đạt ngưỡng. Failure rate=mọi measured non-completed/attempted; missing row là thiếu dữ liệu, không đoán failure runtime. Parent phải đọc raw evidence trước nghiệm thu.

CLI --session/--manifest/--turns/--memory, --budget mặc định config/phase3/performance-budget.json, --output tùy chọn. Output không được trùng input/alias. Exit2 schema/parse error; exit1 incomplete/issue/unfrozen budget/threshold failure; exit0 chỉ khi data complete và frozen budget metadata gates đạt, không official/runtime certification.

TDD tiny fixtures: pending budget không PASS, đúng unit/percentile, missing rows/stages/proxy, reversed time, foreign IDs/clocks, warm-up/failure counts, restart segments, invalid numeric/PSS và frozen budget binding/limits. Không chạy app hoặc dùng số giả làm report thực nghiệm. Các cột mới ở forms chỉ là schema dự kiến, collector chưa emit.
