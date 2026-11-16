import json, re, sys, torch
sys.path.insert(0, sys.argv[1]); from pin import pin
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
torch.set_num_threads(4)
n = sys.argv[2]; tok = AutoTokenizer.from_pretrained(n); model = AutoModelForSeq2SeqLM.from_pretrained(n).eval()
def tr(text, src="vi"):
    ids = tok(f"{src}: {text}", return_tensors="pt").input_ids
    with torch.inference_mode(): out = model.generate(ids, num_beams=2, do_sample=False, max_new_tokens=160)
    return re.sub(r"^\s*(?:en|vi)\s*:\s*", "", tok.decode(out[0], skip_special_tokens=True), flags=re.I).strip()
demo = ["còn đổ bơ tông sàn từ chục bờ đến chục giờ khoảng mười hai khối.", "xe bồn bơ tông đến lúc hai giờ chiều.",
        "chưa được tháo cốp pha ở tầng bốn bơ công chưa đủ ngày.", "chưa được tháo cốp pha ở tầng bốn, bê tông chưa đủ ngày.",
        "cẩu thác đang nâng cốt thép lên tầng sáu.", "cẩu thác đang nâng cốt thép lên tầm sáu.",
        "dạ em kiểm tra xong sẽ báo anh cảm ơn anh.", "dạ em kiểm tra xong sẽ báo anh."]
for s in demo: print("VI|", s, "=>", pin(s), "=>", tr(pin(s)), flush=True)
for s in ["Understood. Do not strip the formwork until Friday.", "Do not remove the formwork until Friday.", "Keep the formwork in place until Friday."]:
    print("EN|", s, "=>", tr(s, "en"), flush=True)
refs = [json.loads(l) for l in open(sys.argv[3])]
changed = 0; vi = 0
for r in refs:
    src = r.get("source_text") or r.get("reference_source") or r.get("source") or ""
    if r.get("source_lang", "vi") != "vi" or not src: continue
    vi += 1
    if pin(src) != src: changed += 1; print("CHANGED", r.get("id"), "|", src, "=>", pin(src), "|", tr(src), "||", tr(pin(src)), flush=True)
print("BTC vi sources:", vi, "changed:", changed)
