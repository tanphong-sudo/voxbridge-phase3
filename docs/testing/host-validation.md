# Kiểm tra và tối ưu host — 15/10/2026

Baseline source: `a583a16100407ae790760637a01b427ee2e5a68d`.
Không chạy lại 1303 mẫu. Raw logs/audio nằm trong ignored
`output/host-fixes-20261015/`, không đưa model hoặc WAV vào Git.

## Lỗi đã sửa và cách kiểm tra

- Replay trước đây phát trực tiếp text đang hiển thị, vượt qua xác nhận của câu
  repaired. Nay replay chỉ nhận sự kiện được policy của chính decision cho phép;
  không phát khi chờ xác nhận, đang nghe hoặc đang xử lý. Hủy session khi đổi hướng
  hoặc bắt đầu lượt mới. Hai test callback dùng source baseline thực sự FAIL;
  cùng test với source hiện tại PASS.
- Trước đây UI về READY ngay sau khi queue âm thanh. Nay chờ playback drain và
  release; synthesis cũ bị hủy không được phát hoặc ghi đè trạng thái lượt mới.
  Lỗi write/stop/release được trả về thay vì báo thành công hoặc treo chờ. Test
  stop failure đã tái hiện timeout trước patch, sau patch trả lỗi và release đúng.
- Cache safety thiếu hoặc PCM rỗng được báo lỗi, không giả vờ đã phát.
- Nút replay/confirm bị vô hiệu khi bận. Diagnostic play cũng kiểm trạng thái.
- Sửa danh sách source của E2E JVM thiếu `ModelIntegrity.kt`; compile-only từng
  fail với unresolved reference, nay compile PASS.

**117/117 JUnit host PASS**, gồm core, benchmark, Android logic thuần được chọn,
AudioOut thật trên AudioTrack fake và method body callback/playback trích nguyên
văn từ ViewModel. Đây không phải full ViewModel/Compose compile hoặc test thiết bị.
Python suites: benchmark **37**, device tools **19**, Phase3 tools **76**, đều PASS.
Lệnh và ranh giới stub: [host-tests](../../tools/host-tests/README.md).

## Tối ưu mel frontend CPU

Mel filters có 391/16080 trọng số khác 0. Bỏ phép nhân các đuôi bằng 0, giữ thứ tự
cộng và mọi xử lý âm thanh. Output bằng nhau **từng bit trên 8 tín hiệu**, gồm
empty/single-sample, sát biên frame và pad/trim 30 giây. So paired warm calls,
21 lần mỗi input, cùng JDK/filter/input và source baseline nêu trên:

| Độ dài input | Median trước | Median sau | Tăng tốc riêng mel |
|---|---:|---:|---:|
|1 giây|2,811 ms|1,870 ms|1,50×|
|3 giây|8,239 ms|5,441 ms|1,51×|

Đây là giảm khoảng 34% thời gian **riêng mel**, chỉ vài ms mỗi câu; không phải
pipeline nhanh hơn 34%. Không thay trọng số hoặc quality threshold.

## ASR thật: chẩn đoán nhỏ, không lấy làm acceptance

Chạy 7 WAV synthetic hiện có: 5 lỗi safety lịch sử và 2 câu so sánh bổ sung.
Header/hash khớp manifest. PhoWhisper-base vẫn sai cả câu phủ định/vùng nguy hiểm
và cụm khẩn cấp/rò rỉ khí; nhiều câu sai có average logprob rất cao. Whisper-small
thử riêng tiếng Việt nhận đúng hơn ở một số câu vùng nguy hiểm, nhưng vẫn sai cả
4 câu rò khí và mất khoảng 1,4–1,8 giây/câu thay vì 0,39–0,52 giây. Vì vậy chưa
đổi production ASR, không tăng threshold tùy ý. **5 lỗi safety vẫn mở.**

Tải 10 WAV VI→EN đầu trong candidate manifest của BTC; kiểm 16kHz/mono/PCM16,
size và SHA256 cục bộ. PhoWhisper-base chạy CPU, reference chỉ dùng sau inference:

| Số đo | Kết quả |
|---|---:|
|Mẫu|10|
|WER|9 lỗi / 78 từ = 11,54%|
|CER|17 lỗi / 272 ký tự = 6,25%|
|Câu khớp sau chuẩn hóa|3/10|
|ASR median / min–max|428,5 / 369–465 ms|

Ví dụ: “Anh cần thêm dây không?” → “anh cần thêm giây không.”;
“Bao lâu thì có đồ uống?” → “bao lâu thì có đủ.”. Đây là transcript BTC cung cấp,
chưa được người nghe xác minh lại; chưa chạy MT/TTS trên 10 WAV này. Không suy
ra lỗi TTS từ WER của WAV đầu vào, không gọi đây là điểm toàn pipeline hoặc điểm BTC.

