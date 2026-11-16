import unittest
from score import assess

class SemanticScoringTest(unittest.TestCase):
    def gold(self,**extra):
        return dict(id='synthetic',book='synthetic',split='test',category='sense',unit='bank',lemma='bank',pos='сущ.',expected='берег',confidence='provisional',acceptable='',**extra)
    def prediction(self,primary,**extra):
        return dict(found=True,lemma='bank',pos='сущ.',russian=[primary],primary=primary,english=[],all=[],contextMeaning='',**extra)
    def test_correct_lemma_does_not_make_wrong_sense_correct(self):
        r=assess(self.gold(),self.prediction('банк'))
        self.assertTrue(r['morphology']);self.assertFalse(r['usable'])
    def test_stress_mark_is_not_a_semantic_error(self):
        g=self.gold();g.update(lemma='write',pos='гл.',expected='писать')
        p=self.prediction('писа́ть');p.update(lemma='write',pos='гл.')
        self.assertEqual('CORRECT',assess(g,p)['status'])
    def test_power_is_not_modal_possibility(self):
        g=self.gold();g.update(unit='might',lemma='might|may',pos='гл.',expected='мог|мочь|возможно')
        p=self.prediction('мощь, могущество, сила');p.update(lemma='might')
        self.assertFalse(assess(g,p)['usable'])
    def test_russian_prefix_does_not_turn_english_into_russian_translation(self):
        g=self.gold();g.update(unit="I'm",lemma="I'm",pos='сокр.',expected='я',category='contraction',expansion='I am')
        p=self.prediction('Сокращение: I am');p.update(lemma="I'm",pos='сокр.',english=['I am'])
        r=assess(g,p)
        self.assertTrue(r['expansion']);self.assertFalse(r['russian']);self.assertEqual('LANGUAGE_FALLBACK',r['status'])
    def test_phrase_root_recognition_does_not_validate_the_phrase_meaning(self):
        g=self.gold();g.update(unit='given',lemma='give',pos='гл.',category='morphology',expected='отдавать')
        p=self.prediction('дайте мне');p.update(lemma='give me',pos='гл.')
        r=assess(g,p)
        self.assertTrue(r['morphology']);self.assertFalse(r['usable'])

if __name__=='__main__':unittest.main()
