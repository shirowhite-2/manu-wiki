#!/bin/sh
# Keep XWiki's original startup/arguments; configure after its saved settings have been restored.
set -eu
entrypoint=${1:?Usage: configure-department-entrypoint.sh /path/to/docker-entrypoint.sh}
if grep -q '# LUXPEDIA_DEPARTMENT_AUTHORIZATION' "$entrypoint"; then exit 0; fi
output=$(mktemp "${entrypoint}.luxpedia.XXXXXX")
trap 'rm -f "$output"' EXIT HUP INT TERM
awk '
  /^[[:space:]]*set -- catalina\.sh run "\$@"[[:space:]]*$/ {
    print "  # LUXPEDIA_DEPARTMENT_AUTHORIZATION"
    print "  sh /usr/local/bin/luxpedia-configure-departments.sh \"/usr/local/tomcat/webapps/$CONTEXT_PATH/WEB-INF/xwiki.properties\""
    count++
  }
  { print }
  END { if (count != 1) { exit 1 } }
' "$entrypoint" > "$output" || {
  echo 'Unrecognized XWiki startup script; department authorization was not installed.' >&2; exit 1;
}
chmod 755 "$output"
mv "$output" "$entrypoint"