## TTS đang kiểm tra

Đã render Piper VI hiện tại (noise 0/0) và cấu hình đi kèm voice (0,667/0,8), cùng
2 câu safety/đo lường, sherpa Python 1.13.3, CPU 2 threads. Tổng 4 WAV, không
clipping; synthesis 163–190 ms, audio 3,27–4,01 giây, RTF 0,047–0,050 sau warmup.
Chưa có human listening score, chưa đổi production hoặc regenerate safety cache.
Script tái lập: `tools/mac-cpu-benchmark/audition_tts.py --out-dir <thư mục mới>`.
[Điều tra model thay thế](../development/tts-models.md).

Đã tải và chạy **VieNeu v3 Turbo FP32**, SDK 3.8.3, CPU 2 threads trên Apple M2
16 GiB. Subset model/codec 520.650.240 bytes, khóa revision và SHA256; cold load
4.773 ms, peak RSS tiến trình 1.213,9 MiB. Bốn render đầu (Hải Đăng/Trúc Ly ×
2 câu) mất 1.089–1.262 ms, RTF 0,294–0,316. Đây là tổng synthesis, chưa đo TTFA.
Giọng Trúc Ly thêm 4 câu ngắn mất 501–664 ms, RTF 0,296–0,323. Không clipping.

Kiểm proxy thêm bằng PhoWhisper-base với **6 câu VI giống nhau**, resample soxr HQ
về 16kHz, cùng scorer chuẩn hóa NFKC/casefold/punctuation:

| Voice | Word errors | WER proxy | Câu khớp chuẩn hóa |
|---|---:|---:|---:|
|Piper hiện tại, noise 0/0|18/71|25,35%|0/6|
|VieNeu Turbo, Trúc Ly|2/71|2,82%|4/6|

Hai lỗi proxy còn lại của Trúc Ly: “bar” → “ba”, “tôi chưa nghe rõ” → “tôi trở
nghe rõ”. Chưa phân định bằng người nghe đó là TTS hay ASR. Piper defaults chỉ thử
2 câu, được 1/2 exact; không so điểm 2 câu ấy với 6 câu. Đây là exposed diagnostic
trên câu được chọn, một ASR có lỗi riêng; không phải MOS, blind test hoặc safety
acceptance, không khẳng định WER toàn pipeline giảm như bảng.

Chọn Trúc Ly làm **ứng viên audition tiếp theo** vì tín hiệu tốt hơn trên nhóm nhỏ;
production Android vẫn dùng Piper. VieNeu cần tích hợp ONNX/orchestration riêng,
không thể thay đường dẫn weights là chạy được trong sherpa. Script
`tools/mac-cpu-benchmark/audition_vieneu.py` đã chạy offline trên artifacts đã khóa,
bao gồm một smoke câu VI/EN hỗn hợp (chưa có đánh giá nghe/ngôn ngữ).

Luna đã thử thêm **VieNeu Nano**, SDK 3.8.3, Trúc Ly, CPU 2 threads, cùng 2 câu
ban đầu. Revision `aba295eb96a6fa6003ebe417cc1f2802a7adc1dc`, sáu runtime files
281.756.486 bytes, cold load 676 ms, peak RSS 420,25 MiB. 16 bước: 1.317–1.555 ms,
RTF 0,299–0,306; giảm còn 8 bước: 737–896 ms, RTF 0,165–0,177. Các lượt Nano
không có warmup riêng, câu đầu gồm first-inference effects; không dùng để xếp
hạng latency trực tiếp với Turbo đã warmup. Hai cấu hình khác seed/lượt sinh,
chưa phải phép thử chất lượng có kiểm soát. Tổng chỉ 4 render Nano.

Proxy 16 bước: 1/36 word edits (2,78%), 1/2 câu khớp; câu safety khớp, câu đơn vị
thêm “mươi” trước “li mét”. Nano nhẹ RAM hơn trong lượt thử này và đáng giữ làm
ứng viên thiết bị yếu; chưa tích hợp vào app hay có human listening score.
Proxy 8 bước: 0/36 word edits, 2/2 câu khớp. Chỉ hai lượt sinh đã được chọn để
kiểm, không kết luận 8 bước có chất lượng cao hơn 16 bước hoặc đủ safety acceptance.

## Cập nhật tiếp theo — 15/10/2026

### Tiếp tục trên main: sửa tokenizer và chọn host TTS

