# Đối chiếu nguồn và license trước đóng gói

Ngày kiểm tra15/10/2026. Inventory này dựa trên source HEAD và metadata cache nhỏ, chưa phải danh sách binary thực ship. Không hash/đọc weights, không tải AAR hoặc đổi model. Không có component nào được tự đánh dấu `cleared` chỉ vì tìm thấy một nhãn license.

## Model và dữ liệu đã đối chiếu

| Component | Bằng chứng hiện có | Còn phải làm |
|---|---|---|
| PhoWhisper Base | local source.json pin `7ebdb9e88f5cc5271fb88f4d642c82ff9388650e`, FP32/shared weights; [card cùng revision](https://huggingface.co/vinai/PhoWhisper-base/blob/7ebdb9e88f5cc5271fb88f4d642c82ff9388650e/README.md) ghi BSD-3-Clause | lấy license text/notices tương ứng, giữ citation; map bản export và file thực ship |
| Whisper Small | local pin `973afd24965f72e36ca33b3055d56a652f456b4d`, FP32/shared weights; [card pinned](https://huggingface.co/openai/whisper-small/blob/973afd24965f72e36ca33b3055d56a652f456b4d/README.md) ghi Apache-2.0 | kiểm tra license file và nguồn weights/conversion đúng revision; không suy nhãn từ tên Whisper hoặc gán MIT theo trí nhớ |
| OPUS VI→EN/EN→VI | selected INT8 graphs; [VI→EN card](https://huggingface.co/Helsinki-NLP/opus-mt-vi-en/blob/main/README.md), [EN→VI card](https://huggingface.co/Helsinki-NLP/opus-mt-en-vi/blob/main/README.md) hiện ghi Apache-2.0 | local hai tree không có source.json; exporter cũ không pin revision; code15/10 đã pin/ghi provenance cho future exports nhưng không sửa cache hiện có. Cần recover provenance export từ log/cache; `main` hiện tại không chứng minh revision cũ |
| Silero VAD | fetcher định dùng tag5.1.2 nhưng có HF fallback, local không source.json; [license tag5.1.2](https://github.com/snakers4/silero-vad/blob/v5.1.2/LICENSE) là MIT | xác định local file lấy từ nguồn nào, revision/hash và notice. Kích thước/đầu protobuf chỉ kiểm tra sơ bộ, không là provenance |
| VI Piper vais1000-medium | local MODEL_CARD ghi dataset CC BY4.0; source.json có archive hash `fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a` | giữ card, attribution và nguồn conversion/voice; card ghi finetune từ lessac-medium nên cần rà cả chuỗi nguồn, không suy dataset license bao trùm mọi thành phần |
| EN Piper lessac-medium | local card khớp nội dung [voice card upstream](https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/lessac/medium/MODEL_CARD); chưa có local source.json | quyền phân phối voice chưa chốt; cần exact archive/revision và điều kiện tương ứng |
| Cached prompts VI/EN | bốn PCM + sample-rate side files, tạo từ TTS | ghi voice/version/script/text; rà quyền chia sẻ audio phát sinh và attribution, không coi cache vô nguồn |
| BTC200 | workbook/metadata/listings đã đọc, chưa WAV | chưa thấy giấy phép chia sẻ lại trong các metadata đã kiểm tra; hỏi BTC phạm vi evidence/package/public repo, không tự gán CC |
| Tập nói thật/video | chưa thu trong chặng này | consent người nói/phạm vi BGK/công khai, lưu original local và bản đã xử lý thông tin riêng tư khi cần |

## Điểm cần làm rõ về Lessac

Voice card dẫn tới [điều khoản dữ liệu Blizzard2013/Lessac](https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html), trong đó nêu sử dụng nghiên cứu, hạn chế chuyển giao và mục đích thương mại đối với Materials được định nghĩa. Đây là điều khoản của dữ liệu nguồn; chỉ trang này chưa đủ kết luận quyền phân phối trained voice/conversion hoặc audio sinh ra.

Vì vậy giữ `unresolved` cho voice/payload liên quan cho tới khi có căn cứ phù hợp. Việc repo piper-voices hiển thị MIT ở cấp repo không giải quyết tự động điều khoản riêng trong voice card. Bước tiếp: tìm thông tin phát hành/ủy quyền cho chính voice artifact, lưu bằng chứng; nếu không làm rõ kịp thì đánh giá phương án có nguồn rõ bằng cùng test VI/EN trước khi đổi, không thay model âm thầm để đóng hồ sơ. Không tự gửi thư hoặc chấp nhận điều khoản thay người dùng.

## Native/runtime và dependency

HEAD Android Gradle pin `onnxruntime-android-qnn:1.20.0` và sherpa static AAR1.13.3. [ORT license1.20.0](https://github.com/microsoft/onnxruntime/blob/v1.20.0/LICENSE) là MIT; [sherpa license1.13.3](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.3/LICENSE) là Apache-2.0. Đây chưa là inventory của mọi native library trong AAR/APK.

Voice bundles có espeak-ng-data. [eSpeak NG upstream COPYING](https://github.com/espeak-ng/espeak-ng/blob/master/COPYING) có GPLv3; chưa xác định revision/code/data/notice thực được gói trong các binary hiện dùng. Cần inspect exact AAR source/build/third-party notices và cách liên kết, thay vì kết luận toàn APK có license nào từ một file upstream hoặc chỉ gọi nó Apache.

Các dependency trực tiếp khác thấy trong HEAD: sentencepiece4j1.0.2, org.json20240303, Room2.6.1, SQLCipher4.5.4, SQLite2.4.0, security-crypto1.1.0, Compose BOM2024.06.00, Activity1.9.0, Lifecycle2.8.7, coroutines1.8.1, Material1.12.0 và core project. Khi source/build sẵn: xuất dependency graph thực của competition runtime, phân biệt build/test dependencies, thu POM/license/notices và transitive native components. Chưa resolve/download Gradle trong chặng này.

Không thấy LICENSE/NOTICE được track qua `git ls-files '*LICENSE*' '*NOTICE*'` ở snapshot hiện tại. Đây không chứng minh dự án không có quyền sử dụng; nghĩa là chưa có license bundle source được xác nhận cho gói phát hành. Không tự chọn license cho code của đội hoặc public repo thay người dùng.

Recovery local15/10: không tìm được revisionMT/VAD/EN voice trong các metadata/cache roots đã kiểm tra; EN config xác định identity chứ không quyền phân phối. Xem [recovery và future exporter fix](../../../docs/development/export-provenance.md).

## Gate trước phân phối

Developer lập `licenses.csv` thực cho exact files: revision/hash, license text/source, attribution/notices, điều kiện chưa rõ, người kiểm tra và ngày. Trạng thái dùng `unresolved|reviewed_conditions_pending|cleared_for_intended_distribution|excluded`; chỉ cleared khi intended use/phạm vi chia sẻ và điều kiện đã có bằng chứng.

Đối chiếu inventory với actual APK/model package, không chỉ cache. Giữ license/notices trong gói kể cả khi Gradle bỏ resource META-INF hoặc staging bỏ `.md`. Kiểm tra không có key/token/PII, còn câu hỏi BTC/public source phải được ghi quyết định riêng. Không xem việc tìm thấy trang license là đã hoàn tất gate này.
