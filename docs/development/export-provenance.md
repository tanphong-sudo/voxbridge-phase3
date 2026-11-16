# Truy nguồn cache và tránh mất provenance ở lần export sau

Ngày15/10: chỉ stat/đọc metadata nhỏ; không đọc/hash weights hoặc chạy exporter thật. Không ghi source.json mới cho các model đã có.

## Kết quả local recovery

- Hai selected MT tree chỉ còn3quantized graphs +2SPM +vocab, không source/config/io metadata; đều regular files, không symlink về HF snapshot. Tên/kích thước không xác định được upstream revision.
- VAD có một ONNX file, không source.json. Fetcher có GitHub tag và HF fallback nên không thể suy nguồn thực chỉ từ code mặc định.
- EN voice config cho biết piper_version1.0.0, dataset lessac, en_US/espeak en-us,22050Hz,256symbols/1speaker. Đây là identity/config, không phải archive digest hoặc quyền phân phối.
- Không có các cache root mặc định ~/.cache/huggingface/hub, ~/Library/Caches/huggingface/hub hoặc ~/.cache/voxbridge/models ở lần kiểm tra;4HF cache env vars không được cấu hình.8file metadata.txt lịch sử không có dòng revision gắn OPUS/Silero/Lessac.
- Phạm vi tìm kiếm này chưa chứng minh không có log/bản gốc ở Drive/Colab/partner. Giữ current MT/VAD/EN voice provenance unresolved, không gán latest revision từ mạng cho weights cũ.

## Patch exporter MT: kế hoạch trước code và kết quả

1. Dùng HfApi.model_info để lấy full commit SHA; nếu người chạy chỉ định revision thì chỉ nhận full40hex, cần --dir để không áp một repo SHA cho mọi hướng. Resolve/validate trước khi xóa/thay destination.
2. Truyền cùng commit cho ORTModelForSeq2SeqLM và AutoTokenizer, tránh model/tokenizer lệch nếu main thay giữa hai lần fetch. Không đổi preset INT8 hoặc model đang deploy.
3. Chỉ sau export/quantize/io thành công ghi source.json: repo/revision, transformation/selected graph filenames và tool versions nếu đọc được; thiếu version ghi null, không đoán. Không tự ghi license cleared.
4. Tests load exporter với external dependencies được stub ở module boundary, destination dưới TemporaryDirectory, file graph vài byte. Kiểm tra revision args, metadata thật được viết, resolver failure/invalidSHA không chạm cây cũ, FP32/INT8 và CLI guard. Không import/nạp thư viện ML thật hoặc gọi mạng/model.
5. Bổ sung gate3graph đúng precision/nonempty trước provenance; file existence chưa là ONNX checker. Current cache không thay; fresh real export/ONNX checker/quality regression để sau khi máy đủ khả năng. Exporter hiện thay destination sau pinning, chưa phải transactional toàn bộ quá trình: lỗi export sau đó vẫn cần recovery. Không hứa cache được giữ trên mọi lỗi.

Kết quả:9tests stub PASS, có red-before-green cho revision/provenance và graph completeness/INT8 claim. Exporter/quantizer/Hub được stub, destination tạm; chưa chạy exporter hoặc ML libs thật. Không dùng test này để chứng minh current cache có provenance.

API kiểm tra từ nguồn chính thức: [HfApi model_info/ModelInfo](https://huggingface.co/docs/huggingface_hub/main/package_reference/hf_api), [Optimum1.23.3 from_pretrained revision](https://huggingface.co/docs/optimum/v1.23.3/en/onnxruntime/package_reference/modeling_ort). Tool versions thực tế vẫn phải ghi ở lượt export thật; docs/API không chứng minh exporter đã chạy thành công.

## Cập nhật hợp nhất code partner 15/10

Exporter nay giữ transaction từ commit partner `9a69ad1`: export vào cây tạm, kiểm tra ba graph bằng `onnx.checker.check_model`, sau đó `promote_tree`. Kết hợp pin model/tokenizer cùng commit và source.json của chặng này. Conversion/checker failure không thay cây cũ; tests thêm hai case bảo toàn prior export, tổng11 stub tests. Các giới hạn "chưa có transaction" ở snapshot phía trên đã được thay thế. Đây chưa phải real ONNX export hoặc cache provenance recovery. Android mặc định hiện dùng ML Kit; OPUS exporter chỉ phục vụ tuyến cũ/tái lập.
