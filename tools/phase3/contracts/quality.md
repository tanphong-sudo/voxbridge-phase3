# Contract bộ tổng hợp chất lượng

Phạm vi: `tools/phase3/score_quality.py`, Python stdlib đọc JSONL manifest + CSV turns/ratings theo forms. Không inference, không tạo reference/rating/audio hoặc kiểm chứng nội dung audio. Kết quả là số liệu từ khai báo đã thu, không official score/runtime certification.

## Input và danh tính

- Manifest không rỗng, mỗi object có `id`, `source_lang`, `target_lang` (VI↔EN), `condition`, `eligible_general` boolean, `reference_provenance` trong btc_human_label/btc_ai_label/team_bilingual_review/unreviewed. Không tự suy eligibility/provenance từ output. Khóa trước chấm và ghi hash input.
- Chọn đúng một `--run-id`. Turns CSV chỉ chứa primary attempts của run đó: mỗi sample đúng tối đa một turn_id duy nhất, sample có trong manifest, direction khớp; `attempt_kind=primary`. Repeat/replay/confirm phải nằm event file riêng, không thay lượt đầu bằng lượt recovery. Không silently drop run khác.
- Turn cần `final_translation` (có thể trống), `actual_audio_emitted` và `output_audio_available` là boolean; audio available cần `output_audio_path` nonempty. Hai cờ độc lập, dành cho translation audio (không phải prompt repeat): file synthesis QDC có thể available nhưng chưa emitted. Đây vẫn là khai báo, script không mở/nghe/hash file audio; evidence checker/người review kiểm tra riêng.
- Ratings CSV dùng đúng run/turn, unique(turn,rater,stage). Stage independent/adjudicated; meaning integer0–4, critical_error boolean, output_available khớp có final text, reference_provenance khớp manifest. Audio scores1–5 hoặc trống, chỉ hợp lệ khi output audio available. No text không được meaning>0 hoặc có translation-audio rating.
- Mỗi turn tối đa một adjudicated rating, cần adjudication_note. Nếu nhiều independent ratings khác meaning hoặc critical flag thì turn unresolved cho tới adjudication; không chọn max hoặc tự bỏ người chấm. Clarity/naturalness có thể khác, lấy trung bình các rater của turn rồi median qua các turn để mỗi turn nặng như nhau.
- Unknown IDs/duplicate IDs/extra columns trùng header/CSV malformed, boolean hoặc integer sai kiểu/range phải báo lỗi. Không có rating không được tạo rating0; missing turn không được tự tạo output.

## Mẫu số và missing

Theo toàn bộ manifest và từng direction/condition/provenance: counts expected/present/missing turns, eligible general, text/audio/emitted coverage, reviewed/unrated/conflicting ratings, critical errors observed và double-reviewed count. Các row safety/non-general vẫn hiện trong overall coverage và critical counts nhưng không làm mẫu số general-success.

Known no-output ở general input là semantic failure ngay cả chưa có manual rating. Semantic rate chỉ chấm nghĩa text được trình bày, không là tỷ lệ voice-to-voice hoàn tất hoặc confirm thành công. Khi mọi eligible input có primary result và mọi output text có resolved rating, `semantic_success_rate = count(meaning≥3)/eligible_total`. Nếu thiếu result/rating hoặc unresolved thì point rate null, báo conservative lower/upper bounds theo số đã biết; không gọi lower bound là model score. Mean meaning chỉ trên rated text outputs (kể cả non-general), báo n, không che no-output bằng mean; semantic-success chỉ dùng eligible_general.

Dữ liệu chỉ complete khi mọi audio available có cả clarity/naturalness resolved; một adjudicated record dùng thay independent nên phải điền đủ phần cần giữ. Clarity/naturalness median kèm số output có audio, số turn thực được chấm và missing count. File synthesis coverage và actual emitted coverage báo riêng. Không gọi những rating nội bộ này là MOS chuẩn. Khi không có mẫu đúng loại, metric null, không0 hay100%.

## CLI và gate

`--manifest`, `--turns`, `--ratings`, `--run-id` bắt buộc; `--output` tùy chọn chỉ ghi summary JSON khi được chỉ định. Raw inputs không sửa; output trùng input/symlink/hardlink alias bị từ chối. Ghi SHA256 của3input nhỏ, scope aggregation_only và runtime_verified=false.

Exit2 khi input không đọc/parse/validate được; exit1 khi coverage/rating chưa đủ hoặc có critical error được resolve (không phải lỗi process); exit0 chỉ khi metadata/rating completeness và zero observed critical theo dữ liệu. Các threshold≥90%/95%/4trên5 chưa được frozen ở tool; exit0 không chứng minh quality gate hoặc safety toàn pipeline. Summary dùng `data_complete`, `observed_critical_errors`, không prototype_passed.

## Thứ tự TDD

Test tiny fixtures: mẫu số manifest, no output/recovery, missing turn/rating, disagreement/adjudication, duplicate/foreign run/ID/direction, invalid type/range/provenance/audio rating, equal per-turn audio weights, grouping và CLI exit/hashes. Chạy đỏ trước implementation, thêm code nhỏ rồi chạy lại. Không dùng raw BTC data hoặc tạo số thực nghiệm để test tool.
