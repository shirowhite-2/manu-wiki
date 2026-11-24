package org.luxpedia.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.classes.GroupsClass;
import com.xpn.xwiki.objects.classes.LevelsClass;
import com.xpn.xwiki.objects.classes.UsersClass;

/** Canonical account references stay in XWiki; role folders are directory views. */
final class IdentityDirectory {
    static final String RIGHTS = "XWiki.XWikiRights";
    static final String ADMINS = "XWiki.LuxManagers,XWiki.XWikiAdminGroup";
    static final Map<String, List<String>> ROLES = Map.of(
        "managers", List.of("XWiki.LuxManagers", "XWiki.XWikiAdminGroup"),
        "editors", List.of("XWiki.LuxEditors"), "reviewers", List.of("XWiki.LuxReviewers"),
        "readers", List.of("XWiki.LuxReaders"));
    final WorkflowPolicyListener policy;
    final XWikiContext context;

    IdentityDirectory(WorkflowPolicyListener policy, XWikiContext context) {
        this.policy = policy; this.context = context;
    }

    static boolean isIdentity(XWikiDocument doc) {
        DocumentReference ref = doc.getDocumentReference();
        return ref != null && ref.getSpaceReferences().size() == 1 &&
            ref.getLastSpaceReference().getName().equals("XWiki") &&
            (doc.getObject("XWiki.XWikiUsers") != null || doc.getObject("XWiki.XWikiGroups") != null);
    }

    /** Native rights fields use strings; older/custom schemas can use list properties. */
    static List<String> rightsValues(BaseObject object, String field) {
        var stored = object.safeget(field);
        if (stored == null) { return List.of(); }
        if (!(stored instanceof BaseProperty<?> property)) {
            throw new IllegalArgumentException("Unsupported ACL property for " + field);
        }
        Object value = property.getValue();
        if (value == null) { return List.of(); }
        if (value instanceof List<?> values) {
            List<String> result = new ArrayList<>();
            for (Object entry : values) {
                if (!(entry instanceof String text)) { throw new IllegalArgumentException("Non-text ACL entry for " + field); }
                result.add(text);
            }
            return result;
        }
        if (value instanceof String text) {
            return switch (field) {
                case "levels" -> LevelsClass.getListFromString(text);
                case "groups" -> GroupsClass.getListFromString(text);
                case "users" -> UsersClass.getListFromString(text);
                default -> throw new IllegalArgumentException("Unsupported ACL field " + field);
            };
        }
        throw new IllegalArgumentException("Unsupported ACL value for " + field);
    }

    /** Replace VIEW only, preserving EDIT and every other right, including programming. */
    boolean normalizeView(XWikiDocument doc) throws Exception {
        String owner = doc.getObject("XWiki.XWikiUsers") != null ? doc.getDocumentReference().toString() : "";
        List<BaseObject> objects = doc.getXObjects(policy.resolver.resolve(RIGHTS, doc.getDocumentReference()));
        List<BaseObject> view = objects == null ? List.of() : objects.stream().filter(o -> o != null &&
            rightsValues(o, "levels").contains("view")).toList();
        boolean adminFound = false, ownerFound = owner.isEmpty();
        boolean clean = view.size() == (owner.isEmpty() ? 1 : 2);
        for (BaseObject object : view) {
            boolean allow = object.getIntValue("allow") == 1 && rightsValues(object, "levels").equals(List.of("view"));
            if (allow && new HashSet<>(rightsValues(object, "groups")).equals(Set.of(ADMINS.split(","))) && rightsValues(object, "users").isEmpty()) {
                adminFound = true;
            } else if (allow && !owner.isEmpty() && rightsValues(object, "users").equals(List.of(owner)) &&
                    rightsValues(object, "groups").isEmpty()) { ownerFound = true; }
            else { clean = false; }
        }
        if (clean && adminFound && ownerFound) { return false; }
        for (BaseObject object : view) {
            String remaining = String.join(",", rightsValues(object, "levels").stream()
                .filter(level -> !level.isEmpty() && !level.equals("view")).toList());
            if (remaining.isEmpty()) { doc.removeXObject(object); }
            else { object.set("levels", remaining, context); }
        }
        addView(doc, ADMINS, "");
        if (!owner.isEmpty()) { addView(doc, "", owner); }
        return true;
    }

    private void addView(XWikiDocument doc, String groups, String users) throws Exception {
        BaseObject right = doc.newXObject(policy.resolver.resolve(RIGHTS, doc.getDocumentReference()), context);
        right.set("groups", groups, context); right.set("users", users, context);
        right.set("levels", "view", context); right.set("allow", 1, context);
    }

