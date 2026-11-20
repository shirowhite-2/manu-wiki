package org.luxpedia.workflow;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.tools.generic.MathTool;
import org.xwiki.velocity.tools.EscapeTool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DepartmentSearchRenderingTest {
    public static class Doc {
        public String getURL(String action, String query) { return "/bin/view/Main/SolrSearch?" + query; }
    }
    String render(int start, int rows, long total) throws Exception {
        var context = new VelocityContext();
        context.put("start", start); context.put("rows", rows);
        context.put("request", Map.of("parameterMap", Map.of("text", new String[] {"a&b<private>"})));
        context.put("searchResponse", Map.of("results", Map.of("numFound", total)));
        context.put("mathtool", new MathTool()); context.put("escapetool", new EscapeTool());
        context.put("doc", new Doc()); context.put("services", Map.of("localization", new TemplateCenterRenderingTest.Localization()));
        String source;
        try (var stream = getClass().getResourceAsStream("/department_search_navigation.vm")) {
            assertNotNull(stream); source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var engine = new VelocityEngine(); engine.init(); var output = new StringWriter();
        assertTrue(engine.evaluate(context, output, "department-search", source + "\n#luxpediaDepartmentSearchNavigation()"));
        return output.toString();
    }
    @Test void firstPageOffersNextWithoutExposingTheGlobalTotal() throws Exception {
        String html = render(0, 10, 1234567);
        assertTrue(html.contains("luxpedia.tableNext")); assertFalse(html.contains("luxpedia.tablePrevious"));
        assertTrue(html.contains("firstIndex=10")); assertFalse(html.contains("1234567")); assertFalse(html.contains("<private>"));
    }
    @Test void laterPagesPreserveSearchAndOfferPreviousWithoutNegativeIndexes() throws Exception {
        String html = render(3, 10, 10);
        assertTrue(html.contains("luxpedia.tablePrevious")); assertFalse(html.contains("luxpedia.tableNext"));
        assertTrue(html.contains("firstIndex=0")); assertTrue(html.contains("text="));
        assertFalse(html.contains("firstIndex=-"));
    }
}
