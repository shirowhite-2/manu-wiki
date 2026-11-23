"""Offline tests for the guarded English demo replacement plan."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import replace_english_demo as demo
import open_english_demo_drafts as drafts


class EnglishDemoTest(unittest.TestCase):
    def test_demo_corpus_has_fifty_english_articles_across_existing_categories(self):
        self.assertEqual(sum(len(articles) for articles in demo.ARTICLES.values()), 50)
        self.assertEqual(set(demo.ARTICLES), {'BOP-ACTIVE', 'BOP-PASSIVE', 'Technical', 'Operations', 'Faults'})
        for articles in demo.ARTICLES.values():
            for title, summary in articles:
                self.assertTrue(title.isascii())
                self.assertTrue(summary.isascii())

    def test_department_test_accounts_separate_the_two_bop_scopes(self):
        departments = {item['en']: set(item['categories']) for item in demo.DEMO_DEPARTMENTS}
        self.assertEqual(departments['Demo BOP Active'], {'Knowledge.BOP-ACTIVE.WebHome'})
        self.assertEqual(departments['Demo BOP Passive'], {'Knowledge.BOP-PASSIVE.WebHome'})
        self.assertIn('XWiki.demo_bop_reviewer', demo.DEMO_DEPARTMENTS[0]['members'])
        self.assertIn('XWiki.demo_bop_reviewer', demo.DEMO_DEPARTMENTS[1]['members'])
        self.assertNotIn('XWiki.demo_unassigned', sum((item['members'] for item in demo.DEMO_DEPARTMENTS), []))

    def test_ten_workflow_drafts_are_split_between_the_two_bop_departments(self):
        self.assertEqual(sum(len(targets) for _, *targets in drafts.TARGETS), 10)
        self.assertEqual({username for username, *_ in drafts.TARGETS}, {
            'demo_bop_active', 'demo_bop_passive'})

    def test_local_reference_parser_preserves_escaped_page_dots(self):
        self.assertEqual(demo.local_parts(r'Knowledge.BOP.Article\.Rev2'), ['Knowledge', 'BOP', 'Article.Rev2'])


if __name__ == '__main__':
    unittest.main()
