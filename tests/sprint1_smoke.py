"""Read-only checks for the Sprint 1 wiki structure and permissions."""
import base64
import hashlib
import os
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("XWIKI_URL", "http://127.0.0.1:8080").rstrip("/")
REST = BASE + "/rest/wikis/xwiki"


def credentials(user):
    if user == "admin":
        name = (ROOT / ".secrets/admin_username").read_text().strip()
        password = (ROOT / ".secrets/admin_password").read_text().strip()
    else:
        name = user
        password = (ROOT / f".secrets/{user}_password").read_text().strip()
    return name, password


def get(path, user=None):
    headers = {}
    if user:
        name, password = credentials(user)
        headers["Authorization"] = "Basic " + base64.b64encode(
            f"{name}:{password}".encode()
        ).decode()
    request = urllib.request.Request(
        path if path.startswith("http") else BASE + path, headers=headers
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        body = error.read()
        error.close()
        return error.code, body


def rest(path, user="admin"):
    return get(REST + path, user)


class SprintOneSmoke(unittest.TestCase):
    def test_main_and_knowledge_pages_exist(self):
        for path in (
            "/bin/view/Main/",
            "/bin/view/Knowledge/WebHome",
            "/bin/view/Knowledge/Technical/WebHome",
            "/bin/view/Knowledge/DocumentLibrary",
            "/bin/view/Drafts/WebHome",
        ):
            with self.subTest(path=path):
                status, body = get(path, "admin")
                self.assertEqual(status, 200)
                self.assertNotIn(b"Unknown macro", body)

    def test_article_and_category_classes_exist(self):
        for class_name, fields in (
            ("LuxPedia.Code.ArticleClass", (b'title', b'primaryCategory', b'status')),
            ("LuxPedia.Code.CategoryClass", (b'name', b'owner', b'template')),
        ):
            with self.subTest(class_name=class_name):
                status, body = rest(f"/classes/{class_name}/properties")
                self.assertEqual(status, 200)
                for field in fields:
                    self.assertIn(b'name="' + field + b'"', body)

    def test_guest_is_denied(self):
        for path in ("/bin/view/Knowledge/WebHome", "/bin/view/Drafts/WebHome"):
            with self.subTest(path=path):
                status, _ = get(path)
                self.assertIn(status, (401, 403, 404))
        self.assertIn(get("/bin/create/Main/TestAnonymous")[0], (401, 403, 404))

    def test_category_guide_and_index_exist(self):
        for path in ("/bin/view/Knowledge/CategoryGuide", "/bin/view/Knowledge/CategoryIndex"):
            with self.subTest(path=path):
                status, body = get(path, "pocreader")
                self.assertEqual(status, 200)
                self.assertIn("技术文档".encode(), body)

    def test_bop_categories_exist_and_are_indexed(self):
        for key in ("BOP-ACTIVE", "BOP-PASSIVE"):
            with self.subTest(key=key):
                status, body = get(f"/bin/view/Knowledge/{key}/WebHome", "pocreader")
                self.assertEqual(status, 200)
                status, body = get("/bin/view/Knowledge/CategoryIndex", "pocreader")
                self.assertIn(f"/bin/view/Knowledge/{key}/".encode(), body)

    def test_category_admin_form_only_for_admins(self):
        status, body = get("/bin/view/Knowledge/CategoryAdmin", "admin")
        self.assertEqual(status, 200)
        self.assertIn(b'name="key"', body)
        status, body = get("/bin/view/Knowledge/CategoryAdmin", "poceditor")
        self.assertEqual(status, 200)
        self.assertNotIn(b'name="key"', body)

    def test_source_edit_link_is_visible_only_for_editors(self):
        status, body = get("/bin/view/Drafts/SampleArticle", "poceditor")
        self.assertEqual(status, 200)
        self.assertIn(b"tmSourceEdit", body)
        for user in ("pocreviewer", "pocreader"):
            with self.subTest(user=user):
                status, body = get("/bin/view/Drafts/SampleArticle", user)
                if status == 200:
                    self.assertNotIn(b"tmSourceEdit", body)

    def test_reader_permissions(self):
        self.assertEqual(get("/bin/view/Knowledge/WebHome", "pocreader")[0], 200)
        self.assertIn(
            get("/bin/view/Drafts/WebHome", "pocreader")[0], (401, 403, 404)
        )
        self.assertIn(
            get("/bin/edit/Knowledge/Technical/SampleArticle", "pocreader")[0],
            (401, 403, 404),
        )

    def test_editor_permissions_and_source_edit(self):
        self.assertEqual(get("/bin/view/Drafts/WebHome", "poceditor")[0], 200)
        self.assertEqual(
            get("/bin/edit/Knowledge/Technical/SampleArticle", "poceditor")[0], 403
        )
        self.assertEqual(
            get("/bin/edit/Drafts/SampleArticle?editor=wiki", "poceditor")[0], 200
        )

    def test_reviewer_is_read_only_in_drafts(self):
        self.assertEqual(get("/bin/view/Drafts/WebHome", "pocreviewer")[0], 200)
        self.assertEqual(
            get("/bin/edit/Drafts/SampleArticle?editor=wiki", "pocreviewer")[0], 403
        )

    def test_category_and_document_library_render_articles(self):
        status, body = get("/bin/view/Knowledge/DocumentLibrary", "pocreader")
        self.assertEqual(status, 200)
        self.assertIn("示例技术文档".encode(), body)
        self.assertIn("多媒体与图表示例".encode(), body)
        status, body = get("/bin/view/Knowledge/Technical/WebHome", "pocreader")
        self.assertEqual(status, 200)
        self.assertIn("示例技术文档".encode(), body)

    def test_media_and_chart_render_for_reader(self):
        status, body = get("/bin/view/Knowledge/Technical/MacroShowcase", "pocreader")
        self.assertEqual(status, 200)
        self.assertIn(b"<video", body)
        self.assertIn(b'class="chart"', body)
        self.assertIn(b"architecture.svg", body)

    def test_attachment_bytes_match_source(self):
        for name in ("architecture.svg", "sample.mp4"):
            with self.subTest(name=name):
                status, body = get(
                    "/rest/wikis/xwiki/spaces/Knowledge/spaces/Technical/pages/MacroShowcase/attachments/"
                    + name,
                    "pocreader",
                )
                self.assertEqual(status, 200)
                expected = ROOT / "examples/attachments" / name
                self.assertEqual(
                    hashlib.sha256(body).digest(),
                    hashlib.sha256(expected.read_bytes()).digest(),
                )

    def test_page_history_is_available(self):
        status, body = rest(
            "/spaces/Knowledge/spaces/Technical/pages/SampleArticle/history",
            "admin",
        )
        self.assertEqual(status, 200)
        self.assertIn(b"version", body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
