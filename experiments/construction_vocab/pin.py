"""Python mirror of ConstructionGlossary.pinViToEn for host MT checks."""
import re, unicodedata
F = re.I
G = [l.rstrip("\n").split("\t") for l in open(__file__.rsplit("/",1)[0] + "/glossary.tsv", encoding="utf-8") if l.strip()]
G.sort(key=lambda p: -len(p[0]))
B, A = r"(?<![^\W_])", r"(?![^\W_])"
FIX = [("bơ tông", "bê tông"), ("bơ công", "bê tông"), ("cẩu thác", "cẩu tháp")]
L = {"bê":"B","bờ":"B","xê":"C","cê":"C","cờ":"C","dê":"D","dờ":"D","đê":"D","đờ":"D","gờ":"G","hát":"H","lờ":"L","mờ":"M","nờ":"N","pê":"P","pờ":"P","rờ":"R","sờ":"S","tê":"T","tờ":"T","vê":"V","vờ":"V","ích":"X"}
N = "|".join(re.escape(k) for k in sorted(L, key=len, reverse=True))
def let(x): return "D" if x.lower()=="giờ" else L.get(x.lower(), x.upper())
RANGE = re.compile(B + rf"từ\s+(?:trục|chục)\s+([a-z]|{N}|giờ)\s+đến\s+(?:trục|chục)\s+([a-z]|{N}|giờ)" + A, F)
LINE = re.compile(B + rf"trục\s+([a-z]|\d{{1,2}}|{N})" + A, F)
CUBIC = re.compile(B + r"(\d+(?:[.,]\d+)?|một|hai|ba|bốn|tư|năm|lăm|sáu|bảy|tám|chín|mười|mươi|mốt|trăm|rưỡi)\s+khối(?=\s*(?:$|[.,;:!?]|bê tông|đất|cát|đá|vữa|sỏi))", F)
def pin(t):
    t = unicodedata.normalize("NFC", t)
    for a, b in FIX: t = re.sub(B + re.escape(a) + A, b, t, flags=F)
    t = RANGE.sub(lambda m: f"từ grid line {let(m.group(1))} đến grid line {let(m.group(2))}", t)
    for a, b in G: t = re.sub(B + re.escape(a) + A, b, t, flags=F)
    t = LINE.sub(lambda m: f"grid line {let(m.group(1))}", t)
    t = CUBIC.sub(lambda m: f"{m.group(1)} cubic meters", t)
    return t
