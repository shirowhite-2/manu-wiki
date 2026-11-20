"""Validate actual generated OOXML with independent ZIP/XML readers and, when present, openpyxl."""
import base64
import io
import json
import subprocess
import unittest
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}


def workbook(rows, merges=None):
    result = subprocess.run(['node', '-e', r'''const writer=require('./scripts/ui/table_export.js');
const fs=require('node:fs'); const input=JSON.parse(fs.readFileSync(0,'utf8'));
process.stdout.write(Buffer.from(writer.xlsx(input.rows,input.merges,'记录')).toString('base64'));'''],
        cwd=ROOT, input=json.dumps({'rows': rows, 'merges': merges or []}), text=True, capture_output=True, check=True)
    return base64.b64decode(result.stdout)


class TableExportTest(unittest.TestCase):
    def test_real_zip_workbook_preserves_unicode_newlines_and_formula_like_text(self):
        values = [['标题', 'Value'], ['装配 & <检查>', '=HYPERLINK("https://example.test")'],
                  ['line\nbreak', '000123'], ['emoji 😀', ' comma, "quote" ']]
        with zipfile.ZipFile(io.BytesIO(workbook(values))) as archive:
            self.assertIsNone(archive.testzip())
            self.assertIn('[Content_Types].xml', archive.namelist())
            root = ET.fromstring(archive.read('xl/worksheets/sheet1.xml'))
            actual = [[cell.find('s:is/s:t', NS).text or '' for cell in row.findall('s:c', NS)]
                      for row in root.findall('s:sheetData/s:row', NS)]
            self.assertEqual(actual, values)
            self.assertFalse(root.findall('.//s:f', NS))
            self.assertTrue(all(cell.get('t') == 'inlineStr' for cell in root.findall('.//s:c', NS)))

    def test_merged_ranges_and_columns_beyond_z_are_valid(self):
        data = workbook([['Header', '', 'Tail'], ['x'] * 30], ['A1:B1'])
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            root = ET.fromstring(archive.read('xl/worksheets/sheet1.xml'))
            self.assertEqual(root.find('s:mergeCells/s:mergeCell', NS).get('ref'), 'A1:B1')
            self.assertEqual(root.findall('.//s:c', NS)[-1].get('r'), 'AD2')

    def test_control_characters_are_removed_and_csv_has_utf8_bom_and_safe_cells(self):
        with zipfile.ZipFile(io.BytesIO(workbook([['a\x00b\x0bc']]))) as archive:
            root = ET.fromstring(archive.read('xl/worksheets/sheet1.xml'))
            self.assertEqual(root.find('.//s:t', NS).text, 'abc')
        result = subprocess.run(['node', '-e', r'''const w=require('./scripts/ui/table_export.js');
process.stdout.write(w.csv([['中文', '=1+1', ' \t@SUM(A1)', 'a,"b"\nc', '\ufeff=2+2']]));'''], cwd=ROOT, capture_output=True, check=True)
        text = result.stdout.decode('utf-8')
        self.assertTrue(text.startswith('\ufeff'))
        self.assertIn('"\'=1+1"', text)
        self.assertIn('"\' \t@SUM(A1)"', text)
        self.assertIn('"a,""b""\nc"', text)
        self.assertIn("'\ufeff=2+2", text)

    def test_excel_cell_limit_fails_instead_of_truncating(self):
        result = subprocess.run(['node', '-e', r'''const w=require('./scripts/ui/table_export.js');
try {w.xlsx([['x'.repeat(32768)]]);process.exit(1)} catch(e) {process.stdout.write(e.message)}'''], cwd=ROOT, capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout, 'exportCellLimit')

    def test_independent_spreadsheet_reader_accepts_workbook_when_available(self):
        try:
            import openpyxl
        except ImportError:
            self.skipTest('Optional openpyxl is not installed; ZIP/XML validation still runs')
        book = openpyxl.load_workbook(io.BytesIO(workbook([['中文', '=1+1'], ['0001', '']], ['A2:B2'])))
        self.assertEqual(book.active['A1'].value, '中文')
        self.assertEqual(book.active['B1'].data_type, 's')
        self.assertEqual(book.active['B1'].value, '=1+1')
        self.assertIn('A2:B2', str(book.active.merged_cells))


if __name__ == '__main__':
    unittest.main()
