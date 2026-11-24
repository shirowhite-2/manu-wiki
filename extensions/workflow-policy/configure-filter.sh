#!/bin/sh
# XWiki's empty absolute-ordering disables ServletContainerInitializer discovery.
# Register the outer request guard explicitly without enabling unrelated scanning.
set -eu
config=${1:?Usage: configure-filter.sh /path/to/WEB-INF/web.xml}
if grep -q '<filter-name>luxpedia-draft-write-boundary</filter-name>' "$config"; then
  count=$(grep -c '<filter-name>luxpedia-draft-write-boundary</filter-name>' "$config")
  [ "$count" = 2 ] && grep -q '<filter-class>org.luxpedia.workflow.DraftWriteBoundary</filter-class>' "$config" || {
    echo 'Incomplete LuxPedia draft filter configuration; refusing to overwrite it.' >&2; exit 1;
  }
  exit 0
fi
output=$(mktemp "${config}.luxpedia.XXXXXX")
trap 'rm -f "$output"' EXIT HUP INT TERM
awk '
  /^[[:space:]]*<filter>$/ && !declared {
    print "  <filter>"
    print "    <filter-name>luxpedia-draft-write-boundary</filter-name>"
    print "    <filter-class>org.luxpedia.workflow.DraftWriteBoundary</filter-class>"
    print "    <async-supported>true</async-supported>"
    print "  </filter>"
    declared = 1
  }
  /^[[:space:]]*<filter-mapping>$/ && !mapped {
    print "  <filter-mapping>"
    print "    <filter-name>luxpedia-draft-write-boundary</filter-name>"
    print "    <url-pattern>/*</url-pattern>"
    print "    <dispatcher>REQUEST</dispatcher>"
    print "    <dispatcher>FORWARD</dispatcher>"
    print "  </filter-mapping>"
    mapped = 1
  }
  { print }
  END { if (!declared || !mapped) { exit 1 } }
' "$config" > "$output" || {
  echo 'Unrecognized XWiki web.xml; draft filter was not installed.' >&2; exit 1;
}
# Keep the container descriptor readable by the Tomcat process.
chmod 644 "$output"
mv "$output" "$config"
