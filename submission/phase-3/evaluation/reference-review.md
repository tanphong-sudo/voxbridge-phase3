# Chuẩn bị đáp án và chấm chất lượng VI↔EN

Trạng thái 15/10: quy trình sẵn để giao reviewer, chưa có bản dịch hoặc rating mới được nghiệm thu. Dữ liệu BTC đã đọc metadata, chưa tải/nghe WAV. Đây là phương pháp tự đánh giá của đội, không phải hướng dẫn chấm chính thức.

## 1. Khóa nguồn và người chấm trước khi xem output

Developer khóa danh sách sample IDs, direction, workbook gốc/hash, WAV/hash, recording condition và phiên bản protocol trong một run. Người lập bản dịch chỉ nhận audio/transcript nguồn, thông tin ngữ cảnh có trong dataset và reference BTC nếu có; không nhận output VoxBridge. Không dùng chính model đang đo để tạo đáp án.

Reviewer A cần hiểu cả tiếng Việt và tiếng Anh. Reviewer B chấm độc lập ít nhất 20% mỗi chiều, có đại diện condition và provenance; thêm mọi câu bị đánh dấu critical hoặc tranh chấp. Nếu chỉ có một reviewer, ghi rõ một người và chưa có kiểm tra độc lập, không tự nhận hai người chấm. Người thu âm phải đồng ý việc lưu/chia sẻ bản ghi theo phạm vi hồ sơ.

## 2. VI→EN: tạo reference còn thiếu

1. Sau khi có WAV, nghe và đối chiếu transcript. Transcript khác audio: giữ nguyên văn bản BTC, ghi bản hiệu chỉnh và lý do riêng; audio là nguồn để đánh giá toàn pipeline. Đánh dấu `audio_checked` chỉ sau khi nghe thật.
2. Dịch sang EN tự nhiên, giữ hành động, phủ định, số/đơn vị, tên/ID, điều kiện và mức khẩn cấp. Không thêm lời giải thích, không “sửa” ý người nói cho tiện dịch. Câu mơ hồ cần ghi chú, không tự đoán một đáp án chắc chắn.
3. Lưu author, thời điểm và revision. Reviewer B xem các câu critical và subset đã khóa; ghi agree/disagree và bản adjudicated riêng. Không sửa đè source reference hoặc xóa lịch sử.
4. Chỉ đặt `human_verified=true` cho sample đã hoàn tất bước review được ghi tên/phương pháp. `authored` khác `reviewed`; mới có transcript chưa có nghĩa đã xác minh audio.

## 3. EN→VI: review 21 nhãn human và 79 nhãn AI

Giữ nguyên bản BTC cùng nhãn provenance. Nhãn human không thay thế việc nghe audio; nhãn AI không tự động đồng nghĩa sai. Reviewer đánh giá đúng/sai/thiếu ngữ cảnh từ source; nếu cần sửa, tạo revision có rationale, không thay target bằng output VoxBridge.

Báo metric trên reference gốc và reference đã review riêng khi đủ dữ liệu, cùng số câu mỗi loại. Tách nhóm 21/79 theo metadata gốc, không chuyển một câu AI sang nhóm human chỉ vì đã được người review; có cột `human_verified` riêng. Nếu chỉ một phần được review, công bố coverage và giới hạn, không gọi cả100 đã xác minh.

## 4. Phiếu review và khóa phiên bản

Copy `forms/reference-review.csv` vào thư mục run ngoài Git. Một record cho mỗi sample/revision; `review_status` dùng `pending|authored|reviewed|needs_adjudication|excluded_with_reason`. Ô thiếu để trống, không điền false/0 như đã kiểm tra.

`source_text_original` và `target_reference_original` luôn giữ nguyên. `source_text_reviewed` và `target_reference_reviewed` là phiên bản mới; reference dùng chấm phải được chỉ rõ bằng `reference_id` và hash của file khóa. Không sửa reference sau khi thấy model sai; correction thực sự cần làm sau đó phải tạo revision, giải thích và chấm lại cùng tập cho mọi bản so sánh.

Câu không nghe được hoặc thiếu ngữ cảnh: ghi lý do, giữ trong intake/coverage và báo số bị loại trước scoring. Không loại chỉ vì pipeline trả kết quả kém. Câu hợp lệ nhưng app không trả output vẫn nằm trong mẫu số thành công.

## 5. Chấm output sau khi khóa reference

Reviewer chấm độc lập theo thứ tự được xáo trước, ẩn tên phiên bản khi có so sánh. Với corpus200, subset reviewer B tối thiểu40 câu:20 mỗi chiều; EN→VI phải có cả human/AI. Khóa danh sách trước khi xem output, không chọn40 câu dễ nhất. Đối với tập tự thu100 câu, tối thiểu20 câu được chấm hai lần, có cả direction/quiet/noise. Đây là mục tiêu nội bộ, tùy nhân lực phải ghi actual n.

- Nghĩa 0–4: 0 không dùng được/không output; 1 sai ý chính; 2 giữ một phần cần sửa; 3 đúng ý chỉ lỗi nhỏ; 4 đầy đủ. Chấp nhận cách diễn đạt khác reference nếu cùng nghĩa. Không phạt paraphrase chỉ vì ít từ trùng.
- Clarity/naturalness 1–5: chỉ nghe actual output audio để chấm. Không có audio thì để trống và báo output coverage, không suy từ text hay tự tạo audio thay.
- Critical error: đối chiếu input thật với output text và audio thực phát. Kiểm tra phủ định, hành động, đối tượng/ID, số/đơn vị, hazard và urgency. Tách `spoken_allowed` khỏi `actual_audio_emitted`. Chưa có phát âm thực thì không kết luận unsafe-spoken bằng permission log.
- Repeat/repair: lưu lượt đầu và recovery riêng. Repair cần confirm trước audio; không lấy câu người dùng xác nhận sau đó thay điểm lượt đầu. Chấm chất lượng và trải nghiệm recovery riêng.

Giữ hai rating độc lập trước adjudication. Báo số câu hai người chấm, tỷ lệ đồng ý về ngưỡng nghĩa≥3 và số critical disagreements; chưa đủ người không tính agreement giả. Lưu lý do và quyết định cuối của mỗi disagreement, không chọn điểm cao hơn tự động.

## 6. Bảng kết quả cần có

Theo từng chiều và condition: tổng input hợp lệ, completed/no-output/error/repeat/repair, số output text/audio, số đã chấm, semantic-success (điểm≥3 trên tổng input hợp lệ), điểm nghĩa, clarity/naturalness kèm n, critical và unsafe-spoken nếu thực đo được. Aggregate dùng mẫu số thật, không lấy trung bình hai tỷ lệ có n khác nhau.

ASR WER/CER cần audio-reference transcript; chrF/BLEU cần target reference đúng phiên bản. Ghi normalization, tool/version/signature nếu dùng; chưa có tool/ref thì `Not measured`, không thêm dependency hoặc chạy corpus lúc này. Metric văn bản không thay chứng minh chất lượng giọng nói.

200 câu BTC đã được xem metadata nên gọi supplied/exposed evaluation set, không gọi blind holdout. Fresh human acceptance cần speaker/session tách dev, chưa đưa vào glossary/memory để “học đáp án”. Mọi số điểm vẫn pending cho tới khi có output và người chấm thật.
