#!/usr/bin/env python3
"""Enforce role boundaries after installing extensions/workflow-policy. Dry run by default."""
import argparse
import importlib.util
import os
import re
import urllib.parse
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEALTH = '''{{velocity}}
#if($services.luxpediaWorkflowPolicy)
$services.luxpediaWorkflowPolicy.status()
#else
POLICY_SERVICE_MISSING
#end
{{/velocity}}'''
SETUP = '''{{velocity}}
$services.luxpediaWorkflowPolicy.initialize($request.getParameter('form_token'))
{{/velocity}}'''
WORKFLOW_ACTIONS = '''{{velocity}}
#if($request.getMethod() == 'POST')
$services.luxpediaWorkflowPolicy.withdraw($request.getParameter('reference'), $request.getParameter('version'), $request.getParameter('form_token'))
#else
WITHDRAW_VERSION=$services.luxpediaWorkflowPolicy.withdrawVersion($request.getParameter('reference'))
#end
{{/velocity}}'''


class DiagnosticText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []

    def handle_data(self, data):
        self.parts.append(data)


def response_text(body):
    parsed = DiagnosticText()
    parsed.feed(body.decode('utf-8', errors='replace'))
    return ''.join(parsed.parts).strip()


def verify_response(status, body, expected, page, redactions=()):
    text = response_text(body)
    if status == 200 and text == expected:
        return
    if status in (401, 403) or text == 'POLICY_FORBIDDEN':
        raise RuntimeError('Deployment requires a wiki-level administrator. Check the account in .secrets/admin_username.')
    if 'not allowed' in text.lower() and ('groovy' in text.lower() or 'velocity' in text.lower()):
        raise RuntimeError('The diagnostic page author cannot execute its script. Update this branch and use a wiki administrator with script rights; programming/Groovy rights are not required by the updated installer.')
    if text in ('POLICY_SERVICE_MISSING', 'POLICY_NOT_INSTALLED') or text.startswith('luxpedia-workflow-policy-'):
        raise RuntimeError('Install or update workflow-policy-1.0.0.jar: rebuild the JAR and Docker image, then recreate the XWiki container. The loaded policy is missing or older than the required version.')
    if text == 'POLICY_BAD_TOKEN':
        raise RuntimeError('Workflow initialization rejected an invalid form token. Run the installer again to fetch a fresh token.')
    if text == 'POLICY_POST_REQUIRED':
        raise RuntimeError('Workflow initialization requires an authenticated POST request.')
    # XWiki can render a Velocity/Java exception while keeping HTTP 200. Surface
    # its final cause rather than asking users to copy an entire HTML stack trace.
    detail = text.rsplit('Caused by:', 1)[-1] if 'Caused by:' in text else text
    for secret in redactions:
        if secret:
            detail = detail.replace(secret, '[redacted]')
    detail = re.split(r'\s+at\s+(?:[\w$]+\.)+[\w$]+\(', detail, maxsplit=1)[0]
    detail = ' '.join(detail.split())[:800] or 'Empty diagnostic response'
    raise RuntimeError(f'Could not verify {page} (HTTP {status}): {detail}')


def load_module():
    spec = importlib.util.spec_from_file_location("apply_sprint1", ROOT / "scripts/apply_sprint1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def apply(module, wiki):
    # A diagnostic page is safe to install before the policy. Do not claim ACL-only
    # configuration prevents workflow-object tampering or reviewer contribution.
    wiki.ensure_page("LuxPedia/Workflow", "PolicyStatus", "Workflow policy status", HEALTH, hidden=True, update=True)
    wiki.ensure_object_values("LuxPedia/Workflow", "PolicyStatus", "XWiki.XWikiRights", {
        "groups": "XWiki.LuxManagers,XWiki.XWikiAdminGroup", "levels": "view", "allow": "1"})
    status, body = wiki.request("GET", "/bin/get/LuxPedia/Workflow/PolicyStatus?outputSyntax=plain")
    verify_response(status, body, 'luxpedia-workflow-policy-3', 'PolicyStatus')
    module.log("server workflow policy is active")
    wiki.ensure_page("LuxPedia/Code", "ReviewAuditClass", "Review submission audit", "Server-managed submission audit.", hidden=True)
    wiki.ensure_page("LuxPedia/Workflow", "PolicySetup", "Workflow policy setup", SETUP, hidden=True, update=True)
    wiki.ensure_object_values("LuxPedia/Workflow", "PolicySetup", "XWiki.XWikiRights", {
        "groups": "XWiki.LuxManagers,XWiki.XWikiAdminGroup", "levels": "view", "allow": "1"})
    token = wiki.form_token()
    status, body = wiki.request("POST", "/bin/get/LuxPedia/Workflow/PolicySetup?outputSyntax=plain",
                                urllib.parse.urlencode({'form_token': token}),
                                'application/x-www-form-urlencoded')
    verify_response(status, body, 'AUDIT_CLASS_READY', 'PolicySetup', redactions=(token,))
    wiki.ensure_page('LuxPedia/Workflow', 'Actions', 'Workflow actions', WORKFLOW_ACTIONS, hidden=True, update=True)
    wiki.ensure_object_values('LuxPedia/Workflow', 'Actions', 'XWiki.XWikiRights', {
        'groups': 'XWiki.LuxEditors,XWiki.LuxManagers,XWiki.XWikiAdminGroup', 'levels': 'view', 'allow': '1'})
    for group in ("XWiki.LuxEditors", "XWiki.LuxReviewers", "XWiki.LuxReaders"):
        # XWiki requires existing programming rights even to write a DENY rule
        # containing "programming". Leave that separately protected right alone;
        # a delegated wiki administrator can safely restrict ordinary scripting.
        wiki.ensure_right("XWiki", "XWikiPreferences", {
            "groups": group, "levels": "script", "allow": "0"})
        wiki.ensure_right("Knowledge", "WebPreferences", {
            "groups": group, "levels": "edit,delete,admin", "allow": "0"})
        for space in ("LuxPedia", "PublicationWorkflow"):
            wiki.ensure_page(space, "WebPreferences", "WebPreferences", "", hidden=True)
            wiki.ensure_right(space, "WebPreferences", {"groups": group, "levels": "view", "allow": "1"})
            wiki.ensure_right(space, "WebPreferences", {
                "groups": group, "levels": "edit,delete,admin", "allow": "0"})
    for space in ("LuxPedia", "PublicationWorkflow"):
        wiki.ensure_right(space, "WebPreferences", {
            "users": "XWiki.XWikiGuest", "levels": "view,edit,delete,admin", "allow": "0"})
    wiki.ensure_right("Knowledge", "WebPreferences", {
        "groups": "XWiki.LuxReaders", "levels": "comment", "allow": "0"})
    module.log("role ACLs and scripting restrictions installed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--base-url", default=os.environ.get("XWIKI_URL", "http://127.0.0.1:8080"))
    args = parser.parse_args()
    if not args.apply:
        print("Dry run: install the policy JAR, restart XWiki, then pass --apply to verify the component and configure role ACLs.")
        return
    module = load_module()
    wiki = module.XWiki(args.base_url, *module.load_admin())
    apply(module, wiki)


if __name__ == "__main__":
    main()
