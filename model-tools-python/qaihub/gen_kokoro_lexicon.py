"""Precompute Kokoro-VI phonemes (vig2p / sea_g2p) for every Vietnamese syllable spelling.

vig2p phonemizes each letter token independently, so a per-token table reproduces it exactly
for the tokens it covers. Candidates: onset x rime x tone mark on any vowel (both old and new
tone placement), plus every token found in the given corpus files.
Usage: python gen_kokoro_lexicon.py OUT.tsv [corpus files...]
Writes vocab.tsv (codepoint, id) and voice.f32 (diem_trinh, 510x256 float32 LE) beside OUT.tsv.
"""
import re
import sys
import unicodedata
from pathlib import Path

from vig2p.core import WORD_RE, create_backend, phonemize_text

ONSETS = ['', 'b', 'c', 'ch', 'd', 'đ', 'g', 'gh', 'gi', 'h', 'k', 'kh', 'l', 'm', 'n', 'ng', 'ngh', 'nh',
          'p', 'ph', 'qu', 'r', 's', 't', 'th', 'tr', 'v', 'x']
RIMES = '''a ac ach ai am an ang anh ao ap at au ay ă ăc ăm ăn ăng ăp ăt â âc âm ân âng âp ât âu ây
e ec em en eng eo ep et ê êch êm ên ênh êp êt êu i ia ich im in inh ip it iu iêc iêm iên iêng iêp iêt iêu
o oa oac oach oai oam oan oang oanh oao oap oat oay oc oe oen oeo oet oi om on ong op ot oo ooc oong
ô ôc ôi ôm ôn ông ôp ôt ơ ơi ơm ơn ơp ơt u ua uân uâng uât uây uc uê uêch uênh uơ ui um un ung uôc uôi
uôm uôn uông uôt up ut uy uya uych uyên uyêt uynh uyt uyu ư ưa ưc ưi ưng ưt ưu ươc ươi ươm ươn ương
ươp ươt ươu y yêm yên yêng yêt yêu'''.split()
TONES = ['̀', '́', '̉', '̃', '̣']
VOWELS = set('aăâeêioôơuưy')


def candidates():
    for onset in ONSETS:
        for rime in RIMES:
            base = onset + rime
            yield base
            for i, ch in enumerate(base):
                if ch in VOWELS and i >= len(onset) - (1 if onset in ('gi', 'qu') else 0):
                    for tone in TONES:
                        yield unicodedata.normalize('NFC', base[:i + 1] + tone + base[i + 1:])


def main():
    out, corpora = sys.argv[1], sys.argv[2:]
    words = set(candidates()) | set('abcdefghijklmnopqrstuvwxyzđ')
    for path in corpora:
        text = unicodedata.normalize('NFC', open(path, encoding='utf-8').read())
        tokens = [t for t in re.findall(r"[A-Za-zÀ-ỹĐđ]+", text) if WORD_RE.match(t)]
        words.update(t.lower() for t in tokens)
        # sea_g2p splits camelCase ("iWatch"); keep those spellings as exact-case rows.
        words.update(t for t in tokens if t != t.lower() and t[1:] != t[1:].lower())
    backend = create_backend()
    rows = 0
    with open(out, 'w', encoding='utf-8') as f:
        for word in sorted(words):
            ps = phonemize_text(word, backend=backend)
            if ps and '\t' not in ps and '\n' not in ps:
                f.write(f'{word}\t{ps}\n')
                rows += 1
    print('LEXICON_ROWS', rows)
    write_vocab_and_voice(Path(out).parent)


def write_vocab_and_voice(folder):
    import json
    import numpy as np
    import torch
    from kokoro_vietnamese.core import DEFAULT_CONFIG_FILE, DEFAULT_HF_REPO_ID, _download_or_resolve
    vocab = json.loads(_download_or_resolve(DEFAULT_HF_REPO_ID, DEFAULT_CONFIG_FILE, None).read_text())['vocab']
    (folder / 'vocab.tsv').write_text(''.join(f'{ord(k)}\t{v}\n' for k, v in vocab.items() if len(k) == 1))
    voice = torch.load(_download_or_resolve(DEFAULT_HF_REPO_ID, 'voicepacks/diem_trinh.pt', None),
                       map_location='cpu', weights_only=True).numpy().astype('<f4')
    assert voice.shape == (510, 1, 256), voice.shape
    voice.reshape(510, 256).tofile(folder / 'voice.f32')
    print('VOCAB', len(vocab), 'VOICE', voice.shape)


if __name__ == '__main__':
    main()
