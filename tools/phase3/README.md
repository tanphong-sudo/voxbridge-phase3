# Kiểm tra bằng chứng Vòng 3

Tool Python stdlib chỉ đọc ledger và các file được khai báo. Không chạy model, tải dữ liệu hoặc thao tác thiết bị.

Từ thư mục gốc repo, kiểm tra cấu trúc kế hoạch:

```sh
python3 tools/phase3/verify_evidence.py \
  --record submission/phase-3/templates/submission-record.example.json \
  --evidence-root . --mode planning
```

Exit 0 ở đây chỉ có nghĩa metadata của kế hoạch hợp lệ. File mẫu vẫn thiếu toàn bộ bằng chứng thực nghiệm.

Sau khi thu bằng chứng thật, dùng ledger riêng và thư mục chứa artifact:

```sh
python3 tools/phase3/verify_evidence.py \
  --record output/phase3/release/submission-record.json \
  --evidence-root output/phase3/release
```

Mode mặc định `submission` đối chiếu đủ 35 trường, file/hash/bytes, phiên bản, reviewer và phạm vi bằng chứng. Exit 1 nếu thiếu hoặc không khớp; exit 2 nếu không đọc được input/CLI. `runtime_verified` luôn `false`: cần người đọc raw logs, xem video và nghiệm thu thiết bị thực.

Đây là gate nội bộ hướng đến hồ sơ có đầy đủ bằng chứng, không phải công cụ chấm chính thức. Nếu mẫu BTC cho phép ghi “Not measured”, phải ghi trung thực; tool vẫn báo thiếu phạm vi đo tương ứng. Không điền số giả để làm tool trả 0. Role/scope do người lập khai báo nên file hợp hash chưa chứng minh nội dung đúng; tool cũng không thử quyền truy cập link nộp bài.

Chạy test nhỏ:

```sh
python3 -m unittest discover -s tools/phase3 -p 'test_*.py'
```

Các fixture chỉ là file giả trong thư mục tạm. Xem [contract](contracts/evidence.md) để hiểu giới hạn. Chỉ khai báo file cần thiết; hash file lớn vẫn cần đọc toàn bộ file.

Bộ test còn kiểm tra `tools/dev/inspect-apk.sh` bằng tiny ZIP và công cụ giả: inspection không gọi ADB mặc định; chỉ cài khi có `--install-device SERIAL`. Không dùng các test này làm chứng cứ APK thật đã cài hoặc chạy được.

## Tổng hợp human quality ratings

`score_quality.py` không chạy model hoặc chấm thay người. Chuẩn bị manifest JSONL theo [contract](contracts/quality.md), primary-turn CSV và ratings CSV đã điền theo forms. Manifest phải có eligibility/condition/provenance đã khóa; candidate BTC NOT-RUNNABLE hiện chưa đáp ứng contract.

```sh
python3 tools/phase3/score_quality.py \
  --manifest output/phase3/RUN/quality-manifest.jsonl \
  --turns output/phase3/RUN/primary-turns.csv \
  --ratings output/phase3/RUN/ratings.csv \
  --run-id RUN \
  --output output/phase3/RUN/quality-summary.json
```

Đây là đường dẫn dự kiến cho dữ liệu thu thật, không phải file measurement đã tồn tại. Primary CSV không gồm replay/repeat/confirm; tool từ chối run lẫn, sample/turn trùng và foreign IDs. `output_available` ở ratings nghĩa có translation text; `output_audio_available` ở turns nghĩa có translation audio artifact, độc lập với `actual_audio_emitted`. Prompt repeat không phải translation audio.

Exit2 cho input sai; exit1 khi thiếu turn/rating/audio score, còn tranh chấp hoặc có critical error đã resolve. Exit0 chỉ nói dữ liệu khai báo đủ và không có resolved critical error; cả bộ không có output vẫn có thể dữ liệu đầy đủ và semantic-success0. Tool không áp dụng quality thresholds, không kiểm tra file audio thật, không xác nhận người chấm/nhãn là đúng. `runtime_verified=false`, `quality_thresholds_evaluated=false` luôn được ghi.

