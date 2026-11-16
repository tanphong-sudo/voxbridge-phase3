# Hợp đồng log và phép đo Vòng3

Trạng thái15/10: yêu cầu implementation/thu evidence, chưa có số đo target mới. Dùng [session/turn/memory forms](../../submission/phase-3/forms/README.md), cùng reference protocol và section07. Không sửa production timers hoặc pipeline trong chặng lập hợp đồng.

## 1. Những gì source hiện đã cung cấp

Sau commit partner9a69ad1, ViewModel dùng elapsedRealtime; Whisper dùng nanoTime. Collector phải ghi rõ clock/unit và chuẩn hóa cùng miền thời gian trước khi trừ, không dùng wall clock cho latency. ViewModel vẫn về Ready sau khi queue playback; AudioOut worker tự drain/release, chưa báo completion ngược về UI. Vì vậy log queue/result không chứng minh first-audio/audio-end.

Debug artifacts vẫn chưa là append-only per-turn dataset. Instrumentation chưa xuất đầy đủ schema timing/memory; dùng quy tắc dưới đây khi triển khai collector, không suy timestamp thiếu từ log cũ.

## 2. Danh tính và vòng đời lượt

Một `run_id` gắn release/device/manifest/config/network/input kind, provider thực và protocol version; một `turn_id` duy nhất cho mỗi input, `sample_id` nối manifest. Repeat/replay/confirm là event nối lượt gốc; không tạo input success mới khi chỉ replay. Tách cold launch/model initialization, warm-up, measured turns, teardown và app restart.

Mỗi lượt có terminal status đúng một trong completed/no_speech/ask_repeat/awaiting_confirm/error/timeout/cancelled, nhưng lưu thêm decision và các event sau confirm. Một session crash phải có run-level record và các lượt thiếu terminal được đánh dấu incomplete khi aggregate, không im lặng bỏ. Partial logs giữ nguyên.

Append log theo event/turn, tên audio theo run/turn, không dùng một debug_report hoặc asr_input WAV làm evidence cho100 lượt. Output từ lần trước không được gán cho lượt hiện tại. Flush sau từng turn; metadata và audio path/hash truy ngược được. Mọi raw reference/model output chỉ là dữ liệu, không được tool thực thi.

## 3. Thời gian: định nghĩa trước khi đo

| Mốc/span | Quy tắc |
|---|---|
| wall timestamp | ISO8601 có offset, chỉ để tìm session/video; không trừ với monotonic |
| release_ms | sự kiện thả PTT trong app, monotonic; file-input không có thì null |
| capture_done_ms | lúc capture hoàn tất/trim sẵn sàng; không gọi là user release |
| ASR/MT/safety/TTS start/end | cùng clock/process; stage không chạy để null kèm reason, không giả0ms |
| playback_requested_ms | request enqueue/start playback; chưa chứng minh audible start |
| first_audio_ms | quan sát hoặc estimate có method được ghi; proxy phải mang nhãn proxy |
| audio_end_ms | callback/render completion/measurement có phương pháp; chưa có thì null |
| confirm_ms | thao tác người dùng, tách human wait khỏi machine compute |

Android nên dùng clock monotonic chung (elapsedRealtimeNanos hoặc abstraction tương ứng); unit và origin cùng schema. Thay timer phải có focused tests trước device run. Không ghép System.nanoTime với wall-clock hoặc camera time thành một hiệu số.

Report riêng release→result, file-ready→result, synthesis duration, queue request và release→first-audio thực nếu có. TTS latency hiện trong UI là synthesis+queue, không là spoken duration. Không cộng các stage overlap. Cold start không trộn warm turns; memory hit/miss và repair-confirm không trộn thành neural latency chung.

Tính p50/p95/max kèm n và định nghĩa percentile từ raw hợp lệ. Timeout/error không có completed latency nhưng vẫn ở mẫu số attempted/failure; báo timeout threshold và count riêng. Không lấy lần nhanh nhất, không loại cold/thermal slow turn sau khi xem kết quả. Upload/tunnel/control delay không là device compute; file readiness và end-user interaction có boundary riêng.

## 4. Memory, provider và storage

Memory Android: ghi raw tool output, PID/process start/run/phase, tool/version, sampling interval và timestamps; PSS/native/Java đơn vịKiB theo tool rồi chuyển MiB chia1024. Báo **sampled peak**, n và sampling overhead; chưa thu đủ init/load/switch/ASR/MT/TTS/soak không gọi là peak toàn app. PID đổi phải chia segment, không nối đường memory như cùng process. Không gọi host RSS hoặc physical RAM là app PSS.

