import contextlib
import copy
import csv
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score_quality import aggregate, main


class QualityTest(unittest.TestCase):
    def setUp(self):
        self.manifest = [dict(id='a', source_lang='vi', target_lang='en', condition='quiet',
                              eligible_general=True, reference_provenance='unreviewed')]
        self.turns = [dict(run_id='run', turn_id='t1', sample_id='a', source_lang='vi', target_lang='en',
                          attempt_kind='primary', final_translation='A translation',
                          output_audio_available=True, actual_audio_emitted=False, output_audio_path='a.wav')]
        self.ratings = [dict(run_id='run', turn_id='t1', rater_id='r1', rating_stage='independent',
                            reference_provenance='unreviewed', meaning_0_to_4=4,
                            clarity_1_to_5=5, naturalness_1_to_5=4, critical_error=False,
                            output_available=True, adjudication_note='')]

    def score(self):
        return aggregate(self.manifest, self.turns, self.ratings, 'run')

    def test_complete_text_ratings_do_not_claim_playback_or_official_quality(self):
        report = self.score()
        self.assertTrue(report['data_complete'])
        self.assertFalse(report['runtime_verified'])
        self.assertEqual(report['overall']['semantic_success_rate'], 1)
        self.assertEqual(report['overall']['audio_available'], 1)
        self.assertEqual(report['overall']['actual_emitted'], 0)

    def test_missing_result_remains_in_denominator_with_explicit_bounds(self):
        self.manifest.append(dict(self.manifest[0], id='missing'))
        result = self.score()['overall']
        self.assertIsNone(result['semantic_success_rate'])
        self.assertEqual(result['semantic_success_lower_bound'], .5)
        self.assertEqual(result['semantic_success_upper_bound'], 1)
        self.assertEqual(result['missing_turns'], 1)

    def test_no_output_is_known_failure_without_inventing_human_rating(self):
        self.turns[0].update(final_translation='', output_audio_available=False, output_audio_path='')
        self.ratings = []
        result = self.score()
        self.assertTrue(result['data_complete'])
        self.assertEqual(result['overall']['semantic_success_rate'], 0)
        self.assertEqual(result['overall']['rated_text_outputs'], 0)
        self.assertIsNone(result['overall']['rated_text_mean_meaning'])
        self.assertIsNone(result['overall']['clarity_median'])

    def test_missing_rating_is_unknown_not_zero(self):
        self.ratings = []
        result = self.score()
        self.assertFalse(result['data_complete'])
        self.assertIsNone(result['overall']['semantic_success_rate'])
        self.assertIsNone(result['overall']['rated_text_mean_meaning'])
        self.assertEqual(result['overall']['semantic_success_upper_bound'], 1)
        self.assertEqual(result['by_direction']['vi-en']['unrated_text_outputs'], 1)
        self.assertEqual(result['by_condition']['quiet']['unrated_text_outputs'], 1)

    def test_available_audio_without_audio_scores_is_incomplete(self):
        self.ratings[0].update(clarity_1_to_5='', naturalness_1_to_5='')
        result = self.score()
        self.assertEqual(result['overall']['semantic_success_rate'], 1)
        self.assertFalse(result['data_complete'])
        self.assertEqual(result['overall']['clarity_unrated_audio_outputs'], 1)
        self.turns[0].update(output_audio_available=False, output_audio_path='')
        self.assertTrue(self.score()['data_complete'])

    def test_disagreement_requires_explicit_adjudication_not_max_rating(self):
        self.ratings.append(dict(self.ratings[0], rater_id='r2', meaning_0_to_4=2))
        self.assertEqual(self.score()['overall']['unresolved_ratings'], 1)
        self.assertIsNone(self.score()['overall']['semantic_success_rate'])
        self.ratings.append(dict(self.ratings[0], rater_id='r3', rating_stage='adjudicated',
                                 meaning_0_to_4=2, adjudication_note='Source meaning was lost.'))
        result = self.score()
        self.assertTrue(result['data_complete'])
        self.assertEqual(result['overall']['semantic_success_rate'], 0)
        self.assertEqual(result['overall']['double_reviewed_turns'], 1)

    def test_critical_disagreement_is_not_silently_dropped(self):
        self.ratings.append(dict(self.ratings[0], rater_id='r2', critical_error=True))
        result = self.score()
        self.assertFalse(result['data_complete'])
        self.assertEqual(result['overall']['critical_flagged_turns'], 1)
        self.ratings.pop()
        self.ratings[0]['critical_error'] = True
        self.assertEqual(self.score()['observed_critical_errors'], 1)

    def test_audio_ratings_weight_turns_equally(self):
        self.manifest.append(dict(self.manifest[0], id='b'))
        self.turns.append(dict(self.turns[0], sample_id='b', turn_id='t2'))
        self.ratings += [dict(self.ratings[0], rater_id='r2', clarity_1_to_5=1),
                         dict(self.ratings[0], turn_id='t2', clarity_1_to_5=5)]
        self.assertEqual(self.score()['overall']['clarity_median'], 4)

    def test_non_general_samples_do_not_inflate_general_success(self):
        self.manifest[0]['eligible_general'] = False
        result = self.score()['overall']
        self.assertEqual(result['expected'], 1)
        self.assertEqual(result['eligible_general'], 0)
        self.assertIsNone(result['semantic_success_rate'])

    def test_duplicate_foreign_run_and_nonprimary_turns_are_rejected(self):
        original = copy.deepcopy(self.turns)
        for key, value in [('run_id', 'other'), ('sample_id', 'unknown'), ('source_lang', 'en'),
                           ('attempt_kind', 'replay'), ('actual_audio_emitted', 'yes')]:
            self.turns = copy.deepcopy(original)
            self.turns[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.score()
        self.turns = original + copy.deepcopy(original)
        with self.assertRaises(ValueError): self.score()

    def test_invalid_rating_values_and_missing_audio_are_rejected(self):
        original = copy.deepcopy(self.ratings)
        for key, value in [('meaning_0_to_4', True), ('meaning_0_to_4', 5),
                           ('clarity_1_to_5', 'nan'), ('clarity_1_to_5', 0),
                           ('critical_error', 'yes'), ('reference_provenance', 'btc_ai_label'),
                           ('turn_id', 'missing'), ('rating_stage', 'best'), ('output_available', False)]:
            self.ratings = copy.deepcopy(original)
            self.ratings[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.score()
        self.ratings = original
        self.turns[0]['output_audio_available'] = False
        with self.assertRaises(ValueError): self.score()

    def test_duplicate_raters_and_undocumented_adjudication_are_rejected(self):
        self.ratings.append(dict(self.ratings[0]))
        with self.assertRaises(ValueError): self.score()
        self.ratings[1].update(rater_id='r2', rating_stage='adjudicated')
        with self.assertRaises(ValueError): self.score()

    def test_groups_keep_direction_condition_and_reference_provenance(self):
        result = self.score()
        self.assertEqual(result['by_direction']['vi-en']['expected'], 1)
        self.assertEqual(result['by_condition']['quiet']['expected'], 1)
        self.assertEqual(result['by_reference_provenance']['unreviewed']['expected'], 1)
        self.assertEqual(result['by_direction_condition']['vi-en']['quiet']['expected'], 1)

    def test_empty_or_duplicate_manifest_is_invalid(self):
        for values in ([], self.manifest * 2):
            with self.subTest(values=values), self.assertRaises(ValueError):
                aggregate(values, self.turns, self.ratings, 'run')

    def test_cli_reports_hashes_and_nonzero_for_incomplete_or_critical_data(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest, turns, ratings = (root / name for name in ['m.jsonl', 't.csv', 'r.csv'])
            manifest.write_text(json.dumps(self.manifest[0]) + '\n')
            def write_csv(path, rows, header):
                with path.open('w', newline='') as f:
                    writer = csv.DictWriter(f, fieldnames=header)
                    writer.writeheader(); writer.writerows(rows)
            def values(rows):
                return [{k: str(v).lower() if type(v) is bool else v for k, v in row.items()} for row in rows]
            write_csv(turns, values(self.turns), list(self.turns[0]))
            args = ['--manifest', str(manifest), '--turns', str(turns), '--ratings', str(ratings), '--run-id', 'run']
            for data, code in [(self.ratings, 0), ([], 1), ([dict(self.ratings[0], critical_error=True)], 1)]:
                write_csv(ratings, values(data), list(self.ratings[0]))
                output = io.StringIO()
                with contextlib.redirect_stdout(output): self.assertEqual(main(args), code)
                self.assertEqual(len(json.loads(output.getvalue())['input_sha256']), 3)
            ratings.write_text('run_id,run_id\nrun,run\n')
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error: main(args)
            self.assertEqual(error.exception.code, 2)

    def test_cli_output_cannot_overwrite_any_input_or_symlink_alias(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest, turns, ratings = (root/name for name in ['m.jsonl', 't.csv', 'r.csv'])
            manifest.write_text(json.dumps(self.manifest[0])+'\n')
            for path, rows in [(turns, self.turns), (ratings, self.ratings)]:
                with path.open('w', newline='') as f:
                    writer = csv.DictWriter(f, fieldnames=list(rows[0]))
                    writer.writeheader()
                    writer.writerows([{k:str(v).lower() if type(v) is bool else v for k,v in row.items()} for row in rows])
            original = manifest.read_bytes()
            alias = root/'alias.json'
            alias.symlink_to(manifest)
            for dest in (manifest, alias):
                manifest.write_bytes(original)
                with self.subTest(dest=dest):
                    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                        main(['--manifest', str(manifest), '--turns', str(turns), '--ratings', str(ratings),
                              '--run-id', 'run', '--output', str(dest)])
                    self.assertEqual(error.exception.code, 2)
                    self.assertEqual(manifest.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