    void initialize() throws Exception {
        List<String> names = policy.queries.createQuery("select distinct doc.fullName from Document doc, " +
            "doc.object(XWiki.XWikiUsers) as profile where doc.space = 'XWiki' and doc.language = ''", Query.XWQL)
            .setWiki(context.getWikiId()).execute();
        List<String> groups = policy.queries.createQuery("select distinct doc.fullName from Document doc, " +
            "doc.object(XWiki.XWikiGroups) as membership where doc.space = 'XWiki' and doc.language = ''", Query.XWQL)
            .setWiki(context.getWikiId()).execute();
        Set<String> references = new HashSet<>(names); references.addAll(groups); references.add("XWiki.WebHome");
        for (String name : references) {
            DocumentReference ref = policy.resolver.resolve(name, new WikiReference(context.getWikiId()));
            XWikiDocument original = context.getWiki().getDocument(ref, context);
            if (original.isNew()) { continue; }
            XWikiDocument doc = original.clone();
            doc.setOriginalDocument(original);
            if (normalizeView(doc)) {
                doc.setContentAuthorReference(original.getContentAuthorReference());
                context.getWiki().saveDocument(doc, "Restrict account directory visibility", true, context);
            }
        }
    }

    String local(String name) {
        DocumentReference ref = policy.resolver.resolve(name, new WikiReference(context.getWikiId()));
        return ref.getWikiReference().getName().equals(context.getWikiId()) ? policy.localSerializer.serialize(ref) : ref.toString();
    }

    Map<String, Set<String>> memberships() throws Exception {
        List<Object[]> rows = policy.queries.createQuery("select doc.fullName, membership.member from Document doc, " +
            "doc.object(XWiki.XWikiGroups) as membership where doc.space = 'XWiki' and doc.language = ''", Query.XWQL)
            .setWiki(context.getWikiId()).execute();
        Map<String, Set<String>> graph = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] instanceof String group && row[1] instanceof String member && !member.isBlank()) {
                graph.computeIfAbsent(local(group), ignored -> new HashSet<>()).add(local(member));
            }
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (var role : ROLES.entrySet()) {
            Set<String> members = new HashSet<>();
            var pending = new ArrayDeque<>(role.getValue());
            while (!pending.isEmpty()) {
                String group = pending.remove();
                if (!members.add(group)) { continue; }
                pending.addAll(graph.getOrDefault(group, Set.of()));
            }
            result.put(role.getKey(), members);
        }
        return result;
    }

    Map<String, Object> users(String requestedRole, String search, String requestedPage) throws Exception {
        String role = ROLES.containsKey(requestedRole == null ? "" : requestedRole) || "unassigned".equals(requestedRole)
            ? requestedRole : "all";
        String term = search == null ? "" : search.strip();
        if (term.length() > 100) { term = term.substring(0, 100); }
        int page = 1;
        try { page = Math.max(1, Integer.parseInt(requestedPage)); } catch (RuntimeException ignored) { /* default */ }
        Map<String, Set<String>> roles = memberships();
        Set<String> assigned = new HashSet<>(); roles.values().forEach(assigned::addAll);
        Set<String> selected = role.equals("unassigned") ? assigned : roles.getOrDefault(role, Set.of());
        String from = " from Document doc, doc.object(XWiki.XWikiUsers) as profile where doc.space = 'XWiki' and doc.language = ''";
        if (!role.equals("all") && !selected.isEmpty()) {
            from += role.equals("unassigned") ? " and doc.fullName not in (:members)" : " and doc.fullName in (:members)";
        }
        if (!term.isEmpty()) {
            from += " and (lower(doc.name) like :search escape '!' or lower(profile.first_name) like :search escape '!'" +
                " or lower(profile.last_name) like :search escape '!')";
        }
        long total = 0;
        List<Object[]> rows = List.of();
        if (role.equals("all") || role.equals("unassigned") || !selected.isEmpty()) {
            Query count = directoryQuery("select count(distinct doc.fullName)" + from, selected, role, term);
            List<Number> counts = count.execute(); total = counts.get(0).longValue();
            int pages = (int) Math.max(1, (total + 24) / 25);
            page = Math.min(page, pages);
            rows = directoryQuery("select distinct doc.fullName, profile.first_name, profile.last_name" + from +
                " order by doc.fullName", selected, role, term).setLimit(25).setOffset((page - 1) * 25).execute();
        } else { page = 1; }
        List<Map<String, Object>> users = new ArrayList<>();
        for (Object[] row : rows) {
            String name = (String) row[0];
            String label = ((row[1] == null ? "" : row[1].toString()) + " " +
                (row[2] == null ? "" : row[2].toString())).strip();
            users.add(Map.of("reference", name, "label", label.isEmpty() ? name.substring(6) : label,
                "roles", ROLES.keySet().stream().sorted().filter(key -> roles.get(key).contains(name)).toList()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", true); result.put("users", users); result.put("total", total);
        result.put("page", page); result.put("pages", Math.max(1L, (total + 24) / 25));
        result.put("search", term); result.put("role", role);
        return result;
    }

    private Query directoryQuery(String statement, Set<String> members, String role, String term) throws Exception {
        Query query = policy.queries.createQuery(statement, Query.XWQL).setWiki(context.getWikiId());
        if (!role.equals("all") && !members.isEmpty()) { query.bindValue("members", new ArrayList<>(members)); }
        if (!term.isEmpty()) {
            query.bindValue("search", "%" + term.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        return query;
    }
}
