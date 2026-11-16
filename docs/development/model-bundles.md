# Staging selected models: kế hoạch và gate

Phạm vi tools/dev/model_bundle.py, không đổi runtime/profile/model/threshold. Không stage model thật trong chặng này; tests chỉ cache giả trong TemporaryDirectory.

## Lỗi đã thấy

Copy gần nguyên selected tree làm unselected graphs/debug/cache có thể lọt vào APK. Ignore `*.md` làm mất notices/model cards Markdown; VAD/cache/root notices còn không được copy. Piper runtime chọn first *.onnx nên nhiều graph trong một voice là ambiguous; validate hiện còn nhận graph rỗng hoặc espeak directory rỗng.

## Thiết kế nhỏ trước code

1. Chọn từng file theo contract VI/EN: VAD; hai selected ASR với3graphs/shared weights/tokens/mel/BPE/provenance; hai MT chỉ3quantized graphs và tokenizer; mỗi voice đúng1nonempty graph/tokens/config đi kèm/espeak data;4cached prompts+rates.
2. Giữ metadata có tên xác định (source/io/config/tokenizer metadata) và notices/license/model-card/README nhận diện được trong selected components, VAD/cache và root. Giữ LICENSES/NOTICES documents; không bỏ hết `.md`. Đây chưa là license clearance hoặc bảo đảm mọi notice upstream đã có.
3. Loại unknown graph/archive/debug/hidden files và ngôn ngữ khác; reject selected symlink trỏ ngoài source/destination cache roots, không follow để copy nội dung bên ngoài.
4. `--prepare-asr` vẫn giữ selected ASR có sẵn ở destination khi source chưa có; chỉ là preparation, không full model-ready gate. Marker legacy SMOKE_VI_EN giữ tương thích notebook, runtime production profile vẫn vi-en-phase2 trong Gradle/ModelProfile.
5. Stage/validate vào temp trước thay destination; copy/validation failure giữ destination cũ. Phát hiện thêm: publish đang remove destination trước move mới, có thể mất gói cũ khi move lỗi. Trước khi sửa, bổ sung hai regression tests: publish lỗi phải restore gói cũ; nếu restore cũng lỗi thì backup phải còn ở sibling path ngoài auto-cleanup tempdir. Dùng backup/restore có đường phục hồi, không hứa atomic exchange hoặc an toàn khi mất điện.

## Test và giới hạn

Tests runtime files copied/extra excluded/notices retained; ambiguous/empty voice/espeak reject; prepare-ASR fallback; copy failure preserves destination; selected symlink escape reject; source/destination overlap reject. Viết đỏ trước implementation, chạy tiny fixtures. Sau đó có thể liệt kê selected cache files + stat bytes read-only, không hash/đọc weights hay copy GB.

File selection/structure không chứng minh ONNX hợp lệ, full espeak completeness, no secret trong nội dung docs hoặc quyền phân phối. Full cache validation chưa là current binary/source/hash/Android acceptance. Exact shipped hashes/notice review/signature/install/offline gates vẫn theo package-contract.

## Kết quả15/10

11tests staging PASS, có red-before-green cho file dư/notices, symlink escape, empty/ambiguous voice/espeak, JSON source root/empty shared weights, publish/restore failures và destination không phải directory. Fixture restore-failure phải so đường dẫn đã resolve vì macOS có alias /var↔/private/var; đã sửa fixture, không sửa production để né test.

Read-only inventory current cache chọn773files,2,117,562,953bytes (gồm runtime/metadata/notices/espeak), mất0.067s; không copy/hash/read weight contents. Đây là selected local staging input, không là kích thước APK hoặc measured installed storage. Current model provenance/license chưa cleared; selected inventory không giải quyết chúng. Full tools suite73PASS2.794s; raw inventory/tests/source hashes ngoài Git ở output/phase3/model-staging-check-20261015/.

Sau hợp nhất partner15/10, staged bundle ghi integrity.sha256 trước publish; regression kiểm hash/bytes từng selected payload. Tổng12staging tests, chưa actual bundle/install.
