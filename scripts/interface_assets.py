"""Shared interface messages for English-only generated XWiki JavaScript."""
import json
from pathlib import Path

UI = Path(__file__).resolve().parent / "ui"
MESSAGES = {language: json.loads((UI / f"{language}.json").read_text()) for language in ("en", "zh")}
if MESSAGES["en"].keys() != MESSAGES["zh"].keys():
    raise ValueError("English and Chinese interface keys must match")
I18N_JS = (UI / "interface.js").read_text().replace(
    "__LUXPEDIA_MESSAGES__", json.dumps({"en": MESSAGES["en"]}, ensure_ascii=False))
