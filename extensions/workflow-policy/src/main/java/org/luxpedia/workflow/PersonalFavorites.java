package org.luxpedia.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;

/** Current-actor operations over the native Favorites Application profile object. */
final class PersonalFavorites {
    static final String CLASS = "Favorites.Code.FavoritesClass";
    static final ThreadLocal<DocumentReference> SAVING = new ThreadLocal<>();
    private static final Object[] LOCKS = new Object[64];
    static { for (int i = 0; i < LOCKS.length; i++) { LOCKS[i] = new Object(); } }
    final WorkflowPolicyListener policy;
    final XWikiContext context;

    PersonalFavorites(WorkflowPolicyListener policy, XWikiContext context) {
        this.policy = policy; this.context = context;
    }

    static boolean saving(XWikiDocument doc, XWikiDocument old, XWikiContext context) {
        return context.getUserReference() != null && context.getUserReference().equals(SAVING.get()) &&
            context.getUserReference().equals(doc.getDocumentReference()) && !old.isNew() &&
            Objects.equals(doc.getContent(), old.getContent()) && Objects.equals(doc.getTitle(), old.getTitle());
    }

    XWikiDocument profile() throws Exception {
        DocumentReference user = context.getUserReference();
        if (user == null || !user.getWikiReference().getName().equals(context.getWikiId()) ||
            !user.getLastSpaceReference().getName().equals("XWiki") ||
            user.getName().equals("XWikiGuest")) { return null; }
        XWikiDocument doc = context.getWiki().getDocument(user, context);
        return !doc.isNew() && doc.getObject("XWiki.XWikiUsers") != null ? doc : null;
    }

