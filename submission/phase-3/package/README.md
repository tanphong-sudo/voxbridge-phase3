# Hợp đồng gói cài và bằng chứng release

Trạng thái: chuẩn bị, chưa tạo/cài nghiệm thu gói Vòng3. Đọc cùng section03/section06; thư mục đích dự kiến `output/phase3/<release_id>/`, ngoài sourceGit. Không chép model hoặc ký APK trong chặng nhẹ này.

## Quyết định trước khi đóng gói

Source Android hiện thiếu46 tracked files; phải xác nhận đúng source trước build. Chọn bundled competition APK hoặc APK+model import dựa trên đường cài có thật. HEAD có buildType competition dùng bundled assets; chưa có chứng cứ signing configuration. DebugCpu dùng external dev cache nên chưa phải installer offline hoàn chỉnh.

Ghi một release ID gắn source commit đầy đủ, versionCode/name, app ID, model profile, device/ABI/API đã kiểm thử, model/config revisions, APK hash và signing certificate fingerprint. Khóa đúng bản quay và bản gửi. Signing key/password ở ngoài repo/package, không cần chia sẻ với BGK.

## Danh sách file được phép đưa vào

| Artifact | Điều kiện chốt |
|---|---|
| Signed APK | signature verify; package/version/ABI đúng; không fake pipeline; permissions từ merged manifest; actual CPU/QNN provider được ghi đúng |
| Model payload nếu không bundled | danh sách từng file/bytes/hash, đúng VI/EN selected profile và side files; installer/import có thật và dùng được offline |
| README cài/running | filename thực, đủ bước chuẩn bị→ngắt mạng→mở→PTT hai chiều→lỗi thường gặp; người khác làm theo được |
| Model/release manifest + SHA256SUMS | exact files; models/shared weights count một lần; raw APK/model hash khớp ledger |
| THIRD_PARTY_NOTICES + license texts | inventory theo binary thực ship, bao gồm native/transitive/data/voices; unresolved phải được xử lý trước phân phối liên quan |
| Evidence index | turn/log/video timestamp và scope; không trộn host cũ với target; link chỉ điền sau upload/check quyền được cho phép |
| Input mẫu nếu cần | có quyền chia sẻ; không tự kèm toàn BTC audio hoặc voice data chưa consent |

Không dùng copy toàn cache/repo. Loại virtualenv, build intermediates, backup FP32 MT không ship, archive nguồn dư, old language/profile, keys/tokens và raw reports/audio không cần thiết. Có danh sách exclusion rõ, nhưng không loại license/model card bằng đuôi `.md` một cách mù quáng.

## Kiểm tra chỉ đọc trước khi thử cài

`tools/dev/inspect-apk.sh` mặc định không liên hệ ADB. Khi có APK thật và Android build-tools, chạy từ repo root:

```sh
bash tools/dev/inspect-apk.sh /absolute/path/to/submitted.apk /absolute/path/to/build-tools
```

Script hiện kiểm tra signature/badging, thống kê ZIP và đúng hai thư mục ASR; nó chưa tự chứng nhận đủ mọi file/profile, không INTERNET, tương thích native, không fake pipeline hoặc cài thành công. Những mục đó vẫn cần kiểm tra riêng trên actual build. Hash helper hiện dùng `sha256sum`; máy thiếu cần cung cấp công cụ tương ứng trước khi chạy.

Chỉ khi đã chọn máy thử và muốn cài, thêm `--install-device SERIAL` vào lệnh trên. Script nhắm đúng serial và kiểm tra trạng thái trước `adb install`; không gỡ app/xóa data. Cài thử vẫn cần người vận hành cho phép thay app trên máy đó. Không dùng lệnh này như chứng minh clean install.

## Test cài thực: tách từng gate

1. Chuẩn bị folder sạch và đủ local files, kiểm tra hashes; ghi actual storage trống. Tính free-space theo APK/archive + extracted data + working headroom đo được, không lấy kích thước model làm toàn storage requirement.
2. Trên máy/session được phép, cài từ package; ghi permission, extraction, Ready và cold startup. Không dùng source/model cache sẵn có của developer làm đường tắt.
3. Ngắt Wi-Fi/mobile data ngay trước live demo, ghi trạng thái thật; khởi động app lại và nói5 câu mỗi chiều qua mic/loa. QDC file-input được ghi riêng.
4. Kiểm tra restart, missing/corrupt payload có thông báo đúng và cách sửa local; data reset chỉ khi được phép, có giải thích mất gì.
5. Một người khác làm theo README; ghi thao tác cần trợ giúp/lỗi/thời gian. Gói chưa qua bước này để `not_validated`.
6. Nếu upload được yêu cầu: tải lại qua link như người nhận, kiểm tra hashes/access, không suy quyền chia sẻ từ file đang mở trên tài khoản chủ.

## Điểm source đã thấy cần xử lý khi có build

- `model_bundle.py` đã chọn per-file runtime/metadata/notices cho VI/EN và giữ Markdown legal docs;11tiny fixture tests. Publish failure có backup/restore, restore failure giữ backup ở sibling path để phục hồi; chưa bảo đảm atomic exchange/mất điện/concurrency. Chưa stage model thật. Xem model-staging-contract.md; source/licensing/binary gates vẫn mở.
- ASR có pinned provenance, MT/VAD/EN voice thiếu source.json local. Không tự điền latest revision như thể đó là revision đã export.
- Gradle HEAD loại META-INF/AL2.0,LGPL2.1: phải có notices/license bundle ngoài các resource bị loại và kiểm tra binary thực. Một nhãn license tổng của AAR không giải quyết mọi native dependency.
- Public repo theo brief/template còn cần BTC xác nhận; không public repo/weights để lấp một ô báo cáo khi chưa có quyết định.