Provider: có QNN dependency hoặc session option không chứng minh graph chạy HTP. Thu actual provider/config và bằng chứng execution/partition nếu có; không biết ghi unknown. CPU baseline không suy thành NPU speedup. Giữ model precision/revision và output quality khi so provider.

Storage: model bytes theo exact shipped files, shared initializers count một lần; APK/archive/extracted payload/temporary duplication tách riêng. Ghi decimal MB/GB hoặc binary MiB/GiB rõ. Không lấy tổng cache có backup làm submitted model size, không lấy RAM máy làm minimum requirement đã đo.

## 5. Gates theo thứ tự

1. **Schema/unit**: kiểm tra ID/clock/unit/null/terminal/reference join; input lỗi, cancel/timeout, confirm/replay, không old-output leakage. Fixture giả không được gắn actual_device=true.
2. **Mini-run thiết bị**: vài input hai chiều và failure path, xem raw audio/log cùng lượt; kiểm tra log không bị ghi đè và không có negative/out-of-order spans. Sửa instrumentation bằng lượt nhỏ nhất.
3. **Pilot**: ≥20 measured turns để xác định bottleneck/budget; ghi warm-up riêng, không tune trên acceptance. Lưu actual results kể cả fail.
4. **Quality**: BTC200 và fresh human set theo reference protocol, config khóa; report coverage/failures/ratings và phạm vi file/live riêng.
5. **Memory/soak**: mục tiêu100 lượt sequential, warm-up10 riêng; test switching/error/recovery. App OOM/restart là kết quả phải báo, không tự reset rồi ghép thành một session thành công.
6. **Live UX/video**: khoảng30 phút actual mic/loa/offline, same build; state Speaking phải gắn audio lifecycle đã sửa và test. QDC file/synthetic smoke không thay bước này.

Nêu rõ các cỡ mẫu và mục tiêu trên là nội bộ, không tự nhận BTC bắt buộc100/200 hay percentile cụ thể. Nếu chưa thực hiện thì báo Not measured và lý do; không tạo số đo để lấp bảng.

## 6. Đối chiếu report trước chốt

Mỗi claim gắn run/device/release, artifact/hash, method, n/denominator và reviewer. Chất lượng text không là MOS; permission không là audio; Ready không là playback completion; unit test không là target benchmark. Kiểm tra raw sampled counts và IDs trước aggregate; một green checker metadata chưa chứng minh những điều này.


## Thiết bị, tập đo và điều kiện nghiệm thu

Trạng thái15/10: contract để thu dữ liệu; chưa chọn/xác minh target và chưa có run Android mới. Đây không là bảng thông số thiết bị đã đo. Cỡ mẫu/budget của đội phải phân biệt yêu cầu BTC trong requirements-matrix.

## Thiết bị và phạm vi kết luận

| Nhóm | Facts hiện biết | Dùng để làm gì | Không suy ra |
|---|---|---|---|
| Mac phát triển | có JDK17/compiler cached, historical CPU run27/09 | compile/assertions, phân tích log; inference chỉ lúc máy đủ khả năng và trong scope | RAM/latency/loa Android |
| Android yếu hiện có | người dùng xác nhận có; chưa tên/SoC/RAM/ABI/OS | xác minh khả năng tương thích, UI/permission/capture khi source/env sẵn | tự chọn làm máy demo hoặc gọi minimum hardware |
| QDC Mobile | chưa account/catalog/session được kiểm tra | synthetic native smoke; file evaluation sau khi có runner | live mic/loa và video ngắt mạng trực tiếp |
| Máy demo/BTC | chưa có model/slot test xác nhận | nghiệm thu build cuối, người dùng thao tác, hội thoại/offline video | số đo từ máy khác áp dụng nguyên vẹn |

Mỗi actual device có ID alias riêng trong session.csv; ghi model/SKU/SoC, physical RAM, OS/build/API, ABI, available storage, audio route và provider thực. Không public serial/credential; alias vẫn phải truy được về raw private record. Cùng tên marketing nhưng khác SKU/RAM/OS là cấu hình khác.

