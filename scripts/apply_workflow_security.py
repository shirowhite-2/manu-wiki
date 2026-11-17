#!/usr/bin/env python3
"""Enforce role boundaries after installing extensions/workflow-policy. Dry run by default."""
import argparse
import importlib.util
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEALTH = '''{{groovy}}
import com.xpn.xwiki.web.Utils
import org.xwiki.observation.EventListener
if (!xwiki.hasAdminRights()) { println("FORBIDDEN"); return }
try {
  println(Utils.getComponent(EventListener.class, "luxpedia-workflow-policy").getPolicyVersion())
} catch (Exception error) { println("POLICY_NOT_INSTALLED") }
{{/groovy}}'''


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
    if status != 200 or body.strip() != b"luxpedia-workflow-policy-1":
        raise RuntimeError("Install workflow-policy-1.0.0.jar and restart XWiki first. "
                           "The status page must be authored by an administrator with programming rights.")
    module.log("server workflow policy is active")
    wiki.ensure_page("LuxPedia/Code", "ReviewAuditClass", "Review submission audit", "Server-managed submission audit.", hidden=True)
    # Use XWiki's existing class bootstrap mechanism, run only by the administrator.
    class_setup = '''{{groovy}}
if (!xwiki.hasAdminRights()) { println("FORBIDDEN"); return }
def d = xwiki.getDocument("LuxPedia.Code.ReviewAuditClass")
def c = d.getDocument().getXClass()
def changed = false
if (c.get("submittedBy") == null) { c.addTextField("submittedBy", "Submitted by", 60); changed = true }
if (c.get("authoredBy") == null) { c.addTextField("authoredBy", "Content authored by", 60); changed = true }
if (c.get("submittedVersion") == null) { c.addTextField("submittedVersion", "Submitted version", 30); changed = true }
if (changed) d.save("Ensure review audit fields")
def listener = com.xpn.xwiki.web.Utils.getComponent(org.xwiki.observation.EventListener.class, "luxpedia-workflow-policy")
println("Draft permissions normalized: " + listener.repairExistingDraftRights(xcontext.getContext()))
println("AUDIT_CLASS_READY")
{{/groovy}}'''
    wiki.ensure_page("LuxPedia/Workflow", "PolicySetup", "Workflow policy setup", class_setup, hidden=True, update=True)
    wiki.ensure_object_values("LuxPedia/Workflow", "PolicySetup", "XWiki.XWikiRights", {
        "groups": "XWiki.LuxManagers,XWiki.XWikiAdminGroup", "levels": "view", "allow": "1"})
    status, body = wiki.request("GET", "/bin/get/LuxPedia/Workflow/PolicySetup?outputSyntax=plain")
    if status != 200 or b"AUDIT_CLASS_READY" not in body:
        raise RuntimeError("Could not initialize server-managed review audit fields")
    for group in ("XWiki.LuxEditors", "XWiki.LuxReviewers", "XWiki.LuxReaders"):
        wiki.ensure_right("XWiki", "XWikiPreferences", {
            "groups": group, "levels": "script,programming", "allow": "0"})
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
