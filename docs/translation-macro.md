# English-only mode

The current test deployment is English-only. Applying `scripts/apply_workflow.py --apply` sets XWiki's default and enabled language to English, disables multilingual pages, removes Chinese translations from LuxPedia-managed pages, and no longer shows an English/Chinese switch. Business pages in `Knowledge` and `Drafts` are replaced separately during the approved content reset.

The XWiki Standard Translation macro remains installed as part of the platform, but the LuxPedia editor documentation and article templates no longer use it. Keep it installed rather than removing a core extension; the English-only configuration doesn't depend on inserting the macro into article content.

To apply the English-only interface to the local instance, run from the repository after deploying the required workflow-policy version:

```bash
python3 scripts/apply_workflow.py --base-url http://127.0.0.1:8081 --apply
```

The installer is idempotent. It removes Chinese translations only from pages it manages; it doesn't rewrite arbitrary user pages. For the approved test-data replacement, first create and verify a full database/XWiki-volume backup, then preview and apply:

```bash
./scripts/backup.sh
python3 scripts/replace_english_demo.py --base-url http://127.0.0.1:8081 \
  --backup-dir backups/<completed-backup-directory>
python3 scripts/replace_english_demo.py --base-url http://127.0.0.1:8081 \
  --backup-dir backups/<completed-backup-directory> --apply --confirm RESET-AND-SEED-ENGLISH
```

The reset moves old `Knowledge`/`Drafts` business pages and their page translations to the native recycle bin, preserves category and system pages, and creates 50 synthetic English articles across BOP-ACTIVE, BOP-PASSIVE, Technical, Operations and Faults. It also creates `demo_bop_active`, `demo_bop_passive`, `demo_bop_reviewer` and `demo_unassigned`; generated passwords are saved with mode 0600 in `.secrets/<username>_password`. Department restrictions are enabled with non-BOP categories public. The pre-existing `IT` department is retained; its current `pocreader` member keeps the two BOP scopes assigned to that department.

To create ten linked drafts for workflow testing—five in each BOP department—run:

```bash
python3 scripts/open_english_demo_drafts.py --base-url http://127.0.0.1:8081 --apply
```

The active/passive demo editors own their corresponding draft leases; the reviewer account belongs to both BOP departments, while the unassigned editor can access only the public categories. The fifty demo articles remain the unique published targets; the ten Drafts pages are their linked working copies.
