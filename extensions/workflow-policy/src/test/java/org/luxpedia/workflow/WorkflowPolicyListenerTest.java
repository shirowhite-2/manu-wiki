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
import org.xwiki.model.reference.WikiReference;
import org.xwiki.model.internal.reference.ExplicitStringDocumentReferenceResolver;
import org.xwiki.model.internal.reference.ExplicitStringEntityReferenceResolver;
import org.xwiki.model.internal.reference.AbstractStringEntityReferenceResolver;
import org.xwiki.model.internal.reference.DefaultSymbolScheme;
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
    DocumentReferenceResolver<String> realResolver() throws Exception {
        var entities = new ExplicitStringEntityReferenceResolver();
        var scheme = AbstractStringEntityReferenceResolver.class.getDeclaredField("symbolScheme");
        scheme.setAccessible(true);
        scheme.set(entities, new DefaultSymbolScheme());
        entities.initialize();
        var documents = new ExplicitStringDocumentReferenceResolver();
        var field = ExplicitStringDocumentReferenceResolver.class.getDeclaredField("entityReferenceResolver");
        field.setAccessible(true);
        field.set(documents, entities);
        return documents;
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
        when(audit.getStringValue("authoredBy")).thenReturn(ref("XWiki.contentAuthor").toString());
        when(article.getObject(WorkflowPolicyListener.AUDIT)).thenReturn(audit);
        when(article.getCreatorReference()).thenReturn(ref("XWiki.originalAuthor"));
    }
    void changedClass(String name) throws Exception {
        ObjectDiff diff = mock(ObjectDiff.class);
        when(diff.getXClassReference()).thenReturn(ref(name));
        when(doc.getObjectDiff(old, doc, context)).thenReturn(List.of(List.of(diff)));
    }
    @org.junit.jupiter.api.AfterEach void clearLeaseScope() { EditLease.OPENING.remove(); }
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
        when(old.getContentAuthorReference()).thenReturn(ref("XWiki.contentAuthor"));
        when(doc.getOriginalDocument()).thenReturn(old);
        group("Editors");
        EditLease.OPENING.set(true);
        var request = mock(com.xpn.xwiki.web.XWikiRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader("X-LuxPedia-Edit-Lease")).thenReturn("lease-fixture");
        BaseObject lease = mock(BaseObject.class);
        when(lease.getStringValue("owner")).thenReturn(user.toString());
        when(lease.getStringValue("generation")).thenReturn("lease-fixture");
        when(lease.getStringValue("expiresAt")).thenReturn(Long.toString(System.currentTimeMillis() + EditLease.DURATION));
        when(old.getObject(EditLease.CLASS)).thenReturn(lease);
        when(doc.getObject(EditLease.CLASS)).thenReturn(lease);
    }
    void profile(String account) throws Exception {
        old = document("XWiki." + account); doc = document("XWiki." + account);
        when(doc.getOriginalDocument()).thenReturn(old);
        when(doc.getObject("XWiki.XWikiUsers")).thenReturn(mock(BaseObject.class));
        when(old.getObject("XWiki.XWikiUsers")).thenReturn(mock(BaseObject.class));
    }
    @Test void ownProfileCanBeEditedButItsSecurityObjectsCannot() throws Exception {
        profile("currentUser");
        assertDoesNotThrow(() -> policy.check(doc, context, false));
        assertEquals(2, rights.size());
        changedClass("XWiki.XWikiRights");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        changedClass("XWiki.XWikiGroups");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void otherProfilesAndSelfCreationAndDirectorySavesAreAdminOnly() throws Exception {
        profile("someoneElse");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        profile("currentUser"); when(old.isNew()).thenReturn(true);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        for (String reference : List.of("XWiki.WebHome", "XWiki.Users.Editors.WebHome")) {
            doc = document(reference); when(doc.getOriginalDocument()).thenReturn(old);
            assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        }
    }
    @Test void onlyScopedPersonalFavoritesSavesCanChangeFavoritesObjectsOnTheActingProfile() throws Exception {
        profile("currentUser"); changedClass(PersonalFavorites.CLASS);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        PersonalFavorites.SAVING.set(user);
        try {
            assertDoesNotThrow(() -> policy.check(doc, context, false));
            changedClass("XWiki.XWikiRights");
            assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
            changedClass(PersonalFavorites.CLASS);
            when(doc.getContent()).thenReturn("Unexpected body change");
            assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
            profile("someoneElse"); changedClass(PersonalFavorites.CLASS);
            assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        } finally { PersonalFavorites.SAVING.remove(); }
    }
    @Test void adminCreatedProfilesReceivePrivacyAndRoleGroupsCannotBeEditedByMembers() throws Exception {
        profile("newAccount"); when(old.isNew()).thenReturn(true);
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        assertDoesNotThrow(() -> policy.check(doc, context, false)); assertEquals(2, rights.size());
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        doc = document("XWiki.Engineers"); when(doc.getOriginalDocument()).thenReturn(old);
        when(doc.getObject("XWiki.XWikiGroups")).thenReturn(mock(BaseObject.class));
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCanWriteAnUnsubmittedArticle() throws Exception {
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void editorCanEditTheWritingAreaHomeContent() throws Exception {
        when(doc.getDocumentReference()).thenReturn(ref("Drafts.WebHome"));
        when(policy.authorization.hasAccess(Right.EDIT, user, ref("Drafts.WebHome"))).thenReturn(true);
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void editorCannotTurnTheWritingAreaHomeIntoAnArticleSubmission() throws Exception {
        when(doc.getDocumentReference()).thenReturn(ref("Drafts.WebHome"));
        states(null, "draft"); policy.operation = "startWorkflow";
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorCanCreateANestedDraftWithCoreEditAccess() throws Exception {
        when(old.isNew()).thenReturn(true);
        when(doc.getObject(EditLease.CLASS)).thenReturn(null);
        when(policy.authorization.hasAccess(Right.EDIT, user, doc.getDocumentReference())).thenReturn(true);
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void editorCanWriteADraft() throws Exception {
        states("draft", "draft"); assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void submittedContentIsLockedForEditor() {
        states("validating", "validating"); assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void submitterCanWithdrawPendingRequestAndRestoreEditing() throws Exception {
        states("validating", "draft"); audit(old, "currentUser"); policy.operation = "withdrawSubmission";
        assertDoesNotThrow(() -> policy.check(doc, context, false));
        verify(doc).setHidden(false);
        verify(doc).removeObjects("XWiki.XWikiRights");
    }
    @Test void anotherEditorCannotWithdrawSomeoneElsesRequest() throws Exception {
        states("validating", "draft"); audit(old, "anotherEditor"); policy.operation = "withdrawSubmission";
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void reviewerCannotUseWithdrawalAsAReviewDecision() throws Exception {
        group("Reviewers"); states("validating", "draft"); audit(old, "currentUser");
        assertFalse(policy.canWithdraw(old, context));
    }
    @Test void approvedOrPublishedRequestsCannotBeWithdrawn() throws Exception {
        audit(old, "currentUser");
        for (String state : List.of("draft", "valid", "published", "archived")) {
            states(state, "draft"); assertFalse(policy.canWithdraw(old, context));
        }
    }
    @Test void invisibleDraftCannotBeWithdrawn() throws Exception {
        states("validating", "draft"); audit(old, "currentUser");
        when(policy.authorization.hasAccess(Right.VIEW, user, old.getDocumentReference())).thenReturn(false);
        assertFalse(policy.canWithdraw(old, context));
    }
    @Test void publicationHidesWorkingCopyAndReopeningRestoresIt() throws Exception {
        group("Reviewers"); states("validating", "published"); audit(old, "otherEditor"); policy.operation = "publish";
        policy.check(doc, context, false); verify(doc).setHidden(true);
        reset(doc); when(doc.getDocumentReference()).thenReturn(ref("Drafts.Article.WebHome"));
        when(doc.getOriginalDocument()).thenReturn(old);
        when(doc.newXObject(any(DocumentReference.class), eq(context))).thenReturn(mock(BaseObject.class));
        group("Editors"); states("published", "draft"); policy.operation = "editDraft";
        policy.check(doc, context, false); verify(doc).setHidden(false);
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
    @Test void workflowCannotOverwriteCategoryHome() throws Exception {
        policy.operation = "startWorkflow"; states(null, "draft");
        XWikiDocument category = document("Knowledge.Technical.WebHome");
        when(category.getObject("LuxPedia.Code.CategoryClass")).thenReturn(mock(BaseObject.class));
        when(doc.getObject(WorkflowPolicyListener.WF).getStringValue("target")).thenReturn("Knowledge.Technical.WebHome");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void editorsCannotEditOrReviewAnExistingCategoryLandingDraft() throws Exception {
        var category = document("Knowledge.BOP-ACTIVE.WebHome");
        when(category.getObject(WorkflowPolicyListener.CATEGORY)).thenReturn(mock(BaseObject.class));
        var previous = object("draft"); var current = object("draft");
        when(previous.getStringValue("target")).thenReturn("Knowledge.BOP-ACTIVE.WebHome");
        when(current.getStringValue("target")).thenReturn("Knowledge.BOP-ACTIVE.WebHome");
        when(old.getObject(WorkflowPolicyListener.WF)).thenReturn(previous);
        when(doc.getObject(WorkflowPolicyListener.WF)).thenReturn(current);

        var error = assertThrows(SecurityException.class, () -> policy.check(doc, context, false));

        assertTrue(error.getMessage().contains("Category home pages are administrator-managed"));
    }
    @Test void categoryLandingDraftRightsHideTheCopyFromEditorsAndReviewers() throws Exception {
        var category = document("Knowledge.BOP-ACTIVE.WebHome");
        when(category.getObject(WorkflowPolicyListener.CATEGORY)).thenReturn(mock(BaseObject.class));
        var flow = object("draft"); when(flow.getStringValue("target")).thenReturn("Knowledge.BOP-ACTIVE.WebHome");
        when(doc.getObject(WorkflowPolicyListener.WF)).thenReturn(flow);

        policy.normalizeDraftRights(doc, "draft", context);

        assertTrue(rights.stream().anyMatch(rule -> {
            var calls = mockingDetails(rule).getInvocations();
            return calls.stream().anyMatch(call -> call.getMethod().getName().equals("set") &&
                call.getArgument(0).equals("groups") && call.getArgument(1).equals("XWiki.LuxEditors,XWiki.LuxReviewers")) &&
                calls.stream().anyMatch(call -> call.getMethod().getName().equals("set") &&
                    call.getArgument(0).equals("levels") && call.getArgument(1).equals("view,comment,edit,delete")) &&
                calls.stream().anyMatch(call -> call.getMethod().getName().equals("set") &&
                    call.getArgument(0).equals("allow") && call.getArgument(1).equals(0));
        }));
    }
    @Test void ordinaryWebHomeArticleDoesNotNeedArticleClassToSupportReviewedChanges() throws Exception {
        document("Knowledge.test.WebHome");
        assertTrue(policy.validTarget(ref("Knowledge.test.WebHome"), context));
    }
    @Test void editorCanCreateALinkedDraftForAnOrdinaryKnowledgeWebHome() throws Exception {
        policy.operation = "createDraftDocument"; states(null, "draft");
        when(old.isNew()).thenReturn(true);
        when(doc.getObject(WorkflowPolicyListener.WF).getStringValue("target")).thenReturn("Knowledge.test.WebHome");
        XWikiDocument article = document("Knowledge.test.WebHome");
        BaseObject published = object("published");
        when(published.getIntValue("istarget")).thenReturn(1);
        when(article.getObject(WorkflowPolicyListener.WF)).thenReturn(published);
        assertDoesNotThrow(() -> policy.check(doc, context, false));
    }
    @Test void aNestedArticleCanShareASystemIndexName() throws Exception {
        document("Knowledge.Technical.CategoryGuide");
        assertTrue(policy.validTarget(ref("Knowledge.Technical.CategoryGuide"), context));
    }
    @Test void spacePreferencesAndTheCategoryManagementActionRemainProtected() throws Exception {
        for (String name : List.of("Knowledge.CategoryAdmin", "Knowledge.WebPreferences", "Knowledge.Technical.WebPreferences")) {
            document(name); assertFalse(policy.validTarget(ref(name), context), name);
        }
    }
    @Test void knowledgeIndexesAndOrdinaryNestedPagesCanUseLinkedDrafts() throws Exception {
        for (String name : List.of("Knowledge.WebHome", "Knowledge.DocumentLibrary", "Knowledge.CategoryIndex", "Knowledge.Catalog.WebHome")) {
            policy.operation = "createDraftDocument"; states(null, "draft");
            when(old.isNew()).thenReturn(true);
            when(doc.getObject(WorkflowPolicyListener.WF).getStringValue("target")).thenReturn(name);
            document(name);
            assertDoesNotThrow(() -> policy.check(doc, context, false), name);
        }
    }
    @Test void categoryDefinitionObjectsCannotBeChangedByEditingTheirDraftBody() throws Exception {
        states("draft", "draft"); changedClass("LuxPedia.Code.CategoryClass");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void newAdministratorImportedContentIsRegisteredForEditorChanges() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);
        doc = document("Knowledge.Imported.Report"); old = document("Knowledge.Imported.Report");
        when(doc.getOriginalDocument()).thenReturn(old); when(old.isNew()).thenReturn(true);
        policy.localSerializer = mock(EntityReferenceSerializer.class);
        when(policy.localSerializer.serialize(doc.getDocumentReference())).thenReturn("Knowledge.Imported.Report");
        assertDoesNotThrow(() -> policy.check(doc, context, false));
        verify(doc).newXObject(ref(WorkflowPolicyListener.WF), context);
        verify(doc, never()).setContent(anyString());
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
    @Test void deletingArticlesIsCanceledForEveryOrdinaryRoleIncludingTheCreator() throws Exception {
        for (String role : List.of("Editors", "Reviewers", "Readers")) {
            group(role);
            for (String reference : List.of("Drafts.Article.WebHome", "Knowledge.Article.WebHome")) {
                doc = document(reference); when(doc.getOriginalDocument()).thenReturn(old);
                when(doc.getCreatorReference()).thenReturn(user);
                var event = new org.xwiki.bridge.event.DocumentDeletingEvent();
                policy.onEvent(event, doc, context);
                assertTrue(event.isCanceled(), role + " cannot delete " + reference);
            }
        }
    }
    @Test void administratorCanDeleteNestedDraftHomeFromBackgroundRefactoringJob() throws Exception {
        doc = document("Drafts.BOP-ACTIVE.WebHome");
        old = document("Drafts.BOP-ACTIVE.WebHome");
        when(doc.getOriginalDocument()).thenReturn(old);
        when(old.isNew()).thenReturn(false); when(old.getVersion()).thenReturn("2.1");
        var latest = document("Drafts.BOP-ACTIVE.WebHome");
        when(latest.isNew()).thenReturn(false); when(latest.getVersion()).thenReturn("3.1");
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);

        var event = new org.xwiki.bridge.event.DocumentDeletingEvent();
        policy.onEvent(event, doc, context);
        assertFalse(event.isCanceled(), "Async native deletion must not require a request write boundary or update baseline.");

        try (var boundary = DraftWriteBoundary.open()) {
            var serializedEvent = new org.xwiki.bridge.event.DocumentDeletingEvent();
            policy.onEvent(serializedEvent, doc, context);
            assertFalse(serializedEvent.isCanceled(), "A stale update baseline must not block an administrator's delete job.");
        }
    }
    @Test void ordinaryUsersStillCannotDeleteWhenTheDraftWriteBoundaryIsAvailable() throws Exception {
        doc = document("Drafts.BOP-ACTIVE.WebHome");
        old = document("Drafts.BOP-ACTIVE.WebHome");
        when(doc.getOriginalDocument()).thenReturn(old);
        when(old.isNew()).thenReturn(false);
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);

        try (var boundary = DraftWriteBoundary.open()) {
            var event = new org.xwiki.bridge.event.DocumentDeletingEvent();
            policy.onEvent(event, doc, context);
            assertTrue(event.isCanceled(), "The administrator delete exception must not grant ordinary delete rights.");
        }
    }
    @Test void everyDraftStateExplicitlyDeniesDeleteToEditorsAndReviewers() throws Exception {
        for (String state : List.of("draft", "validating", "valid", "published", "archived")) {
            rights.clear();
            policy.normalizeDraftRights(doc, state, context);
            assertTrue(rights.stream().anyMatch(rule -> {
                var calls = mockingDetails(rule).getInvocations();
                return calls.stream().anyMatch(call -> call.getMethod().getName().equals("set") &&
                    call.getArgument(0).equals("levels") && call.getArgument(1).equals("delete")) &&
                    calls.stream().anyMatch(call -> call.getMethod().getName().equals("set") &&
                    call.getArgument(0).equals("groups") && call.getArgument(1).equals("XWiki.LuxEditors,XWiki.LuxReviewers")) &&
                    calls.stream().anyMatch(call -> call.getMethod().getName().equals("set") &&
                    call.getArgument(0).equals("allow") && call.getArgument(1).equals(0));
            }), "Missing explicit DELETE denial for " + state);
        }
    }
    @Test void abandonedDraftsCannotBeEditedSubmittedOrForgedThroughRest() throws Exception {
        states("draft", "draft");
        BaseObject workspace = mock(BaseObject.class);
        when(workspace.getStringValue("status")).thenReturn("abandoned");
        when(old.getObject(DraftWorkspace.CLASS)).thenReturn(workspace);
        for (String operation : List.of("", "submitForValidation", "submitForModeration")) {
            policy.operation = operation;
            assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        }
        when(old.getObject(DraftWorkspace.CLASS)).thenReturn(null);
        policy.operation = ""; changedClass(DraftWorkspace.CLASS);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void trustedAbandonmentLocksTheDraftAndTrustedRestorationUnlocksIt() throws Exception {
        states("draft", "draft");
        BaseObject previous = mock(BaseObject.class), next = mock(BaseObject.class);
        when(previous.getStringValue("owner")).thenReturn(user.toString());
        when(previous.getStringValue("shared")).thenReturn("0");
        when(previous.getStringValue("status")).thenReturn("active");
        when(next.getStringValue("status")).thenReturn("abandoned");
        when(old.getObject(DraftWorkspace.CLASS)).thenReturn(previous);
        when(doc.getObject(DraftWorkspace.CLASS)).thenReturn(next);
        when(policy.authorization.hasAccess(eq(Right.EDIT), any(), any())).thenReturn(true);
        policy.operation = "abandonDraft";
        policy.check(doc, context, false); verify(doc).setHidden(true);
        when(previous.getStringValue("status")).thenReturn("abandoned");
        when(next.getStringValue("status")).thenReturn("active");
        when(policy.authorization.hasAccess(eq(Right.EDIT), any(), any())).thenReturn(false);
        policy.operation = "restoreDraft";
        policy.check(doc, context, false); verify(doc).setHidden(false);
    }
    @Test void administratorKeepsRecoveryAccess() {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);
        states(null, "published"); assertDoesNotThrow(() -> policy.check(doc, context, true));
    }
    @Test void administratorPublicationAlsoHidesTheWorkingCopy() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);
        states("validating", "published"); policy.operation = "publish";
        policy.check(doc, context, false); verify(doc).setHidden(true);
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
    @Test void knowledgeRegistrationRequiresAdministrator() {
        assertThrows(SecurityException.class, () -> policy.registerExistingKnowledgeTargets(context));
    }
    @Test void legacyRegistrationCoversArticlesButSkipsCategoryLandingPages() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);
        policy.resolver = realResolver();
        policy.localSerializer = mock(EntityReferenceSerializer.class);
        when(policy.localSerializer.serialize(any())).thenAnswer(call -> call.getArgument(0).toString().replace("xwiki:", ""));
        policy.queries = mock(QueryManager.class); Query query = mock(Query.class, RETURNS_SELF);
        when(policy.queries.createQuery(anyString(), eq(Query.XWQL))).thenReturn(query);
        when(query.execute()).thenReturn(List.of("Knowledge.Article", "Knowledge.Category.Nested.Article", "Knowledge.test.WebHome",
            "Knowledge.BOP-ACTIVE.WebHome", "Knowledge.CategoryAdmin"));
        for (String name : List.of("Knowledge.Article", "Knowledge.Category.Nested.Article", "Knowledge.test.WebHome")) {
            XWikiDocument original = document(name), registered = document(name);
            documents.put(ref(name), original); when(original.clone()).thenReturn(registered);
            when(original.getContentAuthorReference()).thenReturn(ref("XWiki.author"));
        }
        XWikiDocument root = documents.get(ref("Knowledge.Article"));
        XWikiDocument nested = documents.get(ref("Knowledge.Category.Nested.Article"));
        XWikiDocument plain = documents.get(ref("Knowledge.test.WebHome"));
        XWikiDocument category = document("Knowledge.BOP-ACTIVE.WebHome");
        when(category.getObject(WorkflowPolicyListener.CATEGORY)).thenReturn(mock(BaseObject.class));
        assertEquals(3, policy.registerExistingKnowledgeTargets(context));
        for (XWikiDocument original : List.of(root, nested, plain)) {
            XWikiDocument saved = original.clone();
            verify(saved).setContentAuthorReference(ref("XWiki.author"));
            verify(saved, never()).setContent(anyString());
            verify(wiki).saveDocument(saved, "Register existing article for reviewed changes", true, context);
        }
        verify(wiki, never()).saveDocument(eq(category), eq("Register existing article for reviewed changes"), eq(true), eq(context));
    }
    @Test void historicalRightsRepairPreservesContentAuthor() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);
        policy.queries = mock(QueryManager.class);
        Query query = mock(Query.class, RETURNS_SELF);
        when(policy.queries.createQuery(anyString(), eq(Query.XWQL))).thenReturn(query);
        when(query.execute()).thenReturn(List.of("Drafts.Article.WebHome"));
        policy.resolver = realResolver();
        when(old.clone()).thenReturn(doc);
        when(old.getContentAuthorReference()).thenReturn(ref("XWiki.originalAuthor"));
        states("validating", "validating");
        try (var ignored = DraftWriteBoundary.open()) {
            assertEquals(1, policy.repairExistingDraftRights(context));
        }
        verify(doc).setContentAuthorReference(ref("XWiki.originalAuthor"));
        verify(doc, never()).setContent(anyString());
        verify(wiki).saveDocument(doc, "Normalize LuxPedia workflow role permissions", true, context);
    }
    @Test void latestContentAuthorCannotReviewEvenWhenSomeoneElseSubmits() throws Exception {
        group("Reviewers"); policy.operation = "validate"; states("validating", "valid"); audit(old, "anotherEditor");
        when(old.getObject(WorkflowPolicyListener.AUDIT).getStringValue("authoredBy")).thenReturn(user.toString());
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void explicitResolverRequiresWikiForUnprefixedQueryResults() throws Exception {
        var resolver = realResolver();
        var failure = assertThrows(IllegalArgumentException.class, () -> resolver.resolve("Drafts.Article.WebHome"));
        assertTrue(failure.getMessage().contains("[WIKI]"));
        assertEquals(new DocumentReference("secondary", List.of("Drafts", "Part.One"), "WebHome"),
            resolver.resolve("Drafts.Part\\.One.WebHome", new WikiReference("secondary")));
    }
    @Test void rightsMigrationUsesActiveWikiAndRealReferenceResolution() throws Exception {
        when(context.getWikiId()).thenReturn("secondary");
        when(policy.authorization.hasAccess(eq(Right.ADMIN), eq(user), any())).thenReturn(true);
        policy.resolver = realResolver();
        policy.queries = mock(QueryManager.class);
        Query query = mock(Query.class, RETURNS_SELF);
        when(policy.queries.createQuery(anyString(), eq(Query.XWQL))).thenReturn(query);
        when(query.execute()).thenReturn(List.of("Drafts.Part\\.One.WebHome"));
        DocumentReference expected = new DocumentReference("secondary", List.of("Drafts", "Part.One"), "WebHome");
        XWikiDocument original = mock(XWikiDocument.class), draft = mock(XWikiDocument.class);
        when(wiki.getDocument(expected, context)).thenReturn(original);
        when(original.clone()).thenReturn(draft);
        when(draft.getDocumentReference()).thenReturn(expected);
        BaseObject flow = object("draft");
        when(draft.getObject(WorkflowPolicyListener.WF)).thenReturn(flow);
        when(draft.newXObject(any(DocumentReference.class), eq(context))).thenAnswer(invocation -> mock(BaseObject.class));
        try (var ignored = DraftWriteBoundary.open()) {
            assertEquals(1, policy.repairExistingDraftRights(context));
        }
        verify(query).setWiki("secondary");
        verify(wiki).getDocument(expected, context);
        verify(draft, atLeastOnce()).newXObject(new DocumentReference("secondary", "XWiki", "XWikiRights"), context);
    }
    @Test void expiredOrForeignLeasesFenceOrdinarySavesAndWorkflowSubmission() throws Exception {
        states("draft", "draft");
        BaseObject lease = old.getObject(EditLease.CLASS);
        when(lease.getStringValue("expiresAt")).thenReturn("1");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        policy.operation = "submitForValidation"; states("draft", "validating");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        when(lease.getStringValue("expiresAt")).thenReturn(Long.toString(System.currentTimeMillis() + EditLease.DURATION));
        when(lease.getStringValue("owner")).thenReturn(ref("XWiki.someoneElse").toString());
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void anAdministratorMustAcquireTheLeaseBeforeChangingSomeoneElsesDraftContent() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        states("draft", "draft");
        when(old.getContent()).thenReturn("saved"); when(doc.getContent()).thenReturn("changed");
        when(old.getObject(EditLease.CLASS).getStringValue("owner")).thenReturn(ref("XWiki.someoneElse").toString());
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }
    @Test void clientSuppliedLeaseObjectsAndUnprotectedNativeReopenCannotBypassOwnership() throws Exception {
        states("draft", "draft"); changedClass(EditLease.CLASS);
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
        EditLease.OPENING.remove(); policy.operation = "editDraft"; states("published", "draft");
        assertThrows(SecurityException.class, () -> policy.check(doc, context, false));
    }

    @Test void aWriterLoadedBeforeTakeoverCannotCommitAfterTheDocumentVersionChanges() throws Exception {
        states("draft", "draft"); when(old.getVersion()).thenReturn("4.2");
        var latest = document("Drafts.Article.WebHome"); when(latest.getVersion()).thenReturn("4.3");
        try (var boundary = DraftWriteBoundary.open()) {
            var event = new DocumentUpdatingEvent(); policy.onEvent(event, doc, context);
            assertTrue(event.isCanceled());
        }
    }
    @Test void aNewTranslationUsesTheDefaultDraftLeaseWithoutCreatingASecondLease() throws Exception {
        when(old.isNew()).thenReturn(true);
        when(doc.getTranslation()).thenReturn(1); when(doc.getLocale()).thenReturn(java.util.Locale.CHINESE);
        var base = document("Drafts.Article.WebHome");
        when(base.getTranslatedDocument(java.util.Locale.CHINESE, context)).thenReturn(base);
        BaseObject baseFlow = object("draft"), baseLease = old.getObject(EditLease.CLASS);
        when(base.getObject(WorkflowPolicyListener.WF)).thenReturn(baseFlow);
        when(base.getObject(EditLease.CLASS)).thenReturn(baseLease);
        try (var boundary = DraftWriteBoundary.open()) {
            var event = new org.xwiki.bridge.event.DocumentCreatingEvent(); policy.onEvent(event, doc, context);
            assertFalse(event.isCanceled());
            verify(doc, never()).newXObject(eq(ref(EditLease.CLASS)), eq(context));
        }
    }

}
