"""Read-only bulk-import planning checks. No XWiki or real source data is used."""
import csv
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('plan_import', ROOT / 'scripts/plan_import.py')
planner = importlib.util.module_from_spec(spec); spec.loader.exec_module(planner)


class ImportPlanTest(unittest.TestCase):
    def test_titles_are_literal_wiki_text_and_paths_stay_unchanged(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'source'; root.mkdir()
            name = '{{velocity}}[example].docx'
            (root / name).write_bytes(b'content')
            rows, _ = planner.scan(root)
            self.assertNotIn('{{', rows[0]['doc.title'])
            self.assertEqual(rows[0]['doc.file'], './' + name)

    def test_large_videos_are_inventoried_without_loading_or_hashing_them(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'source'; root.mkdir()
            (root / '说明.docx').write_bytes(b'document')
            with (root / 'video.mp4').open('wb') as video:
                video.truncate(3 * 1024 ** 3)
            with patch.object(planner, 'sha256_file', side_effect=AssertionError('must not read video content')):
                report = planner.plan(root, Path(temp) / 'plan', batch_mib=1024, max_file_mib=100)
            self.assertEqual(report['files'], 2)
            self.assertEqual(report['total_bytes'], 3 * 1024 ** 3 + 8)
            self.assertEqual(report['oversized_files'], ['./video.mp4'])
            self.assertEqual(report['by_kind'], {'office': 1, 'video': 1})
            self.assertFalse(report['checksums_computed'])
            self.assertEqual((root / '说明.docx').read_bytes(), b'document')

    def test_csv_keeps_paths_and_unicode_names_and_hashes_are_optional(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'source'; (root / '技术资料').mkdir(parents=True)
            (root / '技术资料' / '带,逗号.docx').write_bytes(b'abc')
            report = planner.plan(root, Path(temp) / 'plan', checksum=True)
            with (Path(temp) / 'plan' / 'office-001.csv').open(encoding='utf-8', newline='') as stream:
                row = next(csv.DictReader(stream))
            self.assertEqual(row['doc.file'], './技术资料/带,逗号.docx')
            self.assertEqual(row['doc.title'], '带,逗号')
            self.assertEqual(row['sha256'], hashlib.sha256(b'abc').hexdigest())
            self.assertTrue(row['doc.name'].startswith('import-'))
            self.assertTrue(report['checksums_computed'])

    def test_symlinks_are_skipped_and_reports_cannot_overwrite_the_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'source'; root.mkdir()
            other = Path(temp) / 'private.docx'; other.write_bytes(b'not source data')
            (root / 'external.docx').symlink_to(other)
            report = planner.plan(root, Path(temp) / 'plan')
            self.assertEqual(report['files'], 0)
            self.assertEqual(report['skipped_symlinks'], ['external.docx'])
            with self.assertRaisesRegex(ValueError, 'outside the source'):
                planner.plan(root, root / 'report')
            with self.assertRaisesRegex(ValueError, 'already exists'):
                planner.plan(root, Path(temp) / 'plan')


if __name__ == '__main__':
    unittest.main()
