"""Exercise URI escaping through a real local HTTP transport, never a live wiki."""
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import StringIO
from pathlib import Path
import sys
import threading
import unittest
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import apply_sprint1 as sprint1
import apply_workflow as workflow


class XWikiClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.reply(200)

            def do_PUT(self):
                self.reply(200 if '/properties/' in self.path else 201)

            def reply(self, status):
                body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                cls.requests.append((self.command, self.path, body))
                self.send_response(status)
                self.end_headers()
                self.wfile.write(b'<properties xmlns="http://www.xwiki.org"/>')

            def log_message(self, *args):
                pass

        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        self.requests.clear()
        self.wiki = sprint1.XWiki(self.base_url, 'fixture-user', 'fixture-password')
        self.wiki.token = 'fixture-token'

    def test_unicode_and_reserved_names_survive_real_http_and_xml_unchanged(self):
        space = 'Knowledge/中文分类/Section + #%'
        page = '流程 说明?版本#1%20+&/文稿'
        with redirect_stdout(StringIO()):
            self.wiki.ensure_page(space, page, '中文标题', '正文与附件引用保持原文。', update=True)
        for method, target, body in self.requests:
            target.encode('ascii')  # urllib/http.client requires ASCII request targets.
            uri = urllib.parse.urlsplit(target)
            self.assertFalse(uri.query)
            self.assertFalse(uri.fragment)
            names = [urllib.parse.unquote(part) for part in uri.path.split('/')]
            self.assertEqual(names, ['', 'rest', 'wikis', 'xwiki', 'spaces', 'Knowledge',
                'spaces', '中文分类', 'spaces', 'Section + #%', 'pages', page])
            if method == 'PUT':
                document = ET.fromstring(body)
                self.assertEqual(document.findtext(sprint1.NS + 'title'), '中文标题')
                self.assertEqual(document.findtext(sprint1.NS + 'content'), '正文与附件引用保持原文。')
        self.assertEqual([method for method, target, body in self.requests], ['GET', 'PUT'])

    def test_object_properties_and_attachments_encode_each_dynamic_segment(self):
        space, page = 'Knowledge/中文分类', '测试文章'
        class_name, field, attachment = 'Demo.中文Class', '字段 #1', '演示 视频#1%20+.mp4'
        self.wiki.object_values(space, page, class_name, 0)
        self.wiki.set_object_property(space, page, class_name, 0, field, '中文属性值')
        self.assertTrue(self.wiki.attachment_exists(space, page, attachment))
        targets = [target for method, target, body in self.requests]
        for target in targets:
            target.encode('ascii')
            self.assertFalse(urllib.parse.urlsplit(target).query)
            self.assertFalse(urllib.parse.urlsplit(target).fragment)
        self.assertTrue(targets[0].endswith('/objects/' + urllib.parse.quote(class_name, safe='') + '/0/properties'))
        self.assertEqual(urllib.parse.unquote(targets[1].rsplit('/', 1)[1]), field)
        self.assertEqual(urllib.parse.unquote(targets[2].rsplit('/', 1)[1]), attachment)
        self.assertEqual(self.requests[1][2].decode(), '中文属性值')

    def test_ascii_paths_stay_stable_and_literal_percent_sequences_are_not_predecoded(self):
        self.assertEqual(sprint1.XWiki.page_path('LuxPedia/Workflow', 'PolicyStatus'),
                         '/rest/wikis/xwiki/spaces/LuxPedia/spaces/Workflow/pages/PolicyStatus')
        target = sprint1.XWiki.page_path('Knowledge', 'name%20literal')
        self.assertTrue(target.endswith('/name%2520literal'))
        self.assertEqual(urllib.parse.unquote(target.rsplit('/', 1)[1]), 'name%20literal')

    def test_category_links_are_decoded_once_before_building_rest_paths(self):
        class Wiki:
            def request(self, method, path):
                return 200, ('<a href="/bin/view/Knowledge/' +
                    urllib.parse.quote('中文分类 100%20+', safe='') + '/">分类名称</a>').encode()
        categories = workflow.knowledge_categories(Wiki())
        self.assertEqual(categories, {'分类名称': '中文分类 100%20+'})
        target = sprint1.XWiki.page_path('Knowledge/' + categories['分类名称'], '中文文章')
        self.assertIn('/spaces/' + urllib.parse.quote('中文分类 100%20+', safe='') + '/pages/', target)
        self.assertEqual(urllib.parse.unquote(target.rsplit('/', 1)[1]), '中文文章')

    def test_bootstrap_checks_chinese_rest_page_names_without_renaming_or_double_encoding(self):
        class Wiki(sprint1.XWiki):
            def __init__(self):
                super().__init__('http://fixture.invalid', 'fixture', 'fixture')
                self.paths = []
                self.created = []

            def add_object(self, space, page, class_name, properties):
                self.created.append((space, page, class_name, properties))

            def request(self, method, path, *args, **kwargs):
                self.paths.append(path)
                path.encode('ascii')
                if path == '/bin/view/Knowledge/CategoryIndex':
                    return 200, '<a href="/bin/view/Knowledge/Technical/">技术文档</a>'.encode()
                if path.endswith('/pages'):
                    return 200, ('<pages xmlns="http://www.xwiki.org"><pageSummary>'
                        '<name>中文文章%20原名</name></pageSummary></pages>').encode()
                if path.endswith('/objects'):
                    published = ('<objectSummary><className>PublicationWorkflow.PublicationWorkflowClass</className>'
                        '<number>0</number></objectSummary>') if '/spaces/Knowledge/' in path else ''
                    return 200, ('<objects xmlns="http://www.xwiki.org">'
                        '<objectSummary><className>LuxPedia.Code.ArticleClass</className><number>0</number></objectSummary>' +
                        published + '</objects>').encode()
                if path.endswith('/properties'):
                    return 200, ('<properties xmlns="http://www.xwiki.org"><property name="primaryCategory">'
                        '<value>技术文档</value></property></properties>').encode()
                if path == '/bin/view/Drafts/WebHome':
                    return 200, ('<a href="/bin/view/Drafts/' + urllib.parse.quote('中文文章%20原名', safe='') + '">中文草稿</a>').encode()
                raise AssertionError(path)

        class Module:
            @staticmethod
            def log(message):
                pass

        wiki = Wiki()
        workflow.bootstrap_workflow_targets(Module, wiki)
        self.assertIn(sprint1.XWiki.page_path('Knowledge/Technical', '中文文章%20原名') + '/objects', wiki.paths)
        self.assertTrue(all('%2520' in path for path in wiki.paths if path.endswith('/objects')))
        self.assertEqual(len(wiki.created), 1)
        self.assertEqual(wiki.created[0][:2], ('Drafts', '中文文章%20原名'))
        self.assertEqual(wiki.created[0][3]['target'], 'Knowledge.Technical.中文文章%20原名')


if __name__ == '__main__':
    unittest.main()
