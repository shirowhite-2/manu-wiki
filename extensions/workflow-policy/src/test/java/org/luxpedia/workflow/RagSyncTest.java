package org.luxpedia.workflow;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.xwiki.context.*;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.model.reference.*;
import org.xwiki.query.*;
import org.xwiki.rendering.block.*;
import org.xwiki.rendering.listener.reference.ResourceReference;
import org.xwiki.rendering.listener.reference.ResourceType;
import org.xwiki.security.authorization.*;
import com.xpn.xwiki.*;
import com.xpn.xwiki.doc.*;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.web.XWikiRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagSyncTest {
    /** In-memory LightRAG with a foreign platform's workspace that the wiki must never touch. */
    static final class FakeLightRag {
        final HttpServer server; final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Map<String, String>> documents = new TreeMap<>(); final Set<String> tombstones = new HashSet<>(); int busyDeletes; int track;
        /** Like DELETE /workspaces/{name}: data is removed and a tombstone makes document calls answer 404. */
        synchronized void dropWorkspace(String name) { documents.remove(name); tombstones.add(name); }
        FakeLightRag() throws Exception {
            documents.put("default", new TreeMap<>()); documents.put("imai_kb", new TreeMap<>(Map.of("contract.pdf", "foreign")));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.createContext("/", this::handle); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void reply(HttpExchange exchange, int status, Object body) throws java.io.IOException {
            byte[] bytes = AdminOperations.JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }
        synchronized void handle(HttpExchange exchange) throws java.io.IOException {
            String path = exchange.getRequestURI().getPath(), query = Objects.toString(exchange.getRequestURI().getQuery(), "");
            Map<String, Object> body = new HashMap<>(); byte[] raw = exchange.getRequestBody().readAllBytes();
            if (raw.length > 0) { body.putAll(AdminOperations.JSON.readValue(raw, Map.class)); }
            String workspace = body.containsKey("workspace") ? (String) body.get("workspace") : query.startsWith("workspace=") ? query.substring(10) : "";
            calls.add(exchange.getRequestMethod() + " " + path + " " + workspace);
            if (path.startsWith("/documents/") && tombstones.contains(workspace)) {
                reply(exchange, 404, Map.of("detail", "Workspace '" + workspace + "' has been deleted")); return;
            }
            if (exchange.getRequestMethod().equals("POST") && path.equals("/workspaces")) { tombstones.remove(workspace); }
            switch (exchange.getRequestMethod() + " " + path) {
                case "GET /health" -> reply(exchange, 200, Map.of("status", "healthy", "core_version", "1.5.0"));
                case "GET /workspaces" -> reply(exchange, 200, Map.of("workspaces", List.copyOf(documents.keySet()), "default_workspace", "default"));
                case "POST /workspaces" -> { documents.putIfAbsent(workspace, new TreeMap<>()); reply(exchange, 200, Map.of("workspaces", List.copyOf(documents.keySet()))); }
                case "POST /documents/text" -> {
                    var docs = documents.get(workspace); String source = (String) body.get("file_source");
                    if (docs.containsKey(source)) { reply(exchange, 409, Map.of("detail", "Document storage already contains '" + source + "' (Status: processed).")); return; }
                    docs.put(source, (String) body.get("text")); reply(exchange, 200, Map.of("status", "success", "message", "ok", "track_id", "insert_" + (++track)));
                }
                case "POST /documents/update_document" -> {
                    var docs = documents.get(workspace); String source = (String) body.get("file_source");
                    if (docs.keySet().stream().noneMatch(name -> RagSync.docId(name).equals(body.get("doc_id")))) { reply(exchange, 404, Map.of("detail", "Document not found")); return; }
                    docs.put(source, (String) body.get("content")); reply(exchange, 200, Map.of("status", "success", "message", "ok", "track_id", "update_" + (++track)));
                }
                case "DELETE /documents/delete_document" -> {
                    if (busyDeletes > 0) { busyDeletes--; reply(exchange, 200, Map.of("status", "busy", "message", "busy", "doc_id", "")); return; }
                    String id = (String) ((List<?>) body.get("doc_ids")).get(0);
                    documents.get(workspace).keySet().removeIf(name -> RagSync.docId(name).equals(id));
                    reply(exchange, 200, Map.of("status", "deletion_started", "message", "ok", "doc_id", id));
                }
                default -> reply(exchange, 404, Map.of("detail", "Not found"));
            }
        }
    }

    WorkflowPolicyListenerTest.Subject policy; XWikiContext context; XWiki wiki; XWikiRequest request;
    WorkflowPolicyScriptService service; FakeLightRag rag; DraftWriteBoundary.Scope boundary;
    DocumentReference admin = new DocumentReference("xwiki", "XWiki", "Admin");
    Map<DocumentReference, XWikiDocument> store = new HashMap<>();
    List<String> categories = new ArrayList<>(), articles = new ArrayList<>();

    DocumentReference ref(String name) { return policy.resolver.resolve(name, new WikiReference("xwiki")); }
    BaseObject object(Map<String, String> fields) throws Exception {
        BaseObject object = spy(new BaseObject()); fields.forEach(object::setStringValue);
        doAnswer(call -> { object.setStringValue(call.getArgument(0), call.getArgument(1)); return null; }).when(object).set(anyString(), anyString(), any(XWikiContext.class));
        doAnswer(call -> { object.setIntValue(call.getArgument(0), call.getArgument(1)); return null; }).when(object).set(anyString(), anyInt(), any(XWikiContext.class));
        return object;
    }
    XWikiDocument document(String name, boolean fresh, Map<String, BaseObject> objects) throws Exception {
        var doc = mock(XWikiDocument.class); var reference = ref(name);
        when(doc.getDocumentReference()).thenReturn(reference); when(doc.isNew()).thenReturn(fresh); when(doc.isHidden()).thenReturn(false);
        when(doc.getVersion()).thenReturn("1.1"); when(doc.getTitle()).thenReturn(reference.getName()); when(doc.getContent()).thenReturn("");
        when(doc.getObject(anyString())).thenAnswer(call -> objects.get(call.getArgument(0)));
        when(doc.newXObject(any(DocumentReference.class), eq(context))).thenAnswer(call -> {
            BaseObject value = object(Map.of()); objects.put(policy.localSerializer.serialize(call.getArgument(0)), value); return value;
        });
        when(doc.getExternalURL("view", context)).thenReturn("http://wiki.test/bin/view/" + name.replace('.', '/'));
        when(doc.clone()).thenReturn(doc);
        return doc;
    }
    XWikiDocument article(String name, String title, String content) throws Exception {
        var flow = object(Map.of("status", "published")); flow.setIntValue("istarget", 1);
        var doc = document(name, false, new HashMap<>(Map.of(WorkflowPolicyListener.WF, flow,
            RagSync.ARTICLE, object(Map.of("tagsText", "demo", "summary", "Synthetic summary")))));
        when(doc.getTitle()).thenReturn(title); when(doc.getContent()).thenReturn(content);
        store.put(ref(name), doc); articles.add(name); return doc;
    }
    void category(String name, String title) throws Exception {
        var doc = document(name, false, new HashMap<>(Map.of(WorkflowPolicyListener.CATEGORY, object(Map.of()))));
        when(doc.getTitle()).thenReturn(title); store.put(ref(name), doc); categories.add(name);
    }
    String payload(Object value) throws Exception { return AdminOperations.JSON.writeValueAsString(value); }
    void configure(Map<String, Object> overrides) throws Exception {
        var settings = new LinkedHashMap<String, Object>(Map.of("baseUrl", rag.url(), "prefix", "wiki", "maxChars", 200000, "timeoutSeconds", 5));
        settings.putAll(overrides);
        var original = context.getWiki().getDocument(new RagSettings(policy, context).reference(), context);
        var result = new RagSettings(policy, context).save(payload(Map.of("version", original.isNew() ? "" : original.getVersion(), "settings", settings)),
            new RagSync(policy, context).categoryReferences());
        assertEquals("ok", result.get("status"));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> sync() throws Exception {
        var preview = new RagSync(policy, context).preview(); assertEquals("ok", preview.get("status"));
        var result = new RagSync(policy, context).start((String) preview.get("ticket"));
        var run = (Map<String, Object>) result.get("run");
        while (!Boolean.TRUE.equals(run.get("done"))) { run = (Map<String, Object>) new RagSync(policy, context).resume((String) run.get("id")).get("run"); }
        return run;
    }
    @SuppressWarnings("unchecked")
    Map<String, Integer> planned() throws Exception { return (Map<String, Integer>) ((Map<String, Object>) new RagSync(policy, context).overview().get("plan")).get("counts"); }

    @BeforeEach void setup() throws Exception {
        boundary = DraftWriteBoundary.open(); RagSync.TICKETS.clear(); RagSync.RUNS.clear(); RagSync.busyPauseMillis = 1;
        rag = new FakeLightRag();
        policy = new WorkflowPolicyListenerTest.Subject(); policy.resolver = new WorkflowPolicyListenerTest().realResolver();
        policy.localSerializer = mock(EntityReferenceSerializer.class);
        when(policy.localSerializer.serialize(any())).thenAnswer(call -> call.getArgument(0).toString().replaceFirst("^xwiki:", ""));
        policy.authorization = mock(AuthorizationManager.class); when(policy.authorization.hasAccess(any(), any(), any())).thenReturn(true);
        policy.queries = mock(QueryManager.class);
        when(policy.queries.createQuery(anyString(), anyString())).thenAnswer(call -> {
            String statement = call.getArgument(0); Query query = mock(Query.class, RETURNS_SELF);
            when(query.execute()).thenAnswer(ignored -> statement.contains("CategoryClass") ? List.copyOf(categories) :
                statement.contains("PublicationWorkflowClass") ? List.copyOf(articles) : List.of());
            return query;
        });
        context = mock(XWikiContext.class); wiki = mock(XWiki.class); request = mock(XWikiRequest.class);
        when(context.getUserReference()).thenReturn(admin); when(context.getWikiId()).thenReturn("xwiki"); when(context.getWiki()).thenReturn(wiki);
        when(context.getRequest()).thenReturn(request); when(request.getMethod()).thenReturn("POST");
        when(wiki.getDocument(any(DocumentReference.class), eq(context))).thenAnswer(call -> {
            DocumentReference reference = call.getArgument(0);
            if (!store.containsKey(reference)) { store.put(reference, document(policy.localSerializer.serialize(reference), true, new HashMap<>())); }
            return store.get(reference);
        });
        doAnswer(call -> {
            XWikiDocument doc = call.getArgument(0); String next = (Integer.parseInt(doc.getVersion().split("\\.")[0]) + 1) + ".1";
            when(doc.isNew()).thenReturn(false); when(doc.getVersion()).thenReturn(next); store.put(doc.getDocumentReference(), doc); return null;
        }).when(wiki).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), eq(context));
        service = new WorkflowPolicyScriptService(); service.listener = policy; service.authorization = policy.authorization;
        service.csrf = mock(CSRFToken.class); when(service.csrf.isTokenValid("valid")).thenReturn(true); when(service.csrf.getToken()).thenReturn("token");
        service.execution = mock(Execution.class);
        var execution = new ExecutionContext(); execution.setProperty("xwikicontext", context); when(service.execution.getContext()).thenReturn(execution);

        category("Knowledge.Technical.WebHome", "Technical");
        category("Knowledge.Technical.Hydraulics.WebHome", "Hydraulics");
        category("Knowledge.Archive.WebHome", "Archive");
        article("Knowledge.Technical.Overview", "Overview", "= Overview =\n\nPlain **technical** text.\n\n[[image:diagram.png]]\n[[Manual>>attach:manual.pdf]]");
        article("Knowledge.Technical.Hydraulics.Pumps", "Pumps", "Pump notes. {{embed reference=\"video.mp4\"/}}");
        article("Knowledge.Archive.Old", "Old", "Archived text.");
        article("Knowledge.Loose", "Loose", "Not in a category.");
    }
    @AfterEach void finish() { rag.server.stop(0); boundary.close(); RagSync.TICKETS.clear(); RagSync.RUNS.clear(); RagSync.LOCKS.clear(); }

    @Test void onlyCategorizedPublishedKnowledgeTextReachesAWikiOwnedWorkspace() throws Exception {
        configure(Map.of());
        assertEquals(Map.of("insert", 2, "update", 0, "move", 0, "delete", 0, "refresh", 0), planned());
        var run = sync();
        assertEquals(2, ((Map<?, ?>) run.get("counts")).get("inserted"));
        var docs = rag.documents.get("wiki_Technical");
        assertEquals(2, docs.size(), docs.toString());
        assertTrue(docs.keySet().stream().anyMatch(name -> name.startsWith("Technical__Hydraulics__Pumps-") && name.endsWith(".md")));
        String overview = docs.entrySet().stream().filter(e -> e.getKey().startsWith("Technical__Overview-")).findFirst().orElseThrow().getValue();
        assertTrue(overview.contains("Category path: Technical") && overview.contains("Plain technical text."), overview);
        assertFalse(overview.contains("diagram.png") || overview.contains("manual.pdf") || overview.contains("attach:"), overview);
        assertFalse(docs.values().stream().anyMatch(text -> text.contains("video.mp4")));
        String pumps = docs.entrySet().stream().filter(e -> e.getKey().startsWith("Technical__Hydraulics")).findFirst().orElseThrow().getValue();
        assertTrue(pumps.contains("Category path: Technical / Hydraulics"), pumps);
        assertEquals(Map.of("contract.pdf", "foreign"), rag.documents.get("imai_kb"));
        assertTrue(rag.calls.stream().noneMatch(call -> call.contains("imai_kb") || call.contains("paginated") || call.contains("status_counts")), rag.calls.toString());
        assertEquals(Map.of("insert", 0, "update", 0, "move", 0, "delete", 0, "refresh", 0), planned());
    }

    @Test void changesSinceTheLastRunAreUpdatedRefreshedOrDeletedByRecordedIdsOnly() throws Exception {
        configure(Map.of()); sync(); rag.calls.clear();
        var overview = store.get(ref("Knowledge.Technical.Overview"));
        when(overview.getContent()).thenReturn("Revised technical text."); when(overview.getVersion()).thenReturn("2.1");
        when(store.get(ref("Knowledge.Technical.Hydraulics.Pumps")).getVersion()).thenReturn("3.1");
        assertEquals(Map.of("insert", 0, "update", 1, "move", 0, "delete", 0, "refresh", 1), planned());
        var run = sync();
        assertEquals(1, ((Map<?, ?>) run.get("counts")).get("updated")); assertEquals(1, ((Map<?, ?>) run.get("counts")).get("refreshed"));
        assertEquals(1, rag.calls.stream().filter(call -> call.startsWith("POST /documents/update_document wiki_Technical")).count(), rag.calls.toString());
        assertTrue(rag.documents.get("wiki_Technical").values().stream().anyMatch(text -> text.contains("Revised technical text.")));

        rag.calls.clear(); rag.busyDeletes = 2;
        when(store.get(ref("Knowledge.Technical.Overview")).isNew()).thenReturn(true);
        assertEquals(1, planned().get("delete"));
        run = sync();
        assertEquals(1, ((Map<?, ?>) run.get("counts")).get("deleted"));
        assertEquals(3, rag.calls.stream().filter(call -> call.startsWith("DELETE /documents/delete_document wiki_Technical")).count());
        assertEquals(1, rag.documents.get("wiki_Technical").size());
        assertEquals(Map.of("contract.pdf", "foreign"), rag.documents.get("imai_kb"));
    }

    @Test void enablingASubcategoryMovesItsArticlesIntoTheirOwnWorkspace() throws Exception {
        configure(Map.of()); sync();
        configure(Map.of("categories", Map.of("Knowledge.Technical.Hydraulics.WebHome", true)));
        assertEquals(1, planned().get("move"));
        var run = sync();
        assertEquals(1, ((Map<?, ?>) run.get("counts")).get("moved"));
        assertEquals(1, rag.documents.get("wiki_Technical").size()); assertEquals(1, rag.documents.get("wiki_Technical_Hydraulics").size());
    }

    @Test void anExistingWorkspaceTheWikiDidNotCreateIsNeverWritten() throws Exception {
        rag.documents.put("wiki_Technical", new TreeMap<>(Map.of("other.md", "another platform")));
        configure(Map.of());
        var run = sync();
        assertEquals(2, ((Map<?, ?>) run.get("counts")).get("failed"));
        assertTrue(run.get("failures").toString().contains("workspace_conflict"));
        assertTrue(rag.calls.stream().noneMatch(call -> call.contains("/documents/") || call.startsWith("POST /workspaces")), rag.calls.toString());
        assertEquals(Map.of("other.md", "another platform"), rag.documents.get("wiki_Technical"));
        assertEquals(List.of("wiki_Technical"), new RagSync(policy, context).test().get("conflicts"));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> preview() throws Exception { return new RagSync(policy, context).preview(); }
    @SuppressWarnings("unchecked")
    Map<String, Object> workspaceState(String name) throws Exception {
        return (Map<String, Object>) ((Map<String, Object>) new RagSync(policy, context).state().get("workspaces")).get(name);
    }

    @Test void aWorkspaceDeletedInLightRagIsDetectedAtPreviewAndSentAgain() throws Exception {
        configure(Map.of()); sync();
        rag.dropWorkspace("wiki_Technical"); rag.calls.clear();
        assertEquals(0, planned().get("insert"));
        var preview = preview();
        assertEquals(List.of("wiki_Technical"), preview.get("lost")); assertEquals(List.of(), preview.get("recreated"));
        assertEquals(2, ((Map<?, ?>) preview.get("counts")).get("insert")); assertEquals(0, ((Map<?, ?>) preview.get("counts")).get("delete"));
        assertFalse(AdminOperations.text(workspaceState("wiki_Technical").get("lost")).isEmpty());
        var run = sync();
        assertEquals(2, ((Map<?, ?>) run.get("counts")).get("inserted")); assertEquals(0, ((Map<?, ?>) run.get("counts")).get("failed"));
        assertEquals(2, rag.documents.get("wiki_Technical").size());
        assertTrue(AdminOperations.text(workspaceState("wiki_Technical").get("lost")).isEmpty());
        assertFalse(AdminOperations.text(workspaceState("wiki_Technical").get("recreatedAt")).isEmpty());
        assertEquals(Map.of("insert", 0, "update", 0, "move", 0, "delete", 0, "refresh", 0), planned());
        assertEquals(List.of(), preview().get("lost"));
        assertEquals(Map.of("contract.pdf", "foreign"), rag.documents.get("imai_kb"));
    }

    @Test void aWorkspaceDeletedDuringASyncFailsClearlyAndIsSentAgainNextTime() throws Exception {
        configure(Map.of()); sync();
        when(store.get(ref("Knowledge.Technical.Overview")).getContent()).thenReturn("Revised technical text.");
        String ticket = (String) preview().get("ticket");
        rag.dropWorkspace("wiki_Technical");
        var run = (Map<String, Object>) new RagSync(policy, context).start(ticket).get("run");
        while (!Boolean.TRUE.equals(run.get("done"))) { run = (Map<String, Object>) new RagSync(policy, context).resume((String) run.get("id")).get("run"); }
        assertEquals(1, ((Map<?, ?>) run.get("counts")).get("failed"));
        assertTrue(run.get("failures").toString().contains("workspace_deleted wiki_Technical"), run.toString());
        assertEquals(2, ((Map<?, ?>) preview().get("counts")).get("insert"));
        sync();
        assertTrue(rag.documents.get("wiki_Technical").values().stream().anyMatch(text -> text.contains("Revised technical text.")));
    }

    @Test void aDeletedWorkspaceRecreatedBySomeoneElseIsNeverWritten() throws Exception {
        configure(Map.of()); sync();
        rag.dropWorkspace("wiki_Technical"); preview();
        rag.tombstones.remove("wiki_Technical"); rag.documents.put("wiki_Technical", new TreeMap<>(Map.of("other.md", "another platform")));
        rag.calls.clear();
        assertEquals(List.of("wiki_Technical"), preview().get("recreated"));
        var run = sync();
        assertEquals(2, ((Map<?, ?>) run.get("counts")).get("failed"));
        assertTrue(run.get("failures").toString().contains("workspace_conflict wiki_Technical"));
        assertTrue(rag.calls.stream().noneMatch(call -> call.contains("/documents/") || call.startsWith("POST /workspaces")), rag.calls.toString());
        assertEquals(Map.of("other.md", "another platform"), rag.documents.get("wiki_Technical"));
    }

    @Test void connectionTestUsesTheTypedAddressWithoutSavingIt() throws Exception {
        var refused = service.adminWrite("rag-test", "", "valid");
        assertEquals("refused", refused.get("status")); assertEquals("Enter the LightRAG address first.", refused.get("reason"));
        var typed = service.adminWrite("rag-test", AdminOperations.JSON.writeValueAsString(Map.of("baseUrl", rag.url() + "/", "prefix", "wiki")), "valid");
        assertEquals(true, typed.get("reachable")); assertEquals(rag.url(), typed.get("baseUrl")); assertEquals("1.5.0", typed.get("core_version"));
        assertEquals("", new RagSettings(policy, context).stored().get("baseUrl"));
        var closed = new java.net.ServerSocket(0); int port = closed.getLocalPort(); closed.close();
        var down = service.adminWrite("rag-test", AdminOperations.JSON.writeValueAsString(Map.of("baseUrl", "http://127.0.0.1:" + port, "prefix", "wiki")), "valid");
        assertEquals(false, down.get("reachable")); assertFalse(AdminOperations.text(down.get("error")).isEmpty());
        assertEquals("refused", service.adminWrite("rag-test", "{\"baseUrl\":\"ftp://x\",\"prefix\":\"wiki\"}", "valid").get("status"));
    }

    @Test void clientRefusesDocumentCallsOutsideOwnedWorkspacesBeforeAnyRequest() throws Exception {
        var client = new LightRagClient(rag.url(), "", 5, Set.of("wiki_Technical"));
        assertThrows(SecurityException.class, () -> client.insert("imai_kb", "text", "a.md"));
        assertThrows(SecurityException.class, () -> client.delete("imai_kb", "doc-1"));
        assertThrows(SecurityException.class, () -> client.update("default", "doc-1", "text", "a.md"));
        assertTrue(rag.calls.isEmpty());
    }

    @Test void previewTicketsAreActorBoundAndSingleUse() throws Exception {
        configure(Map.of());
        String ticket = (String) new RagSync(policy, context).preview().get("ticket");
        when(context.getUserReference()).thenReturn(ref("XWiki.OtherAdmin"));
        assertThrows(SecurityException.class, () -> new RagSync(policy, context).start(ticket));
        when(context.getUserReference()).thenReturn(admin);
        assertEquals("ok", new RagSync(policy, context).start(ticket).get("status"));
        assertThrows(SecurityException.class, () -> new RagSync(policy, context).start(ticket));
    }

    @Test void endpointsRequireAdministratorPostAndCsrfAndReportRefusals() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        assertEquals("forbidden", service.adminRead("rag-overview", "", "", "1", "30").get("status"));
        assertEquals("forbidden", service.adminWrite("rag-preview", "", "valid").get("status"));
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        when(request.getMethod()).thenReturn("GET"); assertEquals("post_required", service.adminWrite("rag-run", "x", "valid").get("status"));
        when(request.getMethod()).thenReturn("POST"); assertEquals("bad_token", service.adminWrite("rag-run", "x", "invalid").get("status"));
        var refused = service.adminWrite("rag-preview", "", "valid");
        assertEquals("refused", refused.get("status")); assertEquals("Configure the LightRAG address first.", refused.get("reason"));
        assertTrue(rag.calls.isEmpty());
        assertEquals("ok", service.adminRead("rag-overview", "", "", "1", "30").get("status"));
    }

    @Test void settingsRejectUnsafeAddressesAndNeverReturnTheApiKey() throws Exception {
        for (String url : List.of("javascript:alert(1)", "http://user:pass@lightrag:9621", "ftp://lightrag", "http://lightrag:9621/?x=1")) {
            assertThrows(SecurityException.class, () -> configure(Map.of("baseUrl", url)));
        }
        assertThrows(SecurityException.class, () -> configure(Map.of("prefix", "1bad")));
        assertThrows(SecurityException.class, () -> configure(Map.of("categories", Map.of("Drafts.WebHome", true))));
        configure(Map.of("apiKey", "secret-key"));
        var read = new RagSettings(policy, context).read();
        assertFalse(read.toString().contains("secret-key")); assertEquals(true, ((Map<?, ?>) read.get("settings")).get("apiKeySet"));
        configure(Map.of("apiKey", ""));
        assertEquals("secret-key", new RagSettings(policy, context).stored().get("apiKey"));
        configure(Map.of("clearApiKey", true));
        assertEquals("", new RagSettings(policy, context).stored().get("apiKey"));
    }

    @Test void planDeletesDuplicateCopiesAndSkipsUnchangedPages() {
        var candidate = new RagSync.Candidate("Knowledge.T.A", "A", "Knowledge.T.WebHome", "wiki_T", "T__A-1.md", RagSync.docId("T__A-1.md"), "text", "h1", "2.1");
        Map<String, Map<String, Map<String, Object>>> ledger = new TreeMap<>();
        ledger.put("wiki_T", new TreeMap<>(Map.of("Knowledge.T.A", new HashMap<>(Map.of("fileSource", "T__A-1.md", "hash", "h1", "version", "2.1", "docId", "d1")))));
        ledger.put("wiki_Old", new TreeMap<>(Map.of("Knowledge.T.A", new HashMap<>(Map.of("fileSource", "T__A-1.md", "hash", "h0", "version", "1.1", "docId", "d0")))));
        var actions = RagSync.plan(Map.of(candidate.reference(), candidate), ledger);
        assertEquals(1, actions.size()); assertEquals("delete", actions.get(0).type()); assertEquals("wiki_Old", actions.get(0).oldWorkspace());
        assertEquals("d0", actions.get(0).oldDocId());
    }

    @Test void extractorKeepsProseAndDropsImagesAttachmentsMacrosAndBase64() {
        var xdom = new XDOM(List.of(
            new HeaderBlock(List.of(new WordBlock("Title")), org.xwiki.rendering.listener.HeaderLevel.LEVEL1),
            new ParagraphBlock(List.of(new WordBlock("Kept"), new SpaceBlock(), new WordBlock("prose"),
                new ImageBlock(new ResourceReference("photo.png", ResourceType.ATTACHMENT), false),
                new LinkBlock(List.of(new WordBlock("manual.pdf")), new ResourceReference("manual.pdf", ResourceType.ATTACHMENT), false),
                new SpaceBlock(), new LinkBlock(List.of(new WordBlock("Related")), new ResourceReference("Knowledge.B", ResourceType.DOCUMENT), false))),
            new MacroBlock("velocity", Map.of(), "$secret", false),
            new MacroBlock("html", Map.of(), "<img src='x'>", false),
            new MacroBlock("info", Map.of(), "Boxed [[image:inline.png]] note", false),
            new ParagraphBlock(List.of(new WordBlock("data:image/png;base64," + "A".repeat(300))))));
        String text = RagTextExtractor.text(xdom);
        assertTrue(text.startsWith("# Title"), text);
        assertTrue(text.contains("Kept prose") && text.contains("Related") && text.contains("Boxed  note"), text);
        for (String forbidden : List.of("photo.png", "manual.pdf", "$secret", "<img", "inline.png", "AAAA")) { assertFalse(text.contains(forbidden), text); }
        String cleaned = RagTextExtractor.clean("Intro **bold** [[label>>Knowledge.X]] [[image:a.png]] [[f>>attach:b.pdf]]\n{{html}}<b>x</b>{{/html}}{{embed reference=\"v.mp4\"/}}\n|=Head|=Two\n|a|b");
        assertEquals("Intro bold label\n\n| Head| Two\n|a|b", cleaned);
    }
}
