# Điền báo cáo Phase 3

Dùng template BTC gốc. Bảng này giữ đủ 35 trường; chỉ điền bằng kết quả của cùng build, thiết bị và bộ model. Chưa đo thì ghi rõ chưa đo. Các biểu mẫu ở [forms](forms/README.md); ledger mẫu ở [templates](templates/submission-record.example.json).

| Trường | Nội dung | Minh chứng cần chuẩn bị |
|---|---|---|
| `P3-T014-R01` | Team / individual participant | Tên đăng ký, commit/version, cặp ngôn ngữ, thiết bị và điều kiện thực tế. |
| `P3-T014-R02` | Project name | Tên đăng ký, commit/version, cặp ngôn ngữ, thiết bị và điều kiện thực tế. |
| `P3-T014-R03` | Submitted build | Tên đăng ký, commit/version, cặp ngôn ngữ, thiết bị và điều kiện thực tế. |
| `P3-T014-R04` | Demo language(s) | Tên đăng ký, commit/version, cặp ngôn ngữ, thiết bị và điều kiện thực tế. |
| `P3-T014-R05` | Test scenario | Tên đăng ký, commit/version, cặp ngôn ngữ, thiết bị và điều kiện thực tế. |
| `P3-T014-R06` | Test conditions | Tên đăng ký, commit/version, cặp ngôn ngữ, thiết bị và điều kiện thực tế. |
| `P3-T014-R07` | Actual demo device | Tên đăng ký, commit/version, cặp ngôn ngữ, thiết bị và điều kiện thực tế. |
| `P3-T014-R08` | Current capability & progress | Tên đăng ký, commit/version, cặp ngôn ngữ, thiết bị và điều kiện thực tế. |
| `P3-P016-1` | Accessible URL | URL truy cập được, thời lượng, timestamp tắt mạng và lượt voice-to-voice hoàn chỉnh; video liền mạch. |
| `P3-P016-2` | mm:ss | URL truy cập được, thời lượng, timestamp tắt mạng và lượt voice-to-voice hoàn chỉnh; video liền mạch. |
| `P3-P018-1` | Network disconnected — mm:ss; first complete voice-to-voice exchange — mm:ss; other useful moments — mm:ss. | URL truy cập được, thời lượng, timestamp tắt mạng và lượt voice-to-voice hoàn chỉnh; video liền mạch. |
| `P3-P021-1` | Download URL; file name; version matching Section 1 | Gói cài cùng build, source/release, hash và thử quyền truy cập bằng tài khoản người nhận. |
| `P3-P021-2` | Packaged source + models / APK / executable | Gói cài cùng build, source/release, hash và thử quyền truy cập bằng tài khoản người nhận. |
| `P3-P021-3` | URL and release / commit, or explain why no public source repository is supplied for an APK/executable submission. | Gói cài cùng build, source/release, hash và thử quyền truy cập bằng tài khoản người nhận. |
| `P3-P023-1` | Short file/folder list and purpose; model files; installer or entry point; dependency bundle; sample input; README. | Gói cài cùng build, source/release, hash và thử quyền truy cập bằng tài khoản người nhận. |
| `P3-P025-1` | Insert a compact diagram showing audio input → processing/model components → translated output. Label the device boundary, models and versions, runtime/SDK versions, and CPU/GPU/NPU execution where applicable. Adapt the diagram to your actual architecture. | Sơ đồ đúng pipeline đang demo; phân biệt thành phần đã chạy và dự kiến. |
| `P3-P026-1` | Briefly explain component connections, model precision/quantization, user controls, and any fallback used. All demo inference must run on-device without API/cloud calls. Distinguish any development tools, such as Qualcomm AI Hub or Device Cloud, from the submitted runtime. | Sơ đồ đúng pipeline đang demo; phân biệt thành phần đã chạy và dự kiến. |
| `P3-T029-R01` | Processor / accelerator | Thông số máy đã test, RAM/storage đo được, OS/ABI và phụ kiện thực dùng. |
| `P3-T029-R02` | Memory and storage | Thông số máy đã test, RAM/storage đo được, OS/ABI và phụ kiện thực dùng. |
| `P3-T029-R03` | OS and software | Thông số máy đã test, RAM/storage đo được, OS/ABI và phụ kiện thực dùng. |
| `P3-T029-R04` | Audio and peripherals | Thông số máy đã test, RAM/storage đo được, OS/ABI và phụ kiện thực dùng. |
| `P3-P032-1` | Obtain all files and dependencies; identify paths and permissions. List anything that must be downloaded before disconnecting. | Lệnh cài, vị trí model, bước chuẩn bị ML Kit, thử trên máy sạch; nói rõ khi cần mạng lần đầu. |
| `P3-P033-1` | Install/load the package and models on compatible hardware using locally available files. | Lệnh cài, vị trí model, bước chuẩn bị ML Kit, thử trên máy sạch; nói rõ khi cần mạng lần đầu. |
| `P3-P034-1` | Disable network connections; provide the launch command or app/control sequence; describe the ready state. | Lệnh cài, vị trí model, bước chuẩn bị ML Kit, thử trên máy sạch; nói rõ khi cần mạng lần đầu. |
| `P3-P035-1` | Select languages, start/stop speech input, and identify the expected translated output. | Lệnh cài, vị trí model, bước chuẩn bị ML Kit, thử trên máy sạch; nói rõ khi cần mạng lần đầu. |
| `P3-P036-1` | Restart procedure; common setup issue and remedy; where to find logs. | Lệnh cài, vị trí model, bước chuẩn bị ML Kit, thử trên máy sạch; nói rõ khi cần mạng lần đầu. |
| `P3-P039-1` | Date; build/device from Section 1 or clearly identified differences; language directions; dataset/source and sample count; relevant test conditions. | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |
| `P3-P039-2` | Accessible URL containing logs/screenshots; use file names or timestamps in the table. | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |
| `P3-T041-R01` | Translation quality | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |
| `P3-T041-R02` | Inference / response time | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |
| `P3-T041-R03` | Model size | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |
| `P3-T041-R04` | Peak memory | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |
| `P3-T041-R05` | Device operation & UX | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |
| `P3-P042-1` | Conditions or language directions that fail, features not yet implemented, and any differences between the demonstrated and packaged build. | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |
| `P3-P043-1` | Up to three concrete improvements before field testing; how each will be checked. | Manifest, raw logs, ratings người chấm, phương pháp đo, mẫu số và failure; không dùng Mac/OPUS thay Android/ML Kit. |

Đối chiếu [yêu cầu](requirements.md), [trạng thái thực tế](STATUS.md) và [inventory gốc](templates/template-field-inventory.json) trước nộp. Các chỉ dẫn ngoài ô điền vẫn áp dụng; bảng trên không thay template BTC.
