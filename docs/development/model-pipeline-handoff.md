# Bàn giao VoxBridge: model, thuật toán Phase 2 và Qualcomm

Cập nhật: 15/10/2026, sau commit `dd83725` trên `main`. File này ghi nhận trạng thái tại lúc bàn giao; không có nghĩa những việc trong kế hoạch đã làm xong. Đọc file này trước, sau đó chỉ mở các file cần cho task đang xử lý.

## 1. Yêu cầu mới nhất của người dùng — ưu tiên cao nhất

Người dùng vừa nhấn mạnh:

> “quan tâm apk làm gì, quan tâm model làm đúng hay k, có nên cân nhắc model khác, rồi tối ưu thuật toán, pipeline đồ, t có đề xuất thuật toán trong phase 2 đó coi hoàn thiện chưa, rồi tự lên kế hoạch, chỉnh sửa, xong thì gửi lên qualcom để compile là ok rồi, làm hết mọi thứ để có máy là lên chạy luôn”

Sau đó yêu cầu **dừng để bàn giao**: đưa thật kỹ context, dự định làm và mọi chỉ thị quan trọng vào một file; đưa prompt để giao agent khác. Đây là file đó. Agent tiếp theo cần tiếp tục công việc kỹ thuật khi người dùng giao, không chỉ viết lại một kế hoạch khác.

Mục tiêu hiện tại: kiểm tra model có làm đúng không, đối chiếu thuật toán Phase 2, sửa những thiếu sót thực sự, tối ưu chất lượng/latency/memory bằng bằng chứng nhỏ, chuẩn bị graph/runtime cho Qualcomm. **Không lấy build APK làm task ưu tiên lúc này.**

Giảm tối đa việc phải điều chỉnh khi có điện thoại; không hứa “compile là chắc chắn chạy đúng toàn app”. Native compatibility, mic/loa, thermal, offline installation vẫn cần kiểm thiết bị.

### Các chỉ thị còn hiệu lực

- Làm kỹ nhưng gọn context, đúng scope, tuần tự từng việc; không mở rộng vô hạn.
- Tự đọc đề và bản Phase 2, tự lên kế hoạch và thực hiện; không dừng hỏi xác nhận những việc đã được giao.
- Phải có benchmark/data để kiểm pipeline, không chỉ code chạy được hoặc đo tốc độ.
- Chọn model tốt hơn nếu có bằng chứng. **User đã nghe và thích VieNeu hơn Piper**, đặc biệt chê TTS cũ khó nghe.
- Ưu tiên Qualcomm/QNN thực chất; không đổi sang model kém hơn chỉ vì có logo/recipe Qualcomm.
- Không rerun 1303 mẫu hoặc benchmark nặng tùy tiện. Khi có lỗi, dùng test nhỏ nhất để tìm nguyên nhân rồi mới mở rộng hợp lý.
- Không đổi threshold để làm đẹp điểm; không đưa reference/oracle/transcript chuẩn vào quyết định runtime; không thêm từ nhận sai làm synonym để đoán ý người nói.
- **Ổ đĩa gần đầy.** User đã nói: “tôi hết dung lượng rồi để đó tính sau làm việc khác trước đi”. Hoãn tải weights lớn, build APK nặng, NDK/toolchain lớn. Không hỏi lại về dung lượng và không tự dọn dữ liệu của họ.
- Không reset/clean, không làm mất thay đổi, không overwrite raw evidence. Không xóa model gốc/global cache/user files.
- **Được commit và push đều lên `main` của private repo, không tạo nhánh mới, không cần PR.** Quyền này đã rõ, không xin lại. Review diff, chạy check liên quan trước push.
- Không commit weights, secrets, caches, WAV hoặc raw reports lớn. Source/config/test/docs/scripts và evidence nhỏ có thể commit.
- Repo cần tổ chức gọn: core/code ở khu vực dự án, tài liệu nộp trong `submission/phase-N`, thử nghiệm/raw output ở ignored `output/`; không tạo nhiều bản nháp trùng nhau.
- User từng yêu cầu `impeccable`: đọc đúng SKILL, chạy context một lần, audit và cải thiện PTT theo Operate, một vòng UI chính + một vòng kiểm/fix, nút lớn/trạng thái VI/EN/lỗi/safety rõ. Không làm lại UI hoặc polish vô tận trong task model hiện tại; chưa xác minh lại lịch sử áp dụng skill ở lượt bàn giao này, không tự tuyên bố đã audit UI trên máy.
- Hướng dẫn partner sau này phải ngắn: clone → Android Studio → model cache → debugCpu → accept-device → instrumentation → mic/loa. Đó là yêu cầu bàn giao sau, không lý do quay lại build lúc đang hết chỗ.
- Không đổi visibility GitHub private thành public khi chưa được yêu cầu trực tiếp. Không mua dịch vụ/chạy paid jobs.

