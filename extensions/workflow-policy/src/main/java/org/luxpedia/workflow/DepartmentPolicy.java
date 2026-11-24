package org.luxpedia.workflow;

import java.util.*;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import org.xwiki.model.reference.*;
import org.xwiki.query.Query;

/** Department groups narrow existing site rights; they never grant a site capability. */
final class DepartmentPolicy {
    static final String CLASS = "LuxPedia.Code.DepartmentClass";
    static final String SETTINGS_CLASS = "LuxPedia.Code.DepartmentSettingsClass";
    static final String PREFIX = "LuxDepartment_";
    static final String MEMBERS = "XWiki.XWikiGroups";
    static final Set<String> PORTALS = Set.of("WebHome", "DocumentLibrary", "CategoryIndex", "CategoryGuide");
    final WorkflowPolicyListener policy; final XWikiContext context;
    DepartmentPolicy(WorkflowPolicyListener policy, XWikiContext context) { this.policy = policy; this.context = context; }
    DocumentReference settingsReference(String wiki) { return new DocumentReference(wiki, List.of("LuxPedia", "Code"), "DepartmentSettings"); }
    static boolean groupReference(DocumentReference ref) {
        return ref.getSpaceReferences().size() == 1 && ref.getLastSpaceReference().getName().equals("XWiki") && ref.getName().startsWith(PREFIX);
    }
    DocumentReference group(String id) {
        WorkflowPolicyListener.require(id != null && id.matches("[a-f0-9]{32}"), "Invalid department identifier.");
        return new DocumentReference(context.getWikiId(), "XWiki", PREFIX + id);
    }
    Map<String, Object> settings(String wiki) throws Exception {
        var object = context.getWiki().getDocument(settingsReference(wiki), context).getObject(SETTINGS_CLASS);
        if (object == null || object.getStringValue("payload").isBlank()) { return Map.of("enabled", false, "publicCategories", List.of()); }
        Map<String, Object> data = AdminOperations.JSON.readValue(object.getStringValue("payload"), Map.class);
        WorkflowPolicyListener.require(data.get("enabled") instanceof Boolean && data.get("publicCategories") instanceof List<?>, "Invalid department access settings.");
        return data;
    }
    Map<String, Object> department(XWikiDocument doc) throws Exception {
        var object = doc.getObject(CLASS); if (object == null) { return Map.of(); }
        return AdminOperations.JSON.readValue(object.getStringValue("payload"), Map.class);
    }
    DocumentReference resolve(String name, String wiki) {
        var ref = policy.resolver.resolve(name, new WikiReference(wiki));
        WorkflowPolicyListener.require(ref.getWikiReference().getName().equals(wiki), "Cross-wiki references are not supported."); return ref;
    }
    boolean inside(DocumentReference ref, Collection<?> categories) {
        for (Object name : categories) {
            if (!(name instanceof String value)) { continue; }
            var category = resolve(value, ref.getWikiReference().getName());
            var parent = category.getSpaceReferences(); var spaces = ref.getSpaceReferences();
            if (category.getName().equals("WebHome") && parent.get(0).getName().equals("Knowledge") && parent.size() > 1 &&
                spaces.size() >= parent.size() && spaces.subList(0, parent.size()).equals(parent)) { return true; }
        } return false;
    }
    /** Evaluated when cached native access is read, so changing a scope cannot leave an old cached grant. */
    boolean permitted(DocumentReference ref, DocumentReference actor, Collection<DocumentReference> groups) throws Exception {
        String area = ref.getSpaceReferences().get(0).getName();
        if (!Set.of("Knowledge", "Drafts").contains(area)) { return true; }
        String wiki = ref.getWikiReference().getName(); var config = settings(wiki);
        if (!Boolean.TRUE.equals(config.get("enabled"))) { return true; }
        if (actor == null || actor.getName().equals("XWikiGuest")) { return false; }
        if (ref.getSpaceReferences().size() == 1 && PORTALS.contains(ref.getName())) { return true; }
        DocumentReference target = ref;
        if (area.equals("Drafts")) {
            var doc = context.getWiki().getDocument(ref, context);
            // Create checks the page before it exists. A missing draft has no owner yet, so the owner rule would deny every editor.
            if (doc.isNew()) { return true; }
            var flow = doc.getObject(WorkflowPolicyListener.WF);
            String selected = flow == null ? "" : flow.getStringValue("target");
            if (selected.isBlank()) {
                var metadata = doc.getObject("LuxPedia.Code.ArticleClass");
                selected = metadata == null ? "" : metadata.getStringValue("primaryCategory");
                // Older unassigned work has no reliable classification. Only its owner may read it.
                if (!selected.startsWith("Knowledge.") && !selected.startsWith(wiki + ":Knowledge.")) {
                    var workspace = doc.getObject(DraftWorkspace.CLASS);
                    return actor.equals(doc.getCreatorReference()) || workspace != null && actor.toString().equals(workspace.getStringValue("owner"));
                }
            }
            target = resolve(selected, wiki);
            if (!target.getSpaceReferences().get(0).getName().equals("Knowledge")) { return false; }
        }
        return permittedTarget(target, actor, groups, config);
    }
    boolean permittedTarget(DocumentReference target, DocumentReference actor, Collection<DocumentReference> groups, Map<String, Object> config) throws Exception {
        if (inside(target, (List<?>) config.getOrDefault("publicCategories", List.of()))) { return true; }
        for (var group : groups) {
            if (!group.getWikiReference().equals(target.getWikiReference()) || !groupReference(group)) { continue; }
            var groupDoc = context.getWiki().getDocument(group, context);
            // Recheck persisted membership even for an older cached native access entry.
            if (!memberOf(groupDoc, actor, new HashSet<>(), 0)) { continue; }
            var data = department(groupDoc);
            if (Boolean.TRUE.equals(data.get("enabled")) && inside(target, (List<?>) data.getOrDefault("categories", List.of()))) { return true; }
        } return false;
    }
    boolean memberOf(XWikiDocument groupDoc, DocumentReference actor, Set<DocumentReference> seen, int depth) throws Exception {
        if (actor == null || depth > 16 || seen.size() >= 256 || !seen.add(groupDoc.getDocumentReference())) { return false; }
        for (String member : members(groupDoc)) {
            var reference = resolve(member, groupDoc.getDocumentReference().getWikiReference().getName());
            if (reference.equals(actor)) { return true; }
            var nested = context.getWiki().getDocument(reference, context);
            if (nested.getObject(MEMBERS) != null && memberOf(nested, actor, seen, depth + 1)) { return true; }
        } return false;
    }
    void requireDraftDestination(XWikiDocument doc, DocumentReference actor, boolean admin) throws Exception {
        if (admin || !EditLease.scope(doc)) { return; }
        var config = settings(context.getWikiId()); if (!Boolean.TRUE.equals(config.get("enabled"))) { return; }
        var flow = doc.getObject(WorkflowPolicyListener.WF); var metadata = doc.getObject("LuxPedia.Code.ArticleClass");
        String target = flow == null ? "" : flow.getStringValue("target");
        if (target.isBlank() && metadata != null) { target = metadata.getStringValue("primaryCategory"); }
        if (target.startsWith("Knowledge.") || target.startsWith(context.getWikiId() + ":Knowledge.")) {
            var groups = new ArrayList<DocumentReference>(); for (String group : policy.groups(actor, context)) { groups.add(resolve(group, context.getWikiId())); }
            WorkflowPolicyListener.require(permittedTarget(resolve(target, context.getWikiId()), actor, groups, config), "This category is outside your department access.");
        }
    }
    List<String> categories(Object value) throws Exception {
        WorkflowPolicyListener.require(value instanceof List<?> && ((List<?>) value).size() <= 200, "Select up to 200 categories.");
        var result = new LinkedHashSet<String>();
        for (Object entry : (List<?>) value) {
            var ref = resolve(NavigationSettings.string(entry, 1024), context.getWikiId());
            var doc = context.getWiki().getDocument(ref, context);
            WorkflowPolicyListener.require(ref.getSpaceReferences().get(0).getName().equals("Knowledge") && ref.getSpaceReferences().size() > 1 &&
                ref.getName().equals("WebHome") && !doc.isNew() && doc.getObject("LuxPedia.Code.CategoryClass") != null, "Select an existing Knowledge category.");
            result.add(policy.localSerializer.serialize(ref));
        } return List.copyOf(result);
    }
    List<String> members(XWikiDocument doc) {
        var objects = doc.getXObjects(policy.resolver.resolve(MEMBERS, doc.getDocumentReference())); var result = new ArrayList<String>();
        if (objects != null) { for (var object : objects) { if (object != null && !object.getStringValue("member").isBlank()) { result.add(object.getStringValue("member")); } } }
        return result;
    }
    Map<String, Object> read() throws Exception {
        var configDoc = context.getWiki().getDocument(settingsReference(context.getWikiId()), context);
        List<String> names = policy.queries.createQuery("select distinct doc.fullName from Document doc, doc.object(LuxPedia.Code.DepartmentClass) as department where doc.space = 'XWiki' and doc.language = '' order by doc.fullName", Query.XWQL)
            .setWiki(context.getWikiId()).execute();
        var departments = new ArrayList<Map<String, Object>>();
        for (String name : names) {
            var ref = resolve(name, context.getWikiId()); if (!groupReference(ref)) { continue; }
            var doc = context.getWiki().getDocument(ref, context); if (doc.isNew()) { continue; }
            var row = new LinkedHashMap<>(department(doc)); row.put("id", ref.getName().substring(PREFIX.length())); row.put("group", policy.localSerializer.serialize(ref));
            row.put("version", doc.getVersion()); row.put("members", members(doc)); departments.add(row);
        }
        return Map.of("status", "ok", "settings", settings(context.getWikiId()), "version", configDoc.isNew() ? "" : configDoc.getVersion(), "departments", departments);
    }
    Map<String, Object> saveSettings(Map<?, ?> input) throws Exception {
        boolean enabled = NavigationSettings.flag(input.get("enabled"), false); var publicCategories = categories(input.get("publicCategories"));
        DraftWriteBoundary.guard("department-settings:" + context.getWikiId());
        var ref = settingsReference(context.getWikiId()); var original = context.getWiki().getDocument(ref, context);
        WorkflowPolicyListener.require((original.isNew() ? "" : original.getVersion()).equals(NavigationSettings.string(input.get("version"), 30)), "Department settings changed.");
        var doc = original.clone(); doc.setOriginalDocument(original); var object = doc.getObject(SETTINGS_CLASS);
        if (object == null) { object = doc.newXObject(policy.resolver.resolve(SETTINGS_CLASS, ref), context); }
        String json = AdminOperations.JSON.writeValueAsString(Map.of("enabled", enabled, "publicCategories", publicCategories)); object.set("payload", json, context);
        context.getWiki().saveDocument(doc, "Configure department category access", true, context);
        WorkflowPolicyListener.require(json.equals(context.getWiki().getDocument(ref, context).getObject(SETTINGS_CLASS).getStringValue("payload")), "Department settings did not persist.");
        return read();
    }
    Map<String, Object> saveDepartment(Map<?, ?> input) throws Exception {
        String id = NavigationSettings.string(input.get("id"), 32); boolean creating = id.isEmpty();
        if (creating) { id = UUID.randomUUID().toString().replace("-", ""); }
        var ref = group(id); String en = NavigationSettings.string(input.get("en"), 120), zh = NavigationSettings.string(input.get("zh"), 120);
        WorkflowPolicyListener.require(!en.isBlank() || !zh.isBlank(), "Department name is required.");
        var scopes = categories(input.get("categories")); boolean enabled = NavigationSettings.flag(input.get("enabled"), true);
        WorkflowPolicyListener.require(input.get("members") instanceof List<?> && ((List<?>) input.get("members")).size() <= 2000, "Choose up to 2000 users.");
        var members = new LinkedHashSet<String>();
        for (Object entry : (List<?>) input.get("members")) {
            var member = resolve(NavigationSettings.string(entry, 1024), context.getWikiId()); var profile = context.getWiki().getDocument(member, context);
            WorkflowPolicyListener.require(!profile.isNew() && !member.getName().equals("XWikiGuest") && (profile.getObject("XWiki.XWikiUsers") != null || profile.getObject(MEMBERS) != null), "Choose an existing user or native group.");
            members.add(policy.localSerializer.serialize(member));
        }
        DraftWriteBoundary.guard(ref.toString()); var original = context.getWiki().getDocument(ref, context);
        WorkflowPolicyListener.require(creating ? original.isNew() : !original.isNew() && original.getObject(CLASS) != null &&
            original.getVersion().equals(NavigationSettings.string(input.get("version"), 30)), "Department changed. Reload before saving.");
        var doc = original.clone(); doc.setOriginalDocument(original); var object = doc.getObject(CLASS);
        if (object == null) { object = doc.newXObject(policy.resolver.resolve(CLASS, ref), context); }
        String json = AdminOperations.JSON.writeValueAsString(Map.of("en", en, "zh", zh, "categories", scopes, "enabled", enabled)); object.set("payload", json, context);
        doc.removeObjects(MEMBERS);
        // Keep an empty native membership object so an empty department remains a native group.
        for (String member : members.isEmpty() ? List.of("") : members) { doc.newXObject(policy.resolver.resolve(MEMBERS, ref), context).set("member", member, context); }
        doc.setTitle(en.isBlank() ? zh : en); doc.setHidden(true);
        context.getWiki().saveDocument(doc, creating ? "Create department role" : "Update department role and membership", true, context);
        var saved = context.getWiki().getDocument(ref, context);
        WorkflowPolicyListener.require(saved.getObject(CLASS) != null && json.equals(saved.getObject(CLASS).getStringValue("payload")) &&
            new HashSet<>(members(saved)).equals(members), "Department did not persist.");
        return read();
    }
}
