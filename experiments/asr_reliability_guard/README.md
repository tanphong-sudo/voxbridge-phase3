# Risk-Aware ASR Reliability Guard — offline prototype

Nhánh `codex/risk-aware-asr-guard`. Thư mục này chứa prototype Python offline; bản replay giữ nguyên model và WER gốc. Xem [workflow replay](WORKFLOW.md) và [báo cáo BTC 100 câu](BTC_RESULTS.md).

Nhánh còn có prototype Android [live audio status](../../docs/development/live-audio-status.md), [bản nháp khi yêu cầu nói lại](../../docs/development/tentative-retry-output.md), và [bộ chạy VPS 200 câu](vps_pipeline/README.md). Bộ chạy VPS dùng môi trường/model đã có trên server, tách biệt với replay Python dưới đây. Mục confidence bên dưới mô tả evidence Parakeet v3 cũ; lượt VPS mới dùng Parakeet v2 với pin riêng.

## Architecture và nguồn config

| File | Trách nhiệm |
|---|---|
| `config/negation_en.json` | Morphological, semantic và multi-word negation; lexicon tự biên soạn từ yêu cầu, không dựa riêng vào tag NEG |
| `config/importance_en.json` | HIGH/LOW/glossary, số viết bằng chữ, regex số/quantity/ID và thứ tự ưu tiên; glossary mặc định rỗng |
| `config/policy.json` | Weights, thresholds, categories bảo vệ, giới hạn bonus/input; **chưa calibration** |
| `detector.py` | Non-overlap longest-match spans, original offsets, default NORMAL |
| `alignment.py` | Bounded LCS: ưu tiên số protected token khớp trước, weighted tie-break sau; LOW/NORMAL không che mất action khớp |
| `guard.py` | Native evidence hoặc categorical decode agreement, maximum span risk, decision và debug JSON |
| `retry.py` | Selective callback, kiểm audio/model/revision/config provenance; kết hợp native evidence cả hai pass |
| `feedback.py` | SQLite local, explicit ASR feedback, exact scoped key, dedup event, rejection/correction demotion |
| `cli.py` | Assess, ghi feedback và replay hai decoding pass đã lưu |
| `evaluate.py` | Tái dùng scorer/loaders đã có; frozen BTC replay, false alarms và lỗi lọt ACCEPT |
| `scoring.py`, `scoring_provenance.json` | Bản trích nguyên 5 helper scoring/validation đã đóng băng; SHA256 source và từng function để kiểm không đổi cách chấm |

Unit/integration/CLI E2E tests dùng evidence giả có nhãn test rõ ràng. BTC dùng output ASR thật đã lưu; không tạo confidence giả cho dữ liệu đó. Guard không sửa transcript, không train/fine-tune model, không gọi API hay tải model/dependency.

Validation ngày 15/11/2026: **93 tests pass**, coverage có branch instrumentation **94%** cho experiment source, không tính tests/listening artifacts. Checkout sạch không có BTC local: **92 pass, 1 skip** (real BTC integration); synthetic100×5-run CLI E2E vẫn chạy đầy đủ. Chi tiết ở `results/tests-green.txt`, `results/coverage.txt` và `results/validation.json`. Test tools dùng Python3.12, pytest9.0.3/coverage7.17.0 đã lock hash; audit toàn bộ 6 dev dependencies không có known vulnerability. Đây không phải coverage toàn app.

## Confidence thực tế đã inspect

