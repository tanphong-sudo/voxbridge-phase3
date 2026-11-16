# Thông báo BTC OneVoice AI Challenge — tổng hợp đến 10/11/2026

Nguồn: email của `info@saigonaihub.com` (Saigon AI Hub / VNG), forward từ hộp thư trường và hộp thư nhóm. Đọc toàn bộ 7 thread `[OneVoice AI Challenge]`, mail cuối cùng BTC gửi là ngày 05/11/2026. File này chỉ ghi lại nội dung BTC đã thông báo; yêu cầu chi tiết của vòng thi vẫn theo [Brief](templates/1V_P3_Prototype_Brief.pdf) và [ma trận yêu cầu](requirements.md).

## Vòng hiện tại: Phase 3 — Prototype Development / Submission

| Mục | Thông tin |
|---|---|
| Kết quả Phase 2 | Đội **được chọn vào Phase 3** (mail 20/09/2026) |
| **Hạn nộp mới** | **Hết ngày 16/11/2026** (gia hạn từ 13/11/2026, mail 05/11/2026) |
| Mục tiêu | Từ Technical Proposal Phase 2, dựng, thử nghiệm và tối ưu prototype dịch thuật on-device (tinh thần Build-to-Deploy) |
| Kênh hỏi đáp | Reply mail `info@saigonaihub.com`; Facebook group OVAC Builder Community |

> Lưu ý: [README Phase 3](README.md) và [requirements.md](requirements.md) vẫn ghi hạn 13/11/2026, đã cũ sau mail gia hạn.

### Tài liệu và cách nộp (mail Phase 3 Guidelines, 14/10)

Phase 3 là biến proposal Phase 2 thành **prototype chạy trên thiết bị thực tế**, kèm minh chứng để BGK đánh giá kết quả và tiến độ.

