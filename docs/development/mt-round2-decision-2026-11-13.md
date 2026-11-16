# MT vòng 2: VinAI v2 và EnViT5, model gốc và cách giải mã

Ngày 13/11/2026. Scope: dịch chữ trên VPS, source transcript chuẩn BTC. Không sửa ASR hoặc backend Android; không làm thêm nén PyTorch. Người dùng muốn tìm model gốc tốt, không loại pilot vì license, ưu tiên Qualcomm khi khả thi.

**Quyết định mới nhất của người dùng (13/11): GIỮ CẢ HAI VinAI Translate v2 và EnViT5, beam2; chưa chốt model cuối.** So chất lượng, latency toàn pipeline tới âm thanh đầu, PSS đỉnh và thời gian đổi chiều trên máy thật sau, QDC để sau. Không xóa weights của hai ứng viên. EnViT5 được tải lại đúng revision đã thử sau khi đã bị xóa ở quyết định trước; giữ raw evidence cả hai. Record tại `config/models/selected-mt-vinai-v2.json` đã đổi trạng thái thành candidate pending real-device comparison, không phải backend Android đã tích hợp.

## Kết luận hiện tại

**Giữ VinAI Translate v2 + beam2 và EnViT5 + beam2 ngang quyền ứng viên, chờ máy thật.** VinAI nhỉnh điểm chữ, EnViT5 nhỏ hơn và dùng chung2chiều. Hai bản gần ngang trên100 dev. Khi chạy cấu hình cố định trên100 câu còn lại, VinAI tốt hơn cả hai chiều; gộp200 câu vẫn nhỉnh hơn. Chưa có human acceptance hoặc chứng minh end-to-end<2s trên Snapdragon cho bất kỳ bản nào.

Không promote VinAI chỉ vì điểm chữ: bản dịch vẫn có lỗi. Cần kiểm riêng số/giờ/phủ định/mệnh lệnh và đường Kotlin/native trước sử dụng thực tế. Reference hiện AI-authored/text-reviewed, human_verified=0, chưa đối chiếu WAV. Tập100 test được dùng ở vòng1 nên vòng2 là regression trên phần dữ liệu đó, không phải holdout độc lập mới.

## 1. Các phép thử đã chạy

Giữ nguyên manifest, split và reference hash vòng1. Mỗi cấu hình dưới có100 dev =50 VI→EN +50 EN→VI, greedy/beam không dùng reference lúc sinh output.

| Model / số phương án | VI→EN chrF2 AI draft | EN→VI chrF2 AI draft | EN→VI chrF2 BTC gốc | Runtime errors |
|---|---:|---:|---:|---:|
| EnViT5 /1 | 54.90 | 48.61 | 49.55 | 0/100 |
| EnViT5 /2 | 56.23 | 49.70 | 50.37 | 0/100 |
| EnViT5 /4 | 55.45 | 48.53 | 49.33 | 0/100 |
| VinAI v2 /1 | 53.68 | 49.08 | 49.69 | 0/100 |
| VinAI v2 /2 | 56.35 | 49.40 | 49.82 | 0/100 |
| VinAI v2 /5 | 56.07 | 49.49 | 50.11 | 0/100 |
| OPUS /4 | 50.99 | 35.14 | 35.81 | 0/100 |

chrF2 là độ giống bản dịch tham chiếu theo ký tự, không phải tỷ lệ câu dịch đúng. Chênh nhỏ không đủ kết luận thắng chắc khi reference còn cần human review.

VinAI2 tốt hơn VinAI5 ở VI→EN dev, gần ngang ở EN→VI, ít phương án hơn; không có lý do chọn beam5 làm default lúc này. EnViT5 beam4 giảm điểm cả hai chiều so với beam2, nên không chọn beam4. Không có nguyên tắc “beam càng lớn càng hay”.

## 2. Kiểm100 câu còn lại, không đổi cấu hình từ output

| Model /2 | VI→EN AI draft | EN→VI AI draft | EN→VI BTC gốc | Runtime errors |
|---|---:|---:|---:|---:|
| EnViT5 | 59.05 | 49.55 | 48.94 | 0/100 |
| VinAI v2 | 64.22 | 51.71 | 50.76 | 0/100 |

## 3. Tính lại trên toàn200 câu,100 mỗi chiều

| Model /2 | VI→EN AI draft | EN→VI AI draft | EN→VI BTC gốc |
|---|---:|---:|---:|
| EnViT5 | 57.56 | 49.62 | 49.63 |
| VinAI v2 | **60.07** | **50.60** | **50.31** |

EN→VI trên21 nhãn human gốc: EnViT5 55.70, VinAI59.00; trên79 nhãn AI gốc: EnViT5 48.49, VinAI48.67. Đó là provenance workbook, không tự chứng nhận correctness của21/79 references hoặc là người đã nghe output. Không lấy trung bình scores của các nhóm để tạo corpus score.

## 4. Đọc output để giải thích chênh lệch

- Hai model beam2 đều giữ hai câu hỏi ở case nguồn hỏi vị trí đau; EnViT5 greedy trước đó bỏ câu thứ hai.
- VinAI giữ rõ “5 giờ30 sáng” ở câu gọi taxi; EnViT5 beam2 vẫn thiếu “sáng”.
- Cả hai vẫn hiểu sai câu hỏi “Buổi tối mở đến mấy giờ?”; tăng beam không chữa được câu này.
- Cả hai có thể đổi ngôi/thì của một mệnh lệnh; đây là lỗi nghĩa cần chấm riêng, không suy an toàn từ chrF.
- VinAI có lỗi literal “chip” trong thành ngữ góp tiền; EnViT5 dịch tự nhiên hơn ở ví dụ đó.
- EnViT5 có thể đổi “đang dọn phòng” thành “đã dọn”; VinAI giữ được diễn tiến ở ví dụ dev. Mỗi model có cả chỗ hơn và chỗ kém.

