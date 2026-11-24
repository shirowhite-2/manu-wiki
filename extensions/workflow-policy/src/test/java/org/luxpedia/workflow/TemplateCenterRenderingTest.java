package org.luxpedia.workflow;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.velocity.tools.EscapeTool;
import static org.junit.jupiter.api.Assertions.*;

/** Render the real page with Velocity; native XWiki routes remain read-only here. */
class TemplateCenterRenderingTest {
    public static class Access {
        final boolean admin;
        Access(boolean admin) { this.admin = admin; }
        public boolean hasAccess(String right, Object reference) { return admin; }
    }
    public static class Model {
        public DocumentReference resolveDocument(String name) { return new DocumentReference("xwiki", "XWiki", "XWikiPreferences"); }
        public DocumentReference createDocumentReference(String name, SpaceReference parent) { return new DocumentReference(name, parent); }
        public String serialize(Object reference, String hint) { return reference.toString().replaceFirst("^xwiki:", ""); }
    }
    public static class Localization {
        public String render(String key) { return key; }
    }
    public static class Doc {
        final DocumentReference reference;
        String title;
        String source;
        boolean visible = true, exists = true;
        Doc(String name) {
            reference = new DocumentReference("xwiki", List.of("LuxPedia", "Templates"), name);
            title = name;
        }
        public DocumentReference getDocumentReference() { return reference; }
        public String getName() { return reference.getName(); }
        public String getFullName() { return reference.toString().replaceFirst("^xwiki:", ""); }
        public String getDisplayTitle() { return title; }
        public Object getObject(String name) { return source == null ? null : Map.of("template", source); }
        public boolean hasAccessLevel(String right) { return visible; }
        public boolean isNew() { return !exists; }
        public String getURL() { return getURL("view"); }
        public String getURL(String action) { return "/bin/" + action + "/LuxPedia/Templates/" + getName(); }
    }
    public static class ProviderObject {
        final String source;
        ProviderObject(String source) { this.source = source; }
        public String getValue(String field) { return source; }
    }
    public static class Provider extends Doc {
        Provider(String name, String source) { super(name); this.source = source; }
        @Override public Object getObject(String name) { return new ProviderObject(source); }
    }
    public static class Wiki {
        final Map<String, Doc> pages = new HashMap<>();
        public Doc getDocument(Object reference) {
            String key = reference.toString().replaceFirst("^xwiki:", "");
            return pages.computeIfAbsent(key, ignored -> {
                Doc missing = new Doc("Missing"); missing.exists = false; return missing;
            });
        }
        public String getURL(String reference, String action, String query) { return "/bin/" + action + "/" + reference + "?" + query; }
    }
    public static class Query {
        final List<String> rows;
        Query(List<String> rows) { this.rows = rows; }
        public List<String> execute() { return rows; }
    }
    public static class Queries {
        final List<String> providers = new ArrayList<>(), templates = new ArrayList<>(), statements = new ArrayList<>();
        public Query xwql(String statement) {
            statements.add(statement);
            return new Query(statement.startsWith("from") ? providers : templates);
        }
    }
    final Wiki wiki = new Wiki();
    final Queries queries = new Queries();
    String render(boolean admin) throws Exception {
        VelocityEngine engine = new VelocityEngine(); engine.init();
        VelocityContext context = new VelocityContext();
        context.put("services", Map.of("security", Map.of("authorization", new Access(admin)),
            "model", new Model(), "query", queries, "localization", new Localization()));
        context.put("xwiki", wiki); context.put("escapetool", new EscapeTool());
        try (var stream = getClass().getResourceAsStream("/template_center.xwiki")) {
            assertNotNull(stream);
            StringWriter output = new StringWriter();
            assertTrue(engine.evaluate(context, output, "template-center", new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
            return output.toString();
        }
    }
    void add(Doc page) { wiki.pages.put(page.getFullName(), page); queries.templates.add(page.getFullName()); }

    @Test void nonAdminCannotListOrCreateTemplates() throws Exception {
        String output = render(false);
        assertTrue(output.contains("luxpedia.adminOnly"));
        assertFalse(output.contains("<table")); assertFalse(output.contains("class=\"btn"));
        assertTrue(queries.statements.isEmpty());
    }
    @Test void nativeRegistrationPresetsTheBodyAndStaysInItsNamespace() throws Exception {
        Doc body = new Doc("Procedure"); body.title = "<script>alert('title')</script>"; add(body);
        String output = render(true);
        assertTrue(output.contains("luxpedia.templateUnregistered"));
        assertTrue(output.contains("template=XWiki.TemplateProviderTemplate"));
        assertTrue(output.contains("name=ProcedureProvider"));
        assertTrue(output.contains("spaceReference=LuxPedia.Templates"));
        assertTrue(output.contains("&#60;script&#62;"), output); assertFalse(output.contains("<script>"));
        assertFalse(output.contains("$providerReference")); assertFalse(output.contains("$query"));
        assertTrue(queries.statements.stream().allMatch(query -> query.contains("doc.space = 'LuxPedia.Templates'")));
    }
    @Test void existingProvidersOpenNativeSettingsWithoutCreatingDuplicates() throws Exception {
        Doc body = new Doc("Article"); add(body);
        Provider provider = new Provider("ArticleProvider", "xwiki:" + body.getFullName()); add(provider);
        queries.providers.add(provider.getFullName());
        String output = render(true);
        assertTrue(output.contains("luxpedia.templateRegistered"));
        assertTrue(output.contains("/bin/inline/LuxPedia/Templates/ArticleProvider"));
        assertFalse(output.contains("luxpedia.registerArticleTemplate"));
    }
    @Test void conflictingProviderNamesDoNotOfferAnOverwrite() throws Exception {
        add(new Doc("Procedure"));
        Doc collision = new Doc("ProcedureProvider"); wiki.pages.put(collision.getFullName(), collision);
        String output = render(true);
        assertTrue(output.contains("luxpedia.templateProviderConflict"));
        assertFalse(output.contains("luxpedia.registerArticleTemplate"));
    }
    @Test void invisibleBodiesAreOmittedAndEmptyCenterHasAUsefulMessage() throws Exception {
        Doc body = new Doc("HiddenBody"); body.visible = false; add(body);
        String output = render(true);
        assertFalse(output.contains("HiddenBody")); assertTrue(output.contains("luxpedia.noArticleTemplates"));
    }
}