1. Đọc [Phase 3 Brief](https://drive.google.com/file/d/1QTnXTz3jPpTyXVNbrFcWA9JrShXx12tB/view?usp=sharing): yêu cầu, cách nộp, các mốc. Bản PDF đính kèm mail (`1V_P3_Prototype_Brief.pdf`) đã có trong [templates/](templates/1V_P3_Prototype_Brief.pdf).
2. Điền [Prototype Submission Template](https://docs.google.com/document/d/1XIet4NzISCvfNRYsF06FWkEordVJetCbqiCZlrIRUtg/edit?usp=sharing) **bằng kết quả thực tế**.
3. Nộp báo cáo và tài liệu qua [Form nộp bài](https://forms.gle/Qhdni9hdFm19ZJfF6), kèm cập nhật ngắn nếu **đổi thành viên hoặc định hướng kỹ thuật** so với Phase 2.

Mail này ghi hạn 13/11/2026 23:59 (UTC+7); đã được gia hạn thành hết 16/11/2026 bởi mail 05/11. Mail cũng báo thiết bị sẽ đặt tại [không gian SAIH](https://maps.app.goo.gl/8uiKMj8djHUFcUbr9) và khuyến khích đội dùng Qualcomm thử trước trên AI Hub/QDC. Nội dung workshop dự kiến: Qualcomm SDK/QNN, triển khai và tối ưu model trên thiết bị, Q&A trực tiếp.

### Thiết bị kiểm thử tại VNG Campus (mail 05/11)

- Khoảng **8–9 thiết bị**, 3 dòng chạy nền tảng Qualcomm:
  - [HSPTEK AIBOX 6490](https://hsptek.com/vi/san-pham/ai-box-6490/) — Qualcomm QCS6490
  - [HSPTEK AIBOX 8550](https://hsptek.com/vi/san-pham/ai-box-8550/) — Qualcomm QCS8550
  - [Qualcomm RB3 Gen 2 Development Kit](https://www.qualcomm.com/developer/hardware/rb3-gen-2-development-kit) — Qualcomm QCS6490
- Dự kiến sẵn sàng trong **tuần 09–15/11/2026**. Chậm vì thiết bị đang chuyển từ Hà Nội vào TP.HCM và cần bảo trì, cấu hình.
- **Chưa có lịch.** BTC sẽ email lịch kiểm thử, cách đăng ký và hướng dẫn sử dụng. **Không đến VNG Campus trước khi được xác nhận lịch.** Đến 10/11/2026 hộp thư chưa có mail lịch này.
- Đội ở Hà Nội: liên hệ anh Khanh Lê (Qualcomm), `vankle@qti.qualcomm.com`, CC `info@saigonaihub.com`.
- Trong lúc chờ: tiếp tục test trên thiết bị tự chuẩn bị và nền tảng test từ xa.

### Điểm BGK/BTC xác nhận tại Technical Workshop 23/10 (mail 26/10)

1. **Test từng bước trước khi dùng thiết bị tại chỗ:** compile, tối ưu và profile model trên [Qualcomm AI Hub](https://aihub.qualcomm.com/), sau đó test app qua [Qualcomm Device Cloud](https://qdc.qualcomm.com/devices). Khi xin quyền QDC, ghi rõ **"OneVoice AI Challenge"** để được duyệt ưu tiên. Thiết bị tại chỗ có hạn nên cần làm bước này trước.
2. **Không giới hạn thiết bị:** được dùng thiết bị hoặc module tự chuẩn bị, không bắt buộc là thiết bị Qualcomm. Điều kiện: chạy trên phần cứng thật và **toàn bộ inference offline trên thiết bị**.
3. **Được đổi model, thiết bị hoặc form factor** so với proposal Phase 2, nhưng phải ghi rõ thay đổi và lý do trong Technical Submission.
4. **Đo toàn bộ pipeline:** với giải pháp nhiều model, kết quả **end-to-end là cơ sở chấm chính**; số đo từng model chỉ để giải thích. Phải nêu rõ công cụ, phương pháp, cấu hình thiết bị và môi trường phần mềm.
5. **Bằng chứng kiểm chứng được:** video thể hiện rõ chạy offline, hạn chế cắt dựng; gói cài đặt và hướng dẫn đủ rõ để BGK tự chạy. **Chất lượng dịch và độ tự nhiên giọng nói vẫn là trọng tâm**, bên cạnh hiệu năng và trải nghiệm.

Bản ghi workshop: [Zoom recording](https://vngcorp.zoom.us/rec/play/3_uX2i6W3WpI4E4loDi0lRpfEyOviNIakm-ssecK-gj8rx3oTGKAkipv1v4PKG9Q-6QM5Ibg6ee9EY0G.K2bgsiZ8QKe1Uzha), mật khẩu `261023-OVAC-P3`.

### Form đã yêu cầu (đều đã quá hạn)

| Form | Hạn | Ghi chú |
|---|---|---|
| [Khảo sát trước workshop](https://forms.gle/mARP2tpGyrtJua5o7) | 21/10/2026 23:59 | Nền tảng phần cứng, nhu cầu thiết bị thật, câu hỏi cho BTC |
| [Khảo sát nhu cầu thiết bị](https://forms.gle/X5vms3zLtaYcx2KfA) | 28/10/2026 | Đội đã trả lời chỉ cập nhật nếu có thay đổi, ghi chú "Cập nhật sau workshop" |

Hộp thư không cho biết đội đã nộp các form này hay chưa; cần người nộp tự xác nhận.

## Đối chiếu repo với yêu cầu nộp bài (tính đến 10/11/2026)

Còn **6 ngày** đến hạn 16/11. Repo (cả `origin/main` và các branch) **không có commit nào sau 15/10/2026**, nên trạng thái dưới đây lấy từ [STATUS.md](STATUS.md) và [requirements.md](requirements.md) cùng ngày. Nếu có việc làm ngoài Git thì chưa phản ánh ở đây.

### Repo đã có

- App Android VI↔EN dùng ML Kit: compile được, 69 core + 36 Android unit test PASS trên host. **Chưa chạy trên thiết bị thật.**
- Sửa replay/playback, safety logic giả lập 51/51 quyết định khớp.
- Whisper Small W8A16 đã profile trên NPU Galaxy S24 Ultra qua AI Hub ([evidence](evaluation/qualcomm-graph-profile.json)): encoder 45,6 ms, decoder 6,4 ms/token. Chỉ là số đo từng graph, không phải end-to-end.
- TTS VieNeu Nano chạy trên Mac (host adapter), người dùng nghe thích hơn Piper. Chưa port lên Android.
- Số đo lịch sử Mac/OPUS 27/09 (WER EN 10,15%, VI 13,04%; latency p50/p95 1883/3417 ms). **Không dùng được làm số Android/ML Kit.**
- Bộ khung hồ sơ: Brief + template gốc, [báo cáo Word V3 (15/10, chưa xong)](<T&P Team_Prototype Submission_V3.docx>), [hướng dẫn 35 trường](report-guide.md), [kịch bản demo nháp](demo.md), [dataset](evaluation/dataset.md), biểu mẫu CSV, [package](package/README.md), [licenses](package/licenses.md), công cụ `tools/phase3`.

### Còn thiếu so với tiêu chí bắt buộc

| Yêu cầu Brief | Hiện trạng | Cần làm |
|---|---|---|
| Chạy trên phần cứng thật (§3) | Chưa chốt thiết bị, chưa có APK mới | Giải phóng ổ đĩa, build `assembleDebugCpu`, cài lên máy thật (điện thoại đội hoặc QDC / máy VNG Campus) |
| Inference on-device, không API/cloud (§3) | ML Kit lần đầu phải tải model; cài mới hoàn toàn offline chưa chứng minh | Đóng gói model sẵn hoặc ghi rõ bước setup; thử cài sạch khi tắt mạng |
| Giao diện để giám khảo tự thao tác (§3) | Mới thử trên emulator | Thử thao tác thật trên máy: mic, loa, lifecycle |
| Ít nhất 1 cặp ngôn ngữ đã đăng ký, kịch bản ~30 phút (§4) | Code VI↔EN; kịch bản nháp chưa tập | Xác nhận cặp đã đăng ký; tập và đo thời lượng kịch bản |
| Chất lượng dịch/giọng nói — 45% điểm | `ratings.csv`, `reference-review.csv` mới có header; 200 WAV BTC chưa tải/kiểm đủ; 5 lỗi safety ASR chưa sửa | Chạy bộ mẫu BTC trên máy, chấm rating thật, ghi ví dụ lỗi |
| Kích thước model, latency, peak memory — 20% | Không có số đo trên thiết bị đích; `memory.csv` trống | Đo end-to-end trên máy, nêu rõ công cụ, cấu hình, môi trường (workshop yêu cầu) |
| Tích hợp/UX — 20% | Chưa nghiệm thu trên máy thật | Ghi log task, lỗi, phục hồi khi demo |
| Video liền mạch, có cảnh tắt mạng, thấy thời gian thực (§7.1) | Chưa quay | Quay sớm, một take, ít cắt — cũng là phương án dự phòng nếu BGK không cài được |
| Gói cài offline đầy đủ + hướng dẫn (§7.2, §7.4) | Chưa có signed APK | Ký APK, đóng gói model/config, viết hướng dẫn cài |
| Benchmark ngắt mạng + raw log (§7.3) | Chưa có | Lưu log/ảnh theo [quy trình đo](../../docs/testing/measurement-protocol.md) |
| Báo cáo theo template, ghi thay đổi so với Phase 2 | Bản V3 chưa có số thật | Điền bằng số đo thật; ghi đổi sang ML Kit và lý do |
| Source GitHub public (§8) | Repo đang private | Quyết định public hoặc hỏi BTC ngoại lệ |
| Nộp form + cập nhật thành viên/định hướng | Chưa nộp | Người đại diện nộp trước 23:59 16/11 |

Thứ tự nên làm (theo STATUS.md): build APK → chạy trên máy thật → đo và chấm bộ mẫu BTC → quay video → đóng gói + điền báo cáo → kiểm link/quyền truy cập → nộp. Port TTS VieNeu lên Android và đồng bộ runtime QNN (ORT 1.30/QAIRT 2.50 so với app 1.20) là việc lớn, khó kịp trong 6 ngày; nên ghi vào roadmap nếu chưa xong.

## Dòng thời gian

| Ngày | Sự kiện |
|---|---|
| 10/07/2026 | Vào Phase 2 — Technical Submission |
| 21/07/2026 | BTC gửi [Technical Proposal Template](https://docs.google.com/document/d/1M7nnGvdhpwYkhkMyCVHwKkOajexjNL3MvfPEFV0E_QM/edit?usp=sharing), form nộp |
| 07/08 và 14/08/2026 | Phase 2 FAQ ([Facebook VI](https://www.facebook.com/share/p/1CRGw7AkPJ/), [LinkedIn EN](https://www.linkedin.com/feed/update/urn:li:activity:7491354573710745601)) |
| 21/08/2026 | Hạn nộp Phase 2 |
| 20/09/2026 | Kết quả Phase 2: vào Phase 3 |
| 14/10/2026 | Phase 3 Guidelines: Brief, template báo cáo, form nộp bài |
| 20/10/2026 | Mời Technical Workshop với Qualcomm |
| 23/10/2026 17:00–18:00 | Technical Workshop (Zoom) |
| 26/10/2026 | Tóm tắt workshop, link recording, form nhu cầu thiết bị |
| 05/11/2026 | Cập nhật thiết bị VNG Campus, **gia hạn nộp đến 16/11/2026** |
| 09–15/11/2026 | Dự kiến thiết bị tại VNG Campus sẵn sàng (chờ mail lịch) |
| **16/11/2026** | **Hạn nộp Phase 3** (gốc 13/11 23:59) |
| 23/11/2026 (dự kiến) | BGK chấm, đối chiếu benchmark, công bố kết quả |
| 28/11/2026 (dự kiến) | Field-Testing: kiểm tra trực tiếp trên thiết bị; đội vào vòng này phải bổ sung cặp ngôn ngữ |
| 05/12/2026 (dự kiến) | Chung kết tại VNG Campus, TP.HCM |

Ba mốc cuối lấy từ Brief (§1, bản 14/10). Mail gia hạn 05/11 chỉ đổi hạn nộp, chưa nói gì về việc các mốc này có lùi theo hay không.

## Phase 2 (đã xong, để tham chiếu)

- Thời gian 1 tháng, nộp Technical Proposal theo template qua [Google Form](https://forms.gle/fhLAX767Z9hQMitZA).
- Proposal cần làm rõ: bài toán, kiến trúc hệ thống và hướng tiếp cận, cách tích hợp Edge AI, tính khả thi và tiềm năng triển khai.
- Tiêu chí nhấn mạnh: **AI Approach & Technical Design** (pipeline, chọn model, tối ưu on-device), **Hardware & Device Concept** (nền tảng phần cứng, công suất, form factor), **Business Solution** (khoảng trống thị trường, khác biệt). Có prototype sớm được cộng điểm theo tiêu chí 5, tối đa 10%.

## Việc cần làm từ các thông báo

- [ ] Theo dõi mail lịch thiết bị VNG Campus và đăng ký slot ngay khi có.
- [ ] Đảm bảo đã có tài khoản AI Hub/QDC; xin quyền QDC có ghi "OneVoice AI Challenge".
- [ ] Ghi các thay đổi so với proposal Phase 2 và lý do vào Technical Submission.
- [ ] Đo và báo cáo latency end-to-end, kèm công cụ, cấu hình thiết bị, môi trường phần mềm.
- [ ] Quay video offline liền mạch, ít cắt; gói cài đặt và hướng dẫn BGK tự chạy được.
- [ ] Nộp qua [form](https://forms.gle/Qhdni9hdFm19ZJfF6) trước hết ngày 16/11/2026, kèm cập nhật thành viên/định hướng nếu có thay đổi.