## 2. Workspace, Git và tool state

- Repo: `/Users/lebaotanphong/Documents/voxbridge`.
- Remote: `git@github.com:tanphong-sudo/voxbridge.git`.
- Branch: **`main`**. Commit `dd83725` đã push thành công; trước khi làm tiếp kiểm `git status --short`, `git log -5 --oneline` và remote tracking, tránh ghi đè công việc của partner.
- Các commit vừa push:
  - `805d055` — Preserve Marian tokenizer case and Unicode normalization.
  - `022f9d7` — Add verified VieNeu Nano host TTS adapter.
  - `bbe7460` — Freeze Qualcomm NPU profiles and compatibility gates.
  - `dd83725` — Document measured model choices and open Android gates.
- Source partner đã hợp nhất trước đó (`9a69ad1`, merge/integration `0eb1c6b`). Đừng khôi phục code Android cũ để làm giống tài liệu OPUS.
- Máy Mac M2, 16 GiB RAM; filesystem gần đầy (~150 MiB lúc kiểm cuối, số này biến động). Không lên lịch download/build lớn.
- JDK17: `/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`.
- `python3` mặc định là Python 3.10, có transformers 5.9.0, torch 2.12.0, sentencepiece 0.2.1 và qai-hub 0.53.0. Không dùng số phiên bản từ trí nhớ thay cho đọc môi trường.
- Python TTS/ORT cách ly: `output/host-fixes-20261015/tts-venv/bin/python` (3.11, VieNeu 3.8.3, sea-g2p 0.10.0, ORT 1.31.0, NumPy 2.4.6). Không sửa dependency global tùy tiện.
- `.gitnexus/` đã index bằng `gitnexus analyze --index-only`, có thể stale sau commit; CLI cần `--repo voxbridge`. Dùng nếu giúp trace code, không index lại chỉ vì có tool. Không có `.codegraph/` ở root lúc kiểm cuối.
- Hai subagents trước: Archimedes `01a13ebe-e05c-7471-b8e9-e47f83cd654c` hoàn thành research; Hilbert `01a13ed4-fe20-7641-b534-a6d210ad4f36` lỗi usage quota, parent tự làm tiếp. Không có agent inference đang chạy cần đợi. Không cứ retry quota. User cho dùng 6 Luna phụ nếu hữu ích, không bắt buộc.
- Không có benchmark của task này đang chạy lúc bàn giao. Các process `run.py` khác trên máy thuộc project khác: không kill.

## 3. Đề thi cần tối ưu cái gì

Nguồn gốc nằm trong repo:

- `submission/phase-3/templates/1V_P3_Prototype_Brief.pdf`.
- `submission/phase-3/requirements.md`, `report-guide.md`, `demo.md`.
- `submission/phase-2/final/1V_P2_TechProposal_VoxBridge_Submission_Final.pdf` và `.docx`. **Giữ nguyên bytes bản final.**
- Bản text trích local để tìm nhanh: `output/model-upgrades-20261015/brief.txt`, `phase2-final.txt`.
- File Word Phase 3 đã điền một phần: `submission/phase-3/T&P Team_Prototype Submission_V3.docx`; không ghi thêm kết quả chưa đo.

Rubric Phase 3: chất lượng voice-to-voice 45%; performance on-device 20%; integration/UX 20%; demo/reproducibility 15%. Hardware design bonus tối đa 0.5, không phải cứ dùng QNN là có bonus.

Prototype phải hoạt động trên phần cứng thật, inference trên thiết bị, không cloud API trong demo. Chuẩn bị tình huống hội thoại khoảng 30 phút; chỉ VI/EN có thể chấp nhận theo phạm vi đội công bố, có tiếng ồn nơi làm việc. Video liên tục phải cho thấy tắt mạng ngay trước demo, không thay bằng mô phỏng/cắt dựng pipeline. Cần package/instructions offline và raw measurement evidence. Deadline 13/11/2026 23:59 UTC+7.

AI Hub Workbench dùng compile/profile/validate trên phần cứng Qualcomm từ xa; **QDC là thiết bị vật lý từ xa, không phải emulator Mac**. Không biến latency graph trên cloud thành latency app. Final source-public requirement khác với quyền đổi repo private hiện tại.

## 4. Pipeline và thuật toán Phase 2 — đã biết gì, chưa audit xong gì

### Thiết kế đã nộp

Một lượt push-to-talk, model-first/verification-second; cheap gates chạy trước. Sáu outcome: NO_SPEECH, ASK_REPEAT, MODEL_TRANSLATION, VERIFIED_TRANSLATION, REPAIRED_TRANSLATION, MEMORY_TRANSLATION.