Đọc expected/missing/unrated/unresolved và audio coverage cạnh điểm. Thiếu rating/result thì point semantic rate=null, có bounds thay vì tự cho0/điểm cao. Điểm nghĩa tính trên text primary; repair/recovery và live mic/loa phải đánh giá riêng theo protocol, không gọi đây là full voice-to-voice success. Không gọi clarity/naturalness nội bộ là official MOS.

## Kiểm tra timing và memory logs

`evaluate_device_run.py` chỉ đọc các log đã thu, theo [contract](contracts/performance.md). Collector Android chưa xuất đủ schema này; không chuyển log cũ sang các timestamp thiếu bằng suy đoán. Đường dẫn dưới là ví dụ cho một run tương lai:

```sh
python3 tools/phase3/evaluate_device_run.py \
  --session output/phase3/RUN/session.json \
  --manifest output/phase3/RUN/measured-manifest.jsonl \
  --turns output/phase3/RUN/performance-turns.csv \
  --memory output/phase3/RUN/memory.csv \
  --output output/phase3/RUN/performance-summary.json
```

`session.dataset_sha256` phải là hash exact file measured-manifest JSONL. Warm-up có phase riêng; manifest/mẫu số chỉ gồm measured primary inputs. Mỗi direction×memory-state có p50/p95/max/n riêng, nearest-rank; timeout/error vẫn tính failed rate. First-audio estimate/proxy không được tính vào measured release-to-first-audio.

Memory cần PID/process-start/clock khớp, raw-log path/tool/version và KiB. Tool báo sampled total/native/Java peak MiB, phase coverage và interval/gap từng process segment; không kiểm chứng raw file, không suy instantaneous peak/leak/thermal từ các snapshot.

Budget mặc định ở `config/phase3/performance-budget.json` là pilot_required, binding/threshold null nên luôn chưa PASS. Sau pilot độc lập, tạo budget đã freeze có provenance/device/release/model/config/dataset đúng và dùng `--budget PATH`; không chỉnh ngưỡng từ acceptance để đạt điểm. Warm-up lỗi, thiếu sample/phase, boundary sai hoặc mismatched group không được qua gate.

Exit2: schema/parse/hash input không hợp lệ; exit1: data/measurement/budget chưa đạt hoặc chưa frozen; exit0: đủ dữ liệu khai báo và frozen metadata budget gates đạt. `runtime_verified=false` luôn giữ nguyên: đây chưa là kiểm thử app, NPU, chất lượng/safety hoặc nghiệm thu thiết bị.

## Tests provenance exporter

Bộ test có11cases cho export_opus_mt.py với Hub/ONNX/Optimum/tokenizer/quantizer được stub và destination trong thư mục tạm. Nó kiểm tra cùng commit cho model/tokenizer, source.json, FP32/INT8, graph completeness và dừng trước khi thay destination nếu resolve thất bại. Không chạy exporter thật hoặc tải/nạp model. Xem [phạm vi và giới hạn](../../docs/development/export-provenance.md).

Lần export tương lai có thể dùng --dir vi-en --revision FULL_COMMIT; không chạy trên máy yếu ở chặng này. Export vào cây tạm, kiểm tra ONNX rồi mới promote; resolve/conversion/checker failure giữ cây cũ. Test checker dùng stub, chưa chạy ONNX thật. Current caches thiếu source.json vẫn unresolved; không tạo provenance hồi tố từ latest revision.

## Tests staging model

12cases kiểm tra model_bundle.py bằng cache giả vài byte: per-file VI/EN selection, notices Markdown, ASR preparation fallback, voice/espeak/provenance structure, symlink escape và destination preservation khi copy/publish/restore lỗi. [Contract](../../docs/development/model-bundles.md) nêu giới hạn; không staging models thật trong các test.

selected_files(root) là helper inventory chỉ đọc metadata/stat. bundle(source,destination) thực sự copy rồi thay destination, nên chỉ gọi khi chủ động chuẩn bị gói và có dung lượng. Nếu publish/restore lỗi, giữ nguyên đường backup được báo; không clean nó trước khi phục hồi. Không dùng unit tests/inventory để claim model/source/license/Android đã nghiệm thu. Legacy marker SMOKE_VI_EN còn để tương thích notebook; runtime selected profile vẫn vi-en-phase2.