- Commit `805d055`: SentencePiece4J `encodeNormalized` tự lowercase, khác tokenizer
  Marian chuẩn. Đã thay bằng NFKC/whitespace chuẩn bị riêng, giữ hoa/thường và raw
  segmenter. Hai test toy-model fail trước patch, sau patch host **119/119 PASS**.
  Token IDs khớp Hugging Face trên cả 6 câu VI kiểm tra. Cùng weights/graphs,
  “Không vào khu vực nguy hiểm số bảy.” và “Không mở van…” hết “No” lặp. Câu
  yêu cầu nói lại vẫn bị mất vế sau; chưa kết luận chất lượng MT đã đạt.
- Chỉ khắc phục tokenizer không giải quyết việc ASR có thể trả chữ thường hoặc
  nhận sai từ. Đây chưa phải evidence đóng 5 lỗi safety lịch sử.
- Thử encoder PhoWhisper dynamic INT8 MatMul/per-channel trên 10 WAV BTC:
  baseline 9/78 lỗi từ, median 452 ms; candidate 10/78, median 333 ms. Candidate
  encoder khoảng 26 MB và mở được trên ORT CPU. **Không promote** vì WER tăng trên
  mẫu nhỏ. Đó là CPU dynamic quantization, không phải QDQ/HTP hoặc số đo QNN.
- Host TTS adapter Nano VI / Piper EN đã render 9 requests thật, VI 24 kHz,
  có engine reuse và seed 42. Năm test artifact/routing/audio failure mới PASS;
  tổng Python benchmark suite **42/42 PASS**. Chưa có human score riêng cho seed
  42; các request này không thay kiểm policy của pipeline và chưa đổi TTS Android.

### Chuẩn bị Android và Qualcomm

Đã cài platform 34/build-tools 34.0.0 vào ignored
`output/model-upgrades-20261015/android-sdk`, tải và kiểm hash AAR Piper 1.13.3.
Gradle online **core tests 69/69, Android debugCpu unit tests 36/36 và full
compileDebugCpuKotlin/Java PASS**. Khác với host harness, lượt này dùng SDK/AGP thật
và compile cả ViewModel/Compose. Chưa test mic/loa/lifecycle trên thiết bị.

AssembleDebugCpu lần đầu dừng vì giới hạn heap 768 MiB; tăng 2 GiB đã vượt D8,
nhưng dừng tại `mergeDebugCpuNativeLibs` do hết dung lượng. Chưa có APK mới và
không ghi assemble PASS. Chỉ bỏ transport archives đã giải nén, SDK bootstrap
utilities sau cài đặt và partial native-merge output của chính lượt lỗi; giữ
platform/build-tools/adb, source, model gốc, dữ liệu và logs. Cần thêm dung lượng.

Đã tải release Qualcomm `v0.64.0` Whisper Small W8A16 cho SM8650/8 Gen 3 và giải
nén/đọc input-output thật. Gói khoảng 285 MB, yêu cầu ORT 1.30.0/QAIRT 2.50.0;
runtime Android hiện 1.20.0. Maven Central khi kiểm chỉ có QNN Android tới 1.29.0.
Chưa stage gói không khớp runtime vào app. Gate kiểm hash/inventory/runtime/SoC
có 4 tests PASS và đã verify bundle thật; khai báo ORT 1.20.0 bị từ chối rõ ràng.

Encoder job `jgk62y2w5`, decoder job `j57oed3qg` đều SUCCESS trên Galaxy S24 Ultra,
Android 14. Mỗi graph 100 lượt; median encoder **45,6435 ms**, decoder **6,364 ms**
cho **một bước token**. Profiler ước tính inference peak 146,19 / 155,73 MiB;
không cộng hoặc coi là RAM toàn app. Trace encoder 1.894 node NPU, decoder 2.853
node NPU, không node CPU trong trace trả về. Input synthetic, chưa chứng minh
độ đúng ASR hoặc latency toàn câu. [Số đo nhỏ và job links](../../submission/phase-3/evaluation/qualcomm-graph-profile.json).

Whisper Small multilingual FP32 thử VI trên đúng 10 WAV BTC: **19/78 lỗi từ
(24,36%)**, so PhoWhisper Base 9/78 (11,54%). Vì vậy không đổi VI sang Whisper
Small chỉ để dùng chung gói QNN. Các tập này nhỏ/exposed, references chưa được
người nghe xác minh; vẫn cần acceptance độc lập. Chưa tải PhoWhisper Small vì
cache cần gần 1 GB và máy không đủ chỗ.

## Chưa được xác nhận

Assemble APK, mic/loa thực tế, permission/lifecycle, ML Kit trên thiết bị,
memory/chạy lâu, TTS Android mới và chất lượng nghe do người chấm. Sửa
replay/playback không tự sửa 5 lỗi acoustic safety lịch sử. Bộ model/runtime
chưa được freeze cho APK cuộc thi; native compatibility vẫn là gate mở.