Các phần cam kết trong bản Phase 2:

1. VAD và Audio Quality Gate trước ASR: silence/short/noisy/clipped input fail-safe.
2. Chọn ngôn ngữ VI/EN trước mỗi lượt; một ASR pass, không cascade/multi-model voting vô điều kiện.
3. ASR Reliability Guard, phân biệt no-speech/hallucination/độ tin cậy.
4. Neural translation là đường mặc định.
5. SafetyFrame giữ action, negation, machine/equipment IDs, locations, hazard, urgency; compare trước khi speak.
6. Bounded repair, nếu không kiểm chứng được thì ASK_REPEAT; không “sửa” tùy ý ý nghĩa câu.
7. Memory/corrections cục bộ có trust và live safety re-check trước reuse.
8. Chuẩn hóa số và đơn vị thành dạng phát âm rõ trước TTS.
9. Model warm-load, chỉ active language/direction resident để hạn chế RAM.
10. Offline operation, fail-safe, audit log và deployment phù hợp SoC/runtime.

Phase 2 còn viết rằng overlap/mixed-language/không rõ phải ASK_REPEAT. **Chưa xác minh có detector đủ tin cậy cho mọi trường hợp đó trong code**; đừng suy ra VAD tự phát hiện overlap/code-switch. Code-switch benchmark cũ rất kém, ngoài supported mode.

### Code đã đọc / sự thật quan trọng

- `core/.../pipeline/DecisionEngine.kt`: pre-ASR → ASR reliability → trusted memory + unconditional live safety compare → MT → reject blank/extreme one-token repetition → verify slots → bounded repair/refuse.
- `core/.../pipeline/SpeechOutputPolicy.kt`: nguồn truth cho được phép speak/confirm/replay. Giữ policy qua mọi backend TTS/model thay thế.
- `core/.../audio/CaptureAnalysis.kt`, `StreamingVadAnalysis.kt`: shared pre-ASR gate, VAD analysis, trim với context; Android `ui/PipelineGates.kt` gọi shared predicate. Có sustained clipping predicate, không reject chỉ vì một sample nóng.
- `core/.../asr/TranscriptResult.kt`: `asrScoreCalibrated`; runtime Whisper đánh dấu raw score không calibrated.
- **Không có mapping calibration đã được xác nhận cài đặt.** DecisionEngine chỉ so `minAsrScore=0.60` khi calibrated=true. Raw exp(mean token logP) không được coi là confidence đã calibrate; không bật lại threshold trực tiếp.
- `android/.../runtime/WhisperAsr.kt`: autoregressive decoding CPU optimum và AIHub; no-speech probability, raw sequence score, avg logP, stop reason có diagnostics. Cần audit decoder termination/hallucination bằng dữ liệu nhỏ, không đoán từ audio quality.
- `core/.../safety/SafetyFrameExtractor.kt`, `SafetyFrameComparator.kt`, `SafetyRepairer.kt`, `SafetySlotRenderer.kt`: compare chỉ thấy transcript ASR, **không thấy ý gốc trong WAV**. ASR bỏ hazard thì comparator có thể “verify” sai so ý thật.
- `core/.../learn/LocalMemoryRetriever.kt`, `FeedbackPromoter.kt`, `LearningExample.kt`: trust/approval thresholds và reuse.
- `core/.../normalize/SpokenTextNormalizer.kt`, `NumberNormalizer.kt`, `VietnameseNormalizer.kt`: đọc số/đơn vị; cần đối chiếu khả năng thực tế với proposal nếu mở task này.
- `android/.../ui/PushToTalkViewModel.kt`: tạo/đổi runtime theo ngôn ngữ, warm-up, serialized runner, capture/decision/confirmation/playback. Logic replay/confirm và Speaking đến lúc audio phát xong đã sửa và test, chưa device-test.

**Chưa có audit đầy đủ proposal→code trong lượt này.** Mới đọc thiết kế và các entrypoints ở trên. Agent tiếp theo phải lập matrix “đã implement / đã test thật / chỉ có stub hoặc chưa có / không còn áp dụng”, không ghi tất cả là hoàn thiện.

## 5. Model hiện tại và kết quả lựa chọn

### ASR

