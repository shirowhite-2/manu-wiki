package org.luxpedia.workflow;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.XWikiDeletedDocument;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;

/** Administrator operations over native history and the native recycle bin. No permanent-delete API. */
final class AdminOperations {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> SYSTEM = Set.of("Knowledge.WebHome", "Knowledge.DocumentLibrary", "Knowledge.CategoryIndex",
        "Knowledge.CategoryGuide", "Knowledge.CategoryAdmin", "Knowledge.Archive.WebHome", "Drafts.WebHome");
    static final Map<String, Ticket> TICKETS = new ConcurrentHashMap<>();
    static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdminOperations.class);
    record Item(DocumentReference reference, String version, Map<String, String> translations, long trashId, String locale) { }
    record Ticket(String actor, String wiki, String operation, List<Item> items, long expires) { }
    final WorkflowPolicyListener policy;
    final XWikiContext context;
    org.xwiki.script.service.ScriptServiceManager services;
    org.xwiki.environment.Environment environment;
    AdminOperations(WorkflowPolicyListener policy, XWikiContext context) { this.policy = policy; this.context = context; }
    AdminOperations(WorkflowPolicyListener policy, XWikiContext context, org.xwiki.script.service.ScriptServiceManager services) { this(policy, context); this.services = services; }
    static String text(Object value) { return value == null ? "" : value.toString(); }
    static int number(String value, int fallback, int max) {
        try { return Math.min(max, Math.max(1, Integer.parseInt(value))); } catch (RuntimeException ignored) { return fallback; }
    }
    DocumentReference reference(String name) {
        WorkflowPolicyListener.require(name != null && !name.isBlank() && name.length() <= 1024, "Invalid page.");
        var ref = policy.resolver.resolve(name, new WikiReference(context.getWikiId()));
        WorkflowPolicyListener.require(ref.getWikiReference().getName().equals(context.getWikiId()) &&
            Set.of("Knowledge", "Drafts").contains(ref.getSpaceReferences().get(0).getName()) &&
            !ref.getName().equals("WebPreferences") && !SYSTEM.contains(policy.localSerializer.serialize(ref)), "Protected page.");
        return ref;
    }
    boolean eligible(XWikiDocument doc) {
        return !doc.isNew() && doc.getObject("LuxPedia.Code.CategoryClass") == null;
    }
    String availability(XWikiDocument doc) throws Exception {
        if (!eligible(doc)) { return "protected"; }
        var flow = doc.getObject(WorkflowPolicyListener.WF);
        if (EditLease.active(doc) || Set.of("moderating", "validating", "valid").contains(WorkflowPolicyListener.status(flow))) { return "busy"; }
        return "ready";
    }
    List<DocumentReference> linkedDrafts(DocumentReference target) throws Exception {
        List<String> names = policy.queries.createQuery("from doc.object(PublicationWorkflow.PublicationWorkflowClass) as wf " +
            "where wf.istarget = 0 and (wf.target = :local or wf.target = :full)", Query.XWQL).setWiki(context.getWikiId())
            .bindValue("local", policy.localSerializer.serialize(target)).bindValue("full", target.toString()).execute();
        List<DocumentReference> result = new ArrayList<>();
        WorkflowPolicyListener.require(names.size() <= 1, "Duplicate linked drafts need repair.");
        for (String name : names) { result.add(reference(name)); }
        return result;
    }
    void guardAndCheck(DocumentReference ref) throws Exception {
        List<DocumentReference> drafts = List.of();
        if (ref.getSpaceReferences().get(0).getName().equals("Knowledge")) {
            DraftWriteBoundary.guard("target:" + ref); drafts = linkedDrafts(ref);
        } else {
            var before = context.getWiki().getDocument(ref, context).getObject(WorkflowPolicyListener.WF);
            if (before != null && !before.getStringValue("target").isBlank()) {
                var target = policy.resolver.resolve(before.getStringValue("target"), ref);
                WorkflowPolicyListener.require(target.getWikiReference().equals(ref.getWikiReference()), "Foreign workflow target.");
                DraftWriteBoundary.guard("target:" + target);
            }
        }
        for (var draft : drafts) {
            DraftWriteBoundary.guard(draft.toString());
            WorkflowPolicyListener.require("ready".equals(availability(context.getWiki().getDocument(draft, context))), "Linked draft is occupied or in review.");
        }
        DraftWriteBoundary.guard(ref.toString());
    }
    Map<String, Object> pages(String search, String area, String requestedPage) throws Exception {
        String scope = "Drafts".equals(area) ? "Drafts" : "Knowledge";
        String term = text(search).strip(); WorkflowPolicyListener.require(term.length() <= 100, "Search is too long.");
        List<String> names = policy.queries.createQuery("where (doc.space = :root or doc.space like :nested) and doc.language = '' " +
            "and (lower(doc.fullName) like :search escape '!' or lower(doc.title) like :search escape '!') order by doc.fullName", Query.XWQL)
            .setWiki(context.getWikiId()).bindValue("root", scope).bindValue("nested", scope + ".%")
            .bindValue("search", "%" + term.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%").execute();
        int page = number(requestedPage, 1, 100000), start = (page - 1) * 25, visible = 0; List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) {
            DocumentReference ref;
            try { ref = reference(name); } catch (SecurityException ignored) { continue; }
            XWikiDocument doc = context.getWiki().getDocument(ref, context); if (!eligible(doc)) { continue; }
            if (visible >= start && rows.size() < 25) {
                rows.add(Map.of("reference", policy.localSerializer.serialize(ref), "title", text(doc.getTitle()).isBlank() ? ref.getName() : doc.getTitle(),
                    "version", doc.getVersion(), "state", availability(doc), "url", context.getWiki().getURL(ref, "view", context)));
            } visible++;
        }
        return Map.of("status", "ok", "items", rows, "page", page, "pages", Math.max(1, (visible + 24) / 25), "total", visible);
    }
    Map<String, Object> trash(String requestedPage) throws Exception {
        int page = number(requestedPage, 1, 100000);
        String where = " from com.xpn.xwiki.doc.XWikiDeletedDocument d where (d.fullName like 'Knowledge.%' or d.fullName like 'Drafts.%')";
        List<Number> count = policy.queries.createQuery("select count(d.id)" + where, Query.HQL).setWiki(context.getWikiId()).execute();
        long total = count.isEmpty() ? 0 : count.get(0).longValue();
        page = (int) Math.min(page, Math.max(1, (total + 24) / 25));
        List<Object[]> entries = policy.queries.createQuery("select d.id, d.fullName, d.language, d.date, d.deleter" + where + " order by d.date desc, d.id desc", Query.HQL)
            .setWiki(context.getWikiId()).setLimit(25).setOffset((page - 1) * 25).execute();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : entries) {
            try { reference(text(row[1])); } catch (SecurityException ignored) { continue; }
            rows.add(Map.of("id", text(row[0]), "reference", text(row[1]), "locale", text(row[2]),
                "date", row[3] instanceof Date date ? date.toInstant().toString() : "", "actor", text(row[4])));
        }
        return Map.of("status", "ok", "items", rows, "page", page, "pages", Math.max(1L, (total + 24) / 25), "total", total);
    }
    XWikiDocument localized(DocumentReference ref, String language) throws Exception {
        var base = context.getWiki().getDocument(ref, context);
        if (language.isEmpty()) { return base; }
        var translated = base.getTranslatedDocument(Locale.forLanguageTag(language.replace('_', '-')), context);
        return translated == base ? null : translated;
    }
    Map<String, Object> preview(String operation, String payload) throws Exception {
        WorkflowPolicyListener.require(context.getWiki().hasRecycleBin(context), "Recycle bin is disabled.");
        WorkflowPolicyListener.require(payload != null && payload.length() <= 40000, "Invalid selection.");
        List<?> selected = JSON.readValue(payload, List.class);
        WorkflowPolicyListener.require(!selected.isEmpty() && selected.size() <= 20, "Select between 1 and 20 pages.");
        List<Item> items = new ArrayList<>(); Set<String> seen = new HashSet<>(); List<Map<String, Object>> rows = new ArrayList<>();
        for (Object entry : selected) {
            WorkflowPolicyListener.require(entry instanceof String, "Invalid selection.");
            String name = (String) entry; if (!seen.add(name)) { continue; }
            if (operation.equals("delete")) {
                var ref = reference(name); var doc = context.getWiki().getDocument(ref, context);
                WorkflowPolicyListener.require("ready".equals(availability(doc)), "Page is protected, occupied or in review.");
                if (ref.getSpaceReferences().get(0).getName().equals("Knowledge")) {
                    for (var draft : linkedDrafts(ref)) { WorkflowPolicyListener.require("ready".equals(availability(context.getWiki().getDocument(draft, context))), "Linked draft is occupied or in review."); }
                }
                Map<String, String> locales = new LinkedHashMap<>();
                for (String locale : doc.getTranslationList(context)) {
                    var translated = localized(ref, locale); if (translated != null && !translated.isNew()) { locales.put(locale, translated.getVersion()); }
                }
                items.add(new Item(ref, doc.getVersion(), locales, 0, ""));
                rows.add(Map.of("reference", policy.localSerializer.serialize(ref), "version", doc.getVersion(), "translations", locales.size(), "attachments", doc.getAttachmentList().size()));
            } else {
                long id = Long.parseLong(name); var deleted = context.getWiki().getDeletedDocument(id, context);
                WorkflowPolicyListener.require(deleted != null, "Trash entry is missing.");
                var ref = reference(deleted.getFullName()); var destination = localized(ref, deleted.getLanguage());
                WorkflowPolicyListener.require(destination == null || destination.isNew(), "Destination already exists.");
                items.add(new Item(ref, "", Map.of(), id, deleted.getLanguage()));
                rows.add(Map.of("reference", policy.localSerializer.serialize(ref), "id", name, "locale", deleted.getLanguage()));
            }
        }
        long now = System.currentTimeMillis(); TICKETS.entrySet().removeIf(e -> e.getValue().expires < now);
        WorkflowPolicyListener.require(TICKETS.size() < 128, "Too many pending previews.");
        String key = UUID.randomUUID().toString();
        TICKETS.put(key, new Ticket(context.getUserReference().toString(), context.getWikiId(), operation, List.copyOf(items), now + 300000));
        return Map.of("status", "ok", "ticket", key, "items", rows, "expiresAt", now + 300000, "operation", operation);
    }
    Map<String, Object> execute(String key) throws Exception {
        Ticket ticket = TICKETS.get(key);
        WorkflowPolicyListener.require(ticket != null && ticket.actor.equals(context.getUserReference().toString()) && ticket.wiki.equals(context.getWikiId()) &&
            ticket.expires > System.currentTimeMillis(), "Preview expired or belongs to another administrator.");
        WorkflowPolicyListener.require(TICKETS.remove(key, ticket), "Preview was already used.");
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Item> ordered = new ArrayList<>(ticket.items); ordered.sort(Comparator.comparing(Item::locale));
        for (Item item : ordered) {
            String result = "failed";
            try {
                guardAndCheck(item.reference);
                if (ticket.operation.equals("delete")) {
                    var doc = context.getWiki().getDocument(item.reference, context);
                    WorkflowPolicyListener.require(doc.getVersion().equals(item.version) && "ready".equals(availability(doc)), "Page changed since preview.");
                    Set<String> translations = new HashSet<>(doc.getTranslationList(context));
                    WorkflowPolicyListener.require(translations.equals(item.translations.keySet()), "Languages changed since preview.");
                    for (var locale : item.translations.entrySet()) {
                        var translated = localized(item.reference, locale.getKey());
                        WorkflowPolicyListener.require(translated != null && translated.getVersion().equals(locale.getValue()), "Translation changed since preview.");
                    }
                    WorkflowPolicyListener.require(context.getWiki().hasRecycleBin(context), "Recycle bin is disabled.");
                    context.getWiki().deleteAllDocuments(doc, true, context);
                    result = context.getWiki().getDocument(item.reference, context).isNew() ? "deleted" : "failed";
                } else {
                    WorkflowPolicyListener.require(item.locale.isEmpty() || !context.getWiki().getDocument(item.reference, context).isNew(), "Restore the default language first.");
                    var destination = localized(item.reference, item.locale);
                    WorkflowPolicyListener.require(destination == null || destination.isNew(), "Destination already exists.");
                    XWikiDeletedDocument deleted = context.getWiki().getDeletedDocument(item.trashId, context);
                    WorkflowPolicyListener.require(deleted != null && reference(deleted.getFullName()).equals(item.reference) && deleted.getLanguage().equals(item.locale), "Trash entry changed.");
                    var restored = context.getWiki().getRecycleBinStore().restoreFromRecycleBin(item.trashId, context, true);
                    WorkflowPolicyListener.require(restored.getDocumentReference().equals(item.reference), "Restored reference mismatch.");
                    var old = destination == null ? new XWikiDocument(item.reference) : destination;
                    restored.setOriginalDocument(old); restored.setNew(true);
                    if (EditLease.scope(restored)) {
                        EditLease.clear(restored);
                        policy.normalizeDraftRights(restored, WorkflowPolicyListener.status(restored.getObject(WorkflowPolicyListener.WF)), context);
                    }
                    EditLease.SAVING.set(restored);
                    try { context.getWiki().saveDocument(restored, "Administrator restore from recycle bin", false, context); }
                    finally { EditLease.SAVING.remove(); }
                    var stored = localized(item.reference, item.locale);
                    WorkflowPolicyListener.require(stored != null && !stored.isNew(), "Restoration did not persist.");
                    context.getWiki().getRecycleBinStore().deleteFromRecycleBin(item.trashId, context, true);
                    result = "restored";
                }
            } catch (SecurityException conflict) { result = "conflict"; }
            catch (Exception failure) { LOG.warn("Admin {} failed on {} by {}", ticket.operation, item.reference, ticket.actor); }
            rows.add(Map.of("reference", policy.localSerializer.serialize(item.reference), "id", Long.toString(item.trashId), "locale", item.locale, "result", result));
            LOG.info("Admin {} on {} by {}: {}", ticket.operation, item.reference, ticket.actor, result);
        }
        return Map.of("status", "ok", "items", rows, "operation", ticket.operation);
    }
    Map<String, Object> dashboard(String requestedDays) throws Exception {
        int days = Set.of("7", "30", "90").contains(requestedDays) ? Integer.parseInt(requestedDays) : 30;
        var result = new LinkedHashMap<String, Object>(); result.put("status", "ok");
        var directory = new IdentityDirectory(policy, context); var roles = new LinkedHashMap<String, Object>();
        for (String role : List.of("all", "managers", "editors", "reviewers", "readers", "unassigned")) { roles.put(role, directory.users(role, "", "1").get("total")); }
        result.put("roles", roles);
        try {
            List<Object[]> authors = policy.queries.createQuery("select r.author, count(*) from com.xpn.xwiki.doc.rcs.XWikiRCSNodeInfo r, com.xpn.xwiki.doc.XWikiDocument d " +
                "where r.id.docId = d.id and r.date >= :since and r.id.version2 = 1 and d.language = '' " +
                "and (d.fullName like 'Knowledge.%' or d.fullName like 'Drafts.%') group by r.author order by count(*) desc", Query.HQL)
                .setWiki(context.getWikiId()).bindValue("since", Date.from(Instant.now().minusSeconds(days * 86400L))).execute();
            long edits = 0; var leaders = new ArrayList<Map<String, Object>>();
            for (Object[] row : authors) { long count = ((Number) row[1]).longValue(); edits += count; if (leaders.size() < 20) { leaders.add(Map.of("user", text(row[0]), "count", count)); } }
            result.put("activity", Map.of("available", true, "days", days, "edits", edits, "users", authors.size(), "leaders", leaders, "basis", "retained-major-revisions"));
        } catch (Exception unavailable) { result.put("activity", Map.of("available", false, "days", days)); }
        Runtime runtime = Runtime.getRuntime();
        var checks = new ArrayList<Map<String, Object>>();
        try { policy.queries.createQuery("select count(doc.fullName) from Document doc where doc.language = ''", Query.XWQL).setWiki(context.getWikiId()).setLimit(1).execute();
            checks.add(Map.of("name", "database", "state", "ok")); } catch (Exception error) { checks.add(Map.of("name", "database", "state", "error")); }
        checks.add(Map.of("name", "workflow", "state", services != null && services.get("publicationworkflow") != null ? "ok" : "unavailable"));
        checks.add(Map.of("name", "recycleBin", "state", context.getWiki().hasRecycleBin(context) ? "ok" : "disabled"));
        checks.add(Map.of("name", "writeGuard", "state", DraftWriteBoundary.present() ? "ok" : "error"));
        checks.add(Map.of("name", "attachments", "state", context.getWiki().getAttachmentStore() == null ? "unavailable" : "ok"));
        java.io.File storage = environment == null ? null : environment.getPermanentDirectory();
        long diskTotal = storage != null && storage.isDirectory() ? storage.getTotalSpace() : 0;
        long diskFree = storage != null && storage.isDirectory() ? storage.getUsableSpace() : 0;
        checks.add(Map.of("name", "storage", "state", diskTotal > 0 ? "ok" : "unavailable"));
        checks.add(Map.of("name", "containers", "state", "unavailable")); checks.add(Map.of("name", "backup", "state", "unavailable"));
        result.put("runtime", Map.of("wiki", text(context.getWiki().getVersion()), "policy", policy.getPolicyVersion(), "java", System.getProperty("java.version"),
            "uptime", ManagementFactory.getRuntimeMXBean().getUptime(), "heapUsed", runtime.totalMemory() - runtime.freeMemory(), "heapMax", runtime.maxMemory(), "diskTotal", diskTotal, "diskFree", diskFree, "checks", checks));
        return result;
    }
}