- **Parakeet thực chạy là v3**, `nvidia/parakeet-tdt-0.6b-v3@541d1f99c6b0c3cd0b11a95167540bb8edefd82b`, Transformers 5.22.2 CPU FP32. `output/phase3/en-asr-20261113/evidence/parakeet/run.py` lưu text/token count; diagnostic có durations nhưng không có word/token confidence/logits/logP trong output BTC. Không coi durations là confidence.
- **HF Whisper Small/Medium/Turbo**: `evidence/whisper/run_whisper.py` yêu cầu `output_scores=True` nhưng chỉ dùng độ dài scores rồi bỏ scores; không thể khôi phục word confidence từ JSONL.
- **App Whisper**: `android/app/src/main/java/com/voxbridge/runtime/WhisperAsr.kt` xuất raw sequence score `exp(mean token logP)`, avg logP và no-speech probability; `asrScoreCalibrated=false`. Không chia sequence score cho từng từ. `DecisionEngine.kt` chỉ so calibrated score với threshold calibrated.
- **NeMo** không phải backend của lượt BTC này và không cài trong môi trường hiện tại. Tài liệu chính thức có cấu hình giữ frame/token/word confidence và TDT duration confidence; đó là hướng thu evidence tiếp theo, chưa phải tín hiệu đo được ở đây. Xem [NeMo Confidence Estimation](https://docs.nvidia.com/nemo/speech/nightly/asr/asr_language_modeling_and_customization.html#confidence-estimation) và [source confidence utilities](https://github.com/NVIDIA-NeMo/Speech/blob/main/nemo/collections/asr/parts/utils/asr_confidence_utils.py). Khi áp dụng cần pin version/config và kiểm field thực tế.

Lượt Parakeet **v2 NeMo** mới hơn được dùng riêng cho nghe/tăng âm lượng trên VPS, không phải bảng v3 ở BTC_RESULTS. V2 runner hiện lưu native sequence scores/N-best; chưa đưa word confidence vào guard. Môi trường Python local của guard không cần NeMo. Các file nghe, WAV, logs và raw evaluation JSON giữ local, được gitignore.

Khi confidence thiếu: `confidence=null`, `sentence_risk=null` nếu chưa đủ mọi span; `known_risk_lower_bound` tách riêng. Agreement chuyển uncertainty heuristic thành 0/1 có nhãn rõ, không tạo xác suất acoustic. Native confidence thấp trong một trong hai pass vẫn giữ risk. HIGH/CRITICAL disagreement luôn ASK_REPEAT.

## Chạy từ repo root

Runtime Python 3.9+, chỉ standard library và không cần network. Môi trường test Python3.12 được khai báo riêng ở `requirements-test.lock`; cần chuẩn bị packages một lần, sau đó lệnh dưới chạy offline với cache đã có.

```bash
uv run --offline --no-project --python 3.12 \
  --with-requirements experiments/asr_reliability_guard/requirements-test.lock \
  python -m pytest experiments/asr_reliability_guard/tests -q
python3 -m experiments.asr_reliability_guard.cli assess "Please do not open valve 15"
python3 -m experiments.asr_reliability_guard.evaluate
```

Assess với word evidence thật: `--confidence-json /absolute/native.json`. Schema phải có source provenance và original character offsets:

```json
{"source": "backend/version/word-confidence-method", "words": [{"start": 0, "end": 4, "confidence": 0.45}]}
```

Số trong schema là ví dụ minh hoạ, không phải evidence BTC. Chỉ cung cấp confidence backend thực xuất; mỗi record phải phủ đúng một word/token, không được phủ nhiều từ bằng sequence mean. Span lấy min confidence để một từ yếu không bị average che; span thiếu coverage giữ null. `sentence_risk` vẫn là max trên mọi span; quyết định chỉ dùng max HIGH/CRITICAL (`protected_risk_lower_bound`), nên LOW/NORMAL riêng lẻ không thể tạo ASK_REPEAT dù chỉnh threshold thấp.

Config phải giữ mọi HIGH/CRITICAL trong protected categories; không cho CRITICAL vào personalization categories. Alignment ưu tiên protected matches theo thứ tự trước khi xét weighted matches, có giới hạn 2 triệu cells; vượt giới hạn trả lỗi rõ ràng để caller không xuất kết quả chưa được đánh giá.

Ghi xác nhận source transcript từ hai utterance đã review riêng; không tái sử dụng event ID để tăng count:

```bash
python3 -m experiments.asr_reliability_guard.cli feedback "open" \
  --db experiments/asr_reliability_guard/results/local-feedback.sqlite \
  --profile operator-1 --site site-1 --model whisper-small --revision local-revision \
  --kind confirm_asr --event-id reviewed-utterance-1
python3 -m experiments.asr_reliability_guard.cli assess "open" \
  --db experiments/asr_reliability_guard/results/local-feedback.sqlite \
  --profile operator-1 --site site-1 --model whisper-small --revision local-revision
```

Assess không có native evidence vẫn RETRY_ASR dù đã xác nhận. `reject_asr` hoặc `correct_asr` hạ bonus câu gốc; chỉ `confirm_asr` explicit mới tăng. `model` nên gồm cả backend identity; `revision` gồm artifact/decoder version khi có khác biệt. SQLite prototype lưu text local chưa mã hoá; DB bị gitignore, chưa nối với encrypted Android store.

Replay paired JSON thực: mỗi file có `transcript`, `audio_sha256`, `model_id`, `revision`, `config_id`, optional `words` và `confidence_source`:

```bash
python3 -m experiments.asr_reliability_guard.cli retry --first /absolute/pass1.json --second /absolute/pass2.json
```

Không có pass2 → RETRY_ASR còn chờ, không tự biến thành agreement. API `run_selective(first, decode_retry)` chỉ gọi decoder callback khi cần; callback trả `DecodePass` cùng audio/model/revision, cấu hình khác. Callback phải là decoder offline do caller cấp; prototype không có ASR engine mới. Config ID/hash là provenance caller cung cấp, không tự chứng minh decoder đã chạy độc lập. Attention SDPA/eager hoặc copy lại một transcript không phải bằng chứng retry độc lập.

## Kết quả, giới hạn và calibration tiếp theo

[BTC_RESULTS.md](BTC_RESULTS.md) chứa counts, WER giữ nguyên, top trigger spans, false alarms và toàn bộ câu ASR sai vẫn ACCEPT. `results/evaluation-*.jsonl` có từng decision/spans/alignment; summary có input SHA256. Mỗi run giữ đủ 100 câu gồm 1 VAD skip; guard demo gọi câu rỗng là ASK_REPEAT còn app production giữ NO_SPEECH.

Raw BTC evidence và generated JSON không được đóng gói trong Git. Clone sạch vẫn chạy unit/integration/synthetic E2E; test real BTC chỉ chạy khi có bộ artifact local đầy đủ. `evaluate --evidence-dir /absolute/frozen/evidence` hỗ trợ vị trí khác; thiếu dữ liệu sẽ báo lỗi rõ, không tải tự động. Các helper scorer cần cho synthetic tests đã được trích nguyên vào module tracked, không import code từ thư mục ignored `output/`.

Không có native word confidence hoặc same-model second decode trong logs. Cross-model Small/Parakeet là diagnostic riêng, không thay model hay giả là retry thật. WER normalized lấy từ frozen report sau khi kiểm SHA256 và exact hypothesis/reference; raw WER chấm lại bằng scorer cũ. Reference không được truyền vào guard hoặc feedback.

Các điểm mù: phủ định bị bỏ hẳn; hai pass cùng sai; glossary chưa đủ; spoken quantities/numeric formats chưa exhaustive; function/content words có thể đổi operational meaning ngoài lexicon. Formatting `one`/`1` hoặc contraction có thể tạo false alarm. ACCEPT không phải đảm bảo transcript đúng. Không có confirmation thật trong BTC nên các số eval không chứng minh learning cải thiện accuracy.

Tiếp theo: export native word evidence có offsets/provenance hoặc chạy same-model greedy/beam trên WAV thực; đo retry latency; thu explicit ASR-confirm/correction labels tách khỏi translation approvals; split speaker/site/session thành calibration và holdout; đo protected false-accept, false-reject, unsafe permission và calibration error. Chốt weights/thresholds/bonus trên calibration rồi freeze trước holdout. Chỉ sau đó mới cân nhắc Kotlin adapter/UI trong thay đổi riêng.