- VI: PhoWhisper Base ONNX FP32; source revision `7ebdb9e88f5cc5271fb88f4d642c82ff9388650e`.
- EN: Whisper Small; revision `973afd24965f72e36ca33b3055d56a652f456b4d`.
- Phase 2 mô tả FP16 là design/footprint estimate; **không đồng nhất với weights CPU đang đo FP32**.
- Cùng 10 WAV tiếng Việt BTC, 78 từ reference (chưa human-review):
  - PhoBase baseline: 9/78 word errors = 11.54%, median ASR 452 ms.
  - Encoder-only dynamic INT8 MatMul/per-channel: 10/78 = 12.82%, median 333 ms. Không promote vì regression chất lượng; dynamic CPU INT8 không chứng minh QNN QDQ/HTP.
  - Multilingual Whisper Small, forced VI: 19/78 = 24.36%, median 1522 ms. Không thay PhoBase chỉ để dùng gói NPU sẵn.
- Candidate chưa tải: `vinai/PhoWhisper-small@a86b604c346caf7148c37512eafe783a16420adb`, PyTorch weights 967,102,729 bytes; hoãn vì hết chỗ. Không gọi là đã test.
- Tiny hay English-only Distil-Whisper không mặc định là candidate tốt cho VI. Cần evidence và target-language support.
- Files: `output/model-upgrades-20261015/asr-baseline-btc10.jsonl`, `asr-encoder-int8-btc10.jsonl`, `asr-whisper-small-btc10.jsonl`; inputs `output/host-fixes-20261015/btc-asr/inputs.json`.
- Quantizer thử nghiệm: `output/model-upgrades-20261015/quantize_encoder.py`. Encoder quantized sinh ra đã bỏ sau khi reject để tiết kiệm chỗ; source metadata ghi rõ không còn giữ generated encoder. Model gốc giữ nguyên.

### MT — phân biệt Android với Mac

- **Android hiện dùng ML Kit VI↔EN.** OPUS-MT là Mac comparator và một offline-package candidate; không viết rằng Android đang chạy OPUS.
- ML Kit cần tải translation model khi setup đầu. Cài app mới hoàn toàn offline chưa chứng minh; đây là gate thật cho cuộc thi, cần xử lý bằng chiến lược model/package hợp lệ.
- Local OPUS quantized: `models/translation/opus-mt-{vi-en,en-vi}/`; split encoder/bootstrap decoder/with-past decoder, mỗi hướng hiện ~197 MB tổng graph files (không lấy estimate 72 MB Phase 2 làm actual).
- Đã sửa `SpmTokenizer.kt`: SP4J `encodeNormalized` tự lowercase. Dùng NFKC + whitespace chuẩn bị riêng, giữ case và raw segmenter, O(1) piece lookup. Boolean `SentencePieceAlgorithm(true,...)` là collapseUnknowns, **không phải lowercase flag**.
- Hai test toy-SPM red→green; token IDs Kotlin khớp official Marian tokenizer 6/6 câu VI.
- Uppercase “Không vào khu vực nguy hiểm số bảy.” và “Không mở van số ba khi máy đang chạy.” hết lặp No sau fix. ASR thường trả lowercase; fix này không tự chữa lowercase output hay 5 safety failures.
- `OnnxTranslator.kt` hiện greedy max64; bootstrap cross/encoder KV giữ nguyên, self/decoder KV cập nhật. Chưa có beam trong production.

#### Chẩn đoán mới nhất trước bàn giao: beam4 không đủ tốt để promote

Chạy local Python ORT1.31 CPU, 2 threads, cùng quantized weights, official input IDs; 6 câu + 6 biến thể lowercase = 12 input. Custom diagnostic beam4, length penalty1, cap64, có EOS; **không gọi đây là implementation HF production hay benchmark chính thức**.

- Greedy median 64.5 ms; beam4 median 1152.5 ms trong diagnostic Python tuần tự, không phải latency Kotlin/Android.
- Cả hai 10/12 input dừng EOS, 2/12 chạm cap. Số lượng bằng nhau nhưng failures khác nhau.
- Lowercase “không mở van…”: greedy No lặp, beam4 đúng hơn.
- Lowercase “áp suất không được vượt quá ba phẩy năm bar.”: greedy còn số/đơn vị, beam4 **No lặp**. Không chọn beam4 vì regression.
- “Tôi chưa nghe rõ. Xin nói lại chậm hơn.”: greedy “I didn't hear you.”, beam4 “I can't hear you.”; đều mất vế hai.
- Tách riêng để chẩn đoán (không sửa runtime): “Xin nói lại chậm hơn.” → greedy “Please say it's slower.”; beam4 “Please tell me it's slower.”. Lowercase → greedy “I'm sorry, but I'm going to say it slower.”, beam4 “Okay, let's say it's slower.”. Vì vậy **không chỉ lỗi nối câu**, model/decoding còn sai ngôi và nghĩa; không chữa bằng split-sentence mù.
- Python greedy khớp 5/6 output uppercase Kotlin trước đó; câu gas-leak khác (“for gas leaks” vs Java “for the leak”). Chưa giải thích khác biệt ORT1.31/Java1.20; cần parity nếu dùng Python làm bằng chứng quyết định backend.
- Không đổi production MT/beam, không sửa safety thresholds.
- Raw: `output/model-upgrades-20261015/mt-beam-{inputs.json,results.jsonl,probe.py,probe.log}`, `mt-single-sentence-{inputs.json,results.jsonl}`, `mt-case-fixed.jsonl`, `mt-baseline.jsonl`, `MtProbe.kt`, `mt-probe.jar`.
- CLI candidate chưa review: `output/model-upgrades-20261015/probe_opus_decode_cli_candidate.py`. Đây là bản nháp bảo toàn source, **không phải script production đã test**. Không cần đưa vào Git nếu chưa có task sử dụng rõ ràng.

