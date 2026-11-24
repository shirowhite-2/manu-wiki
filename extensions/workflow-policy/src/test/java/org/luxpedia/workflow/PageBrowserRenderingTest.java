package org.luxpedia.workflow;

import java.io.StringWriter;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Check the actual deployment renderer before its rows reach browser filters or exports. */
class PageBrowserRenderingTest {
    public static class Fields {
        final Map<String, String> values;
        Fields(Map<String, String> values) { this.values = values; }
        public String getValue(String key) { return values.getOrDefault(key, ""); }
    }
    public static class Page {
        final String name;
        Page(String name) { this.name = name; }
        public boolean hasAccessLevel(String right) { return !name.contains("Hidden"); }
        public String getDisplayTitle() { return name.contains("Hidden") ? "SECRET TITLE" : name + " <script> | 中文"; }
        public String getSpace() { return name.startsWith("Drafts.") ? "Drafts.Writing" : "Knowledge.Technical"; }
        public Date getDate() { return new Date(1795046400000L); }
        public Fields getObject(String cls) {
            if (cls.endsWith("CategoryClass")) { return name.contains("Category") ? new Fields(Map.of()) : null; }
            if (cls.equals(WorkflowPolicyListener.WF)) {
                if (name.contains("Legacy")) { return null; }
                return new Fields(Map.of("status", name.contains("Archived") ? "archived" : name.contains("Published") ? "published" :
                    name.startsWith("Knowledge.") ? "published" : name.contains("Pending") ? "validating" : "draft"));
            }
            if (cls.equals(DraftWorkspace.CLASS)) {
                return new Fields(Map.of("status", name.contains("Abandoned") ? "abandoned" : "active", "owner",
                    name.contains("Other") ? "xwiki:XWiki.other" : "xwiki:XWiki.owner"));
            }
            if (cls.endsWith("ArticleClass")) {
                return name.contains("Legacy") ? null : new Fields(Map.of("primaryCategory", "Technical", "owner", "Alice", "tagsText", "release"));
            }
            return null;
        }
    }
    public static class Wiki {
        public Page getDocument(String name) { return new Page(name); }
        public String getURL(String name) { return "/bin/view/" + name.replace('.', '/'); }
    }
    public static class Access {
        final boolean admin;
        Access(boolean admin) { this.admin = admin; }
        public boolean hasAccess(String right, Object reference) { return admin; }
    }
    public static class Model { public String resolveDocument(String name) { return name; } }
    String render(String language, boolean admin) throws Exception {
        var engine = new VelocityEngine(); engine.init(); var context = new VelocityContext();
        var names = List.of("Knowledge.Live", "Knowledge.Legacy", "Knowledge.Archived", "Drafts.Active", "Drafts.Pending",
            "Drafts.Published", "Drafts.AbandonedOwner", "Drafts.AbandonedOther", "Knowledge.Hidden", "Knowledge.Category");
        context.put("services", Map.of("query", new InterfaceRenderingTest.Query(names),
            "localization", new InterfaceRenderingTest.Localization(language), "security", Map.of("authorization", new Access(admin)), "model", new Model()));
        context.put("xwiki", new Wiki()); context.put("escapetool", new InterfaceRenderingTest.Escape());
        context.put("xcontext", Map.of("userReference", "xwiki:XWiki.owner"));
        String source = "#macro(lp $key)$services.localization.render(\"luxpedia.$key\")#end\n" +
            Files.readString(InterfaceRenderingTest.UI.resolve("pages/page_browser.xwiki"));
        var writer = new StringWriter(); assertTrue(engine.evaluate(context, writer, "page-browser", source));
        return writer.toString();
    }
    @Test void browserNeverRendersInaccessibleTitlesOrPublishedDraftCopiesOrOtherAbandonedWork() throws Exception {
        for (String language : List.of("en", "zh")) {
            String html = render(language, false);
            assertTrue(html.contains("Knowledge.Live")); assertTrue(html.contains("Knowledge.Archived"));
            assertTrue(html.contains("Drafts.Active")); assertTrue(html.contains("Drafts.Pending"));
            assertTrue(html.contains("Drafts.AbandonedOwner"));
            assertFalse(html.contains("SECRET TITLE")); assertFalse(html.contains("Knowledge.Hidden"));
            assertFalse(html.contains("Drafts.Published")); assertFalse(html.contains("Drafts.AbandonedOther"));
            assertFalse(html.contains("Knowledge.Category")); assertFalse(html.contains("<script>"));
            assertFalse(html.contains("$escapetool")); assertFalse(html.contains("$metadata"));
            var parsed = new InterfaceRenderingTest().parse(html.substring(html.indexOf("<table"), html.indexOf("</table>") + 8));
            assertEquals(6, parsed.getElementsByTagName("th").getLength());
            var links = parsed.getElementsByTagName("a"); assertEquals(6, links.getLength());
        }
    }
    @Test void administratorCanSeeAbandonedWorkAndLegacyRowsDoNotInheritMetadataFromThePreviousPage() throws Exception {
        String html = render("en", true);
        assertTrue(html.contains("Drafts.AbandonedOther"));
        String legacy = html.substring(html.indexOf("Knowledge/Legacy"), html.indexOf("</tr>", html.indexOf("Knowledge/Legacy")));
        assertTrue(legacy.contains("Knowledge.Technical")); assertFalse(legacy.contains("Alice")); assertFalse(legacy.contains("release"));
    }
}
