#!/usr/bin/env python3
"""Generate the locked 120-row VI↔EN safety/negative text corpus."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


VI_NUM = {1: "một", 2: "hai", 3: "ba", 4: "bốn", 5: "năm", 6: "sáu", 7: "bảy", 8: "tám", 9: "chín", 10: "mười"}
EN_NUM = {1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six", 7: "seven", 8: "eight", 9: "nine", 10: "ten"}


def pair_rows() -> list[tuple[str, str, str, dict[str, object]]]:
    pairs: list[tuple[str, str, str, dict[str, object]]] = []
    for n in range(1, 11):
        pairs.append((
            "stop-machine",
            f"Dừng máy số {VI_NUM[n]}.",
            f"Stop machine number {EN_NUM[n]}.",
            {"action": "STOP", "objectType": "MACHINE", "objectId": str(n), "negation": False, "urgency": False},
        ))
    for n in range(1, 11):
        pairs.append((
            "negated-start",
            f"Không khởi động máy số {VI_NUM[n]}.",
            f"Do not start machine number {EN_NUM[n]}.",
            {"action": "START", "objectType": "MACHINE", "objectId": str(n), "negation": True, "urgency": False},
        ))
    for n in range(1, 11):
        pairs.append((
            "conveyor-id",
            f"Tắt băng chuyền số {VI_NUM[n]}.",
            f"Turn off conveyor number {EN_NUM[n]}.",
            {"action": "TURN_OFF", "objectType": "CONVEYOR_BELT", "objectId": str(n), "negation": False, "urgency": False},
        ))
    for n in range(1, 11):
        pairs.append((
            "hazard-zone",
            f"Không vào khu vực nguy hiểm số {VI_NUM[n]}.",
            f"Do not enter hazard zone number {EN_NUM[n]}.",
            {"action": "ENTER", "objectType": "HAZARD_ZONE", "objectId": str(n), "negation": True, "urgency": False, "hazard": "GENERIC_DANGER"},
        ))
    for n in range(1, 11):
        pairs.append((
            "urgent-gas",
            f"Khẩn cấp, dừng máy ép số {VI_NUM[n]} vì rò rỉ khí.",
            f"Emergency, shut down press number {EN_NUM[n]} because of a gas leak.",
            {"action": "STOP", "objectType": "MACHINE", "objectId": str(n), "negation": False, "urgency": True, "hazard": "GAS_LEAK"},
        ))

    negatives = [
        ("Đóng cửa sau.", "Close the back door."),
        ("Ba công nhân đang nghỉ.", "Three workers are taking a break."),
        ("Năm nay nhà máy tuyển thêm người.", "The factory is hiring more people this year."),
        ("Hai bên đã đồng ý lịch họp.", "Both sides agreed on the meeting schedule."),
        ("Tôi đứng sau trưởng ca.", "I am standing behind the shift leader."),
        ("Để cái đó ở đây.", "Leave that here."),
        ("Mang hộp này sang bên kia.", "Take this box to the other side."),
        ("Hôm nay trời nóng hơn.", "It is hotter today."),
        ("Chúng ta bắt đầu sau giờ nghỉ.", "We start after the break."),
        ("Cửa này hơi khó mở.", "This door is a little hard to open."),
    ]
    for vi, en in negatives:
        pairs.append(("lexical-negative", vi, en, {}))
    if len(pairs) != 60:
        raise AssertionError(len(pairs))
    return pairs


def generate() -> list[dict[str, object]]:
    output: list[dict[str, object]] = []
    for index, (category, vi, en, slots) in enumerate(pair_rows()):
        group = f"safety-{index + 1:03d}"
        # Assign the semantic pair to a split once, before expanding it into
        # language-direction variants. This prevents paired variants from
        # leaking across dev/holdout.
        split = "safety-dev" if index < 30 else "safety-holdout"
        safety = bool(slots)
        allowed = ["VERIFIED_TRANSLATION", "REPAIRED_TRANSLATION", "ASK_REPEAT"] if safety else ["MODEL_TRANSLATION"]
        output.append({
            "id": f"{group}-vi-en",
            "group_id": group,
            "split": split,
            "category": category,
            "source_kind": "safety-text-template",
            "source_lang": "vi",
            "target_lang": "en",
            "reference_source": vi,
            "reference_target": en,
            "reference_slots": slots,
            "allowed_decisions": allowed,
            "label_status": "provisional-template",
            "human_verified": False,
        })
        output.append({
            "id": f"{group}-en-vi",
            "group_id": group,
            "split": split,
            "category": category,
            "source_kind": "safety-text-template",
            "source_lang": "en",
            "target_lang": "vi",
            "reference_source": en,
            "reference_target": vi,
            "reference_slots": slots,
            "allowed_decisions": allowed,
            "label_status": "provisional-template",
            "human_verified": False,
        })
    return output


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    rows = generate()
    vi = sum(row["source_lang"] == "vi" for row in rows)
    en = sum(row["source_lang"] == "en" for row in rows)
    if len(rows) != 120 or vi != 60 or en != 60:
        raise AssertionError((len(rows), vi, en))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    print(f"wrote {len(rows)} rows: vi={vi}, en={en}, path={args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