Các model khác: Qualcomm catalog OpusMT hiện không có VI recipe; custom ONNX compile là hướng cần chứng minh, không đảm bảo được hỗ trợ. M2M100-418M hỗ trợ VI/MIT nhưng file weights khoảng1.94 GB; mới research, không tải/chưa đánh giá, không ưu tiên trong tình trạng disk hiện tại. Xem `docs/development/qualcomm-models.md`.

### TTS — user chọn VieNeu, native Android còn thiếu

- Piper VI trước đây nghe kém. Cùng6text ASR proxy: Piper18/71errors=25.35%,0/6exact; VieNeu Turbo Trúc Ly2/71=2.82%,4/6exact. Đây là proxy một ASR trên bộ nhỏ/exposed, không phải MOS/human acceptance.
- Turbo: cold~4.77s,RSS~1213.9MiB,synth1.09–1.26s trên mẫu nhỏ.
- Nano16:2texts,synth1.32–1.56s,RSS~420.25MiB,cold~676ms,ASRproxy1/36.
- Nano8:2texts,synth737–896ms,proxy0/36. Không kết luận8steps nói chung hay hơn16; khác draw/seed, mẫu nhỏ.
- **Host selected adapter**: VI VieNeu Nano Trúc Ly8steps,seed42,CPU2threads,24kHz; EN Piper. Engine lazy/resident/reuse; invalid artifacts/empty/nonfinite audio fail, không fallback VI sang Piper.
- Đã render9requests thật: firstVI1933ms gồmload; subsequentVI835/470/402/538/459ms; EN915ms gồmload; VIrepeat/no-speech295/252ms. Output `output/model-upgrades-20261015/tts-selected/`.
- `tools/mac-cpu-benchmark/vieneu_bridge.py`, 5tests mới, tổng42benchmarkPython tests PASS.
- Fetcher `model-tools-python/scripts/fetch_vieneu_nano.py`: pin6files,size+SHA,revision `pnnbao-ump/VieNeu-TTS-v3-Nano@aba295eb96a6fa6003ebe417cc1f2802a7adc1dc`, không silently overwrite corrupt cache; source manifest atomic.
- Cache đầy đủ **281,756,486 bytes**: `output/host-fixes-20261015/nano-artifacts/`; `source.json` và manifest ngoài `nano-artifacts.json`.
- **Android vẫn Piper, chưa port Nano. Host adapter cũng chưa thay mọi host full-pipeline runner**; explicit-request audition không tự chứng minh TTS policy integration.
- SEA-G2P0.10.0 Rust có C ABI không cần Python: `cargo build --release --no-default-features --features capi`. `sea_g2p_open/close`, phonemize, normalize, punc_norm, string_free,last_error; owner repo Apache2.0.
- Primary sources `https://github.com/pnnbao97/sea-g2p/blob/main/{Cargo.toml,src/capi.rs,LICENSE}`; source nhỏ đã lưu `output/model-upgrades-20261015/sea-g2p-*`.
- Có cargo/rustc/clang; chưa có Android NDK/cmake. Chưa build native phonemizer Android, chưa có Nano Kotlin/JNI orchestration/parity. Tiny Java graph-load probe trước đó thất bại lúc disk-full/empty log: **không phải bằng chứng graph unsupported hoặc compatible**.
- Cần giữ TtsEngine/SpeechOutputPolicy, confirmation và playback semantics khi port; không tự bypass safety vì giọng mới.

## 6. Qualcomm — việc thật đã làm, barrier thật còn lại

- QAI Hub account SDK config đã tồn tại ở `~/.qai_hub/client.ini`; **không in token/file contents**. `qai-hub==0.53.0`.
- Hai free hosted profile jobs đã submit và SUCCESS. Chỉ public model graph, không upload WAV/user data. Không có paid job đang chờ.
- Bundle frozen: Whisper Small W8A16, release v0.64.0, SM8650 Snapdragon8Gen3/HTPv75, profile target Galaxy S24 Ultra Android14.
- Public archive làm việc:
  `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_small_quantized/releases/v0.64.0/whisper_small_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip`
