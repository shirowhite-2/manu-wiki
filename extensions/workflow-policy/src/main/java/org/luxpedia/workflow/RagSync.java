package org.luxpedia.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;

/**
 * Manual, incremental sync of published Knowledge articles into wiki-owned LightRAG workspaces.
 * Changes are computed against the local ledger only; LightRAG document lists are never read,
 * and documents are only updated or deleted by the ids this wiki recorded when it inserted them.
 */
final class RagSync {
    static final String ARTICLE = "LuxPedia.Code.ArticleClass";
    static final Set<String> SYSTEM_NAMES = Set.of("WebPreferences", "DocumentLibrary", "CategoryIndex", "CategoryGuide", "CategoryAdmin");
    static final String MANAGERS = "XWiki.LuxManagers,XWiki.XWikiAdminGroup";
    static final Map<String, Ticket> TICKETS = new ConcurrentHashMap<>();
    static final Map<String, Run> RUNS = new ConcurrentHashMap<>();
    static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RagSync.class);
    static long budgetMillis = 20000, busyPauseMillis = 2000;
    static int batchSize = 25, maxBusyAttempts = 30;

    record Category(String reference, String title, List<String> path, boolean enabled, boolean explicit) { }
    record Candidate(String reference, String title, String category, String workspace, String fileSource, String docId,
        String text, String hash, String version) { }
    record Evaluation(Candidate candidate, String reason, int chars) { }
    record Action(String type, String reference, String title, String workspace, String fileSource,
        String oldWorkspace, String oldDocId) { }
    record Step(String kind, Action action) { }
    record Ticket(String actor, String wiki, List<Action> actions, long expires) { }
    static final class Run {
        final String id = UUID.randomUUID().toString(), actor, wiki, startedAt = Instant.now().toString();
        final List<Step> steps; final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<Map<String, Object>> failures = new ArrayList<>(); final Set<String> blocked = new HashSet<>();
        int index, busy; long touched = System.currentTimeMillis(); String aborted = "";
        Run(String actor, String wiki, List<Step> steps) {
            this.actor = actor; this.wiki = wiki; this.steps = steps;
            for (String key : List.of("inserted", "updated", "moved", "deleted", "refreshed", "skipped", "failed")) { counts.put(key, 0); }
        }
        boolean done() { return index >= steps.size() || !aborted.isEmpty(); }
    }
    /** Loaded settings, categories, workspace names and ledger for one request. */
    final class Snapshot {
        final Map<String, Object> settings; final List<Category> categories = new ArrayList<>();
        final Map<List<String>, Category> bySpaces = new HashMap<>(); final Map<String, String> workspaces;
        final Map<String, Object> state; final Map<String, Map<String, Map<String, Object>>> ledger = new TreeMap<>();
        final Set<String> owned = new LinkedHashSet<>(); final Set<String> dirty = new HashSet<>();
        /** Owned workspaces found deleted in LightRAG; their old ledger no longer describes anything there. */
        final Set<String> lost = new TreeSet<>();
        Snapshot() throws Exception { this(null); }
        /** Connection fields from an unsaved form replace the stored ones; nothing is saved. */
        Snapshot(Map<?, ?> connection) throws Exception {
            var stored = new RagSettings(policy, context).stored();
            settings = connection == null ? stored : RagSettings.connection(connection, stored);
            Map<?, ?> chosen = settings.get("categories") instanceof Map<?, ?> map ? map : Map.of();
            for (String name : query("from doc.object(LuxPedia.Code.CategoryClass) as category where doc.space like 'Knowledge.%' " +
                "and doc.name = 'WebHome' and doc.language = '' order by doc.fullName")) {
                DocumentReference ref; try { ref = resolve(name); } catch (SecurityException ignored) { continue; }
                var spaces = names(ref);
                if (!spaces.get(0).equals("Knowledge") || spaces.size() < 2) { continue; }
                var doc = context.getWiki().getDocument(ref, context);
                if (doc.isNew() || doc.getObject(WorkflowPolicyListener.CATEGORY) == null) { continue; }
                String key = policy.localSerializer.serialize(ref); var path = spaces.subList(1, spaces.size());
                boolean explicit = chosen.containsKey(key);
                boolean enabled = explicit ? Boolean.TRUE.equals(chosen.get(key)) : path.size() == 1 && !path.get(0).equals("Archive");
                String title = AdminOperations.text(doc.getTitle()).isBlank() ? path.get(path.size() - 1) : doc.getTitle();
                var category = new Category(key, title, List.copyOf(path), enabled, explicit);
                categories.add(category); bySpaces.put(List.copyOf(spaces), category);
            }
            workspaces = workspaceNames(AdminOperations.text(settings.get("prefix")), categories);
            state = state();
            for (var entry : ((Map<String, Map<String, Object>>) state.get("workspaces")).entrySet()) {
                String workspace = entry.getKey(); owned.add(workspace);
                if (AdminOperations.text(entry.getValue().get("lost")).isEmpty()) { ledger.put(workspace, loadLedger(workspace)); }
                else { lost.add(workspace); ledger.put(workspace, new TreeMap<>()); }
            }
        }
        LightRagClient client() {
            return new LightRagClient(AdminOperations.text(settings.get("baseUrl")), RagSettings.apiKey(settings),
                RagSettings.number(settings, "timeoutSeconds", 30), owned);
        }
    }

    final WorkflowPolicyListener policy; final XWikiContext context;
    RagSync(WorkflowPolicyListener policy, XWikiContext context) { this.policy = policy; this.context = context; }

    List<String> query(String statement) throws Exception {
        return policy.queries.createQuery(statement, Query.XWQL).setWiki(context.getWikiId()).execute();
    }
    DocumentReference resolve(String name) {
        var ref = policy.resolver.resolve(name, new WikiReference(context.getWikiId()));
        WorkflowPolicyListener.require(ref.getWikiReference().getName().equals(context.getWikiId()), "Cross-wiki page.");
        return ref;
    }
    static List<String> names(DocumentReference ref) {
        return ref.getSpaceReferences().stream().map(EntityReference::getName).toList();
    }
    DocumentReference code(String name) { return new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Code"), name); }

    static String hex(String algorithm, String value) {
        try {
            var digest = MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
            var out = new StringBuilder(); for (byte b : digest) { out.append(String.format("%02x", b)); } return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** LightRAG derives text document ids as "doc-" + md5(file_source) (lightrag/pipeline.py). */
    static String docId(String fileSource) { return "doc-" + hex("MD5", fileSource); }
    static String part(String value, String allowed) {
        String cleaned = value.replaceAll(allowed, "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        return cleaned.isEmpty() ? "x" : cleaned;
    }
    /** Hierarchy is flattened into the name; LightRAG keeps only the basename of file_source. */
    static String fileSource(DocumentReference ref, String serialized) {
        var parts = new ArrayList<>(names(ref).subList(1, ref.getSpaceReferences().size()));
        if (!ref.getName().equals("WebHome")) { parts.add(ref.getName()); }
        String stem = String.join("__", parts.stream().map(value -> part(value, "[^\\p{L}\\p{N}_-]")).toList());
        if (stem.length() > 120) { stem = stem.substring(0, 120); }
        return stem + "-" + hex("SHA-1", serialized).substring(0, 10) + ".md";
    }
    static Map<String, String> workspaceNames(String prefix, List<Category> categories) {
        Map<String, String> result = new LinkedHashMap<>(); Set<String> used = new HashSet<>();
        for (var category : categories.stream().filter(Category::enabled).sorted(Comparator.comparing(Category::reference)).toList()) {
            String name = prefix + "_" + part(String.join("_", category.path()), "[^A-Za-z0-9_]");
            if (name.length() > 60) { name = name.substring(0, 60); }
            if (!used.add(name.toLowerCase(Locale.ROOT))) {
                name = name + "_" + hex("SHA-1", category.reference()).substring(0, 6); used.add(name.toLowerCase(Locale.ROOT));
            }
            result.put(category.reference(), name);
        }
        return result;
    }

    // ---- local state and ledger --------------------------------------------------------------

    static void restrict(WorkflowPolicyListener policy, XWikiDocument doc, XWikiContext context) throws Exception {
        BaseObject rule = doc.newXObject(policy.resolver.resolve("XWiki.XWikiRights", doc.getDocumentReference()), context);
        rule.set("groups", MANAGERS, context); rule.set("levels", "view,edit", context); rule.set("allow", 1, context);
        doc.setHidden(true);
    }
    Map<String, Object> payload(DocumentReference ref) throws Exception {
        var object = context.getWiki().getDocument(ref, context).getObject(RagSettings.CLASS);
        if (object == null || object.getStringValue("payload").isBlank()) { return new LinkedHashMap<>(); }
        return AdminOperations.JSON.readValue(object.getStringValue("payload"), LinkedHashMap.class);
    }
    void store(DocumentReference ref, Object value, String comment) throws Exception {
        var original = context.getWiki().getDocument(ref, context); var doc = original.clone();
        var object = doc.getObject(RagSettings.CLASS);
        if (object == null) { object = doc.newXObject(policy.resolver.resolve(RagSettings.CLASS, ref), context); }
        if (original.isNew()) { restrict(policy, doc, context); doc.setTitle(ref.getName()); }
        object.set("payload", AdminOperations.JSON.writeValueAsString(value), context);
        context.getWiki().saveDocument(doc, comment, true, context);
    }
    Map<String, Object> state() throws Exception {
        var state = payload(code("RagSyncState"));
        state.putIfAbsent("workspaces", new LinkedHashMap<>()); state.putIfAbsent("runs", new ArrayList<>()); state.putIfAbsent("failures", new ArrayList<>());
        return state;
    }
    DocumentReference ledgerReference(String workspace) { return code("RagLedger_" + workspace); }
    Map<String, Map<String, Object>> loadLedger(String workspace) throws Exception {
        Object entries = payload(ledgerReference(workspace)).get("entries");
        return entries instanceof Map<?, ?> map ? new TreeMap<>((Map<String, Map<String, Object>>) map) : new TreeMap<>();
    }
    void markWorkspace(Snapshot snapshot, String workspace, String flag, String value) throws Exception {
        var state = state(); var owned = (Map<String, Object>) state.get("workspaces");
        var info = new LinkedHashMap<String, Object>(owned.get(workspace) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
        if (value == null) { info.remove(flag); } else { info.put(flag, value); }
        owned.put(workspace, info); store(code("RagSyncState"), state, "Update LightRAG workspace state");
        ((Map<String, Object>) snapshot.state.get("workspaces")).put(workspace, info);
    }
    void markLost(Snapshot snapshot, String workspace) throws Exception {
        if (snapshot.lost.contains(workspace)) { return; }
        markWorkspace(snapshot, workspace, "lost", Instant.now().toString());
        snapshot.lost.add(workspace); snapshot.ledger.put(workspace, new TreeMap<>());
        LOG.warn("LightRAG workspace {} was deleted outside the wiki; its articles will be sent again", workspace);
    }
    static boolean workspaceDeleted(LightRagClient.Response response) {
        return response.status() == 404 && response.detail().contains("has been deleted");
    }
    /** Compares owned workspaces with LightRAG's workspace names only; returns lost workspaces that reappeared. */
    List<String> detect(Snapshot snapshot, LightRagClient client) throws Exception {
        var existing = new HashSet<>(client.workspaces()); var reappeared = new ArrayList<String>();
        for (String workspace : List.copyOf(snapshot.owned)) {
            if (!existing.contains(workspace)) { markLost(snapshot, workspace); }
            else if (snapshot.lost.contains(workspace)) { reappeared.add(workspace); }
        }
        return reappeared;
    }
    void persist(Snapshot snapshot) throws Exception {
        for (String workspace : snapshot.dirty) {
            store(ledgerReference(workspace), Map.of("workspace", workspace, "entries", snapshot.ledger.get(workspace)), "Update LightRAG sync ledger");
        }
        snapshot.dirty.clear();
    }

    // ---- scanning and planning ---------------------------------------------------------------

    Evaluation evaluate(DocumentReference ref, Snapshot snapshot) throws Exception {
        var spaces = names(ref);
        if (!spaces.get(0).equals("Knowledge") || SYSTEM_NAMES.contains(ref.getName())) { return new Evaluation(null, "excluded", 0); }
        var doc = context.getWiki().getDocument(ref, context);
        var flow = doc.getObject(WorkflowPolicyListener.WF);
        if (doc.isNew() || Boolean.TRUE.equals(doc.isHidden()) || doc.getObject(WorkflowPolicyListener.CATEGORY) != null || flow == null ||
            flow.getIntValue("istarget") != 1 || !"published".equals(WorkflowPolicyListener.status(flow))) { return new Evaluation(null, "excluded", 0); }
        Category owner = null; boolean categorized = false; var trail = new ArrayList<String>();
        for (int size = spaces.size(); size >= 2; size--) {
            var category = snapshot.bySpaces.get(spaces.subList(0, size));
            if (category == null) { continue; }
            categorized = true; trail.add(0, category.title());
            if (owner == null && category.enabled()) { owner = category; }
        }
        if (!categorized) { return new Evaluation(null, "uncategorized", 0); }
        if (owner == null) { return new Evaluation(null, "disabled", 0); }
        String serialized = policy.localSerializer.serialize(ref);
        String title = AdminOperations.text(doc.getTitle()).isBlank() ? ref.getName() : doc.getTitle();
        String text = document(doc, title, serialized, String.join(" / ", trail));
        if (text.length() > RagSettings.number(snapshot.settings, "maxChars", 200000)) { return new Evaluation(null, "too_large", text.length()); }
        String source = fileSource(ref, serialized);
        return new Evaluation(new Candidate(serialized, title, owner.reference(), snapshot.workspaces.get(owner.reference()), source,
            docId(source), text, hex("SHA-256", text), doc.getVersion()), "", text.length());
    }

    String document(XWikiDocument doc, String title, String serialized, String trail) {
        String body;
        try { var xdom = doc.getXDOM(); body = xdom == null ? RagTextExtractor.clean(doc.getContent()) : RagTextExtractor.text(xdom); }
        catch (RuntimeException unparsable) { body = RagTextExtractor.clean(doc.getContent()); }
        var lines = new ArrayList<String>(); lines.add("# " + title); lines.add("");
        String url = "";
        try { url = AdminOperations.text(doc.getExternalURL("view", context)); } catch (RuntimeException ignored) { }
        var article = doc.getObject(ARTICLE);
        for (String[] field : new String[][] {{"Source", "LuxPedia wiki"}, {"Page", serialized}, {"URL", url}, {"Category path", trail},
            {"Tags", article == null ? "" : article.getStringValue("tagsText")}, {"Summary", article == null ? "" : article.getStringValue("summary")},
            {"Owner", article == null ? "" : article.getStringValue("owner")}, {"Applicable version", article == null ? "" : article.getStringValue("applicableVersion")}}) {
            String value = RagTextExtractor.tidy(AdminOperations.text(field[1])).replace('\n', ' ');
            if (!value.isBlank()) { lines.add(field[0] + ": " + value); }
        }
        lines.add(""); lines.add(body);
        return RagTextExtractor.tidy(String.join("\n", lines));
    }

    record Scan(Map<String, Candidate> candidates, List<Map<String, Object>> skipped, Map<String, Integer> excluded) { }
    Scan scan(Snapshot snapshot) throws Exception {
        Map<String, Candidate> candidates = new TreeMap<>(); List<Map<String, Object>> skipped = new ArrayList<>();
        Map<String, Integer> excluded = new LinkedHashMap<>(Map.of("uncategorized", 0, "disabled", 0, "too_large", 0));
        for (String name : query("from doc.object(PublicationWorkflow.PublicationWorkflowClass) as wf where (doc.space = 'Knowledge' or " +
            "doc.space like 'Knowledge.%') and doc.language = '' and wf.istarget = 1 order by doc.fullName")) {
            DocumentReference ref; try { ref = resolve(name); } catch (SecurityException ignored) { continue; }
            var result = evaluate(ref, snapshot);
            if (result.candidate() != null) { candidates.put(result.candidate().reference(), result.candidate()); continue; }
            if (excluded.containsKey(result.reason())) { excluded.merge(result.reason(), 1, Integer::sum); }
            if (result.reason().equals("too_large") && skipped.size() < 100) { skipped.add(Map.of("reference", name, "reason", "too_large", "chars", result.chars())); }
        }
        return new Scan(candidates, skipped, excluded);
    }

    static List<Action> plan(Map<String, Candidate> current, Map<String, Map<String, Map<String, Object>>> ledger) {
        List<Action> deletes = new ArrayList<>(), moves = new ArrayList<>(), updates = new ArrayList<>(), inserts = new ArrayList<>(), refreshes = new ArrayList<>();
        // One record per page is kept; prefer the one already in the page's target workspace. Any other copy is deleted.
        Map<String, String> primary = new HashMap<>();
        for (var workspace : ledger.entrySet()) {
            for (String reference : workspace.getValue().keySet()) {
                var candidate = current.get(reference);
                if (!primary.containsKey(reference) || candidate != null && workspace.getKey().equals(candidate.workspace())) { primary.put(reference, workspace.getKey()); }
            }
        }
        for (var workspace : ledger.entrySet()) {
            for (var entry : workspace.getValue().entrySet()) {
                if (current.containsKey(entry.getKey()) && workspace.getKey().equals(primary.get(entry.getKey()))) { continue; }
                deletes.add(new Action("delete", entry.getKey(), AdminOperations.text(entry.getValue().get("title")), "", "",
                    workspace.getKey(), AdminOperations.text(entry.getValue().get("docId"))));
            }
        }
        for (var candidate : current.values()) {
            String workspace = primary.get(candidate.reference());
            if (workspace == null) {
                inserts.add(new Action("insert", candidate.reference(), candidate.title(), candidate.workspace(), candidate.fileSource(), "", ""));
                continue;
            }
            var values = ledger.get(workspace).get(candidate.reference());
            if (!workspace.equals(candidate.workspace()) || !candidate.fileSource().equals(values.get("fileSource"))) {
                moves.add(new Action("move", candidate.reference(), candidate.title(), candidate.workspace(), candidate.fileSource(),
                    workspace, AdminOperations.text(values.get("docId"))));
            } else if (!candidate.hash().equals(values.get("hash"))) {
                updates.add(new Action("update", candidate.reference(), candidate.title(), candidate.workspace(), candidate.fileSource(), "", ""));
            } else if (!candidate.version().equals(values.get("version"))) {
                refreshes.add(new Action("refresh", candidate.reference(), candidate.title(), candidate.workspace(), candidate.fileSource(), "", ""));
            }
        }
        var result = new ArrayList<Action>(); result.addAll(deletes); result.addAll(moves); result.addAll(updates); result.addAll(inserts); result.addAll(refreshes);
        return result;
    }

    static Map<String, Integer> counts(List<Action> actions) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String type : List.of("insert", "update", "move", "delete", "refresh")) { result.put(type, 0); }
        for (var action : actions) { result.merge(action.type(), 1, Integer::sum); }
        return result;
    }
    static Map<String, Object> row(Action action) {
        var row = new LinkedHashMap<String, Object>();
        row.put("type", action.type()); row.put("reference", action.reference()); row.put("title", action.title());
        row.put("workspace", action.workspace()); row.put("fileSource", action.fileSource()); row.put("oldWorkspace", action.oldWorkspace());
        return row;
    }

    // ---- administrator operations --------------------------------------------------------------

    Map<String, Object> overview() throws Exception {
        var snapshot = new Snapshot(); var scan = scan(snapshot);
        var actions = plan(scan.candidates(), snapshot.ledger);
        var result = new LinkedHashMap<String, Object>(); result.put("status", "ok");
        result.putAll(new RagSettings(policy, context).read());
        Map<String, Integer> articles = new HashMap<>();
        for (var candidate : scan.candidates().values()) { articles.merge(candidate.category(), 1, Integer::sum); }
        var categories = new ArrayList<Map<String, Object>>();
        for (var category : snapshot.categories) {
            String workspace = snapshot.workspaces.getOrDefault(category.reference(), "");
            if (workspace.isEmpty()) {
                for (int size = category.path().size() - 1; size >= 1 && workspace.isEmpty(); size--) {
                    var parent = snapshot.bySpaces.get(concat("Knowledge", category.path().subList(0, size)));
                    if (parent != null && parent.enabled()) { workspace = snapshot.workspaces.get(parent.reference()); }
                }
            }
            categories.add(Map.of("reference", category.reference(), "title", category.title(), "depth", category.path().size(),
                "enabled", category.enabled(), "explicit", category.explicit(), "workspace", workspace, "articles", articles.getOrDefault(category.reference(), 0)));
        }
        result.put("categories", categories);
        result.put("plan", Map.of("counts", counts(actions), "total", actions.size(), "items", actions.stream().limit(300).map(RagSync::row).toList()));
        result.put("skipped", scan.skipped()); result.put("excluded", scan.excluded());
        var owned = new ArrayList<Map<String, Object>>();
        for (var entry : ((Map<String, Map<String, Object>>) snapshot.state.get("workspaces")).entrySet()) {
            owned.add(Map.of("name", entry.getKey(), "category", AdminOperations.text(entry.getValue().get("category")),
                "createdAt", AdminOperations.text(entry.getValue().get("createdAt")), "documents", snapshot.ledger.getOrDefault(entry.getKey(), Map.of()).size(),
                "lost", AdminOperations.text(entry.getValue().get("lost"))));
        }
        result.put("workspaces", owned); result.put("runs", snapshot.state.get("runs")); result.put("failures", snapshot.state.get("failures"));
        var run = active();
        result.put("run", run == null ? Map.of() : progress(run));
        return result;
    }
    Set<String> categoryReferences() throws Exception {
        var result = new HashSet<String>(); for (var category : new Snapshot().categories) { result.add(category.reference()); } return result;
    }
    static List<String> concat(String first, List<String> rest) { var list = new ArrayList<String>(); list.add(first); list.addAll(rest); return list; }

    Map<String, Object> test() throws Exception { return test(null); }
    Map<String, Object> test(String input) throws Exception {
        WorkflowPolicyListener.require(input == null || input.length() <= 4000, "Connection settings are too large.");
        Map<?, ?> connection = input == null || input.isBlank() ? null : AdminOperations.JSON.readValue(input, Map.class);
        var snapshot = new Snapshot(connection); var result = new LinkedHashMap<String, Object>();
        String base = AdminOperations.text(snapshot.settings.get("baseUrl"));
        result.put("status", "ok"); result.put("baseUrl", base);
        WorkflowPolicyListener.require(!base.isEmpty(), "Enter the LightRAG address first.");
        var client = snapshot.client();
        try {
            var health = client.health(); result.put("reachable", health.ok()); result.put("httpStatus", health.status());
            for (String key : List.of("core_version", "api_version")) { if (health.body().get(key) != null) { result.put(key, AdminOperations.text(health.body().get(key))); } }
            var existing = new HashSet<>(client.workspaces()); var conflicts = new ArrayList<String>(); var missing = new ArrayList<String>();
            for (String workspace : new TreeSet<>(snapshot.workspaces.values())) { if (existing.contains(workspace) && !snapshot.owned.contains(workspace)) { conflicts.add(workspace); } }
            for (String workspace : snapshot.owned) { if (!existing.contains(workspace)) { missing.add(workspace); } }
            result.put("conflicts", conflicts); result.put("missing", missing);
        } catch (IOException | InterruptedException unreachable) { result.put("reachable", false); result.put("error", unreachable.getClass().getSimpleName()); }
        return result;
    }

    ReentrantLock lock() {
        var lock = LOCKS.computeIfAbsent(context.getWikiId(), ignored -> new ReentrantLock());
        WorkflowPolicyListener.require(lock.tryLock(), "Another LightRAG sync batch is running."); return lock;
    }
    Run active() {
        var run = RUNS.get(context.getWikiId());
        if (run != null && System.currentTimeMillis() - run.touched > 900000) { RUNS.remove(context.getWikiId(), run); return null; }
        return run;
    }

    Map<String, Object> preview() throws Exception {
        var lock = lock();
        try {
            var snapshot = new Snapshot();
            WorkflowPolicyListener.require(!AdminOperations.text(snapshot.settings.get("baseUrl")).isEmpty(), "Configure the LightRAG address first.");
            List<String> reappeared;
            try { reappeared = detect(snapshot, snapshot.client()); }
            catch (IOException | InterruptedException unreachable) { throw new SecurityException("LightRAG is not reachable (" + unreachable.getClass().getSimpleName() + ")."); }
            var actions = plan(scan(snapshot).candidates(), snapshot.ledger);
            long now = System.currentTimeMillis(); TICKETS.entrySet().removeIf(entry -> entry.getValue().expires() < now);
            WorkflowPolicyListener.require(TICKETS.size() < 32, "Too many pending previews.");
            String key = UUID.randomUUID().toString();
            TICKETS.put(key, new Ticket(context.getUserReference().toString(), context.getWikiId(), List.copyOf(actions), now + 300000));
            return Map.of("status", "ok", "ticket", key, "counts", counts(actions), "total", actions.size(), "expiresAt", now + 300000,
                "items", actions.stream().limit(300).map(RagSync::row).toList(), "lost", List.copyOf(snapshot.lost), "recreated", reappeared);
        } finally { lock.unlock(); }
    }

    Map<String, Object> start(String key) throws Exception {
        var lock = lock();
        try {
            var ticket = TICKETS.get(key == null ? "" : key);
            WorkflowPolicyListener.require(ticket != null && ticket.actor().equals(context.getUserReference().toString()) &&
                ticket.wiki().equals(context.getWikiId()) && ticket.expires() > System.currentTimeMillis(), "Preview expired or belongs to another administrator.");
            WorkflowPolicyListener.require(TICKETS.remove(key, ticket), "Preview was already used.");
            WorkflowPolicyListener.require(active() == null, "A LightRAG sync is already in progress.");
            List<Step> deletes = new ArrayList<>(), writes = new ArrayList<>();
            for (var action : ticket.actions()) {
                if (Set.of("delete", "move").contains(action.type())) { deletes.add(new Step("delete", action)); }
                if (!action.type().equals("delete")) { writes.add(new Step(action.type().equals("move") ? "insert" : action.type(), action)); }
            }
            var steps = new ArrayList<>(deletes); steps.addAll(writes);
            var run = new Run(ticket.actor(), ticket.wiki(), steps); RUNS.put(context.getWikiId(), run);
            LOG.info("LightRAG sync {} started by {} with {} steps", run.id, run.actor, steps.size());
            return advance(run);
        } finally { lock.unlock(); }
    }

    Map<String, Object> resume(String id) throws Exception {
        var lock = lock();
        try {
            var run = active();
            WorkflowPolicyListener.require(run != null && run.id.equals(id) && run.actor.equals(context.getUserReference().toString()), "This sync is no longer active.");
            return advance(run);
        } finally { lock.unlock(); }
    }

    static Map<String, Object> progress(Run run) {
        return Map.of("id", run.id, "processed", run.index, "total", run.steps.size(), "done", run.done(), "counts", new LinkedHashMap<>(run.counts),
            "failures", List.copyOf(run.failures.subList(Math.max(0, run.failures.size() - 50), run.failures.size())), "aborted", run.aborted, "startedAt", run.startedAt);
    }

    Map<String, Object> advance(Run run) throws Exception {
        var snapshot = new Snapshot(); var client = snapshot.client();
        long deadline = System.currentTimeMillis() + budgetMillis; int processed = 0;
        try {
            while (!run.done() && processed < batchSize && System.currentTimeMillis() < deadline) {
                var step = run.steps.get(run.index); String outcome;
                try { outcome = perform(step, snapshot, client, run); }
                catch (IOException | InterruptedException unreachable) { outcome = "failed:unreachable"; run.aborted = "unreachable"; }
                catch (SecurityException refused) { outcome = "failed:" + refused.getMessage(); }
                if (outcome.equals("busy")) {
                    if (++run.busy < maxBusyAttempts) {
                        if (System.currentTimeMillis() + busyPauseMillis >= deadline) { break; }
                        Thread.sleep(busyPauseMillis); continue;
                    }
                    outcome = "failed:busy";
                }
                if (outcome.startsWith("failed:")) {
                    run.counts.merge("failed", 1, Integer::sum);
                    run.failures.add(Map.of("reference", step.action().reference(), "step", step.kind(), "reason", outcome.substring(7), "at", Instant.now().toString()));
                    LOG.warn("LightRAG sync {} {} {} failed: {}", run.id, step.kind(), step.action().reference(), outcome.substring(7));
                } else if (!outcome.equals("pending")) { run.counts.merge(outcome, 1, Integer::sum); }
                run.index++; run.busy = 0; processed++;
            }
        } finally {
            persist(snapshot); run.touched = System.currentTimeMillis();
            if (run.done()) { finish(run, snapshot); }
        }
        var result = new LinkedHashMap<String, Object>(); result.put("status", "ok"); result.put("run", progress(run)); return result;
    }

    void finish(Run run, Snapshot snapshot) throws Exception {
        RUNS.remove(run.wiki, run);
        var state = state(); var runs = new ArrayList<Object>((List<?>) state.get("runs"));
        runs.add(0, Map.of("id", run.id, "actor", run.actor, "startedAt", run.startedAt, "finishedAt", Instant.now().toString(),
            "counts", run.counts, "total", run.steps.size(), "aborted", run.aborted));
        state.put("runs", runs.subList(0, Math.min(20, runs.size())));
        state.put("failures", run.failures.subList(0, Math.min(200, run.failures.size())));
        store(code("RagSyncState"), state, "Record LightRAG sync run");
        LOG.info("LightRAG sync {} finished: {}", run.id, run.counts);
    }

    String perform(Step step, Snapshot snapshot, LightRagClient client, Run run) throws Exception {
        var action = step.action();
        if (step.kind().equals("delete")) {
            var entries = snapshot.ledger.get(action.oldWorkspace());
            var entry = entries == null ? null : entries.get(action.reference());
            if (entry == null || !action.oldDocId().equals(entry.get("docId"))) { return action.type().equals("move") ? "pending" : "deleted"; }
            if (snapshot.lost.contains(action.oldWorkspace())) { return action.type().equals("move") ? "pending" : "deleted"; }
            var response = client.delete(action.oldWorkspace(), action.oldDocId());
            if (workspaceDeleted(response)) { markLost(snapshot, action.oldWorkspace()); return action.type().equals("move") ? "pending" : "deleted"; }
            String status = AdminOperations.text(response.body().get("status"));
            if (response.ok() && status.equals("busy")) { return "busy"; }
            if (!response.ok() || !status.equals("deletion_started")) { return "failed:delete " + response.status() + " " + status + " " + response.detail(); }
            entries.remove(action.reference()); snapshot.dirty.add(action.oldWorkspace());
            return action.type().equals("move") ? "pending" : "deleted";
        }
        var evaluation = evaluate(resolve(action.reference()), snapshot); var candidate = evaluation.candidate();
        if (candidate == null || !action.workspace().equals(candidate.workspace()) || !action.fileSource().equals(candidate.fileSource())) { return "skipped"; }
        String workspace = candidate.workspace();
        String ensured = ensureWorkspace(workspace, candidate.category(), snapshot, client, run);
        if (!ensured.isEmpty()) { return ensured; }
        var entries = snapshot.ledger.computeIfAbsent(workspace, ignored -> new TreeMap<>());
        var entry = entries.get(candidate.reference());
        if (step.kind().equals("refresh") && entry != null && candidate.hash().equals(entry.get("hash"))) {
            entry.put("version", candidate.version()); snapshot.dirty.add(workspace); return "refreshed";
        }
        LightRagClient.Response response;
        boolean replacing = entry != null;
        if (replacing) {
            response = client.update(workspace, candidate.docId(), candidate.text(), candidate.fileSource());
            if (response.status() == 404) { replacing = false; }
        } else { response = null; }
        if (!replacing) {
            response = client.insert(workspace, candidate.text(), candidate.fileSource());
            if (response.status() == 409 && response.detail().contains("already contains")) {
                // Same wiki-derived name in a wiki-owned workspace: an earlier batch inserted it before the ledger was saved.
                response = client.update(workspace, candidate.docId(), candidate.text(), candidate.fileSource());
            }
        }
        if (workspaceDeleted(response)) { markLost(snapshot, workspace); return "failed:workspace_deleted " + workspace; }
        if (response.status() == 409) { return "busy"; }
        if (!response.ok()) { return "failed:" + (replacing ? "update " : "insert ") + response.status() + " " + response.detail(); }
        var record = new LinkedHashMap<String, Object>();
        record.put("title", candidate.title()); record.put("fileSource", candidate.fileSource()); record.put("docId", candidate.docId());
        record.put("version", candidate.version()); record.put("hash", candidate.hash()); record.put("chars", candidate.text().length());
        record.put("trackId", AdminOperations.text(response.body().get("track_id"))); record.put("syncedAt", Instant.now().toString());
        entries.put(candidate.reference(), record); snapshot.dirty.add(workspace);
        return action.type().equals("move") ? "moved" : replacing || step.kind().equals("refresh") ? "updated" : "inserted";
    }

    /** Creates a workspace only when no workspace of that name exists; never writes into another platform's workspace. */
    String ensureWorkspace(String workspace, String category, Snapshot snapshot, LightRagClient client, Run run) throws Exception {
        if (run.blocked.contains(workspace)) { return "failed:workspace_conflict " + workspace; }
        if (snapshot.lost.contains(workspace)) {
            // Present again although the wiki has not recreated it: someone else did, so it may hold another platform's files.
            if (client.workspaces().contains(workspace)) { run.blocked.add(workspace); return "failed:workspace_conflict " + workspace; }
            var recreated = client.createWorkspace(workspace);
            if (!recreated.ok()) { return "failed:workspace " + recreated.status() + " " + recreated.detail(); }
            markWorkspace(snapshot, workspace, "lost", null); markWorkspace(snapshot, workspace, "recreatedAt", Instant.now().toString());
            snapshot.lost.remove(workspace); snapshot.ledger.put(workspace, new TreeMap<>()); snapshot.dirty.add(workspace);
            return "";
        }
        if (snapshot.owned.contains(workspace)) { return ""; }
        if (client.workspaces().contains(workspace)) { run.blocked.add(workspace); return "failed:workspace_conflict " + workspace; }
        var created = client.createWorkspace(workspace);
        if (!created.ok()) { return "failed:workspace " + created.status() + " " + created.detail(); }
        var state = state(); var owned = (Map<String, Object>) state.get("workspaces");
        owned.put(workspace, Map.of("category", category, "createdAt", Instant.now().toString(), "createdBy", run.actor));
        store(code("RagSyncState"), state, "Record wiki-owned LightRAG workspace");
        snapshot.owned.add(workspace); ((Map<String, Object>) snapshot.state.get("workspaces")).put(workspace, owned.get(workspace));
        return "";
    }
}
