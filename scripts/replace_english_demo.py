#!/usr/bin/env python3
"""Replace Knowledge/Drafts business pages with an English department-access demo.

The default mode only prints the replacement plan. Applying requires a verified
full backup directory and an explicit confirmation phrase. Deleted pages are sent
to the native recycle bin; Knowledge category/system pages are preserved.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import apply_sprint1 as sprint
import wiki_admin

POLICY = "luxpedia-workflow-policy-15"
ADMIN_TOOLS = "/bin/get/LuxPedia/Workflow/AdminTools?outputSyntax=plain"
CONFIRMATION = "RESET-AND-SEED-ENGLISH"
DEMO_PREFIX = "EnglishDemo"
PUBLIC_CATEGORIES = [
    "Knowledge.Technical.WebHome",
    "Knowledge.Operations.WebHome",
    "Knowledge.Faults.WebHome",
    "Knowledge.Archive.WebHome",
]

# Ten short, non-procedural search/test records in each existing business category.
ARTICLES = {
    "BOP-ACTIVE": [
        ("Active BOP Control System Overview", "System components, terminology and information links for the active BOP control area."),
        ("Active Control Unit Overview", "A synthetic catalog entry describing the active control unit and its related records."),
        ("Active Accumulator System Inventory", "An English test record for accumulator components, identifiers and ownership fields."),
        ("Active Control Panel Interface", "A sample reference for control-panel labels, display conventions and documentation links."),
        ("Active Hydraulic Circuit Map", "A non-operational overview record linking circuit names with their documentation owners."),
        ("Active Valve Status Catalogue", "A sample index of valve names, status vocabulary and associated records."),
        ("Active Redundant Supply Register", "A test record for documenting redundant supply components and revision metadata."),
        ("Active Remote Control Links", "A catalog of remote-control interface references for search and access testing."),
        ("Active Alarm and Event Glossary", "A synthetic glossary entry for alarm labels, event categories and related records."),
        ("Active System Readiness Summary", "A sample summary page for testing English search, sorting and category access."),
    ],
    "BOP-PASSIVE": [
        ("Passive BOP Stack Overview", "A synthetic catalog entry describing the passive BOP stack and its documentation links."),
        ("Passive Ram Configuration Register", "An English test record for ram identifiers, configuration names and revision history."),
        ("Passive Annular Component Index", "A sample index of annular components with documentation ownership and search terms."),
        ("Passive Choke Line Asset Map", "A non-operational asset map entry for the passive choke-line documentation set."),
        ("Passive Kill Line Equipment Index", "A catalog page for test-search coverage of passive kill-line equipment records."),
        ("Passive Seal Reference Catalogue", "A synthetic reference page for seal names, related records and metadata filters."),
        ("Passive Valve Position Register", "A sample register of valve labels and their associated Knowledge references."),
        ("Passive Barrier History Summary", "A test summary for barrier-related records, revision tracking and ownership fields."),
        ("Passive Equipment Identification Guide", "An English catalog entry for identifier conventions and linked reference pages."),
        ("Passive System Document Index", "A synthetic index page used to test department visibility and category filtering."),
    ],
    "Technical": [
        ("BOP Data Model and Naming", "An English reference for sample data fields, page naming and category metadata."),
        ("Control System Interface Overview", "A synthetic overview of interface records and their documentation relationships."),
        ("Hydraulic Fluid Reference", "A test article for terminology search and English-language metadata display."),
        ("Pressure Sensor Catalogue", "A sample catalogue of sensor identifiers, record owners and revision labels."),
        ("Signal and Alarm Vocabulary", "An English glossary-style page for sample signal and alarm terms."),
        ("Equipment Version Matrix", "A synthetic version matrix for testing article tables and export controls."),
        ("Document Revision Convention", "A sample guide to revision labels, ownership metadata and link structure."),
        ("Data Quality Review Checklist", "A non-operational checklist template for validating imported test records."),
        ("Maintenance Record Schema", "A sample description of maintenance-record fields and category references."),
        ("System Integration Reference", "A synthetic overview of integration documents and their cross-links."),
    ],
    "Operations": [
        ("Daily Readiness Record", "An English sample record for testing operational search and page metadata."),
        ("Shift Handover Summary", "A synthetic handover summary with searchable terms and revision details."),
        ("Equipment Condition Log", "A sample log page for testing category, owner, tag and update filters."),
        ("Routine Inspection Record", "A non-operational example record with an English summary and related links."),
        ("Maintenance Planning Overview", "A synthetic planning overview for testing article lists and exports."),
        ("Operations Support Directory", "A sample directory-style article that supports text search and role testing."),
        ("Work Request Intake Guide", "An English overview page describing sample request metadata and references."),
        ("Change Review Record", "A synthetic change-record entry for validating version history and workflow navigation."),
        ("Site Observation Summary", "A sample observation page with safe placeholder content for search testing."),
        ("Periodic Review Schedule", "A test schedule summary with English dates, tags and category metadata."),
    ],
    "Faults": [
        ("Low Pressure Event Summary", "A synthetic event record for testing search terms and article metadata."),
        ("Slow Response Observation", "A sample troubleshooting record with descriptive, non-procedural text."),
        ("Sensor Signal Deviation Note", "An English test page for signal-related terminology and category access."),
        ("Control Panel Alert Reference", "A sample alert reference with owner, status and revision fields."),
        ("Hydraulic Leak Observation Record", "A synthetic observation summary; it contains no operating instructions."),
        ("Communication Interruption Summary", "A test article for communication-related search and access filters."),
        ("Valve Position Mismatch Note", "A sample record describing a mismatch as a searchable test scenario."),
        ("Repeated Alarm Review", "A synthetic review summary with tags for dashboard and table testing."),
        ("Equipment Availability Incident", "An English incident record used to test activity, history and filtering."),
        ("Corrective Action Summary", "A placeholder summary page for workflow and category-access demonstrations."),
    ],
}

DEMO_USERS = [
    ("demo_bop_active", "Demo", "BOP Active", "Editors"),
    ("demo_bop_passive", "Demo", "BOP Passive", "Editors"),
    ("demo_bop_reviewer", "Demo", "BOP Reviewer", "Reviewers"),
    ("demo_unassigned", "Demo", "Unassigned", "Editors"),
]
DEMO_DEPARTMENTS = [
    {"en": "Demo BOP Active", "categories": ["Knowledge.BOP-ACTIVE.WebHome"],
     "members": ["XWiki.demo_bop_active", "XWiki.demo_bop_reviewer"]},
    {"en": "Demo BOP Passive", "categories": ["Knowledge.BOP-PASSIVE.WebHome"],
     "members": ["XWiki.demo_bop_passive", "XWiki.demo_bop_reviewer"]},
]


def admin_read(wiki, operation, **params):
    query = {"operation": operation, **params}
    status, body = wiki.request("GET", ADMIN_TOOLS + "&" + urllib.parse.urlencode(query))
    if status != 200:
        raise RuntimeError(f"Administrator read {operation} failed: HTTP {status} {body[:250]!r}")
    result = json.loads(body)
    if result.get("status") != "ok":
        raise RuntimeError(f"Administrator read {operation} returned {result.get('status')}")
    return result


def pages(wiki, area):
    result, page = [], 1
    while True:
        response = admin_read(wiki, "pages", area=area, page=str(page), q="")
        result.extend(response.get("items", []))
        if page >= int(response.get("pages", 1)):
            return result
        page += 1


def local_parts(reference):
    result, current, escaped = [], [], False
    for char in reference.removeprefix("xwiki:"):
        if escaped:
            current.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == ".":
            result.append("".join(current))
            current = []
        else:
            current.append(char)
    if escaped:
        current.append("\\")
    result.append("".join(current))
    if len(result) < 2:
        raise ValueError(f"Invalid local document reference: {reference}")
    return result


def page_resource(wiki, reference):
    parts = local_parts(reference)
    return wiki.page_path("/".join(parts[:-1]), parts[-1])


def deletion_plan(wiki):
    candidates, preserved = [], []
    for area in ("Drafts", "Knowledge"):
        for item in pages(wiki, area):
            reference = item["reference"]
            # Knowledge category homes are system definitions. Drafts category-home
            # copies are stale business drafts and are intentionally removed.
            if area == "Knowledge" and item["state"] == "protected":
                preserved.append(reference)
                continue
            if reference in {"Drafts.WebHome", "Knowledge.WebHome"}:
                preserved.append(reference)
                continue
            candidates.append(item)
    candidates.sort(key=lambda item: (0 if item["reference"].startswith("Drafts.") else 1,
                                     -len(local_parts(item["reference"])), item["reference"]))
    return candidates, preserved


def verify_backup(backup_dir):
    path = Path(backup_dir).expanduser().resolve()
    required = ("SHA256SUMS", "database.dump", "xwiki.tar.gz", "config.tar.gz")
    if not path.is_dir() or any(not (path / name).is_file() for name in required):
        raise RuntimeError(f"Incomplete backup directory: {path}")
    subprocess.run(["sha256sum", "-c", "SHA256SUMS"], cwd=path, check=True,
                   stdout=subprocess.DEVNULL)
    return path


def check_server(wiki):
    status, body = wiki.request("GET", "/bin/get/LuxPedia/Workflow/PolicyStatus?outputSyntax=plain")
    if status != 200 or POLICY.encode() not in body:
        raise RuntimeError(f"Install {POLICY} and reapply English-only configuration before resetting content.")
    numbers = wiki.objects("XWiki", "XWikiPreferences", "XWiki.XWikiPreferences")
    if not numbers:
        raise RuntimeError("XWiki preferences are unavailable.")
    prefs = wiki.object_values("XWiki", "XWikiPreferences", "XWiki.XWikiPreferences", numbers[0])
    if prefs.get("default_language") != "en" or prefs.get("languages") != "en" or prefs.get("multilingual") not in ("0", "false"):
        raise RuntimeError("Apply the English-only interface first.")


def category_keys(wiki):
    status, body = wiki.request("GET", "/bin/view/Knowledge/CategoryIndex?xpage=plain")
    if status != 200:
        raise RuntimeError(f"Could not load Knowledge category index: HTTP {status}")
    keys = list(dict.fromkeys(value.decode() for value in re.findall(rb"/bin/view/Knowledge/([A-Za-z0-9-]+)/", body)))
    missing = set(ARTICLES) - set(keys)
    if missing:
        raise RuntimeError("Missing Knowledge categories: " + ", ".join(sorted(missing)))
    return keys


def delete_old_pages(wiki, candidates):
    for item in candidates:
        reference = item["reference"]
        resource = page_resource(wiki, reference)
        status, body = wiki.request("DELETE", resource)
        if status not in (200, 202, 204, 404):
            raise RuntimeError(f"Could not recycle {reference}: HTTP {status} {body[:300]!r}")
        deadline = time.monotonic() + 30
        while status != 404 and time.monotonic() < deadline:
            status, _ = wiki.request("GET", resource)
            if status != 404:
                time.sleep(0.25)
        if status != 404:
            raise RuntimeError(f"Page still exists after recycle request: {reference}")
        print(f"Recycled {reference}")


def create_demo_users(wiki):
    for username, first, last, role in DEMO_USERS:
        sprint.ensure_user(wiki, username, first, last, sprint.GROUPS[role][0])
        print(f"Account ready: XWiki.{username} ({role}); password: .secrets/{username}_password")


def admin_write(wiki, operation, payload):
    token = wiki.form_token()
    fields = urllib.parse.urlencode({"operation": operation, "payload": json.dumps(payload), "form_token": token})
    status, body = wiki.request("POST", ADMIN_TOOLS, fields, "application/x-www-form-urlencoded", token=token)
    if status != 200:
        raise RuntimeError(f"Administrator write {operation} failed: HTTP {status} {body[:250]!r}")
    result = json.loads(body)
    if result.get("status") != "ok":
        raise RuntimeError(f"Administrator write {operation} returned {result.get('status')}")
    return result


def configure_demo_departments(wiki):
    state = admin_read(wiki, "departments")
    for department in DEMO_DEPARTMENTS:
        existing = next((item for item in state.get("departments", []) if item.get("en") == department["en"]), None)
        payload = {
            "id": existing.get("id", "") if existing else "",
            "version": existing.get("version", "") if existing else "",
            "en": department["en"], "zh": "", "enabled": True,
            "categories": department["categories"], "members": department["members"],
        }
        state = admin_write(wiki, "save-department", payload)
    state = admin_read(wiki, "departments")
    admin_write(wiki, "save-department-settings", {
        "version": state.get("version", ""), "enabled": True, "publicCategories": PUBLIC_CATEGORIES,
    })


def create_articles(wiki):
    count = 0
    slugs = {"BOP-ACTIVE": "Active", "BOP-PASSIVE": "Passive", "Technical": "Technical",
             "Operations": "Operations", "Faults": "Fault"}
    for category, articles in ARTICLES.items():
        space = "Knowledge/" + category
        if not wiki.has_object(space, "WebHome", "LuxPedia.Code.CategoryClass"):
            raise RuntimeError(f"Not a category homepage: {space}.WebHome")
        for index, (title, summary) in enumerate(articles, 1):
            page = f"{DEMO_PREFIX}{slugs[category]}{index:02d}"
            body = (f"= {title} =\n\n"
                    "> Demonstration-only synthetic content for English UI, search, filtering and access tests. "
                    "This is not an approved operating procedure.\n\n"
                    f"== Overview ==\n{summary} This sample is stored in the {category} category to exercise "
                    "article metadata, history, navigation and department visibility.\n\n"
                    "== Test notes ==\nUse this page to verify English-only rendering, article lists, search terms, "
                    "version history and the linked-draft workflow. No operating limits or real equipment "
                    "instructions are specified.\n")
            wiki.ensure_page(space, page, title, body)
            wiki.ensure_object_values(space, page, "LuxPedia.Code.ArticleClass", {
                "title": title, "primaryCategory": category, "tagsText": f"{category}, demo, English",
                "owner": "XWiki.Admin", "status": "published", "summary": summary,
                "applicableVersion": "Demo-1.0",
            })
            wiki.ensure_object_values(space, page, "XWiki.TagClass", {"tags": f"{category}|demo|English"})
            count += 1
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("XWIKI_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--backup-dir", help="Verified full backup directory required with --apply")
    parser.add_argument("--apply", action="store_true", help="Recycle old pages and seed the English demo")
    parser.add_argument("--confirm", default="", help="Must equal RESET-AND-SEED-ENGLISH")
    args = parser.parse_args()
    module = wiki_admin.load_apply_module()
    wiki = module.XWiki(args.base_url, *module.load_admin())
    status, _ = wiki.request("GET", "/rest/wikis/xwiki")
    if status != 200:
        raise RuntimeError(f"Could not connect to XWiki: HTTP {status}")
    check_server(wiki)
    category_keys(wiki)
    candidates, preserved = deletion_plan(wiki)
    print(f"Plan: recycle {len(candidates)} Knowledge/Drafts pages; preserve {len(preserved)} system/category pages.")
    for item in candidates:
        print(f"  {item['reference']} [{item['state']}]")
    if not args.apply:
        print("Dry run only; no pages, users, or settings changed.")
        return
    if args.confirm != CONFIRMATION:
        raise RuntimeError(f"Pass --confirm {CONFIRMATION} to apply the reset.")
    if not args.backup_dir:
        raise RuntimeError("--backup-dir is required when applying the reset.")
    backup = verify_backup(args.backup_dir)
    print(f"Verified backup: {backup}")
    delete_old_pages(wiki, candidates)
    create_demo_users(wiki)
    total = create_articles(wiki)
    configure_demo_departments(wiki)
    print(f"Created or verified {total} English demo articles across {len(ARTICLES)} categories.")
    print("Department restrictions are enabled; BOP-ACTIVE and BOP-PASSIVE are isolated, other listed categories are public.")
    print("Demo accounts: demo_bop_active, demo_bop_passive, demo_bop_reviewer, demo_unassigned.")
    print("Passwords are stored in .secrets/<username>_password; do not paste them into chat or logs.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