- Archive285,196,969bytes; transport ZIP đã bỏ sau verify/extract để đỡ đầy disk. Payload vẫn ở `output/model-upgrades-20261015/qualcomm-whisper-small-8gen3/`:
  encoder.onnx~12.7KB+encoder.bin113,123,776B;
  decoder.onnx~39.4KB+decoder.bin225,298,736B;
  metadata.json~43.7KB. SHA inventory/target/tool versions trong `config/models/qnn-whisper-small-sm8650.json`.
- **Đây là precompiled reference bundle/profile, chưa phải compile custom PhoBase/OPUS/Nano của dự án.** Không nói đã gửi toàn pipeline đi compile.
- Encoder job `jgk62y2w5`: https://workbench.aihub.qualcomm.com/jobs/jgk62y2w5/ .100runs,median45.6435ms,estimate43.281ms.
- Decoder job `j57oed3qg`: https://workbench.aihub.qualcomm.com/jobs/j57oed3qg/ .100runs,median6.364ms,estimate6.248ms,**một token step**.
- Trace nodes: encoder1894NPU,decoder2853NPU,không CPU node trong trace. Không có nghĩa preprocessing/token loop/toàn app không dùng CPU.
- Profiler inference peak estimates146.19/155.73MiB; cold/warm-load peaks lớn hơn, có trong evidence; không lấy đây làm whole-app RSS.
- Compact evidence commit: `submission/phase-3/evaluation/qualcomm-graph-profile.json` (100 timing samples/graph,joblinks,rawSHA,scope). Raw ignored: `output/model-upgrades-20261015/qnn-profile/`.
- **Native mismatch chặn triển khai**: bundle metadata ORT1.30.0,QAIRT2.50.0.260828221209; app pinORT1.20.0. Maven QNN Android lúc kiểm tới1.29.0,1.30POM404. Cần runtime hợp lệ tương thích hoặc compile lại nhắm runtime chọn; đừng nâng version đoán/mặc định copy gói.
- WrapperIO đã inspect: encoderfeatures[1,80,3000],decoderinput_ids[1,1],position_ids[1],12layer self/crossKV fixed shapes. Current loader expects `*_ctx.onnx`, bundle names encoder.onnx/decoder.onnx and references .bin. Rename không giải quyết graph/runtime/SoC compatibility.
- `model-tools-python/qaihub/prepare_whisper.py`: inventory/hash/runtime/SoC gate,4tests PASS; verify actualbundle PASS,declaredORT1.20reject.
- `profile_whisper.py`: resume existing job ledger, no duplicate resubmit, reuse complete raw profiles,atomicwrite,256MiBreserve;2tests PASS. Tuple/list cache identity bug đã fix red→green. Đừng rerun submit nếu chỉ cần đọc evidence.
- `model-tools-python/qaihub/README.md` có commands hiện hành. Custom QNN workflow cần đọc official compile/link API mới; release notes Sep2026 đổi legacy context-binary path. Không copy lệnh API cũ theo trí nhớ.

## 7. Safety failures và dữ liệu chất lượng

Lịch sử Mac/OPUS ngày 27/09: **1303/1303 hoàn tất**, clean EN WER 10.1541%, VI 13.0435%, latency p50/p95 1883/3417 ms, **chưa tính phát hết TTS**. Không phải Android/ML Kit/current-model acceptance. Không rerun full mặc định.

`docs/testing/safety-failures.md` ghi chi tiết 5 case 037/044/046/047/050. Bộ 60 mẫu synthetic/provisional, 40 có slots: 26 ASK_REPEAT, 9 REPAIRED chờ confirm, 1 MODEL + 4 VERIFIED cho phép speak. **5/40 = 12.5% unsafe permission** theo oracle, không phải đo 5 WAV thực phát. 35 lượt blocked/confirmation không được tính là 35 successful translations.

- 037: ASR làm mất ENTER/hazard zone/ID7; source frame inactive nên MODEL path.
- 044: mất urgency vì ASR nhận sai “Khẩn cấp”.
- 046: mất STOP và urgency trước safety compare.
- 047/050: mất GAS_LEAK và urgency; STOP/ID khớp nên VERIFIED trên transcript sai.

Đã patch extractor cho cụm chuẩn “rò rỉ khí” (`ro ri khi`), có test dấu/không dấu/negation/context. Không thêm từ sai “cần cất/rừng/giò” để đoán. Patch giúp extraction trên transcript chuẩn, **không đóng 5 ASR failures**.

