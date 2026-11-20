package org.luxpedia.workflow;

import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.xwiki.context.*;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.model.reference.*;
import org.xwiki.query.*;
import org.xwiki.security.authorization.*;
import com.xpn.xwiki.*;
import com.xpn.xwiki.doc.*;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.web.XWikiRequest;
import com.xpn.xwiki.store.XWikiRecycleBinStoreInterface;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminToolsTest {
    WorkflowPolicyListenerTest.Subject policy;
    WorkflowPolicyScriptService service;
    XWikiContext context; com.xpn.xwiki.XWiki wiki; XWikiRequest request;
    DocumentReference user = new DocumentReference("xwiki", "XWiki", "Admin");
    Map<DocumentReference, XWikiDocument> store = new HashMap<>();
    DraftWriteBoundary.Scope boundary;
    AdminOperations operations; NavigationSettings navigation;
    Function<String, List<?>> queries = statement -> statement.startsWith("select count") ? List.of(0L) : List.of();
    DocumentReference ref(String value) { return policy.resolver.resolve(value, new WikiReference("xwiki")); }
    BaseObject object(Map<String, String> fields) throws Exception {
        BaseObject object = spy(new BaseObject()); fields.forEach(object::setStringValue);
        doAnswer(call -> { object.setStringValue(call.getArgument(0), call.getArgument(1)); return null; }).when(object).set(anyString(), anyString(), any(XWikiContext.class));
        doAnswer(call -> { object.setIntValue(call.getArgument(0), call.getArgument(1)); return null; }).when(object).set(anyString(), anyInt(), any(XWikiContext.class));
        return object;
    }
    XWikiDocument document(String name, boolean fresh, Map<String, BaseObject> objects) throws Exception {
        var doc = mock(XWikiDocument.class); var reference = ref(name);
        when(doc.getDocumentReference()).thenReturn(reference); when(doc.isNew()).thenReturn(fresh); when(doc.getVersion()).thenReturn("2.1");
        when(doc.getTitle()).thenReturn(name); when(doc.getContent()).thenReturn("Saved content");
        when(doc.getAttachmentList()).thenReturn(List.of()); when(doc.getTranslationList(context)).thenReturn(List.of());
        when(doc.getObject(anyString())).thenAnswer(call -> objects.get(call.getArgument(0)));
        when(doc.newXObject(any(DocumentReference.class), eq(context))).thenAnswer(call -> {
            BaseObject value = object(Map.of()); objects.put(policy.localSerializer.serialize(call.getArgument(0)), value); return value;
        });
        doAnswer(call -> { objects.remove(call.getArgument(0)); return null; }).when(doc).removeObjects(anyString());
        doAnswer(call -> { when(doc.getOriginalDocument()).thenReturn(call.getArgument(0)); return null; }).when(doc).setOriginalDocument(any(XWikiDocument.class));
        when(doc.clone()).thenAnswer(call -> {
            var copy = new HashMap<String, BaseObject>();
            for (var entry : objects.entrySet()) {
                var values = new HashMap<String, String>();
                for (String field : List.of("payload", "owner", "generation", "acquiredAt", "expiresAt", "shared", "status", "target", "workflow")) { values.put(field, entry.getValue().getStringValue(field)); }
                copy.put(entry.getKey(), object(values));
            }
            var clone = document(name, fresh, copy); when(clone.getOriginalDocument()).thenReturn(doc); return clone;
        });
        when(doc.getTranslatedDocument(any(Locale.class), eq(context))).thenReturn(doc);
        when(doc.getOriginalDocument()).thenReturn(doc); return doc;
    }
    @BeforeEach void setup() throws Exception {
        boundary = DraftWriteBoundary.open(); AdminOperations.TICKETS.clear();
        policy = new WorkflowPolicyListenerTest.Subject(); policy.resolver = new WorkflowPolicyListenerTest().realResolver();
        policy.localSerializer = mock(EntityReferenceSerializer.class);
        when(policy.localSerializer.serialize(any())).thenAnswer(call -> call.getArgument(0).toString().replaceFirst("^xwiki:", ""));
        policy.authorization = mock(AuthorizationManager.class); policy.queries = mock(QueryManager.class);
        when(policy.authorization.hasAccess(any(), any(), any())).thenReturn(true);
        policy.roleGroups = Set.of("xwiki:XWiki.LuxEditors");
        when(policy.queries.createQuery(anyString(), anyString())).thenAnswer(call -> {
            String statement = call.getArgument(0); Query query = mock(Query.class, RETURNS_SELF);
            when(query.execute()).thenAnswer(ignored -> queries.apply(statement)); return query;
        });
        context = mock(XWikiContext.class); wiki = mock(com.xpn.xwiki.XWiki.class); request = mock(XWikiRequest.class);
        when(context.getUserReference()).thenReturn(user); when(context.getWikiId()).thenReturn("xwiki"); when(context.getWiki()).thenReturn(wiki); when(context.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn("POST"); when(wiki.hasRecycleBin(context)).thenReturn(true); when(wiki.getVersion()).thenReturn("17.10.15");
        when(wiki.getDocument(any(DocumentReference.class), eq(context))).thenAnswer(call -> {
            var reference = (DocumentReference) call.getArgument(0);
            if (!store.containsKey(reference)) { store.put(reference, document(policy.localSerializer.serialize(reference), true, new HashMap<>())); }
            return store.get(reference);
        });
        when(wiki.getURL(any(DocumentReference.class), eq("view"), eq(context))).thenAnswer(call -> "/bin/view/" + policy.localSerializer.serialize(call.getArgument(0)).replace('.', '/'));
        doAnswer(call -> { var doc = (XWikiDocument) call.getArgument(0); store.put(doc.getDocumentReference(), doc); when(doc.isNew()).thenReturn(false); when(doc.getVersion()).thenReturn("3.1"); return null; })
            .when(wiki).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), eq(context));
        doAnswer(call -> { var doc = (XWikiDocument) call.getArgument(0); store.put(doc.getDocumentReference(), document(policy.localSerializer.serialize(doc.getDocumentReference()), true, new HashMap<>())); return null; })
            .when(wiki).deleteAllDocuments(any(XWikiDocument.class), eq(true), eq(context));
        service = new WorkflowPolicyScriptService(); service.listener = policy; service.authorization = policy.authorization; service.csrf = mock(CSRFToken.class);
        when(service.csrf.isTokenValid("valid")).thenReturn(true); service.execution = mock(Execution.class);
        var execution = new ExecutionContext(); execution.setProperty("xwikicontext", context); when(service.execution.getContext()).thenReturn(execution);
        operations = new AdminOperations(policy, context); navigation = new NavigationSettings(policy, context);
        store.put(ref("Knowledge.Article"), document("Knowledge.Article", false, new HashMap<>()));
        store.put(ref("Knowledge.WebHome"), document("Knowledge.WebHome", false, new HashMap<>()));
    }
    @AfterEach void finish() { boundary.close(); AdminOperations.TICKETS.clear(); EditLease.SAVING.remove(); }
    String payload(Object value) throws Exception { return AdminOperations.JSON.writeValueAsString(value); }
    Map<String, Object> menu(String id, String parent, String destination, List<String> roles) {
        return new LinkedHashMap<>(Map.of("id", id, "parent", parent, "en", id, "zh", "中文", "destination", destination, "location", "sidebar", "enabled", true, "roles", roles));
    }
    Map<String, Object> settings(List<?> menus, List<?> banners) { return new LinkedHashMap<>(Map.of("menus", menus, "banners", banners, "qrEnabled", true)); }
    void configured(Map<String, Object> settings) throws Exception {
        store.put(navigation.settingsReference(), document("LuxPedia.Code.NavigationSettings", false,
            new HashMap<>(Map.of(NavigationSettings.CLASS, object(Map.of("payload", payload(settings)))))));
    }
    XWikiDeletedDocument deleted(long id, String name) throws Exception {
        var record = mock(XWikiDeletedDocument.class); when(record.getId()).thenReturn(id); when(record.getFullName()).thenReturn(name); when(record.getLanguage()).thenReturn("");
        when(wiki.getDeletedDocument(id, context)).thenReturn(record); return record;
    }
    @Test void allAdministrationEndpointsCheckActualAdminAndPostCsrfBeforeAnyData() {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        assertEquals("forbidden", service.adminRead("dashboard", "", "", "1", "30").get("status"));
        assertEquals("forbidden", service.adminWrite("preview-delete", "[]", "valid").get("status"));
        verifyNoInteractions(wiki, policy.queries);
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        when(request.getMethod()).thenReturn("GET"); assertEquals("post_required", service.adminWrite("execute", "bad", "valid").get("status"));
        when(request.getMethod()).thenReturn("POST"); assertEquals("bad_token", service.adminWrite("execute", "bad", "invalid").get("status"));
        verifyNoInteractions(wiki, policy.queries);
    }
    @Test void removalNeedsPreviewAndOneTimeActorBoundConfirmationAndUsesNativeRecycleBin() throws Exception {
        var preview = operations.preview("delete", payload(List.of("Knowledge.Article")));
        verify(wiki, never()).deleteAllDocuments(any(), anyBoolean(), any());
        String ticket = (String) preview.get("ticket");
        when(context.getUserReference()).thenReturn(ref("XWiki.SomeoneElse")); assertThrows(SecurityException.class, () -> operations.execute(ticket));
        when(context.getUserReference()).thenReturn(user);
        var result = operations.execute(ticket); assertTrue(result.toString().contains("deleted"));
        verify(wiki).deleteAllDocuments(any(), eq(true), eq(context)); assertThrows(SecurityException.class, () -> operations.execute(ticket));
    }
    @Test void changedVersionAndExpiredPreviewNeverDelete() throws Exception {
        String ticket = (String) operations.preview("delete", payload(List.of("Knowledge.Article"))).get("ticket");
        when(store.get(ref("Knowledge.Article")).getVersion()).thenReturn("9.1");
        assertTrue(operations.execute(ticket).toString().contains("conflict")); verify(wiki, never()).deleteAllDocuments(any(), anyBoolean(), any());
        var expiry = new AdminOperations.Ticket(user.toString(), "xwiki", "delete", List.of(), 1); AdminOperations.TICKETS.put("expired", expiry);
        assertThrows(SecurityException.class, () -> operations.execute("expired"));
    }
    @Test void protectedForeignOversizeAndOccupiedSelectionsCannotReachDeletion() throws Exception {
        for (String name : List.of("XWiki.Admin", "Knowledge.WebHome", "Knowledge.WebPreferences", "foreign:Knowledge.Article")) {
            assertThrows(SecurityException.class, () -> operations.preview("delete", payload(List.of(name))));
        }
        assertThrows(SecurityException.class, () -> operations.preview("delete", payload(Collections.nCopies(21, "Knowledge.Article"))));
        var draft = document("Drafts.Article", false, new HashMap<>(Map.of(EditLease.CLASS, object(Map.of("owner", user.toString(), "expiresAt", Long.toString(System.currentTimeMillis() + 100000))))));
        store.put(ref("Drafts.Article"), draft);
        assertThrows(SecurityException.class, () -> operations.preview("delete", payload(List.of("Drafts.Article"))));
        when(wiki.hasRecycleBin(context)).thenReturn(false);
        assertThrows(SecurityException.class, () -> operations.preview("delete", payload(List.of("Knowledge.Article"))));
        verify(wiki, never()).deleteAllDocuments(any(), anyBoolean(), any());
    }
    @Test void pendingLinkedDraftBlocksFormalPageRemoval() throws Exception {
        var flow = object(Map.of("status", "validating")); store.put(ref("Drafts.Article"), document("Drafts.Article", false, new HashMap<>(Map.of(WorkflowPolicyListener.WF, flow))));
        queries = statement -> statement.contains("wf.target =") ? List.of("Drafts.Article") : List.of();
        assertThrows(SecurityException.class, () -> operations.preview("delete", payload(List.of("Knowledge.Article"))));
    }
    @Test void restoreClearsLeaseAndPurgesTrashOnlyAfterPersistence() throws Exception {
        deleted(12, "Drafts.Recovered"); var bin = mock(XWikiRecycleBinStoreInterface.class); when(wiki.getRecycleBinStore()).thenReturn(bin);
        var source = document("Drafts.Recovered", true, new HashMap<>(Map.of(EditLease.CLASS, object(Map.of("owner", "old", "generation", "stale")))));
        when(bin.restoreFromRecycleBin(12, context, true)).thenReturn(source);
        String ticket = (String) operations.preview("restore", payload(List.of("12"))).get("ticket");
        assertTrue(operations.execute(ticket).toString().contains("restored"));
        verify(source).removeObjects(EditLease.CLASS); verify(source, never()).setContent(anyString()); verify(source, never()).setAttachmentList(any());
        verify(bin).deleteFromRecycleBin(12, context, true); assertNull(EditLease.SAVING.get());
    }
    @Test void canceledRestoreNeverPurgesTrashAndNeverOverwritesAnExistingPage() throws Exception {
        deleted(15, "Knowledge.Recovered"); var bin = mock(XWikiRecycleBinStoreInterface.class); when(wiki.getRecycleBinStore()).thenReturn(bin);
        var restored = document("Knowledge.Recovered", true, new HashMap<>());
        when(bin.restoreFromRecycleBin(15, context, true)).thenReturn(restored);
        String ticket = (String) operations.preview("restore", payload(List.of("15"))).get("ticket");
        doNothing().when(wiki).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), eq(context));
        assertTrue(operations.execute(ticket).toString().contains("conflict")); verify(bin, never()).deleteFromRecycleBin(anyLong(), any(), anyBoolean());
        store.put(ref("Knowledge.Recovered"), document("Knowledge.Recovered", false, new HashMap<>()));
        assertThrows(SecurityException.class, () -> operations.preview("restore", payload(List.of("15"))));
    }
    @Test void menuValidationRejectsCyclesProtocolsForeignTargetsAndInvalidBanners() throws Exception {
        assertThrows(SecurityException.class, () -> navigation.validate(payload(settings(List.of(menu("a", "b", "", List.of("all")), menu("b", "a", "", List.of("all"))), List.of()))));
        for (String destination : List.of("javascript:alert(1)", "foreign:Knowledge.Article", "https://user:pass@example.com/")) {
            assertThrows(SecurityException.class, () -> navigation.validate(payload(settings(List.of(menu("a", "", destination, List.of("all"))), List.of()))));
        }
        assertThrows(SecurityException.class, () -> navigation.validate(payload(settings(List.of(), List.of(Map.of("category", "Drafts.Article"))))));
    }
    @Test void settingsSaveIsVersionedAndConflictsDoNotWrite() throws Exception {
        configured(settings(List.of(), List.of()));
        var desired = settings(List.of(menu("docs", "", "Knowledge.Article", List.of("all"))), List.of());
        assertEquals("ok", navigation.save(payload(Map.of("version", "2.1", "settings", desired))).get("status"));
        assertThrows(SecurityException.class, () -> navigation.save(payload(Map.of("version", "2.1", "settings", desired))));
        verify(wiki, times(1)).saveDocument(any(), eq("Update administrator navigation settings"), eq(true), eq(context));
    }
    @Test void publicMenusFilterRolesAndNativePageRightsAndHideOrphanChildren() throws Exception {
        configured(settings(List.of(menu("public", "", "Knowledge.Article", List.of("all")), menu("private", "", "Knowledge.Secret", List.of("all")),
            menu("child", "private", "Knowledge.Article", List.of("all")), menu("admin", "", "https://example.com/admin", List.of("managers"))), List.of()));
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        when(policy.authorization.hasAccess(Right.VIEW, user, ref("Knowledge.Secret"))).thenReturn(false);
        var result = navigation.presentation("Knowledge.Article");
        List<?> menus = (List<?>) result.get("menus"); assertEquals(1, menus.size()); assertTrue(menus.toString().contains("public"));
        assertFalse(menus.toString().contains("Secret")); assertFalse(menus.toString().contains("private")); assertFalse(menus.toString().contains("admin"));
    }
    @Test void nearestReadableCategoryBannerIsInheritedAndQrUsesCanonicalViewUrl() throws Exception {
        var category = document("Knowledge.Technical.WebHome", false, new HashMap<>(Map.of("LuxPedia.Code.CategoryClass", object(Map.of())))); store.put(ref("Knowledge.Technical.WebHome"), category);
        store.put(ref("Knowledge.Technical.Article"), document("Knowledge.Technical.Article", false, new HashMap<>()));
        var root = Map.of("category", "Knowledge.WebHome", "headerEn", "Root", "headerZh", "根", "footerEn", "Footer", "footerZh", "页脚", "inherit", true, "enabled", true);
        var nested = Map.of("category", "Knowledge.Technical.WebHome", "headerEn", "Nearest", "headerZh", "最近", "footerEn", "", "footerZh", "", "inherit", true, "enabled", true);
        configured(settings(List.of(), List.of(root, nested)));
        var result = navigation.presentation("Knowledge.Technical.Article"); assertEquals("Nearest", ((Map<?, ?>) result.get("banner")).get("headerEn"));
        assertEquals("/bin/view/Knowledge/Technical/Article", result.get("qrURL"));
        when(policy.authorization.hasAccess(Right.VIEW, user, ref("Knowledge.Technical.WebHome"))).thenReturn(false);
        assertEquals("Root", ((Map<?, ?>) navigation.presentation("Knowledge.Technical.Article").get("banner")).get("headerEn"));
    }
    @Test void historyCountsAreMajorRevisionsAndUnavailableSourcesAreExplicit() throws Exception {
        queries = statement -> statement.startsWith("select count") ? List.of(0L) : statement.startsWith("select r.author") ?
            List.of(new Object[]{"XWiki.Alice", 3L}, new Object[]{"XWiki.Bob", 2L}) : List.of();
        var result = operations.dashboard("7"); Map<?, ?> activity = (Map<?, ?>) result.get("activity");
        assertEquals(5L, activity.get("edits")); assertEquals(2, activity.get("users")); assertEquals("retained-major-revisions", activity.get("basis"));
        assertTrue(result.toString().contains("unavailable")); assertTrue(result.toString().contains("containers"));
    }
    @Test void knowledgeChangesAreGuardedAgainstStaleVersionsAndRestoreCannotBeForged() throws Exception {
        var latest = document("Knowledge.Example.WebHome", false, new HashMap<>());
        var changed = document("Knowledge.Example.WebHome", false, new HashMap<>());
        var original = mock(XWikiDocument.class);
        when(original.isNew()).thenReturn(false); when(original.getVersion()).thenReturn("1.1");
        when(changed.getOriginalDocument()).thenReturn(original);
        store.put(latest.getDocumentReference(), latest);
        var event = new org.xwiki.bridge.event.DocumentUpdatingEvent();
        assertFalse(policy.nativeRestore());
        policy.onEvent(event, changed, context);
        assertTrue(event.isCanceled());
    }

    XWikiDocument migrationDraft(String state, Map<String, String> lease, boolean abandoned) throws Exception {
        String name = "Drafts.BOP-PASSIVE.管理员测试.WebHome";
        var objects = new HashMap<String, BaseObject>();
        objects.put(WorkflowPolicyListener.WF, object(Map.of("status", state, "target", "Knowledge.Article", "workflow", "LuxPedia.Workflow.Config")));
        objects.put(DraftWorkspace.CLASS, object(Map.of("status", abandoned ? "abandoned" : "active", "owner", "xwiki:XWiki.originalEditor", "shared", "0")));
        if (lease != null) { objects.put(EditLease.CLASS, object(lease)); }
        var original = document(name, false, objects);
        when(original.getContentAuthorReference()).thenReturn(ref("XWiki.originalAuthor"));
        var attachment = new XWikiAttachment(); attachment.setFilename("old-diagram.pdf");
        var copy = original.clone();
        when(original.getAttachmentList()).thenReturn(List.of(attachment));
        when(copy.getAttachmentList()).thenReturn(List.of(attachment.clone()));
        when(original.clone()).thenReturn(copy);
        assertTrue(DraftWorkspace.contentChanged(original, copy), "Native attachment clones use distinct object identities");
        store.put(original.getDocumentReference(), original);
        queries = statement -> List.of(name);
        return original;
    }
    void saveThroughListener() throws Exception {
        doAnswer(call -> {
            var copy = (XWikiDocument) call.getArgument(0);
            assertSame(copy, EditLease.SAVING.get());
            var event = new org.xwiki.bridge.event.DocumentUpdatingEvent();
            policy.onEvent(event, copy, context);
            assertFalse(event.isCanceled(), "Migration must pass the real save listener without an editor lease");
            store.put(copy.getDocumentReference(), copy); return null;
        }).when(wiki).saveDocument(any(XWikiDocument.class), eq("Normalize LuxPedia workflow role permissions"), eq(true), eq(context));
    }
    @Test void legacyAttachedDraftMigratesThroughSaveListenerWithoutClaimingOrChangingContent() throws Exception {
        var original = migrationDraft("draft", null, false); var copy = original.clone();
        saveThroughListener();
        assertEquals(1, policy.repairExistingDraftRights(context));
        assertSame(copy, store.get(original.getDocumentReference()));
        assertSame(original, copy.getOriginalDocument());
        assertEquals("Saved content", copy.getContent()); assertEquals(original.getTitle(), copy.getTitle());
        assertNull(copy.getObject(EditLease.CLASS));
        assertEquals("xwiki:XWiki.originalEditor", copy.getObject(DraftWorkspace.CLASS).getStringValue("owner"));
        assertEquals("active", copy.getObject(DraftWorkspace.CLASS).getStringValue("status"));
        verify(copy).setContentAuthorReference(ref("XWiki.originalAuthor"));
        verify(copy, never()).setContent(anyString()); verify(copy, never()).setAttachmentList(any());
        assertNull(EditLease.SAVING.get());
    }
    @Test void attachedMigrationPreservesForeignActiveAndExpiredLeasesAndWorkflowStates() throws Exception {
        for (String state : List.of("draft", "validating", "published")) {
            for (String expires : List.of("1", Long.toString(System.currentTimeMillis() + EditLease.DURATION))) {
                var fields = Map.of("owner", "xwiki:XWiki.anotherEditor", "generation", "original-generation", "acquiredAt", "123", "expiresAt", expires);
                var original = migrationDraft(state, fields, true); var copy = original.clone();
                saveThroughListener();
                assertEquals(1, policy.repairExistingDraftRights(context));
                for (var field : fields.entrySet()) { assertEquals(field.getValue(), EditLease.field(copy, field.getKey())); }
                assertEquals(state, copy.getObject(WorkflowPolicyListener.WF).getStringValue("status"));
                assertEquals("abandoned", copy.getObject(DraftWorkspace.CLASS).getStringValue("status"));
                verify(copy).setHidden(true); assertNull(EditLease.SAVING.get());
            }
        }
    }
    @Test void migrationTrustIsRemovedAfterFailureAndDoesNotAuthorizeOrdinaryAttachedSaves() throws Exception {
        var original = migrationDraft("draft", null, false); var copy = original.clone();
        doAnswer(call -> { assertSame(copy, EditLease.SAVING.get()); throw new IllegalStateException("storage failed"); })
            .when(wiki).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), eq(context));
        assertThrows(IllegalStateException.class, () -> policy.repairExistingDraftRights(context));
        assertNull(EditLease.SAVING.get());
        // A request parameter, administrator identity and a prior migration are never migration authority.
        when(request.getParameter("migration")).thenReturn("true");
        assertThrows(SecurityException.class, () -> policy.check(copy, context, false));
        verify(copy, never()).setContent(anyString());
    }
    @Test void migrationRejectsNonAdminBeforeQueryAndSkipsPagesDeletedBeforeGuardedRead() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        assertThrows(SecurityException.class, () -> policy.repairExistingDraftRights(context));
        verifyNoInteractions(policy.queries);
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        queries = statement -> List.of("Drafts.Deleted.WebHome");
        assertEquals(0, policy.repairExistingDraftRights(context));
        verify(wiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), eq(context));
        assertNull(EditLease.SAVING.get());
    }

    DepartmentPolicy departmentPolicy() { return new DepartmentPolicy(policy, context); }
    void departmentSettings(boolean enabled, List<String> publicCategories) throws Exception {
        store.put(departmentPolicy().settingsReference("xwiki"), document("LuxPedia.Code.DepartmentSettings", false,
            new HashMap<>(Map.of(DepartmentPolicy.SETTINGS_CLASS, object(Map.of("payload", payload(Map.of("enabled", enabled, "publicCategories", publicCategories))))))));
    }
    XWikiDocument departmentGroup(String id, List<String> categories, List<String> members) throws Exception {
        String name = "XWiki.LuxDepartment_" + id;
        var group = document(name, false, new HashMap<>(Map.of(DepartmentPolicy.CLASS,
            object(Map.of("payload", payload(Map.of("en", "Engineering", "zh", "工程部", "enabled", true, "categories", categories)))))));
        var memberships = new ArrayList<BaseObject>();
        for (String member : members) { memberships.add(object(Map.of("member", member))); }
        when(group.getXObjects(ref(DepartmentPolicy.MEMBERS))).thenReturn(memberships);
        store.put(ref(name), group); return group;
    }
    org.xwiki.security.authorization.SecurityAccessEntry departmentAccess(String name, List<DocumentReference> memberships, boolean wikiAdmin, Set<Right> nativeDenied) {
        return departmentAccess(name, memberships, wikiAdmin, nativeDenied, false);
    }
    org.xwiki.security.authorization.SecurityAccessEntry departmentAccess(String name, List<DocumentReference> memberships, boolean wikiAdmin, Set<Right> nativeDenied, boolean asSpace) {
        var settler = new DepartmentAuthorizationSettler(); settler.delegate = mock(org.xwiki.security.authorization.AuthorizationSettler.class);
        settler.listener = () -> policy; settler.contexts = () -> context;
        var nativeEntry = mock(org.xwiki.security.authorization.SecurityAccessEntry.class);
        var resource = mock(org.xwiki.security.SecurityReference.class); when(resource.getSecurityType()).thenReturn(asSpace ? org.xwiki.model.EntityType.SPACE : org.xwiki.model.EntityType.DOCUMENT); when(resource.getOriginalDocumentReference()).thenReturn(asSpace ? null : ref(name)); when(resource.getOriginalSpaceReference()).thenReturn(asSpace ? ref(name).getLastSpaceReference() : null);
        when(nativeEntry.getReference()).thenReturn(resource);
        when(nativeEntry.getAccess()).thenReturn(right -> nativeDenied.contains(right) ? RuleState.DENY : RuleState.ALLOW);
        var wikiEntry = mock(org.xwiki.security.authorization.SecurityAccessEntry.class);
        when(wikiEntry.getAccess()).thenReturn(right -> right == Right.ADMIN && wikiAdmin ? RuleState.ALLOW : RuleState.DENY);
        var targetRule = mock(org.xwiki.security.authorization.SecurityRuleEntry.class); when(targetRule.getReference()).thenReturn(resource);
        var rootRule = mock(org.xwiki.security.authorization.SecurityRuleEntry.class); var wikiResource = mock(org.xwiki.security.SecurityReference.class);
        when(wikiResource.getSecurityType()).thenReturn(org.xwiki.model.EntityType.WIKI); when(rootRule.getReference()).thenReturn(wikiResource);
        var currentActor = context.getUserReference(); var actor = mock(org.xwiki.security.UserSecurityReference.class); when(actor.getOriginalReference()).thenReturn(currentActor);
        var groups = memberships.stream().map(reference -> {
            var group = mock(org.xwiki.security.GroupSecurityReference.class); when(group.getOriginalDocumentReference()).thenReturn(reference); return group;
        }).toList();
        when(settler.delegate.settle(eq(actor), eq(groups), any())).thenAnswer(call ->
            ((Deque<?>) call.getArgument(2)).size() == 1 ? wikiEntry : nativeEntry);
        return settler.settle(actor, groups, new ArrayDeque<>(List.of(targetRule, rootRule)));
    }
    @Test void departmentAccessIntersectsNativeSiteRightsInsteadOfGrantingEditing() throws Exception {
        departmentSettings(true, List.of());
        var group = departmentGroup("a".repeat(32), List.of("Knowledge.Engineering.WebHome"), List.of("XWiki.Admin"));
        var access = departmentAccess("Knowledge.Engineering.Article.WebHome", List.of(group.getDocumentReference()), false, Set.of(Right.EDIT, Right.DELETE));
        assertEquals(RuleState.ALLOW, access.getAccess().get(Right.VIEW));
        assertEquals(RuleState.DENY, access.getAccess().get(Right.EDIT));
        assertEquals(RuleState.DENY, access.getAccess().get(Right.DELETE));
        var outside = departmentAccess("Knowledge.Finance.Article.WebHome", List.of(group.getDocumentReference()), false, Set.of());
        for (Right right : List.of(Right.VIEW, Right.EDIT, Right.COMMENT, Right.DELETE)) { assertEquals(RuleState.DENY, outside.getAccess().get(right)); }
    }
    @Test void departmentsUnionDescendantScopesWhileUnassignedUsersOnlyReadPublicCategories() throws Exception {
        departmentSettings(true, List.of("Knowledge.Public.WebHome"));
        var engineering = departmentGroup("a".repeat(32), List.of("Knowledge.Engineering.WebHome"), List.of("XWiki.Admin"));
        var finance = departmentGroup("b".repeat(32), List.of("Knowledge.Finance.WebHome"), List.of("XWiki.Admin"));
        var groups = List.of(engineering.getDocumentReference(), finance.getDocumentReference());
        for (String name : List.of("Knowledge.Engineering.Child.Article", "Knowledge.Finance.Article", "Knowledge.Public.Article")) {
            assertEquals(RuleState.ALLOW, departmentAccess(name, groups, false, Set.of()).getAccess().get(Right.VIEW));
        }
        assertEquals(RuleState.ALLOW, departmentAccess("Knowledge.Public.Article", List.of(), false, Set.of()).getAccess().get(Right.VIEW));
        assertEquals(RuleState.DENY, departmentAccess("Knowledge.Engineering.Article", List.of(), false, Set.of()).getAccess().get(Right.VIEW));
        assertEquals(RuleState.ALLOW, departmentAccess("Knowledge.DocumentLibrary", List.of(), false, Set.of()).getAccess().get(Right.VIEW));
    }
    @Test void cachedDepartmentEntriesImmediatelyObserveScopeAndMembershipRevocation() throws Exception {
        departmentSettings(true, List.of());
        var group = departmentGroup("a".repeat(32), List.of("Knowledge.Engineering.WebHome"), List.of("XWiki.Admin"));
        var access = departmentAccess("Knowledge.Engineering.Article", List.of(group.getDocumentReference()), false, Set.of());
        assertEquals(RuleState.ALLOW, access.getAccess().get(Right.VIEW));
        group.getObject(DepartmentPolicy.CLASS).setStringValue("payload", payload(Map.of("enabled", true, "categories", List.of("Knowledge.Finance.WebHome"))));
        assertEquals(RuleState.DENY, access.getAccess().get(Right.VIEW));
        group.getObject(DepartmentPolicy.CLASS).setStringValue("payload", payload(Map.of("enabled", true, "categories", List.of("Knowledge.Engineering.WebHome"))));
        assertEquals(RuleState.ALLOW, access.getAccess().get(Right.VIEW));
        group.getXObjects(ref(DepartmentPolicy.MEMBERS)).clear();
        assertEquals(RuleState.DENY, access.getAccess().get(Right.VIEW));
    }
    @Test void administratorsKeepAccessGuestsDoNotAndDisabledOrBrokenPoliciesFailAsDefined() throws Exception {
        departmentSettings(true, List.of("Knowledge.Public.WebHome"));
        assertEquals(RuleState.ALLOW, departmentAccess("Knowledge.Private.Article", List.of(), true, Set.of()).getAccess().get(Right.VIEW));
        when(context.getUserReference()).thenReturn(ref("XWiki.XWikiGuest"));
        assertEquals(RuleState.DENY, departmentAccess("Knowledge.Public.Article", List.of(), false, Set.of()).getAccess().get(Right.VIEW));
        when(context.getUserReference()).thenReturn(user);
        var cached = departmentAccess("Knowledge.Private.Article", List.of(), false, Set.of());
        departmentSettings(false, List.of()); assertEquals(RuleState.ALLOW, cached.getAccess().get(Right.VIEW));
        store.get(departmentPolicy().settingsReference("xwiki")).getObject(DepartmentPolicy.SETTINGS_CLASS).setStringValue("payload", "invalid-json");
        assertEquals(RuleState.DENY, cached.getAccess().get(Right.VIEW));
    }
    @Test void linkedDraftsUseTheKnowledgeTargetAndUnassignedDraftsStayWithTheirOwner() throws Exception {
        departmentSettings(true, List.of());
        var group = departmentGroup("a".repeat(32), List.of("Knowledge.Engineering.WebHome"), List.of("XWiki.Admin"));
        var draft = document("Drafts.Work.WebHome", false, new HashMap<>(Map.of(WorkflowPolicyListener.WF, object(Map.of("target", "Knowledge.Engineering.Article.WebHome")))));
        store.put(draft.getDocumentReference(), draft);
        var cached = departmentAccess("Drafts.Work.WebHome", List.of(group.getDocumentReference()), false, Set.of());
        assertEquals(RuleState.ALLOW, cached.getAccess().get(Right.VIEW));
        draft.getObject(WorkflowPolicyListener.WF).setStringValue("target", "Knowledge.Finance.Article.WebHome");
        assertEquals(RuleState.DENY, cached.getAccess().get(Right.VIEW));
        draft.removeObjects(WorkflowPolicyListener.WF); when(draft.getCreatorReference()).thenReturn(user);
        assertEquals(RuleState.ALLOW, cached.getAccess().get(Right.VIEW));
        when(draft.getCreatorReference()).thenReturn(ref("XWiki.anotherEditor"));
        assertEquals(RuleState.DENY, cached.getAccess().get(Right.VIEW));
    }
    @Test void editorsCannotSubmitIntoAnotherDepartmentOrForgeDepartmentGroupObjects() throws Exception {
        departmentSettings(true, List.of());
        var group = departmentGroup("a".repeat(32), List.of("Knowledge.Engineering.WebHome"), List.of("XWiki.Admin"));
        policy.roleGroups = Set.of("xwiki:XWiki.LuxEditors", group.getDocumentReference().toString());
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        var draft = document("Drafts.Work.WebHome", false, new HashMap<>(Map.of(WorkflowPolicyListener.WF, object(Map.of("target", "Knowledge.Finance.Article.WebHome")))));
        var error = assertThrows(SecurityException.class, () -> policy.check(draft, context, false));
        assertTrue(error.getMessage().contains("department"));
        assertThrows(SecurityException.class, () -> policy.check(group, context, false));
        assertEquals("forbidden", service.adminWrite("save-department", "{}", "valid").get("status"));
    }
    @Test void nativeDepartmentGroupsSupportNestedTeamsWithoutCyclesGrantingAccess() throws Exception {
        departmentSettings(true, List.of());
        var group = departmentGroup("a".repeat(32), List.of("Knowledge.Engineering.WebHome"), List.of("XWiki.Team"));
        var team = document("XWiki.Team", false, new HashMap<>(Map.of(DepartmentPolicy.MEMBERS, object(Map.of("member", "XWiki.Admin")))));
        var teamMember = team.getObject(DepartmentPolicy.MEMBERS); when(team.getXObjects(ref(DepartmentPolicy.MEMBERS))).thenReturn(List.of(teamMember));
        store.put(team.getDocumentReference(), team);
        var access = departmentAccess("Knowledge.Engineering.Article", List.of(group.getDocumentReference()), false, Set.of());
        assertEquals(RuleState.ALLOW, access.getAccess().get(Right.VIEW));
        team.getObject(DepartmentPolicy.MEMBERS).setStringValue("member", policy.localSerializer.serialize(group.getDocumentReference()));
        assertEquals(RuleState.DENY, access.getAccess().get(Right.VIEW));
    }
    @Test void departmentSettingsAreVersionedAndInvalidCategoriesAndMembersCannotMutateRoles() throws Exception {
        departmentSettings(false, List.of());
        var category = document("Knowledge.Engineering.WebHome", false, new HashMap<>(Map.of("LuxPedia.Code.CategoryClass", object(Map.of()))));
        store.put(category.getDocumentReference(), category);
        assertThrows(SecurityException.class, () -> departmentPolicy().saveSettings(Map.of("enabled", true, "publicCategories", List.of("Knowledge.WebHome"), "version", "2.1")));
        assertThrows(SecurityException.class, () -> departmentPolicy().saveSettings(Map.of("enabled", true, "publicCategories", List.of(), "version", "1.1")));
        var result = departmentPolicy().saveSettings(Map.of("enabled", true, "publicCategories", List.of("Knowledge.Engineering.WebHome"), "version", "2.1"));
        assertEquals("ok", result.get("status")); assertTrue((Boolean) departmentPolicy().settings("xwiki").get("enabled"));
        assertThrows(SecurityException.class, () -> departmentPolicy().saveDepartment(Map.of("id", "LuxManagers", "en", "Bad", "categories", List.of(), "members", List.of())));
        assertThrows(SecurityException.class, () -> departmentPolicy().saveDepartment(Map.of("id", "", "en", "Bad", "categories", List.of(), "members", List.of("XWiki.XWikiGuest"))));
    }
    @Test void creatingAnEmptyDepartmentUsesANativeGroupWithoutChangingSiteRoleGroups() throws Exception {
        var result = departmentPolicy().saveDepartment(Map.of("id", "", "en", "Research", "zh", "研发部", "categories", List.of(), "members", List.of()));
        assertEquals("ok", result.get("status"));
        var created = store.values().stream().filter(doc -> DepartmentPolicy.groupReference(doc.getDocumentReference())).findFirst().orElseThrow();
        assertNotNull(created.getObject(DepartmentPolicy.CLASS)); assertNotNull(created.getObject(DepartmentPolicy.MEMBERS));
        verify(wiki).saveDocument(eq(created), eq("Create department role"), eq(true), eq(context));
        assertTrue(store.keySet().stream().noneMatch(ref -> Set.of("LuxEditors", "LuxManagers", "LuxReviewers").contains(ref.getName())));
    }

    @Test void nativeSpaceAuthorizationIsScopedAsWellAsDocumentAndAttachmentParents() throws Exception {
        departmentSettings(true, List.of());
        assertEquals(RuleState.DENY, departmentAccess("Knowledge.Private.WebHome", List.of(), false, Set.of(), true).getAccess().get(Right.VIEW));
        departmentSettings(true, List.of("Knowledge.Private.WebHome"));
        assertEquals(RuleState.ALLOW, departmentAccess("Knowledge.Private.WebHome", List.of(), false, Set.of(), true).getAccess().get(Right.VIEW));
    }

    @Test void updatingDepartmentMembershipAndScopeUsesOneVersionedNativeDocumentSave() throws Exception {
        var group = departmentGroup("a".repeat(32), List.of("Knowledge.Old.WebHome"), List.of("XWiki.before"));
        var updated = group.clone(); when(group.clone()).thenReturn(updated);
        var savedMembers = new ArrayList<BaseObject>();
        when(updated.getXObjects(ref(DepartmentPolicy.MEMBERS))).thenReturn(savedMembers);
        when(updated.newXObject(eq(ref(DepartmentPolicy.MEMBERS)), eq(context))).thenAnswer(call -> {
            var member = object(Map.of()); savedMembers.add(member); return member;
        });
        store.put(user, document("XWiki.Admin", false, new HashMap<>(Map.of("XWiki.XWikiUsers", object(Map.of())))));
        var input = Map.of("id", "a".repeat(32), "version", "2.1", "en", "Engineering", "zh", "工程部", "enabled", false, "categories", List.of(), "members", List.of("XWiki.Admin"));
        assertEquals("ok", departmentPolicy().saveDepartment(input).get("status"));
        assertEquals(List.of("XWiki.Admin"), departmentPolicy().members(updated));
        assertEquals(false, departmentPolicy().department(updated).get("enabled"));
        assertEquals(List.of(), departmentPolicy().department(updated).get("categories"));
        verify(wiki, times(1)).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), eq(context));
        assertThrows(SecurityException.class, () -> departmentPolicy().saveDepartment(input));
        verify(wiki, times(1)).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), eq(context));
    }

    @Test void nativeDepartmentGroupEditsAreFencedAgainstAConcurrentScopeUpdate() throws Exception {
        var latest = departmentGroup("a".repeat(32), List.of(), List.of());
        var update = latest.clone(); var stale = mock(XWikiDocument.class);
        when(stale.getVersion()).thenReturn("1.1"); when(update.getOriginalDocument()).thenReturn(stale);
        var event = new org.xwiki.bridge.event.DocumentUpdatingEvent();
        policy.onEvent(event, update, context); assertTrue(event.isCanceled());
    }

    @Test void nativeSearchOnlyDisablesGlobalMetadataForRestrictedNonAdministrators() throws Exception {
        departmentSettings(true, List.of());
        assertFalse(service.departmentSearchRestricted());
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        assertTrue(service.departmentSearchRestricted());
        departmentSettings(false, List.of()); assertFalse(service.departmentSearchRestricted());
    }

    @Test void ordinaryEditorsCannotRemoveTheNativeSearchPrivacyProtection() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        var macros = document("Main.SolrSearchMacros", false, new HashMap<>());
        assertThrows(SecurityException.class, () -> policy.check(macros, context, false));
    }

}