    static List<String> stored(XWikiDocument profile) {
        BaseObject object = profile.getObject(CLASS);
        if (object == null || object.safeget("pages") == null) { return List.of(); }
        if (object.safeget("pages") instanceof BaseProperty<?> property && property.getValue() == null) { return List.of(); }
        if (!(object.safeget("pages") instanceof BaseProperty<?> property) ||
            !(property.getValue() instanceof List<?> values)) {
            throw new IllegalStateException("Unsupported native favorites storage");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String name)) { throw new IllegalStateException("Invalid favorite reference"); }
            if (!name.isBlank()) { result.add(name); }
        }
        return result;
    }

    DocumentReference resolve(String name) {
        if (name == null || name.isBlank() || name.length() > 2048) { return null; }
        try {
            DocumentReference ref = policy.resolver.resolve(name, new WikiReference(context.getWikiId()));
            return ref.getWikiReference().getName().equals(context.getWikiId()) ? ref : null;
        } catch (RuntimeException error) { return null; }
    }

    boolean visible(DocumentReference ref) {
        return ref != null && policy.authorization.hasAccess(Right.VIEW, context.getUserReference(), ref);
    }

    boolean eligible(DocumentReference ref) throws Exception {
        return visible(ref) && Set.of("Knowledge", "Drafts", "Main").contains(ref.getSpaceReferences().get(0).getName()) &&
            !ref.getName().equals("WebPreferences") && !context.getWiki().getDocument(ref, context).isNew();
    }

    String canonical(DocumentReference ref) { return policy.localSerializer.serialize(ref); }

    Map<String, Object> read(String name, String requestedSearch, String requestedPage) throws Exception {
        XWikiDocument profile = profile();
        if (profile == null) { return Map.of("status", "forbidden", "authenticated", false); }
        String search = requestedSearch == null ? "" : requestedSearch.strip();
        if (search.length() > 100) { search = search.substring(0, 100); }
        DocumentReference target = resolve(name);
        List<String> names = new ArrayList<>(stored(profile));
        Collections.reverse(names);
        var seen = new LinkedHashSet<DocumentReference>();
        List<Map<String, Object>> visible = new ArrayList<>();
        boolean favorite = false;
        for (String stored : names) {
            DocumentReference ref = resolve(stored);
            if (ref == null || !seen.add(ref) || !visible(ref)) { continue; }
            XWikiDocument doc = context.getWiki().getDocument(ref, context);
            if (doc.isNew()) { continue; }
            if (ref.equals(target)) { favorite = true; }
            String title = doc.getTitle();
            if (title == null || title.isBlank()) {
                title = ref.getName().equals("WebHome") && ref.getSpaceReferences().size() > 1 ?
                    ref.getLastSpaceReference().getName() : ref.getName();
            }
            int end = ref.getSpaceReferences().size() - (ref.getName().equals("WebHome") ? 1 : 0);
            String category = String.join(".", ref.getSpaceReferences().subList(0, Math.max(1, end))
                .stream().map(space -> space.getName()).toList());
            visible.add(Map.of("reference", canonical(ref), "title", title, "category", category,
                "url", context.getWiki().getURL(ref, "view", context)));
        }
        final String needle = search.toLowerCase(Locale.ROOT);
        var matches = visible.stream().filter(item -> (item.get("title") + " " + item.get("category") + " " +
            item.get("reference")).toLowerCase(Locale.ROOT).contains(needle)).toList();
        int pages = Math.max(1, (matches.size() + 24) / 25), page = 1;
        try { page = Math.max(1, Math.min(pages, Integer.parseInt(requestedPage))); }
        catch (RuntimeException ignored) { /* first page */ }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok"); result.put("authenticated", true);
        result.put("eligible", eligible(target)); result.put("favorite", favorite);
        result.put("version", profile.getVersion()); result.put("search", search);
        result.put("page", page); result.put("pages", pages); result.put("total", matches.size());
        result.put("recent", visible.subList(0, Math.min(5, visible.size())));
        int offset = (page - 1) * 25;
        result.put("items", matches.subList(offset, Math.min(offset + 25, matches.size())));
        return result;
    }

    Map<String, Object> change(String name, String operation, String version) throws Exception {
        XWikiDocument actorProfile = profile();
        if (actorProfile == null) { return Map.of("status", "forbidden"); }
        if (!Set.of("add", "remove").contains(operation == null ? "" : operation)) { return Map.of("status", "invalid"); }
        DocumentReference target = resolve(name);
        if (target == null) { return Map.of("status", "forbidden"); }
        synchronized (LOCKS[Math.floorMod(actorProfile.getDocumentReference().hashCode(), LOCKS.length)]) {
            XWikiDocument original = profile();
            if (original == null) { return Map.of("status", "forbidden"); }
            if (!Objects.equals(version, original.getVersion())) { return Map.of("status", "conflict"); }
            var previous = stored(original);
            boolean add = operation.equals("add");
            if (add && !eligible(target)) { return Map.of("status", "forbidden"); }
            var updated = new ArrayList<>(previous);
            boolean present = updated.stream().map(this::resolve).anyMatch(target::equals);
            if (add && !present) { updated.add(canonical(target)); }
            else if (!add) { updated.removeIf(value -> target.equals(resolve(value))); }
            if (previous.equals(updated)) { return Map.of("status", "ok", "favorite", present); }
            XWikiDocument doc = original.clone();
            BaseObject object = doc.getObject(CLASS);
            if (object == null) { object = doc.newXObject(policy.resolver.resolve(CLASS, doc.getDocumentReference()), context); }
            object.setStringListValue("pages", updated);
            doc.setAuthorReference(context.getUserReference());
            doc.setContentAuthorReference(original.getContentAuthorReference());
            doc.setMetaDataDirty(true);
            SAVING.set(doc.getDocumentReference());
            try { context.getWiki().saveDocument(doc, add ? "Add personal favorite" : "Remove personal favorite", true, context); }
            finally { SAVING.remove(); }
            // A canceled save must never be reported as a successful toggle.
            boolean saved = stored(context.getWiki().getDocument(doc.getDocumentReference(), context)).stream()
                .map(this::resolve).anyMatch(target::equals);
            return Map.of("status", saved == add ? "ok" : "error", "favorite", saved);
        }
    }
}
