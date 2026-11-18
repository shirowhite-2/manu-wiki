package org.luxpedia.workflow;

import java.io.StringWriter;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.w3c.dom.Document;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xwiki.component.embed.EmbeddableComponentManager;
import org.xwiki.component.descriptor.DefaultComponentDescriptor;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.rendering.parser.Parser;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.match.ClassBlockMatcher;
import static org.junit.jupiter.api.Assertions.*;

/** Render the actual deployment templates through Velocity, not a copied HTML fixture. */
class InterfaceRenderingTest {
    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    static final Path UI = ROOT.resolve("scripts/ui");
    public static class Localization {
        final Map<String, String> messages;
        Localization(String language) throws Exception {
            messages = new ObjectMapper().readValue(UI.resolve(language + ".json").toFile(), new TypeReference<>() {});
        }
        public String render(String key) { return messages.get(key.replace("luxpedia.", "")); }
    }
    public static class Query {
        final List<String> items;
        Query(List<String> items) { this.items = items; }
        public Query xwql(String query) { return this; }
        public List<String> execute() { return items; }
    }
    public static class Fields {
        final String name;
        Fields(String name) { this.name = name; }
        public String getValue(String field) {
            return switch (field) {
                case "target" -> "Knowledge.Technical." + "LongArticleName".repeat(30);
                case "status" -> name.startsWith("Published ") ? "published" : name.startsWith("Archived ") ? "archived" : "validating";
                case "description" -> "User category <script>text</script>";
                default -> "User value | <text>";
            };
        }
    }
    public static class Page {
        final String name;
        final boolean visible;
        Page(String name, boolean visible) { this.name = name; this.visible = visible; }
        public boolean hasAccessLevel(String right) { return visible; }
        public String getDisplayTitle() { return name; }
        public Fields getObject(String className) { return new Fields(name); }
    }
    public static class Wiki {
        public Page getDocument(String reference) {
            return new Page(reference.equals("Hidden") ? "SECRET TITLE" : reference + " <script> | 自定义标题", !reference.equals("Hidden"));
        }
        public String getURL(String reference) { return "/bin/view/Drafts/" + reference + "?a=1&b=2"; }
    }
    public static class Escape {
        public String xml(Object value) {
            return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
    String renderSource(String name, String language, List<String> items) throws Exception {
        var engine = new VelocityEngine(); engine.init();
        var context = new VelocityContext();
        context.put("services", Map.of("localization", new Localization(language), "query", new Query(items)));
        context.put("xwiki", new Wiki()); context.put("escapetool", new Escape());
        var result = new StringWriter();
        String source = "#macro(lp $key)$services.localization.render(\"luxpedia.$key\")#end\n#lp('nextReview')\n"
            + Files.readString(UI.resolve("pages/" + name + ".xwiki"));
        assertTrue(engine.evaluate(context, result, name, source));
        String output = result.toString();
        assertFalse(output.contains("$escapetool"), output);
        assertFalse(output.contains("SECRET TITLE"), output);
        return output;
    }
    String render(String name, String language, List<String> items) throws Exception {
        String output = renderSource(name, language, items);
        return output.substring(output.indexOf("<table"), output.indexOf("</table>") + 8);
    }
    Document parse(String html) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(html)));
    }
    @Test void tableMacroStartsANewBlockAfterTheLocalizedIntro() throws Exception {
        var manager = new EmbeddableComponentManager();
        manager.initialize(new ClassLoader(getClass().getClassLoader()) {
            @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                var resources = java.util.Collections.list(super.getResources(name));
                if (name.equals("META-INF/components.txt")) {
                    resources.removeIf(url -> !url.toString().contains("/org/xwiki/rendering/"));
                }
                return java.util.Collections.enumeration(resources);
            }
        });
        var context = new DefaultComponentDescriptor<ComponentManager>();
        context.setRoleType(ComponentManager.class); context.setRoleHint("context");
        manager.registerComponent(context, manager);
        Parser parser = manager.getInstance(Parser.class, "xwiki/2.1");
        for (String name : List.of("review_queue", "categories", "articles", "drafts")) {
            var xdom = parser.parse(new StringReader(renderSource(name, "en", List.of("First", "Second"))));
            var macros = xdom.<MacroBlock>getBlocks(new ClassBlockMatcher(MacroBlock.class), Block.Axes.DESCENDANT);
            assertEquals(1, macros.size());
            assertEquals("html", macros.get(0).getId());
            assertFalse(macros.get(0).isInline(), name + " must be a standalone table block");
        }
    }
    @Test void queueHasOneThreeCellRowPerVisibleArticleInBothLanguages() throws Exception {
        for (String language : List.of("en", "zh")) {
            String html = render("review_queue", language, List.of("First", "Hidden", "Second"));
            var table = parse(html);
            var rows = table.getElementsByTagName("tbody").item(0).getChildNodes();
            int count = 0;
            for (int i = 0; i < rows.getLength(); i++) {
                var row = rows.item(i);
                if (row instanceof org.w3c.dom.Element element) {
                    assertEquals(3, element.getElementsByTagName("td").getLength()); count++;
                }
            }
            assertEquals(2, count);
            assertEquals(0, table.getElementsByTagName("script").getLength());
            assertEquals(language.equals("en") ? "Title" : "标题", table.getElementsByTagName("th").item(0).getTextContent());
            assertTrue(table.getDocumentElement().getTextContent().contains("自定义标题"));
            Path fixture = ROOT.resolve("extensions/workflow-policy/target/review-queue-" + language + ".html");
            Files.writeString(fixture, "<!doctype html><html lang=\"" + language + "\"><meta charset=\"utf-8\"><body>" + html + "</body></html>");
        }
    }
    @Test void emptyQueueDoesNotDiscloseInaccessibleArticles() throws Exception {
        for (String language : List.of("en", "zh")) {
            var table = parse(render("review_queue", language, List.of("Hidden")));
            assertEquals(1, table.getElementsByTagName("td").getLength());
            assertEquals(language.equals("en") ? "No articles are awaiting review." : "暂无待审核文章。",
                table.getElementsByTagName("td").item(0).getTextContent());
        }
    }
    @Test void categoryAndArticleListsAlsoKeepSeparateRows() throws Exception {
        for (String name : List.of("categories", "articles")) {
            var table = parse(render(name, "en", List.of("First", "Hidden", "Second")));
            assertEquals(3, table.getElementsByTagName("tr").getLength());
            assertEquals(name.equals("categories") ? 4 : 8, table.getElementsByTagName("td").getLength());
        }
    }
    @Test void activeDraftsHidePublishedAndArchivedCopiesButKeepEditableAndPendingArticles() throws Exception {
        var table = parse(render("drafts", "en", List.of("First", "Published", "Archived", "Hidden", "Second", "Drafts.WebHome")));
        assertEquals(3, table.getElementsByTagName("tr").getLength());
        assertFalse(table.getDocumentElement().getTextContent().contains("Published"));
        assertFalse(table.getDocumentElement().getTextContent().contains("Archived"));
    }
}
