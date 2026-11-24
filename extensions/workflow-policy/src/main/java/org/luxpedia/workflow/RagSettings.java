package org.luxpedia.workflow;

import java.net.URI;
import java.util.*;
import com.xpn.xwiki.XWikiContext;
import org.xwiki.model.reference.DocumentReference;

/** Administrator LightRAG settings. The API key is write-only and is never returned to the browser. */
final class RagSettings {
    static final String CLASS = NavigationSettings.CLASS;
    final WorkflowPolicyListener policy; final XWikiContext context;
    RagSettings(WorkflowPolicyListener policy, XWikiContext context) { this.policy = policy; this.context = context; }
    DocumentReference reference() { return new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Code"), "RagSettings"); }

    static Map<String, Object> defaults() {
        var result = new LinkedHashMap<String, Object>();
        result.put("baseUrl", ""); result.put("prefix", "wiki"); result.put("apiKey", "");
        result.put("maxChars", 200000); result.put("timeoutSeconds", 30); result.put("categories", new LinkedHashMap<String, Object>());
        return result;
    }
    Map<String, Object> stored() throws Exception {
        var doc = context.getWiki().getDocument(reference(), context); var object = doc.getObject(CLASS);
        var result = defaults();
        if (object != null && !object.getStringValue("payload").isBlank()) { result.putAll(AdminOperations.JSON.readValue(object.getStringValue("payload"), Map.class)); }
        return result;
    }
    Map<String, Object> read() throws Exception {
        var doc = context.getWiki().getDocument(reference(), context); var settings = new LinkedHashMap<>(stored());
        settings.put("apiKeySet", !AdminOperations.text(settings.remove("apiKey")).isEmpty());
        return Map.of("version", doc.isNew() ? "" : doc.getVersion(), "settings", settings);
    }

    static String baseUrl(Object value) throws Exception {
        String url = NavigationSettings.string(value, 512);
        if (url.isEmpty()) { return url; }
        URI uri = new URI(url);
        WorkflowPolicyListener.require(Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)) && uri.getHost() != null &&
            uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null, "Use an http(s) LightRAG address without credentials or query.");
        return url.replaceAll("/+$", "");
    }
    static int bounded(Object value, int fallback, int min, int max) {
        if (value == null) { return fallback; }
        WorkflowPolicyListener.require(value instanceof Number, "Number expected.");
        int number = ((Number) value).intValue(); WorkflowPolicyListener.require(number >= min && number <= max, "Number is out of range."); return number;
    }

    Map<String, Object> validate(Map<?, ?> raw, Map<String, Object> previous, Set<String> categories) throws Exception {
        var connection = connection(raw, previous); var result = new LinkedHashMap<String, Object>();
        for (String name : List.of("baseUrl", "prefix")) { result.put(name, connection.get(name)); }
        result.put("apiKey", AdminOperations.text(connection.get("apiKey")));
        result.put("maxChars", bounded(raw.get("maxChars"), 200000, 1000, 2000000));
        result.put("timeoutSeconds", connection.get("timeoutSeconds"));
        WorkflowPolicyListener.require(raw.get("categories") == null || raw.get("categories") instanceof Map<?, ?>, "Invalid category selection.");
        var selection = new TreeMap<String, Object>();
        Map<?, ?> rawCategories = raw.get("categories") == null ? Map.of() : (Map<?, ?>) raw.get("categories");
        WorkflowPolicyListener.require(rawCategories.size() <= 500, "Too many categories.");
        for (var entry : rawCategories.entrySet()) {
            WorkflowPolicyListener.require(entry.getKey() instanceof String name && categories.contains(name), "Choose a Knowledge category.");
            selection.put((String) entry.getKey(), NavigationSettings.flag(entry.getValue(), false));
        }
        result.put("categories", selection);
        return result;
    }

    static Map<String, Object> connection(Map<?, ?> raw, Map<String, Object> stored) throws Exception {
        var result = new LinkedHashMap<>(stored);
        result.put("baseUrl", baseUrl(raw.get("baseUrl")));
        String prefix = NavigationSettings.string(raw.get("prefix"), 16);
        WorkflowPolicyListener.require(prefix.matches("[A-Za-z][A-Za-z0-9]{0,15}"), "Workspace prefix must start with a letter and use letters or digits.");
        result.put("prefix", prefix);
        String key = NavigationSettings.string(raw.get("apiKey"), 512);
        if (NavigationSettings.flag(raw.get("clearApiKey"), false)) { result.put("apiKey", ""); } else if (!key.isEmpty()) { result.put("apiKey", key); }
        result.put("timeoutSeconds", bounded(raw.get("timeoutSeconds"), 30, 5, 120));
        return result;
    }

    Map<String, Object> save(String input, Set<String> categories) throws Exception {
        WorkflowPolicyListener.require(input != null && input.length() <= 60000, "Settings are too large.");
        Map<?, ?> envelope = AdminOperations.JSON.readValue(input, Map.class);
        String expected = NavigationSettings.string(envelope.get("version"), 30);
        WorkflowPolicyListener.require(envelope.get("settings") instanceof Map<?, ?>, "Settings are required.");
        DraftWriteBoundary.guard("rag-settings:" + context.getWikiId());
        var original = context.getWiki().getDocument(reference(), context);
        WorkflowPolicyListener.require((original.isNew() ? "" : original.getVersion()).equals(expected), "Settings changed. Reload before saving.");
        var validated = validate((Map<?, ?>) envelope.get("settings"), stored(), categories);
        var doc = original.clone(); var object = doc.getObject(CLASS);
        if (object == null) { object = doc.newXObject(policy.resolver.resolve(CLASS, doc.getDocumentReference()), context); }
        if (original.isNew()) { RagSync.restrict(policy, doc, context); }
        String canonical = AdminOperations.JSON.writeValueAsString(validated); object.set("payload", canonical, context);
        context.getWiki().saveDocument(doc, "Update LightRAG sync settings", true, context);
        return Map.of("status", "ok", "saved", read());
    }

    static String apiKey(Map<String, Object> settings) { return AdminOperations.text(settings.get("apiKey")); }
    static int number(Map<String, Object> settings, String name, int fallback) {
        return settings.get(name) instanceof Number number ? number.intValue() : fallback;
    }
}
