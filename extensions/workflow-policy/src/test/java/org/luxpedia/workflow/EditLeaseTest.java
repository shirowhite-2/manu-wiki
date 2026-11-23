package org.luxpedia.workflow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.*;
import com.xpn.xwiki.*;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.web.XWikiRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EditLeaseTest {
    WorkflowPolicyListenerTest.Subject policy;
    XWikiContext context;
    XWiki wiki;
    XWikiRequest request;
    DocumentReference user = new DocumentReference("xwiki", "XWiki", "alice");
    DocumentReference reference = new DocumentReference("xwiki", List.of("Drafts", "Article"), "WebHome");
    Map<DocumentReference, XWikiDocument> store;
    long now = Instant.parse("2026-11-19T00:00:00Z").toEpochMilli();
    DraftWriteBoundary.Scope scope;
    EditLeaseManager manager;
    BaseObject object(Map<String, String> values) throws Exception {
        var object = spy(new BaseObject()); values.forEach(object::setStringValue);
        doAnswer(call -> { object.setStringValue(call.getArgument(0), call.getArgument(1)); return null; })
            .when(object).set(anyString(), anyString(), any(XWikiContext.class));
        doAnswer(call -> { object.setIntValue(call.getArgument(0), call.getArgument(1)); return null; })
            .when(object).set(anyString(), anyInt(), any(XWikiContext.class));
        return object;
    }
    XWikiDocument doc(Map<String, BaseObject> objects) throws Exception {
        var doc = mock(XWikiDocument.class);
        when(doc.getDocumentReference()).thenReturn(reference);
        when(doc.getVersion()).thenReturn("3.1");
        when(doc.getContent()).thenReturn("Saved unfinished content");
        when(doc.getContentAuthorReference()).thenReturn(user);
        when(doc.getOriginalDocument()).thenReturn(doc);
        when(doc.getObject(anyString())).thenAnswer(call -> objects.get(call.getArgument(0)));
        when(doc.newXObject(any(DocumentReference.class), eq(context))).thenAnswer(call -> {
            var cls = (DocumentReference) call.getArgument(0); var value = object(Map.of());
            objects.put(policy.localSerializer.serialize(cls), value); return value;
        });
        doAnswer(call -> { objects.remove(call.getArgument(0)); return null; }).when(doc).removeObjects(anyString());
        when(doc.clone()).thenAnswer(call -> {
            Map<String, BaseObject> copy = new HashMap<>();
            for (var entry : objects.entrySet()) { copy.put(entry.getKey(), entry.getValue().clone()); }
            // Clone values use the same controlled string-property setter as persisted objects.
            for (var entry : objects.entrySet()) {
                var fields = new HashMap<String, String>();
                for (String name : List.of("owner", "generation", "acquiredAt", "expiresAt", "shared", "status")) {
                    fields.put(name, entry.getValue().getStringValue(name));
                }
                copy.put(entry.getKey(), object(fields));
            }
            return doc(copy);
        });
        return doc;
    }
    @BeforeEach void setup() throws Exception {
        EditLease.clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        scope = DraftWriteBoundary.open();
        policy = new WorkflowPolicyListenerTest.Subject();
        policy.resolver = new WorkflowPolicyListenerTest().realResolver();
        policy.localSerializer = mock(org.xwiki.model.reference.EntityReferenceSerializer.class);
        when(policy.localSerializer.serialize(any())).thenAnswer(call -> call.getArgument(0).toString().replace("xwiki:", ""));
        policy.authorization = mock(AuthorizationManager.class);
        when(policy.authorization.hasAccess(eq(Right.VIEW), any(), any())).thenReturn(true);
        policy.roleGroups = Set.of("xwiki:XWiki.LuxEditors");
        context = mock(XWikiContext.class); wiki = mock(XWiki.class); request = mock(XWikiRequest.class);
        when(context.getWikiId()).thenReturn("xwiki"); when(context.getUserReference()).thenReturn(user);
        when(context.getWiki()).thenReturn(wiki); when(context.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn("POST"); store = new ConcurrentHashMap<>();
        when(wiki.getDocument(any(DocumentReference.class), eq(context))).thenAnswer(call -> store.get(call.getArgument(0)));
        doAnswer(call -> { store.put(reference, call.getArgument(0)); return null; }).when(wiki)
            .saveDocument(any(XWikiDocument.class), anyString(), eq(true), eq(context));
        store.put(reference, doc(new HashMap<>()));
        manager = new EditLeaseManager(policy, context, null);
    }
    @AfterEach void cleanup() { scope.close(); EditLease.clock = Clock.systemUTC(); EditLease.SAVING.remove(); EditLease.OPENING.remove(); }
    XWikiDocument stored() { return store.get(reference); }
    void time(long milliseconds) { EditLease.clock = Clock.fixed(Instant.ofEpochMilli(milliseconds), ZoneOffset.UTC); }
    @Test void leasePersistsForExactly24HoursAndRepeatedClaimNeverExtendsIt() throws Exception {
        assertEquals("free", manager.read("Drafts.Article.WebHome").get("status"));
        Map<String, Object> first = manager.change("Drafts.Article.WebHome", "claim");
        assertEquals("owned", first.get("status")); assertEquals(Long.toString(now + EditLease.DURATION), first.get("expiresAt"));
        time(now + EditLease.DURATION - 1);
        assertEquals(first.get("generation"), manager.change("Drafts.Article.WebHome", "claim").get("generation"));
        assertEquals(first.get("expiresAt"), manager.read("Drafts.Article.WebHome").get("expiresAt"));
        time(now + EditLease.DURATION);
        assertEquals("free", manager.read("Drafts.Article.WebHome").get("status"));
        verify(stored(), never()).setContent(anyString()); verify(stored(), never()).setAttachmentList(any());
    }
    @Test void anotherEditorCanReadButCannotTakeAnActiveLeaseAndGenerationIsPrivate() throws Exception {
        manager.change("Drafts.Article.WebHome", "claim");
        when(context.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "bob"));
        var value = manager.change("Drafts.Article.WebHome", "claim");
        assertEquals("busy", value.get("status")); assertEquals("", value.get("generation"));
        assertThrows(SecurityException.class, () -> EditLease.requireOwner(stored(), context));
    }
    @Test void editorCannotOpenCategoryLandingAsAnArticleDraft() throws Exception {
        var target = new DocumentReference("xwiki", List.of("Knowledge", "BOP-ACTIVE"), "WebHome");
        var category = mock(XWikiDocument.class);
        var workflow = object(Map.of("status", "published")); workflow.setIntValue("istarget", 1);
        when(category.getObject(WorkflowPolicyListener.WF)).thenReturn(workflow);
        when(category.getObject(WorkflowPolicyListener.CATEGORY)).thenReturn(mock(BaseObject.class));
        when(category.isNew()).thenReturn(false);
        store.put(target, category);

        var error = assertThrows(SecurityException.class,
            () -> manager.change("Knowledge.BOP-ACTIVE.WebHome", "open"));

        assertTrue(error.getMessage().contains("category home pages"));
    }
    @Test void takeoverKeepsSavedContentAndFencesTheOldEditorEvenAfterReacquisition() throws Exception {
        String first = manager.change("Drafts.Article.WebHome", "claim").get("generation").toString();
        when(request.getHeader("X-LuxPedia-Edit-Lease")).thenReturn(first);
        assertDoesNotThrow(() -> EditLease.requireOwner(stored(), context));
        time(now + EditLease.DURATION);
        assertThrows(SecurityException.class, () -> EditLease.requireOwner(stored(), context));
        when(context.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "bob"));
        var second = manager.change("Drafts.Article.WebHome", "claim");
        assertEquals("owned", second.get("status")); assertNotEquals(first, second.get("generation"));
        assertEquals("Saved unfinished content", stored().getContent());
        assertThrows(SecurityException.class, () -> EditLease.requireOwner(stored(), context));
        when(request.getHeader("X-LuxPedia-Edit-Lease")).thenReturn(second.get("generation").toString());
        assertDoesNotThrow(() -> EditLease.requireOwner(stored(), context));
    }
    @Test void releasePreservesContentAndRequiresBothOwnerAndGeneration() throws Exception {
        String generation = manager.change("Drafts.Article.WebHome", "claim").get("generation").toString();
        assertThrows(SecurityException.class, () -> manager.change("Drafts.Article.WebHome", "release"));
        when(request.getHeader("X-LuxPedia-Edit-Lease")).thenReturn(generation);
        assertEquals("free", manager.change("Drafts.Article.WebHome", "release").get("status"));
        assertEquals("Saved unfinished content", stored().getContent());
        assertThrows(SecurityException.class, () -> EditLease.requireOwner(stored(), context));
    }
    @Test void nonEditorForeignWikiAndUnreadableDraftCannotAcquire() throws Exception {
        policy.roleGroups = Set.of("xwiki:XWiki.LuxReviewers");
        assertThrows(SecurityException.class, () -> manager.change("Drafts.Article.WebHome", "claim"));
        assertThrows(SecurityException.class, () -> manager.read("other:Drafts.Article.WebHome"));
        when(policy.authorization.hasAccess(eq(Right.VIEW), any(), any())).thenReturn(false);
        assertThrows(SecurityException.class, () -> manager.read("Drafts.Article.WebHome"));
    }
    @Test void pendingReviewAndAbandonedDraftStayLockedEvenAfter24Hours() throws Exception {
        var flow = object(Map.of("status", "validating"));
        store.put(reference, doc(new HashMap<>(Map.of(WorkflowPolicyListener.WF, flow))));
        time(now + 2 * EditLease.DURATION);
        assertEquals("locked", manager.read("Drafts.Article.WebHome").get("status"));
        assertThrows(SecurityException.class, () -> manager.change("Drafts.Article.WebHome", "claim"));
        store.put(reference, doc(new HashMap<>(Map.of(DraftWorkspace.CLASS, object(Map.of("status", "abandoned"))))));
        assertEquals("locked", manager.read("Drafts.Article.WebHome").get("status"));
    }
    @Test void canceledPersistenceDoesNotReportSuccessfulAcquisition() throws Exception {
        doNothing().when(wiki).saveDocument(any(XWikiDocument.class), anyString(), eq(true), eq(context));
        assertThrows(SecurityException.class, () -> manager.change("Drafts.Article.WebHome", "claim"));
    }
    @Test void leaseCanBeReadAfterCreatingANewServiceInstance() throws Exception {
        var first = manager.change("Drafts.Article.WebHome", "claim");
        assertEquals(first.get("generation"), new EditLeaseManager(policy, context, null).read("Drafts.Article.WebHome").get("generation"));
    }
    @Test void forceReleaseRequiresAdministratorAndTheCurrentGeneration() throws Exception {
        var first = manager.change("Drafts.Article.WebHome", "claim");
        when(request.getHeader("X-LuxPedia-Edit-Lease")).thenReturn(first.get("generation").toString());
        assertThrows(SecurityException.class, () -> manager.change("Drafts.Article.WebHome", "force-release"));
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        assertEquals("free", manager.change("Drafts.Article.WebHome", "force-release").get("status"));
    }
    @Test void boundaryHoldsUntilRequestCommitAndAlwaysReleasesAfterExceptions() throws Exception {
        DraftWriteBoundary.guard(reference.toString());
        var executor = Executors.newSingleThreadExecutor();
        var attempted = new CountDownLatch(1);
        try {
            var next = executor.submit(() -> {
                try (var other = DraftWriteBoundary.open()) { attempted.countDown(); DraftWriteBoundary.guard(reference.toString()); return true; }
            });
            assertTrue(attempted.await(2, TimeUnit.SECONDS)); assertFalse(next.isDone());
            scope.close(); scope = DraftWriteBoundary.open();
            assertTrue(next.get(2, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertThrows(IllegalStateException.class, () -> new DraftWriteBoundary().doFilter(null, null,
            (request, response) -> { throw new IllegalStateException(); }));
    }
    @Test void twoConcurrentKnowledgeOpensReuseOneDraftAndOnlyTheFirstEditorAcquiresIt() throws Exception {
        var target = new DocumentReference("xwiki", List.of("Knowledge"), "Article");
        var published = mock(XWikiDocument.class); var flow = object(Map.of("status", "published"));
        flow.setIntValue("istarget", 1); when(published.getObject(WorkflowPolicyListener.WF)).thenReturn(flow);
        store.put(target, published);
        policy.queries = mock(org.xwiki.query.QueryManager.class);
        var query = mock(org.xwiki.query.Query.class, RETURNS_SELF);
        when(policy.queries.createQuery(anyString(), anyString())).thenReturn(query);
        var matches = new CopyOnWriteArrayList<String>();
        when(query.execute()).thenAnswer(call -> List.copyOf(matches));
        var creating = new CountDownLatch(1); var finishCreation = new CountDownLatch(1);
        var copies = new java.util.concurrent.atomic.AtomicInteger();
        manager = new EditLeaseManager(policy, context, null) {
            @Override Object nativeCall(String method, DocumentReference ref) throws Exception {
                assertTrue(EditLease.OPENING.get()); assertEquals("createDraftDocument", method); assertEquals(target, ref);
                copies.incrementAndGet(); creating.countDown(); assertTrue(finishCreation.await(3, TimeUnit.SECONDS));
                store.put(reference, doc(new HashMap<>(Map.of(WorkflowPolicyListener.WF, object(Map.of("status", "draft"))))));
                matches.add("Drafts.Article.WebHome"); return reference;
            }
        };
        var bob = mock(XWikiContext.class);
        when(bob.getWikiId()).thenReturn("xwiki"); when(bob.getWiki()).thenReturn(wiki);
        when(bob.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "bob"));
        when(wiki.getDocument(any(DocumentReference.class), eq(bob))).thenAnswer(call -> store.get(call.getArgument(0)));
        var second = new EditLeaseManager(policy, bob, null);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstResult = executor.submit(() -> { try (var boundary = DraftWriteBoundary.open()) { return manager.change("Knowledge.Article", "open"); } });
            assertTrue(creating.await(3, TimeUnit.SECONDS));
            var secondStarted = new CountDownLatch(1);
            var secondResult = executor.submit(() -> { try (var boundary = DraftWriteBoundary.open()) {
                secondStarted.countDown(); return second.change("Knowledge.Article", "open");
            } });
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS)); assertFalse(secondResult.isDone());
            finishCreation.countDown();
            assertEquals("owned", firstResult.get(3, TimeUnit.SECONDS).get("status"));
            assertEquals("busy", secondResult.get(3, TimeUnit.SECONDS).get("status"));
            assertEquals(1, copies.get()); assertEquals(1, matches.size());
        } finally { finishCreation.countDown(); executor.shutdownNow(); }
    }
    @Test void initializerRegistersBeforeXWikiFiltersAndSynchronousFailuresCloseTheBoundary() throws Exception {
        var servlet = mock(jakarta.servlet.ServletContext.class);
        var registration = mock(jakarta.servlet.FilterRegistration.Dynamic.class);
        when(servlet.addFilter(eq("luxpedia-draft-write-boundary"), any(jakarta.servlet.Filter.class))).thenReturn(registration);
        var filter = new DraftWriteBoundary(); filter.onStartup(Set.of(), servlet);
        verify(registration).addMappingForUrlPatterns(any(), eq(false), eq("/*"));
        verify(registration).setAsyncSupported(true);
        scope.close();
        assertFalse(DraftWriteBoundary.present());
        try {
            assertThrows(IllegalStateException.class, () -> filter.doFilter(null, null, (request, response) -> {
                assertTrue(DraftWriteBoundary.present()); throw new IllegalStateException();
            }));
            assertFalse(DraftWriteBoundary.present());
            assertEquals("POLICY_WRITE_BOUNDARY_MISSING", policy.getPolicyVersion());
            assertTrue(java.util.ServiceLoader.load(jakarta.servlet.ServletContainerInitializer.class).stream()
                .anyMatch(provider -> provider.type().equals(DraftWriteBoundary.class)));
        } finally { scope = DraftWriteBoundary.open(); }
    }

}
