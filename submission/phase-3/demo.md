# Kịch bản rehearsal VoxBridge

Draft protocol, chưa phải video/result. Dự kiến khoảng30 phút, điều chỉnh số turn sau rehearsal actual. Hai vai VI worker và EN supervisor/colleague; chỉ role-play, không điều khiển thiết bị công nghiệp thật. Người nói đồng ý thu/chia sẻ giọng; ghi rõ scripted vs spontaneous.

## Chuẩn bị ngoài take

Chốt release/device/model hashes, cài từ local package, đủ pin/storage, kiểm tra camera thu giọng và speaker. Không pre-seed memory bằng đáp án của acceptance. Người không viết app đọc README trước; không dạy họ từng button giữa take trừ khi cần, mọi assistance ghi log.

## Mở take và chứng minh offline

Quay actual device/build, mở Settings tắt Wi-Fi/mobile data/airplane theo route, quay lại appReady. Mốc disconnect ghi timestamp sau khi quay. Không dùng mốc dự kiến như actual; nếu kết nối tự bật lại phải ghi hoặc quay take mới đầy đủ. QDC rehearsal gắn input=file và không được coi live disconnected demo.

## Khối hội thoại

| Thời gian dự kiến | Công việc | Ví dụ câu VI/EN độc lập theo lượt | Điều phải quan sát |
|---|---|---|---|
|0–5 phút|Giao ca/giải thích nhiệm vụ|“Hôm nay chúng ta kiểm tra khu đóng gói.” / “Please show me where the new cartons are.”|hai chiều, Ready/Listening/Processing/Speaking, người nghe hiểu|
|5–12|Số lượng/mốc giờ/vị trí|“Có mười hai thùng ở kệ B bảy.” / “Move only three boxes after the inspection at two thirty.”|quantity, IDs, only, sequence/time; không hành động thật|
|12–20|Hướng dẫn/đối chiếu|“Đừng mở van số bảy trước khi kiểm tra.” / “Do not restart machine four until the guard is closed.”|negation, action, ID; nếu uncertain phải repeat, không ép nói|
|20–25|Tạp âm nhẹ/recovery|“Xin nói lại chậm hơn.” / “Could you repeat the last number?”|ghi actual fan/background, repeat source language, không blame user|
|25–30|Câu mới và hoàn tất|Người tham gia tự hỏi vị trí/thời gian hoặc logistics chưa trong script; tổng kết giao ca|không scripted lookup, long text/replay, help/restart/failures|

Ví dụ là prompt hội thoại, không là target references vàng và không nhúng vào model/runtime. Mỗi block có nhiều câu trao đổi tự nhiên, không chờ giả để đủ phút; nếu tốc độ chậm ghi actual duration/turn count và chỉnh scenario trước final take.

## Các tình huống cần ghi nếu xuất hiện

- NO_SPEECH/ASK_REPEAT: không có translation audio, prompt đúng source; replay không phát stale result.
- REPAIRED: text chờ user kiểm tra, confirm chủ động mới speak; không chạm nút confirm tự động để chứng minh case.
- TTS failure: text còn đọc được, báo nguyên nhân/recovery, log giữ nguyên.
- Memory nếu claim: reviewer sửa một câu, explicit approval, lặp query sau đó và kiểm tra reused target qua safety; gắn learning demo riêng, không dùng nó để chấm neural general quality.
- Confirmed safe/verified label chỉ là kiểm tra slots, không hướng dẫn người tham gia tin tuyệt đối nội dung.

## Sổ phiên

Ghi planned/actual start/end, device/network facts, release, source directions, turn IDs, input type, spoken duration, output emitted, repeat/repair/help/restart, errors và observer notes. Người chấm lấy raw audio/transcript sau đó; không sửa model output trong take.

## Điều kiện qua rehearsal

Hoàn tất ~30 phút không crash/restart ngoài ý muốn, evidence mỗi turn truy được, người không viết app tự thao tác được, hai chiều có actual voice-to-voice. Không có unsafe spoken trên labeled safety observations; nếu gặp thì FAIL và giữ reproduction. Năm câu safety lỗi cũ phải có regression ngoài video; một take đẹp không đủ thay acceptance corpus.
