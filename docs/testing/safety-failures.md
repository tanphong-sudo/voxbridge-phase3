# Điều tra năm lỗi safety từ log lịch sử

Ngày15/10: đọc60 logs cũ, kiểm tra5WAV nhỏ và thử logic core với MT output stub. Không chạy ASR/MT/TTS, phát loa hoặc test Android. Nhãn synthetic provisional, human_verified=false; đây là exposed regression, không phải blind safety acceptance.

## Bằng chứng và mẫu số

Manifest60 rows,40 có safety slots. Trong40:26ASK_REPEAT,9REPAIRED chưa cho phát,1MODEL+4VERIFIED cho phát. Cả5 lượt được phép phát đều lệch oracle: **5/40=12.5% unsafe permission**, chưa phải đo audio thực phát.35 lượt chặn/chờ confirm không được coi là35 bản dịch hoàn chỉnh đúng.

Manifest `reports/mac-cpu/task6-safety-holdout-synth-corrected/manifest.jsonl` hash `879bef9ee23389b8afb1c96dc634236e53358dccc961af3877dac86f84a6b31b`; results `reports/mac-cpu/task6-safety-holdout-corrected-run/results.jsonl` hash `126e601a5699198e9a11e573ec35c26e18e2e5ab4f41706233086bd93f7b76f0`. Năm WAV tồn tại, hash khớp, header16kHz/mono/PCM16; chưa nghe xác minh pronunciation/transcript. Raw cases và verification ở `output/phase3/safety-investigation-20261015/`.

## Từng lỗi VI→EN

| ID | Câu nguồn → ASR → output ghi trong log | Sai theo oracle |
|---|---|---|
|037|“Không vào khu vực nguy hiểm số bảy.” → “không phải khu vực núi giềm số bài.” → “Not the middle of the mountain.”|mất ENTER, hazard zone/type/ID7 và hazard; final negation slot khác oracle. Source frame inactive nên MODEL_TRANSLATION không qua comparator|
|044|“Khẩn cấp, dừng máy ép số bốn vì rò rỉ khí.” → “cần thất dừng máy ép số bốn vì giò rỉ khí.” → “We need to stop press four because of the gas leak.”|mất urgency; STOP/ID giữ, MT khôi phục hazard trong output nhưng source frame không có hazard|
|046|“…số sáu…” → “cần cất rừng máy ép số sáu vì giò rỉ khí.” → “We need to keep the press-six because of the gas leak.”|mất STOP và urgency; source frame vẫn active vì ID6, comparator không biết action đã mất trước đó|
|047|“…số bảy…” → “cần cất dừng máy ép số bảy vì giò gì khí.” → “We need to stop the press seven for air.”|mất GAS_LEAK và urgency; STOP/ID7 khớp nên VERIFIED|
|050|“…số mười…” → “cần cất dừng máy ép số mười vì giò rỉ khí.” → “We need to stop the press number ten because the leg leaks.”|mất GAS_LEAK và urgency; STOP/ID10 khớp nên VERIFIED|

VERIFIED so slot từ ASR với target, không so âm thanh/reference gốc. Audio scores0.839–1 và voiced1312–2624ms không chứng minh nhận đúng nghĩa. Log thiếu detailed ASR confidence để calibrate; không dùng audioScore như calibrated ASR score.

Nhánh MT từ transcript chuẩn của037 còn thành chuỗi “No” lặp. Sửa ASR chưa tự chứng minh MT đã đúng. Nhánh reference-MT chỉ diagnostic sau primary decision, không được đưa reference vào runtime.

## Sửa phép chấm urgency

Oracle dùng boolean, runtime dùng HIGH/NORMAL. Đã sửa đối chiếu đúng nghĩa chỉ cho urgency: true↔HIGH, false↔NORMAL; missing/unknown/numeric không tương đương. Cùng mapping dùng cho false-repair; slot khác giữ nguyên.37 Python tests PASS, đã có red-before-green.

Re-score log cũ vào output mới: vẫn5unsafe IDs; slot_correct **54/240→64/240 (22.5%→26.67%)**. Đây là sửa số đo, không cải thiện model. Mẫu số có cả lượt không có final output; không gọi slot accuracy là semantic-success hoặc official quality score.

## Phát hiện và kế hoạch patch core nhỏ nhất

Probe dùng core thật trên recorded ASR và fixed MT stub, passing audio đơn giản hóa, uncalibrated score: tái hiện1ModelTranslation+4VerifiedTranslation. Không phải replay acoustic/confidence/runtime chính xác. So reference với output còn thiếu GAS_LEAK trên047/050: extractor VI chưa nhận **“rò rỉ khí”**, mới nhận “rò khí”, “rỉ gas”, “rò gas”.

1. Thêm tests cho cụm chuẩn có dấu/không dấu, nguyên câu cảnh báo, câu phủ nhận, nước leak và narrative dài. Không thêm các lỗi “cần cất/rừng/giò” làm synonym đoán lời nói.
2. Chạy test bằng compiler/Kotlin test cached, không tải JUnit/SDK. Adapter bỏ annotation chỉ ở output; assertions/method bodies giữ nguyên, ghi phương pháp.
3. Thêm đúng cue `ro ri khi`, giữ activation/comparator/threshold/repair. Chạy lại extractor assertions và logic harness với heap256MiB, không overwrite RESULTS lịch sử.
4. Reference extraction phải nhận hazard; năm log ASR cũ vẫn open. Không rewrite log hoặc tuyên bố fixed5/40.

Đây là patch pure-core độc lập vừa có bằng chứng; không cần phục hồi46 Android deletions. Patch đã qua23extractor methods,51/51logic cases (0/38unsafe) và75self-verification checks; runtime models không chạy. Xem verification15/10. Năm decision từ recorded ASR vẫn không đổi sau patch.

## Khi được chạy inference tiếp

Nghe5WAV và đối chiếu pronunciation/trim/raw trace. Thu detailed ASR logprob/no-speech/stop reason/audio segmentation với exact model/config; chỉ5file diagnosis trước, không full1303. Chưa đủ bằng chứng quy lỗi riêng cho Piper, VAD hoặc acoustic ASR.

Mỗi giả thuyết thử một biến. Confidence cần dev có cả câu đúng/sai và đo false refusal/coverage/latency; không chọn threshold chỉ từ5câu lỗi. Sau sửa thực phải khóa config, có fresh human safety acceptance và test confirm/replay/actual audio riêng. Không đạt zero unsafe bằng từ chối mọi câu; toàn bộ5lỗi vẫn mở tới lúc có bằng chứng mới.
