"""The official XWiki descriptor skips library SCI discovery; install only our filter."""
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'extensions/workflow-policy/configure-filter.sh'
XML = '''<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="5.0" metadata-complete="true">
  <absolute-ordering />
  <display-name>XWiki</display-name>
  <filter>
    <filter-name>RoutingFilter</filter-name>
    <filter-class>org.xwiki.resource.servlet.RoutingFilter</filter-class>
  </filter>
  <filter-mapping>
    <filter-name>RoutingFilter</filter-name>
    <url-pattern>/*</url-pattern>
  </filter-mapping>
  <servlet><servlet-name>unchanged</servlet-name><servlet-class>Original</servlet-class></servlet>
</web-app>
'''


class ConfigureFilterTest(unittest.TestCase):
    def run_script(self, path):
        return subprocess.run(['sh', str(SCRIPT), str(path)], capture_output=True, text=True)

    def test_registers_outer_guard_without_enabling_scanning_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'web with spaces.xml'
            path.write_text(XML)
            result = self.run_script(path)
            self.assertEqual(result.returncode, 0, result.stderr)
            updated = path.read_text()
            ns = {'j': 'https://jakarta.ee/xml/ns/jakartaee'}
            root = ET.fromstring(updated)
            self.assertEqual(root.get('metadata-complete'), 'true')
            self.assertEqual(len(root.find('j:absolute-ordering', ns)), 0)
            self.assertEqual(root.findall('j:filter/j:filter-name', ns)[0].text, 'luxpedia-draft-write-boundary')
            self.assertEqual(root.findall('j:filter-mapping/j:filter-name', ns)[0].text, 'luxpedia-draft-write-boundary')
            self.assertIn('<servlet-class>Original</servlet-class>', updated)
            self.assertEqual(self.run_script(path).returncode, 0)
            self.assertEqual(path.read_text(), updated)

    def test_unrecognized_or_partial_descriptor_is_left_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'web.xml'
            for content in ['<web-app/>', '<filter-name>luxpedia-draft-write-boundary</filter-name>']:
                path.write_text(content)
                self.assertNotEqual(self.run_script(path).returncode, 0)
                self.assertEqual(path.read_text(), content)


if __name__ == '__main__':
    unittest.main()
