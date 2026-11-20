ARG XWIKI_IMAGE=xwiki:17.10.15-postgres-tomcat
FROM ${XWIKI_IMAGE}
COPY target/workflow-policy-1.0.0.jar /usr/local/tomcat/webapps/ROOT/WEB-INF/lib/workflow-policy-1.0.0.jar
# Official XWiki disables library SCI scanning. Keep that setting and register only our guard.
COPY configure-filter.sh /tmp/luxpedia-configure-filter.sh
RUN sh /tmp/luxpedia-configure-filter.sh /usr/local/tomcat/webapps/ROOT/WEB-INF/web.xml \
    && rm /tmp/luxpedia-configure-filter.sh

COPY configure-departments.sh /usr/local/bin/luxpedia-configure-departments.sh
COPY configure-department-entrypoint.sh /tmp/luxpedia-configure-department-entrypoint.sh
RUN sh /usr/local/bin/luxpedia-configure-departments.sh /usr/local/tomcat/webapps/ROOT/WEB-INF/xwiki.properties \
    && sh /tmp/luxpedia-configure-department-entrypoint.sh "$(command -v docker-entrypoint.sh)" \
    && rm /tmp/luxpedia-configure-department-entrypoint.sh
