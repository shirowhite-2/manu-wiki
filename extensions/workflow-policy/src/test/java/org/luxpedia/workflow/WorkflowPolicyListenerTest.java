package org.luxpedia.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.DocumentUpdatingEvent;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.observation.event.CancelableEvent;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.query.QueryManager;
import org.xwiki.query.Query;
import org.xwiki.model.reference.EntityReferenceSerializer;
import com.xpn.xwiki.user.api.XWikiGroupService;
import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.ObjectDiff;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowPolicyListenerTest {
    static class Subject extends WorkflowPolicyListener {
        String operation = "";
        Set<String> roleGroups = Set.of();
        DocumentReference draftReference;
        @Override String workflowOperation() { return operation; }
        @Override Set<String> groups(DocumentReference user, XWikiContext context) { return roleGroups; }
        @Override DocumentReference findDraft(DocumentReference target, XWikiContext context) { return draftReference; }
    }
    Subject policy;
    XWikiContext context;
    XWiki wiki;
    DocumentReference user;
    XWikiDocument doc;
    XWikiDocument old;
    Map<DocumentReference, XWikiDocument> documents;
    List<BaseObject> rights;

    DocumentReference ref(String value) {
        String local = value.replace("xwiki:", "");
        List<String> parts = new ArrayList<>(List.of(local.split("\\.")));
        String name = parts.remove(parts.size() - 1);
        return new DocumentReference("xwiki", parts, name);
    }
    BaseObject object(String state) {
        BaseObject result = mock(BaseObject.class);
        when(result.getStringValue(anyString())).thenReturn("");
        when(result.getStringValue("status")).thenReturn(state);
        when(result.getStringValue("workflow")).thenReturn("LuxPedia.Workflow.Config");
        when(result.getStringValue("target")).thenReturn("Knowledge.Technical.Article");
        return result;
    }
    XWikiDocument document(String reference) throws Exception {
        XWikiDocument result = mock(XWikiDocument.class);
        when(result.getDocumentReference()).thenReturn(ref(reference));
        when(result.getAttachmentList()).thenReturn(List.of());
        when(result.getObjectDiff(any(XWikiDocument.class), any(XWikiDocument.class), any(XWikiContext.class))).thenReturn(List.of());
        when(result.newXObject(any(DocumentReference.class), eq(context))).thenAnswer(invocation -> {
            BaseObject rule = mock(BaseObject.class); rights.add(rule); return rule;
        });
        documents.put(ref(reference), result);
        return result;
    }
    void group(String... names) throws Exception {
        policy.roleGroups = Set.copyOf(java.util.Arrays.stream(names).map(name -> "xwiki:XWiki.Lux" + name).toList());
    }
    void states(String before, String after) {
        BaseObject previous = before == null ? null : object(before);
        BaseObject next = after == null ? null : object(after);
        when(old.getObject(WorkflowPolicyListener.WF)).thenReturn(previous);
        when(doc.getObject(WorkflowPolicyListener.WF)).thenReturn(next);
    }
    void audit(XWikiDocument article, String submitter) {
        BaseObject audit = mock(BaseObject.class);
        when(audit.getStringValue("submittedBy")).thenReturn(ref("XWiki." + submitter).toString());
        when(article.getObject(WorkflowPolicyListener.AUDIT)).thenReturn(audit);
        when(article.getCreatorReference()).thenReturn(ref("XWiki.originalAuthor"));
    }
    void changedClass(String name) throws Exception {
        ObjectDiff diff = mock(ObjectDiff.class);
        when(diff.getXClassReference()).thenReturn(ref(name));
        when(doc.getObjectDiff(old, doc, context)).thenReturn(List.of(List.of(diff)));
    }
    @BeforeEach void setup() throws Exception {
        policy = new Subject();
        policy.authorization = mock(AuthorizationManager.class);
        when(policy.authorization.hasAccess(eq(Right.VIEW), any(), any())).thenReturn(true);
        policy.resolver = mock(DocumentReferenceResolver.class);
        when(policy.resolver.resolve(anyString(), any())).thenAnswer(invocation -> ref(invocation.getArgument(0)));
        context = mock(XWikiContext.class); wiki = mock(XWiki.class);
        user = ref("XWiki.currentUser");
        when(context.getUserReference()).thenReturn(user);
        when(context.getWikiId()).thenReturn("xwiki");
        when(context.getWiki()).thenReturn(wiki);
        documents = new HashMap<>(); rights = new ArrayList<>();
        when(wiki.getDocument(any(DocumentReference.class), eq(context))).thenAnswer(invocation -> {
            DocumentReference requested = invocation.getArgument(0);
            if (documents.containsKey(requested)) { return documents.get(requested); }
            XWikiDocument empty = mock(XWikiDocument.class);
            when(empty.getDocumentReference()).thenReturn(requested);
            return empty;
        });
        doc = document("Drafts.Article.WebHome");
        old = document("Drafts.Article.WebHome");
        when(doc.getOriginalDocument()).thenReturn(old);
        group("Editors");
    }
    @Test void editorCanWriteAnUnsubmittedArticle() throws Exception {
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void editorCanWriteADraft() throws Exception {
        states("draft", "draft"); assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void submittedContentIsLockedForEditor() {
        states("validating", "validating"); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void reviewerCannotChangeDraftBody() throws Exception {
        group("Reviewers"); states("draft", "draft");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void readerCannotCreateADraft() throws Exception {
        group("Readers"); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void guestCannotCreateADraft() throws Exception {
        group(); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void workflowObjectCannotBeForgedThroughRest() {
        states(null, "published"); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void workflowObjectCannotBeRemoved() {
        states("draft", null); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void workflowTargetCannotBeChangedDirectly() throws Exception {
        states("draft", "draft"); changedClass(WorkflowPolicyListener.WF);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCannotGrantTheirOwnRights() throws Exception {
        changedClass("XWiki.XWikiRights"); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCannotInstallJavaScriptOnAnArticle() throws Exception {
        changedClass("XWiki.JavaScriptExtension"); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void submissionAuditCannotBeForged() throws Exception {
        changedClass(WorkflowPolicyListener.AUDIT); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCanStartTheConfiguredWorkflow() {
        policy.operation = "startWorkflow"; states(null, "draft");
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void editorCannotUseAnotherWorkflowConfig() {
        policy.operation = "startWorkflow"; states(null, "draft");
        when(doc.getObject(WorkflowPolicyListener.WF).getStringValue("workflow")).thenReturn("Other.Config");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void workflowCannotPublishOutsideKnowledge() {
        policy.operation = "startWorkflow"; states(null, "draft");
        when(doc.getObject(WorkflowPolicyListener.WF).getStringValue("target")).thenReturn("XWiki.LuxManagers");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void workflowCannotOverwriteCategoryHome() {
        policy.operation = "startWorkflow"; states(null, "draft");
        when(doc.getObject(WorkflowPolicyListener.WF).getStringValue("target")).thenReturn("Knowledge.Technical.WebHome");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCannotStartAsPublishedTarget() {
        policy.operation = "startWorkflowAsTarget"; states(null, "published");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCanSubmitAndPendingAclDeniesEdits() throws Exception {
        policy.operation = "submitForValidation"; states("draft", "validating");
        policy.check(doc, context, false);
        assertTrue(rights.stream().anyMatch(rule -> mockingDetails(rule).getInvocations().stream()
            .anyMatch(call -> call.getMethod().getName().equals("set") && call.getArgument(0).equals("levels") && call.getArgument(1).equals("edit"))));
        verify(doc).removeObjects("XWiki.XWikiRights");
    }
    @Test void reviewerCannotSubmitTheirOwnNewDraft() throws Exception {
        group("Reviewers"); policy.operation = "submitForValidation"; states("draft", "validating");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void reviewerCannotReopenPublishedDraft() throws Exception {
        group("Reviewers"); policy.operation = "editDraft"; states("published", "draft");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCanReopenPublishedDraft() {
        policy.operation = "editDraft"; states("published", "draft");
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void reviewerCanApproveAnotherSubmission() throws Exception {
        group("Reviewers"); policy.operation = "validate"; states("validating", "valid"); audit(old, "anotherEditor");
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void dualRoleUserCannotApproveOwnSubmission() throws Exception {
        group("Editors", "Reviewers"); policy.operation = "validate"; states("validating", "valid"); audit(old, "currentUser");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void creatorCannotApproveAnArticleResubmittedBySomeoneElse() throws Exception {
        group("Reviewers"); policy.operation = "validate"; states("validating", "valid"); audit(old, "anotherEditor");
        when(old.getCreatorReference()).thenReturn(user);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void legacyPendingArticleCannotSkipSubmitterAudit() throws Exception {
        group("Reviewers"); policy.operation = "validate"; states("validating", "valid");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCannotArchiveOrUnpublishViaNativeApi() {
        for (String operation : List.of("archive", "unpublish", "unarchive", "publishFromArchive")) {
            policy.operation = operation; states("published", "archived");
            assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        }
    }
    @Test void nonAdminCannotDeleteDrafts() { assertThrows(SecurityException.class, () -> policy.check(doc, context, true)); }
    @Test void nonAdminCannotChangeRoleMembership() throws Exception {
        doc = document("XWiki.LuxManagers"); when(doc.getOriginalDocument()).thenReturn(old);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void nonAdminCannotChangeWorkflowConfiguration() throws Exception {
        doc = document("LuxPedia.Workflow.Config"); when(doc.getOriginalDocument()).thenReturn(old);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void reviewerCannotWriteKnowledgeDirectly() throws Exception {
        group("Reviewers"); doc = document("Knowledge.Technical.Article"); when(doc.getOriginalDocument()).thenReturn(old);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void publishedCopyIsValidatedBeforeItIsSaved() throws Exception {
        group("Reviewers"); policy.operation = "publish";
        XWikiDocument draft = document("Drafts.Article.WebHome");
        BaseObject flow = object("validating");
        when(draft.getObject(WorkflowPolicyListener.WF)).thenReturn(flow); audit(draft, "anotherEditor");
        doc = document("Knowledge.Technical.Article"); when(doc.getOriginalDocument()).thenReturn(old);
        DocumentReference draftReference = draft.getDocumentReference();
        policy.draftReference = draftReference;
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void publishingOwnSubmissionCannotCreateATargetFirst() throws Exception {
        group("Editors", "Reviewers"); policy.operation = "publish";
        XWikiDocument draft = document("Drafts.Article.WebHome");
        BaseObject flow = object("validating");
        when(draft.getObject(WorkflowPolicyListener.WF)).thenReturn(flow); audit(draft, "currentUser");
        doc = document("Knowledge.Technical.Article"); when(doc.getOriginalDocument()).thenReturn(old);
        DocumentReference draftReference = draft.getDocumentReference();
        policy.draftReference = draftReference;
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void canceledEventCannotBeSwallowedAsALoggedException() {
        states(null, "published"); DocumentUpdatingEvent event = new DocumentUpdatingEvent();
        policy.onEvent(event, doc, context); assertTrue(((CancelableEvent) event).isCanceled());
    }
    @Test void administratorKeepsRecoveryAccess() {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);
        states(null, "published"); assertDoesNotThrow(() -> policy.check(doc, context, true));
    }
    @Test void httpParametersDoNotCountAsNativeWorkflowExecution() {
        assertEquals("", new WorkflowPolicyListener().workflowOperation());
    }
    @Test void categoryViewRestrictionsAreEnforcedDuringSubmission() {
        policy.operation = "submitForValidation"; states("draft", "validating");
        when(policy.authorization.hasAccess(Right.VIEW, user, ref("Knowledge.Technical.Article"))).thenReturn(false);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCannotSpoofTheOriginalCreator() {
        when(old.getCreatorReference()).thenReturn(ref("XWiki.author"));
        when(doc.getCreatorReference()).thenReturn(user);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void legacyMetadataIsRemovedOnlyDuringAnEditorsOwnSave() throws Exception {
        String article = "= My article =\nMy body stays unchanged.";
        when(doc.getContent()).thenReturn(WorkflowPolicyListener.LEGACY_METADATA + article);
        policy.check(doc, context, false);
        verify(doc).setContent(article);
        verify(doc).setContentAuthorReference(user);
    }
    @Test void customVelocityIsNotRewrittenOrReauthored() throws Exception {
        when(doc.getContent()).thenReturn("{{velocity}}User-authored content{{/velocity}}");
        policy.check(doc, context, false);
        verify(doc, never()).setContent(anyString());
        verify(doc, never()).setContentAuthorReference(any());
    }
    @Test void nestedRoleMembershipHandlesCycles() throws Exception {
        var service = mock(XWikiGroupService.class);
        when(wiki.getGroupService(context)).thenReturn(service);
        when(service.getAllGroupsReferencesForMember(user, 0, 0, context)).thenReturn(List.of(ref("XWiki.Team")));
        when(service.getAllGroupsReferencesForMember(ref("XWiki.Team"), 0, 0, context)).thenReturn(List.of(ref("XWiki.LuxEditors")));
        when(service.getAllGroupsReferencesForMember(ref("XWiki.LuxEditors"), 0, 0, context)).thenReturn(List.of(ref("XWiki.Team")));
        assertTrue(new WorkflowPolicyListener().groups(user, context).contains("xwiki:XWiki.LuxEditors"));
    }
    @Test void publicationLookupIsBoundToTheTargetWikiAndBothReferenceFormats() throws Exception {
        var listener = new WorkflowPolicyListener();
        listener.queries = mock(QueryManager.class);
        listener.localSerializer = mock(EntityReferenceSerializer.class);
        listener.resolver = policy.resolver;
        Query query = mock(Query.class, RETURNS_SELF);
        when(listener.queries.createQuery(anyString(), eq(Query.XWQL))).thenReturn(query);
        when(listener.localSerializer.serialize(ref("Knowledge.Technical.Article"))).thenReturn("Knowledge.Technical.Article");
        when(query.execute()).thenReturn(List.of("Drafts.Article.WebHome"));
        assertEquals(ref("Drafts.Article.WebHome"), listener.findDraft(ref("Knowledge.Technical.Article"), context));
        verify(query).setWiki("xwiki");
        verify(query).bindValue("full", "xwiki:Knowledge.Technical.Article");
        when(query.execute()).thenReturn(List.of("Drafts.One", "Drafts.Two"));
        assertThrows(SecurityException.class, () -> listener.findDraft(ref("Knowledge.Technical.Article"), context));
    }
    @Test void historicalRightsRepairRequiresAdministrator() {
        assertThrows(SecurityException.class, () -> policy.repairExistingDraftRights(context));
    }
    @Test void historicalRightsRepairPreservesContentAuthor() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);
        policy.queries = mock(QueryManager.class);
        Query query = mock(Query.class, RETURNS_SELF);
        when(policy.queries.createQuery(anyString(), eq(Query.XWQL))).thenReturn(query);
        when(query.execute()).thenReturn(List.of("Drafts.Article.WebHome"));
        when(policy.resolver.resolve("Drafts.Article.WebHome")).thenReturn(ref("Drafts.Article.WebHome"));
        when(old.clone()).thenReturn(doc);
        when(old.getContentAuthorReference()).thenReturn(ref("XWiki.originalAuthor"));
        states("validating", "validating");
        assertEquals(1, policy.repairExistingDraftRights(context));
        verify(doc).setContentAuthorReference(ref("XWiki.originalAuthor"));
        verify(doc, never()).setContent(anyString());
        verify(wiki).saveDocument(doc, "Normalize LuxPedia workflow role permissions", true, context);
    }
}
