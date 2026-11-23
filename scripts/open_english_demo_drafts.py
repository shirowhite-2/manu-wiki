#!/usr/bin/env python3
"""Open ten linked demo drafts as their department-scoped editor owners."""
import argparse
import json
import os
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import apply_sprint1 as sprint

POLICY = "luxpedia-workflow-policy-16"
ENDPOINT = "/bin/get/LuxPedia/Workflow/EditLock?outputSyntax=plain"
TARGETS = [
    ("demo_bop_active", *(f"Knowledge.BOP-ACTIVE.EnglishDemoActive{i:02d}" for i in range(1, 6))),
    ("demo_bop_passive", *(f"Knowledge.BOP-PASSIVE.EnglishDemoPassive{i:02d}" for i in range(1, 6))),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("XWIKI_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--apply", action="store_true", help="Open the linked drafts and acquire their editor leases")
    args = parser.parse_args()
    if not args.apply:
        for username, *targets in TARGETS:
            print(username + ": " + ", ".join(targets))
        print("Dry run only; use --apply to create/open these linked drafts.")
        return

    status_client = sprint.XWiki(args.base_url, *sprint.load_admin())
    status, body = status_client.request("GET", "/bin/get/LuxPedia/Workflow/PolicyStatus?outputSyntax=plain")
    if status != 200 or POLICY.encode() not in body:
        raise RuntimeError(f"Install {POLICY} before opening demo drafts.")

    for username, *targets in TARGETS:
        password_path = sprint.SECRETS / f"{username}_password"
        if not password_path.is_file():
            raise RuntimeError(f"Missing demo account password file: {password_path}")
        wiki = sprint.XWiki(args.base_url, username, password_path.read_text().strip())
        token = wiki.form_token()
        for target in targets:
            fields = urllib.parse.urlencode({"reference": target, "operation": "open", "form_token": token})
            status, body = wiki.request("POST", ENDPOINT, fields, "application/x-www-form-urlencoded", token=token)
            if status != 200:
                raise RuntimeError(f"Could not open {target} as {username}: HTTP {status} {body[:250]!r}")
            result = json.loads(body)
            if result.get("status") != "owned":
                raise RuntimeError(f"Could not acquire the draft lease for {target} as {username}: {result.get('status')}")
            print(f"{username} owns {result.get('reference')}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
