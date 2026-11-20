package org.luxpedia.workflow;

import java.net.URI;
import java.util.*;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.query.Query;

/** Declarative menu/banner settings; never execute administrator-entered text or markup. */
final class NavigationSettings {
    static final String CLASS = "LuxPedia.Code.AdminSettingsClass";
    static final Set<String> ROLES = Set.of("all", "authenticated", "managers", "editors", "reviewers", "readers");
    final WorkflowPolicyListener policy; final XWikiContext context;
    NavigationSettings(WorkflowPolicyListener policy, XWikiContext context) { this.policy = policy; this.context = context; }
    DocumentReference settingsReference() { return new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Code"), "NavigationSettings"); }
    static String string(Object value, int max) {
        WorkflowPolicyListener.require(value == null || value instanceof String, "Text expected.");
        String text = value == null ? "" : ((String) value).strip();
        WorkflowPolicyListener.require(text.length() <= max && text.indexOf('\0') < 0, "Text is too long."); return text;
    }
    static boolean flag(Object value, boolean fallback) {
        WorkflowPolicyListener.require(value == null || value instanceof Boolean, "Boolean expected."); return value == null ? fallback : (Boolean) value;
    }
    DocumentReference resolve(String text) {
        DocumentReference ref;
        try { ref = policy.resolver.resolve(text, new WikiReference(context.getWikiId())); }
        catch (RuntimeException invalid) { throw new SecurityException("Invalid page reference."); }
        WorkflowPolicyListener.require(ref.getWikiReference().getName().equals(context.getWikiId()), "Cross-wiki target is not supported."); return ref;
    }
    Map<String, Object> defaults() {
        return new LinkedHashMap<>(Map.of("menus", new ArrayList<>(), "banners", new ArrayList<>(), "qrEnabled", true));
    }
    Map<String, Object> stored(XWikiDocument doc) throws Exception {
        var object = doc.getObject(CLASS); if (object == null || object.getStringValue("payload").isBlank()) { return defaults(); }
        return AdminOperations.JSON.readValue(object.getStringValue("payload"), Map.class);
    }
    Map<String, Object> read() throws Exception {
        var doc = context.getWiki().getDocument(settingsReference(), context);
        return Map.of("status", "ok", "version", doc.isNew() ? "" : doc.getVersion(), "settings", stored(doc));
    }
    Map<String, Object> validate(String payload) throws Exception {
        WorkflowPolicyListener.require(payload != null && payload.length() <= 100000, "Settings are too large.");
        Map<?, ?> raw = AdminOperations.JSON.readValue(payload, Map.class);
        WorkflowPolicyListener.require(raw.get("menus") instanceof List<?> && raw.get("banners") instanceof List<?>, "Menus and banners are required.");
        List<?> rawMenus = (List<?>) raw.get("menus"), rawBanners = (List<?>) raw.get("banners");
        WorkflowPolicyListener.require(rawMenus.size() <= 50 && rawBanners.size() <= 100, "Too many entries.");
        List<Map<String, Object>> menus = new ArrayList<>(), banners = new ArrayList<>(); Map<String, String> parents = new HashMap<>();
        for (Object entry : rawMenus) {
            WorkflowPolicyListener.require(entry instanceof Map<?, ?>, "Invalid menu entry."); Map<?, ?> row = (Map<?, ?>) entry;
            String id = string(row.get("id"), 40), parent = string(row.get("parent"), 40), en = string(row.get("en"), 120), zh = string(row.get("zh"), 120);
            String destination = string(row.get("destination"), 1024), location = string(row.get("location"), 20);
            WorkflowPolicyListener.require(id.matches("[A-Za-z0-9_-]{1,40}") && !parents.containsKey(id) && (!en.isBlank() || !zh.isBlank()), "Invalid menu name or duplicate identifier.");
            WorkflowPolicyListener.require(Set.of("top", "sidebar").contains(location), "Choose a menu location.");
            if (!destination.isBlank()) {
                if (destination.startsWith("https://") || destination.startsWith("http://")) {
                    URI uri = new URI(destination); WorkflowPolicyListener.require(uri.getHost() != null && uri.getUserInfo() == null, "Invalid web target.");
                } else { destination = policy.localSerializer.serialize(resolve(destination)); }
            }
            WorkflowPolicyListener.require(row.get("roles") instanceof List<?>, "Choose menu visibility.");
            List<String> roles = new ArrayList<>();
            for (Object role : (List<?>) row.get("roles")) { WorkflowPolicyListener.require(role instanceof String && ROLES.contains(role), "Unknown role."); roles.add((String) role); }
            WorkflowPolicyListener.require(!roles.isEmpty(), "Choose at least one role.");
            parents.put(id, parent);
            menus.add(Map.of("id", id, "parent", parent, "en", en, "zh", zh, "destination", destination, "location", location,
                "roles", roles.stream().distinct().toList(), "enabled", flag(row.get("enabled"), true)));
        }
        for (var row : menus) {
            Set<String> seen = new HashSet<>(); String current = (String) row.get("id");
            while (!current.isEmpty()) {
                WorkflowPolicyListener.require(parents.containsKey(current) && seen.add(current) && seen.size() <= 4, "Menu nesting is invalid or cyclic."); current = parents.get(current);
            }
            if (!row.get("parent").equals("")) {
                var parent = menus.stream().filter(p -> p.get("id").equals(row.get("parent"))).findFirst().orElseThrow();
                WorkflowPolicyListener.require(parent.get("location").equals(row.get("location")), "Parent and child must use the same location.");
            }
        }
        Set<String> categories = new HashSet<>();
        for (Object entry : rawBanners) {
            WorkflowPolicyListener.require(entry instanceof Map<?, ?>, "Invalid category banner."); Map<?, ?> row = (Map<?, ?>) entry;
            var ref = resolve(string(row.get("category"), 1024)); var doc = context.getWiki().getDocument(ref, context);
            WorkflowPolicyListener.require(ref.getSpaceReferences().get(0).getName().equals("Knowledge") && ref.getName().equals("WebHome") && !doc.isNew() &&
                (ref.getSpaceReferences().size() == 1 || doc.getObject("LuxPedia.Code.CategoryClass") != null), "Choose a Knowledge category.");
            String category = policy.localSerializer.serialize(ref); WorkflowPolicyListener.require(categories.add(category), "Duplicate category banner.");
            banners.add(Map.of("category", category, "headerEn", string(row.get("headerEn"), 2000), "headerZh", string(row.get("headerZh"), 2000),
                "footerEn", string(row.get("footerEn"), 2000), "footerZh", string(row.get("footerZh"), 2000), "inherit", flag(row.get("inherit"), true), "enabled", flag(row.get("enabled"), true)));
        }
        return new LinkedHashMap<>(Map.of("menus", menus, "banners", banners, "qrEnabled", flag(raw.get("qrEnabled"), true)));
    }
    Map<String, Object> save(String input) throws Exception {
        WorkflowPolicyListener.require(input != null && input.length() <= 110000, "Settings are too large.");
        Map<?, ?> envelope = AdminOperations.JSON.readValue(input, Map.class);
        String expected = string(envelope.get("version"), 30);
        Map<String, Object> validated = validate(AdminOperations.JSON.writeValueAsString(envelope.get("settings")));
        DraftWriteBoundary.guard("admin-settings:" + context.getWikiId());
        var original = context.getWiki().getDocument(settingsReference(), context);
        WorkflowPolicyListener.require((original.isNew() ? "" : original.getVersion()).equals(expected), "Settings changed. Reload before saving.");
        var doc = original.clone(); var object = doc.getObject(CLASS);
        if (object == null) { object = doc.newXObject(policy.resolver.resolve(CLASS, doc.getDocumentReference()), context); }
        String canonical = AdminOperations.JSON.writeValueAsString(validated); object.set("payload", canonical, context);
        context.getWiki().saveDocument(doc, "Update administrator navigation settings", true, context);
        var saved = context.getWiki().getDocument(settingsReference(), context).getObject(CLASS);
        WorkflowPolicyListener.require(saved != null && saved.getStringValue("payload").equals(canonical), "Settings were not persisted.");
        return read();
    }
    List<Map<String, String>> categories() throws Exception {
        List<String> names = policy.queries.createQuery("from doc.object(LuxPedia.Code.CategoryClass) as category where (doc.space = 'Knowledge' or doc.space like 'Knowledge.%') " +
            "and doc.name = 'WebHome' and doc.language = '' order by doc.fullName", Query.XWQL).setWiki(context.getWikiId()).execute();
        var result = new ArrayList<Map<String, String>>(); result.add(Map.of("reference", "Knowledge.WebHome", "title", "Knowledge"));
        for (String name : names) { var ref = resolve(name); var doc = context.getWiki().getDocument(ref, context); result.add(Map.of("reference", name, "title", AdminOperations.text(doc.getTitle()))); }
        return result;
    }
    Map<String, Object> presentation(String name) throws Exception {
        WorkflowPolicyListener.require(name != null && !name.isBlank() && name.length() <= 1024, "Invalid page.");
        var ref = resolve(name); var actor = context.getUserReference();
        WorkflowPolicyListener.require(policy.authorization.hasAccess(Right.VIEW, actor, ref), "Page is not readable.");
        var doc = context.getWiki().getDocument(ref, context); WorkflowPolicyListener.require(!doc.isNew(), "Page is missing.");
        Map<String, Object> data = stored(context.getWiki().getDocument(settingsReference(), context));
        Set<String> roles = new HashSet<>(Set.of("all"));
        if (actor != null && !actor.getName().equals("XWikiGuest")) {
            roles.add("authenticated"); var groups = policy.groups(actor, context);
            for (String role : List.of("managers", "editors", "reviewers", "readers")) {
                if (groups.contains(context.getWikiId() + ":XWiki.Lux" + Character.toUpperCase(role.charAt(0)) + role.substring(1))) { roles.add(role); }
            }
            if (DraftWorkspace.admin(policy, context)) { roles.add("managers"); }
        }
        var menus = new ArrayList<Map<String, Object>>(); Set<String> visible = new HashSet<>();
        for (Object entry : (List<?>) data.get("menus")) {
            Map<String, Object> row = (Map<String, Object>) entry;
            if (!Boolean.TRUE.equals(row.get("enabled")) || ((List<?>) row.get("roles")).stream().noneMatch(roles::contains)) { continue; }
            String destination = (String) row.get("destination"), href = destination;
            if (!destination.isBlank() && !destination.startsWith("https://") && !destination.startsWith("http://")) {
                var target = resolve(destination);
                if (!policy.authorization.hasAccess(Right.VIEW, actor, target) || context.getWiki().getDocument(target, context).isNew()) { continue; }
                href = context.getWiki().getURL(target, "view", context);
            }
            var item = new LinkedHashMap<String, Object>();
            for (String key : List.of("id", "parent", "en", "zh", "location")) { item.put(key, row.get(key)); }
            item.put("href", href); menus.add(item); visible.add((String) row.get("id"));
        }
        boolean changed;
        do {
            changed = menus.removeIf(row -> !row.get("parent").equals("") && !visible.contains(row.get("parent")));
            visible.clear(); for (var row : menus) { visible.add((String) row.get("id")); }
        } while (changed);
        Map<String, Object> banner = Map.of(); int nearest = -1;
        for (Object entry : (List<?>) data.get("banners")) {
            Map<String, Object> row = (Map<String, Object>) entry; if (!Boolean.TRUE.equals(row.get("enabled"))) { continue; }
            var category = resolve((String) row.get("category"));
            if (!policy.authorization.hasAccess(Right.VIEW, actor, category)) { continue; }
            var spaces = category.getSpaceReferences(); var own = ref.getSpaceReferences();
            boolean matches = own.size() >= spaces.size() && own.subList(0, spaces.size()).equals(spaces);
            if (matches && (Boolean.TRUE.equals(row.get("inherit")) || ref.equals(category)) && spaces.size() > nearest) { nearest = spaces.size(); banner = row; }
        }
        String qr = Boolean.TRUE.equals(data.get("qrEnabled")) && Set.of("Knowledge", "Drafts", "Main").contains(ref.getSpaceReferences().get(0).getName()) ?
            context.getWiki().getURL(ref, "view", context) : "";
        return Map.of("status", "ok", "menus", menus, "banner", banner, "qrURL", qr);
    }
}