Raw BTC source/output chỉ ở ignored `output/phase3/mt3/`; không chép vào public source. Có bảng so sánh readable riêng và AI diagnostic review, chưa phải human rating mù.

## 5. Kích thước và yêu cầu cuộc thi

| Thuộc tính | EnViT5 | VinAI v2 |
|---|---|---|
| Kiến trúc | T5 | mBART |
| Parameters đo từ model đã load | 275,102,976, chung2chiều | 423,199,744 mỗi chiều |
| Weights nguồn FP32 | 1,100,503,117bytes | 1,693,231,033bytes mỗi chiều |
| Số checkpoint để hỗ trợ2chiều | 1 | 2 |
| Độ lớn model đang chạy, theo params | 1× | khoảng1.54×EnViT5 nếu chỉ nạp1chiều |
| Tổng weights FP32 của2chiều | khoảng1.10GB | khoảng3.39GB, khoảng3.08×EnViT5 |

**Cả hai FP32 đều chưa đạt cam kết active-model<0.8GB.** Đây là đánh giá base model trước nén, không phải bản đóng gói cuối. VinAI423M/chiều vẫn đáng thử tối ưu cho edge; không coi nó chắc chắn fit chỉ vì INT8 lý thuyết là423MB. Phải tính embeddings, KV, runtime, ASR và TTS chung. Nạp một chiều tiết kiệm RAM nhưng đổi chiều có thể tăng latency; giữ cả hai giảm reload nhưng tăng memory. Chưa chốt chiến lược này khi chưa đo thiết bị.

Thời gian VPS không xác nhận mốc<2s. Các lượt4CPU threads có lúc chạy song song và khác tải so vòng1. Không lấy p95 VPS so trực tiếp với Snapdragon hoặc tuyên bố model “trượt2s” từ CPU server. Mốc cần đo là end-of-speech→first audio cả pipeline; MT không được tự dùng hết2s. Thiết kế trước chia khoảng700ms ASR,400ms MT+safety,500ms TTS đầu, phần còn lại overhead; đó là budget thiết kế cần kiểm, không phải số đã đạt.

## 6. Qualcomm: làm được gì lúc này

Theo source AI Hub đã đọc, template OPUS dùng Marian; VinAI mBART và EnViT5 T5 không dùng nguyên recipe đó bằng cách thay checkpoint. Chưa thấy precompiled VI↔EN đúng model+SM8650 cho VinAI/EnViT5 trong catalog đã kiểm. Không có bản Qualcomm đúng target để chạy parity ở lượt này.

Không mở thêm job cloud hoặc dùng audio BTC trên AI Hub. Chưa có export/adaptation graph tương ứng, SSD ít, nên dừng ở quyết định base model và ghi gate cần làm, đúng yêu cầu “có bản đó thì test, không thì thôi”. Sau khi chọn final, thử custom export/compile với runtime/SoC đúng rồi kiểm output nếu có. Không khẳng định AI Hub tự bảo đảm thứ hạng/chất lượng sau nén.

## 7. Môi trường, cleanup và lỗi đã xử lý

- Venv pilot riêng,torch2.5.1CPU,transformers4.51.3,HFhub0.30.2,sacrebleu2.5.1. Không sửa env export hoặc source Android cũ trên VPS.
- VinAI pilot lần đầu partial10VI/20 rồi dừng disk gate trước EN: thư viện tự tạo/tải auto-converted safetensors, làm trùng weights. Failed run giữ log, không chấm như20 hoàn tất.
- Sửa runner tải đúng một weight format, pin revision rồi local-only load, tránh background auto_conversion. Download-size check giữ reserve2GiB riêng lượt2; không chạy export nặng. Các bản chuyển đổi trùng do task tạo đã xóa có hash ledger.
- VinAI v2 có cả bản1/2/5phương án, output/errors/token caps/timing riêng từng câu. Source language tag và target decoder-start đúng docs chính thức; có test bảo vệ.
- Source reference freeze không đổi sau khi xem outputs. Round2 có90 Phase3 tool tests PASS, project static validator PASS và diff whitespace check PASS. Không suy tool tests thành quality accuracy.
- SMaLL100 được retire khỏi shortlist hiện tại: nặng hơn EnViT5, điểm dev kém cả2chiều, không có lợi thế recipe Qualcomm trong nguồn đã kiểm, license không còn là tiêu chí loại pilot theo user. Xóa task-created weights cache sau giữ hash/result, có thể tải lại theo revision. Không tuyên bố mọi cấu hình SMaLL100 đều dở.

## 8. Quyết định để tiếp tục

1. Giữ weights cả VinAI v2 beam2 và EnViT5 beam2 để so trên máy thật; chưa chọn bản cuối. OPUS còn source/loader và baseline evidence, không còn weights trên VPS.
2. Không thử thêm model to hơn hoặc nén PyTorch tiếp trong lượt này. Không tăng beam5/beam4 làm default chỉ vì “nhiều hơn”.
3. Chưa đổi backend Android; bước tiếp theo là đánh giá lỗi nghĩa còn lại và nối model được chọn vào đường export/runtime phù hợp, rồi chứng minh cả pipeline trên Snapdragon khi có artifact đúng. Nếu VinAI không giữ budget sau tối ưu/đo, quay về EnViT5 beam2 là phương án đã có bằng chứng chất lượng, không phải bắt đầu lại.
