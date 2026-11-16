# MT Việt↔Anh: nguồn model và vòng thử đầu ngày 13/11/2026

**Quyết định mới nhất:** giữ cả VinAI v2 beam2 và EnViT5 beam2, chưa chọn cuối; so trên máy thật sau. Xem [note quyết định](mt-round2-decision-2026-11-13.md). Không xóa weights hai ứng viên này. Các kết luận bên dưới là lịch sử thử nghiệm.

Mục tiêu: model dịch đủ tốt cho VoxBridge; kiểm chất lượng trên VPS trước khi chứng minh Snapdragon latency/PSS. Ưu tiên nguồn Qualcomm không thay thế chất lượng.

## Nguồn đã đọc

- [AI Hub Models catalog](https://github.com/qualcomm/ai-hub-models/tree/b3b4b85ce67dee3556e5f9c0a88d3f2b3f02ef8b/src/qai_hub_models/models): cây GitHub không truncated, 284 thư mục model. Có 14 cặp OPUS, chưa thấy VI↔EN. Không nói AI Hub không có model dịch.
- [OPUS template](https://github.com/qualcomm/ai-hub-models/blob/b3b4b85ce67dee3556e5f9c0a88d3f2b3f02ef8b/src/qai_hub_models/models/templates/opus_mt/model.py): Marian encoder/decoder được sửa để export, static length 256, mixed precision/calibration; một đường nghiên cứu cho custom VI weights, chưa chứng minh compile hoặc parity.
- [Qualcomm Qwen3-0.6B](https://huggingface.co/qualcomm/Qwen3-0.6B/tree/56e799b788fac6d31a6428808477864cbfd9f618): W4A16/Genie QAIRT 2.50; bảng download có Snapdragon 8 Elite/Elite Gen5, X và Dragonwing. Không thấy 8 Gen 3. Không copy gói cho chip khác vào SM8650.
- [Qwen3 upstream](https://huggingface.co/Qwen/Qwen3-0.6B/tree/c1899de289a04d12100db370d81485cdf75e47ca): Apache-2.0; multilingual general LLM, không phải model dịch chuyên dụng. Non-thinking chat template có sẵn. Card khuyến nghị sampling T=.7/top_p=.8/top_k=20 cho non-thinking; vòng đầu dùng greedy để bounded deterministic screening, không kết luận mọi cấu hình Qwen đều kém.
- [EnViT5](https://huggingface.co/VietAI/envit5-translation/tree/840bc88104d5a4277af740eaedb024df8c3093e7): chuyên VI↔EN, prefix nguồn `vi: `/`en: `, T5; card OpenRAIL chưa nêu rõ biến thể, chưa đóng license gate.
- [OPUS VI→EN](https://huggingface.co/Helsinki-NLP/opus-mt-vi-en/tree/c8d2853e77f5fae31124d993e0b35176b1c8914e), [EN→VI](https://huggingface.co/Helsinki-NLP/opus-mt-en-vi/tree/989c9fb9ec63987901022baf0182dcec3e149be6): card hiện Apache-2.0. Đây là license model cụ thể, không copy nhãn CC-BY từ tài liệu cũ. Default upstream beams lần lượt 6/4; vòng đầu greedy phù hợp decode hiện tại, chưa thử beam production.
- [VinAI Translate](https://github.com/VinAIResearch/VinAI_Translate): hai model riêng; [v2 VI→EN](https://huggingface.co/vinai/vinai-translate-vi2en-v2/tree/ae7baa85da07dbe8e23ac26a9f5ef560c17e2138), [v2 EN→VI](https://huggingface.co/vinai/vinai-translate-en2vi-v2/tree/82f8c91bd22e82085186b45a8a76373f5a79f667), AGPL-3.0, mỗi weights FP32 1,693,231,033 bytes. Homepage ghi model đời đầu 448M, không gán số đó thành số đo v2. Chưa tải vì disk và deployment overhead hai model.
- [SMaLL-100](https://huggingface.co/alirezamsh/small100/tree/8ab680e26a596d2e3d2d2d17ae0f68df1037328c): 332,735,488 parameters theo safetensors metadata, MIT, explicit VI/EN, một model đa ngôn ngữ, 1,330,973,772 bytes FP32 safetensors. M2M100 architecture, protocol encoder `[TARGET_LANG] + source + EOS` khác M2M100 thường. Chưa thấy recipe AI Hub riêng.

## Shortlist chạy

| Model | Payload nguồn | Ưu điểm | Rào cản Snapdragon |
|---|---|---|---|
| OPUS 2 chiều | ~289 MB FP32 mỗi chiều; cache INT8 hiện có ~99 MiB mỗi chiều gồm external weights | Nhẹ, Kotlin loader sẵn, template Qualcomm cùng họ | Chất lượng/decoder; custom VI NPU chưa chứng minh |
| EnViT5 | ~1.10 GB FP32, một model | Chuyên cặp VI↔EN, dùng chung hai chiều | Phải INT8/parity, license variant và Android RAM/latency |
| Qwen3-0.6B | 1.50 GB BF16 safetensors; FP32 lớn hơn trong RAM | Có Qualcomm W4A16 chính thức | Gói SM8650 chưa thấy, Genie integration khác ORT, chất lượng dịch chưa chắc |
| SMaLL-100 | 1.33 GB FP32 safetensors | 333M, MIT, nhiều ngôn ngữ, một model | Cần INT8, tokenizer/KV Kotlin mới, không có recipe AI Hub đã xác minh |

Số bytes FP32/BF16 trên không phải payload INT8/W4 thực hoặc PSS. Tính 1 byte/parameter chỉ là lower-bound weights, chưa gồm embeddings ngoại lệ/KV/workspace/runtime. Cam kết active model 0.8 GB và AI PSS1.6 GB áp dụng cả pipeline; chưa ứng viên nào được xác nhận đạt chỉ từ số tham số.

## Hợp đồng phép thử

References được AI soạn/text-review độc lập trước output, khóa SHA256 `49404b12c769f0e5b561e3b8ed660097c2c6a4c9b9aa3c7e19ccfef2be3ae704`. 100 VI target mới, 100 EN giữ original và AI-draft riêng (16 revisions); human_verified=0, chưa nghe WAV. Ambiguity được đánh dấu, không tự coi tham chiếu là tuyệt đối đúng.

Text source chuẩn vào model, không chạy ASR. 50 dev/50 test mỗi chiều; hash split cố định, stratify direction/provenance/question. Pilot 10 dev mỗi chiều chung cho mọi model, mở rộng 50 dev mỗi chiều sau khi pilot không lỗi kỹ thuật. Model generation chỉ nhận source/direction, không nhận target. Các instruction trong source là dữ liệu để dịch, không thực thi.

Hai native threads, nice10, tuần tự model; không kill jobs khác. Mỗi record giữ output/error/token cap/time; summary ghi mẫu số, original provenance scores và AI-draft scores riêng. CPU timings ở môi trường còn tải khác chỉ là diagnostic, không dùng kết luận tốc độ Snapdragon hoặc so benchmark tuyệt đối.

VPS thử riêng `/home/opc/voxbridge-mt3-20261113`; không đụng env export cũ. Venv mới có torch2.5.1+cpu từ env export, transformers4.51.3, huggingface-hub0.30.2, sacrebleu2.5.1; optimum cũ không tương thích transformers mới nên KHÔNG dùng optimum/export trong venv pilot. Python HF FP32 greedy chỉ sàng lọc; model được chọn vẫn phải qua Kotlin/ONNX INT8 parity và kiểm target.

Raw evidence nằm trong ignored `output/phase3/mt3/`. Không commit BTC sources/references/output. Kết quả chạy sẽ cập nhật trong báo cáo cùng ngày.

## Kết quả thực chạy

### 100 câu dev cố định (50 mỗi chiều), chrF2 càng cao càng gần reference

| Model FP32 greedy | VI→EN, AI draft | EN→VI, AI text-reviewed draft | EN→VI, BTC original | Lỗi kỹ thuật | Chạm cap |
|---|---:|---:|---:|---:|---:|
| OPUS | 48.67 | 34.13 | 34.97 | 0/100 | 0/100 |
| EnViT5 | **54.90** | **48.61** | **49.55** | 0/100 | 0/100 |
| SMaLL-100 | 50.74 | 41.56 | 43.16 | 0/100 | 0/100 |
| Qwen3-0.6B | 41.98 | 30.29 | 29.92 | 0/100 | 1/100 |

Qwen output language/meaning errors đủ để reject cấu hình pilot dù không có lỗi runtime. Greedy nonthinking là một cấu hình, không chứng minh mọi bản Qwen3 đều kém.

EnViT5 là provisional leader dựa trên100 dev, chưa phải human acceptance. Sau khi khóa set model/config, OPUS và EnViT5 chạy tiếp100 test, không chỉnh luật/prompt từ test.

### 100 câu test còn lại (50 mỗi chiều)

| Model | VI→EN AI draft | EN→VI AI draft | EN→VI BTC original | Lỗi kỹ thuật |
|---|---:|---:|---:|---:|
| OPUS | 51.51 | 36.06 | 36.44 | 0/100 |
| EnViT5 | **57.26** | **49.72** | **48.98** | 0/100 |

Cả hai đã chạy đủ200 câu. Các bước dấu câu/coverage sau này phải ghi BTC đã được xem ở giai đoạn chọn MT; dùng fresh controls hoặc independent acceptance để tránh gọi lại là unseen. Reference hash không đổi sau khi xem output.

### Toàn bộ200 câu (100 mỗi chiều)

| Model | VI→EN AI draft | EN→VI AI draft | EN→VI BTC original |
|---|---:|---:|---:|
| OPUS | 50.02 | 35.15 | 35.74 |
| EnViT5 | 56.01 | 49.19 | 49.25 |

Đây là corpus score tính lại trên100 câu mỗi chiều, không lấy trung bình hai điểm dev/test.

### Đọc output thật: các lỗi còn lại

- OPUS có câu đổi thứ Năm thành thứ Tư và lặp “hẹn”; EnViT5 giữ thứ Năm trong câu đó.
- EnViT5 sửa được nghĩa “withdraw money with an international card”, trong khi OPUS ra “cash in an international card”.
- EnViT5 vẫn dịch sai câu hỏi giờ mở cửa và làm mất câu hỏi thứ hai ở một mẫu hai câu. Cần bước punctuation/coverage, nhưng không được hứa hai bước đó chữa mọi lỗi.
- Qwen có output giữ nguyên tiếng Anh, đổi half hour thành half minute, đổi Friday thành Tuesday. Các lỗi này được lưu trong raw output và AI diagnostic review, không phải lời phỏng đoán từ điểm metric.
- SMaLL-100 khá hơn OPUS theo chrF dev nhưng còn sai 5:30→5:00 ở một mẫu; chưa loại chắc chắn toàn model, giữ làm comparator.

Có80 AI review records cho20 câu pilot ×4 model; mỗi model loại2 câu ambiguous khỏi semantic scoring. Đó là AI source/output review không mù tên model, không phải human rating/MOS. Chỉ dùng để giải thích ví dụ, không đưa số đó như chất lượng chính thức.

### Nén EnViT5: có nhẹ hơn nhưng chưa promote

| Diagnostic PyTorch INT8 | Serialized state bytes | VI→EN chrF dev | EN→VI chrF dev | So gate ≤1 điểm giảm mỗi chiều |
|---|---:|---:|---:|---|
| Linear per-tensor | 390,873,834 | 53.28 | 47.70 | Không đạt VI (giảm1.61) |
| Linear per-channel | 395,432,366 | 54.07 | 45.52 | Không đạt EN (giảm3.08) |
| Linear per-tensor, lm_head FP32 | 506,183,692 | 54.45 | 47.06 | Không đạt EN (giảm1.54) |

Đây là state PyTorch, **không phải payload ONNX/Android**, không thay số peak PSS. Không promote ba bản nén này, chưa đổi MT Android. Bước tiếp theo hợp lý là ONNX parity/quantization đúng graph và selective calibration rồi human review; không tự hạ gate để chọn bản nhẹ.

### Cleanup được user cho phép

- Xóa đúng HF cache Qwen3-0.6B tạo trong task, khoảng1.41GiB, sau khi kết thúc100 dev và lưu revision/SHA inventory/result.
- Xóa ba state INT8 failed gate do task tạo, tổng1,292,489,892bytes; metadata, hash và output giữ lại, có thể tái tạo từ source FP32.
- Không xóa OPUS/PhoWhisper/Whisper/Piper/VieNeu có sẵn và không đụng cache/project của tác vụ khác. Giữ SMaLL-100 vì chưa đủ bằng chứng để gọi chắc chắn dở.

### Kiểm chứng tooling

- 6 tests mới cho grouped split/reference preservation, duplicate/mismatched IDs, prompt isolation, missing/failure denominators và token cap/forced EOS.
- Toàn bộ Phase3 Python suite:88 tests PASS. `scripts/validate_project.py` PASS. `git diff --check` PASS.
- SMaLL100 tokenizer adapter dùng M2M100Tokenizer với source language field đặt theo target, so input IDs với tokenizer upstream đã đọc source:100/100 dev khớp. Đây là Python tokenizer parity, không phải Android.
- Chưa run Android inference, chưa compile QNN custom VI, chưa xác minh ORT/Genie/native integration hoặc Snapdragon PSS/latency. Chưa commit/push.


## Vòng 2 — theo chỉ thị mới: ưu tiên model gốc, không chặn pilot bởi license

Người dùng yêu cầu thử thêm ứng viên đủ vừa cho Snapdragon và end-to-end<2s; không tập trung nén PyTorch nữa, ưu tiên Qualcomm AI Hub về sau. License được ghi nhận để đóng gói, **không dùng loại VinAI khỏi pilot**. Các nhận định chưa tải VinAI ở vòng1 chỉ là lịch sử.

### Cấu hình và giới hạn dữ liệu

- VinAI v2: mBART, 423,199,744 tham số mỗi chiều; FP32 weights1,693,231,033bytes/chiều. Chạy lần lượt, không giữ hai chiều resident. Tokenizer source `vi_VN`/`en_XX`; decoder-start là ngôn ngữ đích đúng theo ví dụ chính thức VinAI, không forced-BOS kiểu NLLB.
- Thử VinAI greedy, beam2, beam5; EnViT5 greedy/beam2/beam4; OPUS beam4. Không fine-tune, không sửa ASR, punctuation, coverage, safety hoặc TTS.
- Cùng100 dev đã khóa và cùng reference đã khóa trước output.100 test đã được dùng trong vòng1; chạy lại chỉ là fixed-config regression, không gọi là blind/unseen acceptance mới. Không chỉnh reference sau khi thấy model sai.
- Vòng2 có4CPU threads/process, nice10; VPS hết workload lớn lúc bắt đầu nhưng có thể có hai lượt chạy song song, nên wall-time chỉ là diagnostic, không suy tốc độ Snapdragon hoặc hứa2s.
- Không chạy thêm nén PyTorch. Không hạ quality gate chỉ để promote bản nén vòng1.

### Lỗi tải trùng và cách sửa

Transformers4.51.3 có nhánh background `auto_conversion` cho online `.bin` load, kể cả `use_safetensors=False`. VinAI VI pilot lần đầu hoàn thành10 câu rồi dừng disk gate trước model EN vì bản chuyển đổi tải thêm1.69GB. Giữ log partial, không coi là20 mẫu hoàn tất.

Runner mới tải tường minh đúng `config.json` và một weights file bằng immutable revision, sau đó nạp local snapshot với `local_files_only=True`; không sửa thư viện global. Đã thêm test cho file plan và VinAI decoder language contract. Thêm download-size preflight trước tải; reserve2GiB được dùng riêng cho vòng2, không xóa project khác. Các cache auto-converted của EnViT5/VinAI chỉ là bản trùng do task tạo, đã xóa có SHA ledger, original weights giữ nguyên.

### AI Hub: đã kiểm đúng phạm vi

Catalog284 model đã lưu tree/hash ở vòng1 không có recipe `envit5`, `mbart`, `small100` hoặc `m2m100`. Source `templates/opus_mt/model.py` cùng `model_adaptation.py` dùng `MarianMTModel`, không mBART/T5. Không thể coi thay tên checkpoint là compile VinAI/EnViT5 xong. Chưa có gói precompiled VI↔EN cho hai model này trên SM8650 trong nguồn đã kiểm, nên **chưa có bản Qualcomm đúng model/target để test chất lượng**.

Không submit job compile/profile/inference, không gửi data BTC lên AI Hub. Bước có thể làm sau là export/adapt graph của model đã chọn và compile target/runtime chính xác; chi phí chuẩn bị/SSD hiện chưa được coi là một phép thử NPU hoàn tất. Chất lượng float thắng không đảm bảo thứ hạng sau quantization, nên target output vẫn cần được kiểm nếu có bản compile.