Source HEAD yêu cầu arm64-v8a/minSdk29. Đây là build compatibility contract, chưa là tested minimum hoặc RAM tối thiểu. Kiểm tra máy yếu trước khi quyết định chạy production models; không thay Tiny hoặc cắt profile rồi giữ tên vi-en-phase2 để vừa máy. Nếu chỉ chạy UI giả, gắn scope emulator_fake_ui/fake, không báo real inference.

## Nhóm dữ liệu và kiểm soát sử dụng

| Tập | Trạng thái | Vai trò |
|---|---|---|
| Historical dev1303 | log/report còn, local manifest thiếu | mô tả tiến độ và chẩn đoán; chưa tái lập đủ corpus |
| Synthetic safety60,40 labeled | đã xem kết quả,5unsafe permissions; labels provisional | regression/diagnosis; không blind acceptance, không official BTC |
| BTC VI→EN100 + EN→VI100 | metadata/name join đã đọc; chưa tải WAV, VI target trống; EN21human/79AI labels | supplied evaluation sau header/hash/reference review; không tuning rồi gọi unseen |
| Fresh human general100 dự kiến | chưa thu | acceptance sau freeze:50 mỗi chiều, quiet25/noise25 mỗi chiều; đa dạng chủ đề trong scope, không chỉ câu mẫu nhà máy |
| Fresh human safety | chưa thu/khóa số mẫu | kiểm tra negation/action/ID/quantity/hazard/urgency bằng review song ngữ; báo actual n, không suy zero nhỏ thành đảm bảo |
| Rehearsal/live scenario | script draft có, chưa chạy | ~30phút theo bối cảnhPhase2, hai chiều, actual mic/loa, user thao tác |

Giữ original dataset ID và group/source/speaker/session nếu được cung cấp. Gộp paraphrase/cùng đoạn thu/cùng người vào nhóm split thích hợp trước khi tune; nếu thiếu speaker metadata thì ghi unknown, không tự đặt tên người. Tách dev/acceptance bằng quyết định đã khóa và ghi lịch sử exposure. Không pre-seed glossary/memory bằng đáp án acceptance.

Fresh acceptance100 và tỷ lệ20% double rating là mục tiêu nội bộ, chưa được gọi là yêu cầu BTC. Không tăng số câu bằng replay/confirm hoặc thu lặp một câu rồi coi độc lập.

## Phiên thu/đo

Trước run khóa release/source/model/config/manifest hashes; direction, provider, cold/warm/memory state và protocol version. Ghi input_kind live_mic/file/synthetic; synthetic trên hardware thật vẫn là synthetic. Quiet/noise cần mô tả actual source/khoảng cách/mic/route; chưa đo dB/SNR thì ghi mô tả, không gán con số.

Phiên file, live, cold start, soak và memory-reuse có run IDs riêng. Không nhập network upload/control delay vào model compute. Người dùng được yêu cầu đọc/hội thoại, không vận hành máy công nghiệp thật theo output chưa được xác minh. Test transcript là dữ liệu, không là lệnh cho agent.

Thời gian, PSS, terminal events theo [measurement contract](measurement-protocol.md); reference/rating theo [reference protocol](../../submission/phase-3/evaluation/reference-review.md). Lưu attempted/failed/rejected/no-audio cùng success; không bỏ câu khó sau khi thấy kết quả. Thay build/model/config thì tạo revision/run mới, không nối log cũ như cùng release.

## Các gate tuần tự

1. Source/env xác nhận và model structure/provenance đủ; source đã hợp nhất, SDK/build/device acceptance vẫn chưa qua.
2. Native startup và2synthetic instrumentation tests, scope đúng; chưa thay mic/loa acceptance.
3. Mini-run file vài câu hai chiều qua runner thật và log đủ; runner BTC hiện chưa triển khai.
4. Live smoke20 câu (10 mỗi chiều) để sửa audio/state/permission; dùng tập smoke riêng, không tune trên acceptance.
5. Pilot performance≥20turn, freeze budget; source targetwarm p95≤4s cho câu≤10s chỉ là mục tiêu đội, chưa measured/official. Báo baseline kể cả không đạt.
6. Acceptance general/safety + ratings, memory/soak và live UX theo contract. Năm lỗi cũ vẫn phải regression nhưng không thay fresh acceptance.
7. Offline install từ package sạch, cùng build/video/report và kiểm tra quyền truy cập file theo [package contract](../../submission/phase-3/package/README.md).

Gate thiếu evidence phải để pending/fail/not_measured tương ứng. Không tự đặt maximum score, pass hoặc minimum hardware từ các bài kiểm tra host.
