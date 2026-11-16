"""Protocol/audio validation tests only; no fake ASR and no model inference."""
import hashlib
import io
import json
from enum import Enum
from pathlib import Path
import struct
import tempfile
import unittest
import wave

from parakeet_jsonl_worker import checked_audio, parse_request, serve, write_response


class AdapterContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.wav = self.root / "provided.wav"
        self.pcm = struct.pack("<hhhh", -32768, -1, 0, 32767)
        self.write_wav(self.wav, self.pcm)

    def write_wav(self, path, pcm, channels=1, rate=16000, width=2):
        with wave.open(str(path), "wb") as handle:
            handle.setnchannels(channels)
            handle.setsampwidth(width)
            handle.setframerate(rate)
            handle.writeframes(pcm)

    def request(self, **extra):
        return {"id": "EV-0523", "op": "asr", "wav": str(self.wav), **extra}

    def test_exact_pcm_is_retained_without_gain_padding_or_trimming(self):
        result = checked_audio(parse_request(self.request()), self.root, 16000)
        self.assertEqual(result[0], self.pcm)
        self.assertEqual(result[1], 4)
        self.assertEqual(result[2], hashlib.sha256(self.wav.read_bytes()).hexdigest())

    def test_optional_digest_is_checked(self):
        req = parse_request(self.request(sha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "checksum"):
            checked_audio(req, self.root, 16000)

    def test_correct_digest_passes(self):
        digest = hashlib.sha256(self.wav.read_bytes()).hexdigest()
        checked_audio(parse_request(self.request(sha256=digest)), self.root, 16000)

    def test_no_reference_fields_enter_inference(self):
        for field in ("reference_source", "reference_target", "text", "target_lang"):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "fields"):
                parse_request(self.request(**{field: "secret reference"}))

    def test_malformed_requests_rejected(self):
        for value in (None, [], {}, self.request(op="mt"), self.request(id="../id"),
                      self.request(id="a" * 129), self.request(wav="relative.wav"),
                      self.request(sha256=True), self.request(sha256="invalid")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_request(value)

    def test_outside_root_and_symlink_escape_rejected(self):
        with tempfile.TemporaryDirectory() as outside:
            other = Path(outside) / "outside.wav"
            self.write_wav(other, self.pcm)
            link = self.root / "linked.wav"
            link.symlink_to(other)
            for path in (other, link):
                with self.subTest(path=path), self.assertRaisesRegex(ValueError, "audio root"):
                    checked_audio(parse_request(self.request(wav=str(path))), self.root, 16000)

    def test_wrong_audio_format_rejected(self):
        for channels, rate, width in ((2,16000,2), (1,8000,2), (1,16000,1)):
            self.write_wav(self.wav, self.pcm, channels, rate, width)
            with self.subTest(channels=channels,rate=rate,width=width), self.assertRaisesRegex(ValueError,"mono PCM16"):
                checked_audio(parse_request(self.request()), self.root, 16000)

    def test_empty_and_oversize_audio_rejected(self):
        for pcm, limit in ((b"",16000), (self.pcm,3)):
            self.write_wav(self.wav, pcm)
            with self.subTest(pcm=pcm), self.assertRaisesRegex(ValueError,"sample count"):
                checked_audio(parse_request(self.request()), self.root, limit)

    def test_truncated_wave_payload_rejected(self):
        self.wav.write_bytes(self.wav.read_bytes()[:-2])
        with self.assertRaisesRegex(ValueError,"truncated"):
            checked_audio(parse_request(self.request()), self.root,16000)

    def test_jsonl_malformed_lines_report_error_and_keep_processing(self):
        incoming = io.StringIO("broken\n[]\n" + json.dumps(self.request(op="mt")) + "\n")
        outgoing = io.StringIO()
        # Invalid protocol must not call a model; none is installed/injected here.
        serve(incoming, outgoing, None, self.root, 16000)
        rows = [json.loads(line) for line in outgoing.getvalue().splitlines()]
        self.assertEqual(len(rows),3)
        self.assertTrue(all(row["ok"] is False for row in rows))

    def test_ready_native_decoder_nested_enum_serializes_as_stable_name(self):
        class BlankLMScoreMode(Enum):
            LM_WEIGHTED_FULL = "lm_weighted_full"
        decoder = {"config": {"beam": {"blank_lm_score_mode": BlankLMScoreMode.LM_WEIGHTED_FULL}},
                   "nested": [BlankLMScoreMode.LM_WEIGHTED_FULL]}
        output = io.StringIO()
        write_response(output, {"ready": True, "decoder": decoder})
        actual = json.loads(output.getvalue())
        self.assertEqual(actual["decoder"]["config"]["beam"]["blank_lm_score_mode"], "LM_WEIGHTED_FULL")
        self.assertEqual(actual["decoder"]["nested"], ["LM_WEIGHTED_FULL"])
        self.assertIs(decoder["config"]["beam"]["blank_lm_score_mode"], BlankLMScoreMode.LM_WEIGHTED_FULL)

    def test_protocol_does_not_stringify_unknown_objects(self):
        output = io.StringIO()
        with self.assertRaises(TypeError):
            write_response(output, {"ready": True, "decoder": {"unknown": object()}})
        self.assertEqual(output.getvalue(), "")

    def test_protocol_still_rejects_nonfinite_numbers(self):
        for number in (float("nan"), float("inf"), float("-inf")):
            output = io.StringIO()
            with self.subTest(number=number), self.assertRaises(ValueError):
                write_response(output, {"ready": True, "decoder": {"number": number}})
            self.assertEqual(output.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
