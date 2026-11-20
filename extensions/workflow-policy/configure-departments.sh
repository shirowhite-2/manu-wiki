#!/bin/sh
set -eu
config=${1:?Usage: configure-departments.sh /path/to/WEB-INF/xwiki.properties}
[ -f "$config" ] || { echo 'XWiki configuration was not found.' >&2; exit 1; }
current=$(sed -n 's/^[[:space:]]*security\.authorization\.settler[[:space:]]*=[[:space:]]*//p' "$config")
case "$current" in ''|default|luxpedia-departments) ;; *) echo 'A different authorization settler is configured; integrate it before replacing it.' >&2; exit 1;; esac
output=$(mktemp "${config}.luxpedia.XXXXXX")
trap 'rm -f "$output"' EXIT HUP INT TERM
sed '/^[[:space:]]*security\.authorization\.settler[[:space:]]*=/d' "$config" > "$output"
printf '\n# LuxPedia department scope supplements native site permissions.\nsecurity.authorization.settler=luxpedia-departments\n' >> "$output"
chmod 644 "$output"
mv "$output" "$config"
