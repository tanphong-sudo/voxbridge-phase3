import unittest
from punctuation_model import strip_marks, render_labels, terminal_candidate, capitalize_initial

class PunctuationModelTest(unittest.TestCase):
    def test_model_preparation_keeps_numbers_and_identifiers(self):
        self.assertEqual('van A-12 ở mức 3.5 bar lúc 10:30', strip_marks('van A-12 ở mức 3.5 bar, lúc 10:30?'))

    def test_preparation_preserves_abbreviations_and_word_boundaries(self):
        self.assertEqual("arrive at nine a.m local time",strip_marks("arrive at nine a.m., local time"))
        self.assertEqual("at sixteen:forty five",strip_marks("at sixteen:forty five."))

    def test_word_end_predictions_do_not_split_identifier_or_decimal(self):
        text='van A-12 ở mức 3.5 bar'
        labels={3:'.|Cap',5:'?|UPPER',8:'0|UPPER',17:'?|lower',18:'0|lower',22:'.|lower'}
        out=render_labels(text,labels)
        self.assertIn('A-12',out)
        self.assertIn('3.5',out)
        self.assertNotIn('A?',out)

    def test_terminal_policy_preserves_explicit_intent(self):
        self.assertEqual('Đừng mở van!',terminal_candidate('đừng mở van!','?',.999))
        self.assertEqual('Anh đã kiểm tra chưa?',terminal_candidate('anh đã kiểm tra chưa?','.',.999))
        self.assertEqual('Không rõ...',terminal_candidate('không rõ...','?',.999))

    def test_terminal_policy_needs_high_confidence_and_no_indirect_context(self):
        self.assertEqual('Anh cần thêm dây không?',terminal_candidate('anh cần thêm dây không.','?',.95))
        self.assertEqual('Anh cần thêm dây không.',terminal_candidate('anh cần thêm dây không.','?',.89))
        self.assertEqual('Tôi nói không.',terminal_candidate('tôi nói không.','?',.999))
        self.assertEqual('Kiểm tra xem máy đã dừng chưa.',terminal_candidate('kiểm tra xem máy đã dừng chưa.','?',.999))
        self.assertEqual('Mấy anh đang sửa máy.',terminal_candidate('mấy anh đang sửa máy.','?',.999))

    def test_casing_only_keeps_existing_content_and_punctuation(self):
        self.assertEqual('Van A-12, áp suất 3.5 bar.',capitalize_initial('van A-12, áp suất 3.5 bar.'))
        self.assertEqual('"Anh đến chưa."',capitalize_initial('"anh đến chưa."'))
        self.assertEqual('',capitalize_initial(''))

if __name__=='__main__': unittest.main()