Đã sửa scoring urgency true↔HIGH, false↔NORMAL; re-score log cũ vẫn 5 unsafe. Slot correct 54/240→64/240 (22.5→26.67%) là sửa phép chấm, không cải thiện model.

Logic synthetic hiện 51/51 decisions khớp, 0/38 unsafe; không thay acoustic holdout. Raw safety verification: `output/phase3/safety-investigation-20261015/`. Detailed ASR confidence không có đủ trong logs cũ để calibrate từ chúng.

BTC 200 WAV: mới dùng 10 VI cục bộ; còn cần kiểm/fetch header/hash đầy đủ, VI→EN references và human review. EN→VI có 21 human/79 AI references cần review. Khi thiếu chỗ không tải cả dataset chỉ để đủ số. Xem `submission/phase-3/evaluation/dataset.md` và `reference-review.md`.

## 8. Tests, tối ưu sẵn và giới hạn xác minh

- Host JUnit **119/119 PASS**: core, selected Android pure logic, playback/callback boundary fakes; không phải thiết bị.
- Python: Phase3 tools **82 PASS**, benchmark **42 PASS**, device tools **19 PASS**, SQL migration **4 PASS**. Đây là suite khác nhau, không cộng thành model accuracy.
- **Gradle thật với SDK/AGP đã PASS**: 69 core tests + 36 Android debugCpu unit tests, không fail/skip; compile ViewModel/Compose/Java đầy đủ. Log `output/model-upgrades-20261015/android-compile.log`.
- SDK còn ở `output/model-upgrades-20261015/android-sdk`: platform34/build-tools34/adb. SDK bootstrap cmdline-tools đã bỏ sau cài để cứu disk; đừng nói sdkmanager còn ở path cũ.
- Piper AAR 1.13.3 đã tải và verify SHA tại `android/app/libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar`, ignored.
- Assemble lần 1 heap768MiB OOM; lần 2 heap2GiB qua D8 nhưng ENOSPC tại mergeDebugCpuNativeLibs. **Chưa có APK mới**; user đã đổi ưu tiên, không làm APK giờ.
- CPU mel frontend đã tối ưu và có paired performance harness: `tools/host-tests/performance/run.py --baseline a583a16100407ae790760637a01b427ee2e5a68d`, kiểm bitwise equality trên 8 signals trước timing. Chưa đọc lại số cụ thể trong lượt này; không bịa speedup.
- Entrypoints: `python3 tools/host-tests/run.py`; `python3 -m unittest discover -s tools/phase3 -p 'test_*.py'`; `python3 -m unittest discover -s tools/mac-cpu-benchmark -p 'test_*.py'`.
- Chọn check đúng thay đổi, không chạy all tests/benchmarks để lấp thời gian. Đọc README/harness trước inference vì có script tự fetch dependencies hoặc chỉ test silence.
- Exporter dependency audit pin cũ: 83 advisories trong 4 packages (torch2.5.1, transformers4.42.4, onnx1.17.0, sentencepiece0.2.0), chưa xử lý; không phải Android audit. Không nâng pin mù làm hỏng export.

## 9. Kế hoạch agent tiếp theo — dự định, chưa thực hiện

Không quay lại ưu tiên APK đầu STATUS.md: đó là thứ tự cũ trước chỉ thị mới. Làm lần lượt, mỗi task có kết quả review được và commit/push riêng.

### A. Audit proposal→code và freeze tiêu chí

1. Đọc phần 4.1–4.5, 6.2 bản Phase2 final và rubric Phase3; trace các entrypoint ở mục 4.
2. Lập matrix ngắn trong docs/development: từng thuật toán, implementation, tests/data, limitations, việc còn thiếu. Chỉ đóng gate khi có evidence.
3. Chốt thứ tự sửa từ 5 acoustic safety failures/MT meaning loss/TTS port, thay vì tăng tốc trước khi biết model đúng. Phân biệt quality, compute optimization và package readiness.

### B. ASR reliability và 5 failures — ưu tiên chất lượng

1. Kiểm 5 WAV/recorded ASR, human_verified flag, raw metrics; reproduce chỉ case cần thiết bằng model đang có.
2. Xác định lỗi ở waveform/TTS source, frontend, tokenization, decode hay checkpoint. Không cho transcript reference đi vào decision.
3. Nếu thêm reliability guard/calibration: cần tín hiệu runtime có thật, labels reviewed, split calibration/holdout, đo false-reject cùng unsafe permission. Raw probability không phải confidence calibrated. Khi thiếu dữ liệu, chuẩn bị harness/contract, không đặt threshold đoán.
4. Candidate PhoSmall để queue khi có chỗ; CPU INT8 và generic Whisper VI đã reject, không lặp lại nguyên experiment.

### C. MT correctness và offline strategy

1. Giải thích Python/Java parity trên câu gas trước khi dùng Python để quyết định production.
2. Giữ 12 cases và 4 single-sentence cases làm exposed diagnostics. Không promote beam4, không sentence-split mù, không uppercase đầu câu chỉ để hợp 6 samples.
3. Android ML Kit cần đánh giá trên thiết bị khi có; hiện có thể chuẩn bị quality contract/adapter tests/offline install strategy và source port cho candidate hợp lệ.
4. Chọn model khác sau đối chiếu language/license/size/quality/exportability, không download nhiều GB khi user đã hoãn.

### D. VieNeu native và host policy integration

1. Audit host Nano SDK graph IO, phonemizer dictionary/normalization, steps/sampler/seed, reference voice inputs, sample-rate/PCM contracts. Không viết adapter giả rồi claim port done.
2. Kiểm host TTS stage đã wire vào output policy/full runner chưa; test ASK_REPEAT/NO_SPEECH/repair confirmation/replay bằng fakes nhỏ trước inference.
3. Chuẩn bị native SEA-G2P C ABI + Kotlin/JNI interface, exact artifact manifest/license notices. Build toolchain khi disk cho phép; đặt parity goldens nhỏ trước port logic.
4. ORT graph compatibility phải đo tách với generation quality. Không suy từ host Python thành Android ready.

### E. Qualcomm compile/validate đúng model đã chọn

1. Chọn exact SoC/runtime profile; máy demo chưa chốt, SM8650 chỉ là reference đã profile.
2. Dùng nguồn official để resolve ORT/QAIRT compatibility; không copy bundle ORT1.30 vào app1.20.
3. Với Pho/OPUS/Nano custom graphs: audit static shapes/dynamic KV, operators, precision/calibration requirements; chuẩn bị export harness và manifest hashes trước submit. Giữ CPU fallback và reference outputs.
4. Submit free compile jobs khi artifact/runtime hợp lệ; log job IDs, input SHAs, tool versions, target, evidence. Resume thay vì duplicate. Không mở paid jobs.
5. Compile PASS chỉ là gate biên dịch; validate output parity/quality và profile cùng target trước claim acceleration. Decoder một token không là latency cả câu.

### F. Chốt package contract để có máy test nhanh

Sau các sửa đã kiểm: freeze backend/model/runtime/config/license/hash, diagnostic/device acceptance commands và remaining device gates. Không cần build APK ngay trong tình trạng disk hiện tại. Còn lại thật: device install/runtime, mic/loa/lifecycle, offline model availability, sustained RAM/thermal và continuous video. Ghi rõ chưa đo; không đợi điện thoại mới làm những phần source/quality làm được lúc này.

## 10. Quy tắc làm việc và skills

- Làm theo AGENTS.md hiện hành và chỉ thị người dùng trong phiên mới; file này là ảnh chụp context, không được ghi đè chỉ thị mới hơn.
- Skills đã áp dụng ở công việc trước: systematic-debugging, test-driven-development, brainstorming, pdf, gitnexus-debugging; mới đọc writing-plans để chuẩn bị kế hoạch trước khi user yêu cầu bàn giao. Kế hoạch audit mục 9 chưa được thực thi.
- Đọc skill phù hợp trước khi dùng và thông báo ngắn cho user; nếu skill đòi hỏi approval nhưng user đã giao rõ scope thì không hỏi lại không cần thiết. Debug bằng nguyên nhân và red→green khi sửa bug.
- User cho dùng subagent 6 Luna khi hữu ích. Dùng multi-agent tools cho subtask, không tự tạo chat/thread mới trong sidebar để thay subagent. Không cố retry agent hết quota.
- Giao tiếp tiếng Việt dễ hiểu, cập nhật có ý nghĩa trong lúc chạy; không gọi kết quả proxy là điểm thi chính thức, không dùng từ “xong” cho phần mới chuẩn bị source.
- Không đọc/in secrets từ config tài khoản. Network mutations ngoài quyền commit/push và công việc Qualcomm đã giao cần đúng phạm vi, đặc biệt không mua paid jobs hay gửi mail/tin nhắn cho người khác.

## 11. Cách tiếp tục gọn context

Đọc file này → `git status` → chọn A → chỉ mở source phục vụ A. Dùng rg/GitNexus định vị symbol, không cat cả repo hoặc tất cả logs. Duy trì một status/matrix được cập nhật thay vì nhiều nháp. Test đúng scope; commit/push main sau check. Báo cáo bằng số thật: đã chạy ở đâu, mẫu số, gì fail, gì chưa test và bước tiếp theo.
