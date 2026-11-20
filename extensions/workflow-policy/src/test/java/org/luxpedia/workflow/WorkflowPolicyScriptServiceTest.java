package org.luxpedia.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.StringClass;
import com.xpn.xwiki.objects.BaseObject;
import org.xwiki.model.reference.DocumentReferenceResolver;
import com.xpn.xwiki.web.XWikiRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowPolicyScriptServiceTest {
    WorkflowPolicyScriptService service;
    XWikiContext context;
    XWiki wiki;
    XWikiRequest request;
    WorkflowPolicyListener listener;

    DraftWriteBoundary.Scope boundary;
    @org.junit.jupiter.api.AfterEach void endBoundary() { boundary.close(); }
    @BeforeEach void setup() {
        boundary = DraftWriteBoundary.open();
        service = new WorkflowPolicyScriptService();
        service.execution = mock(Execution.class);
        service.authorization = mock(AuthorizationManager.class);
        service.csrf = mock(CSRFToken.class);
        listener = mock(WorkflowPolicyListener.class); service.listener = listener;
        when(listener.getPolicyVersion()).thenReturn("luxpedia-workflow-policy-13");
        context = mock(XWikiContext.class); wiki = mock(XWiki.class); request = mock(XWikiRequest.class);
        when(context.getWiki()).thenReturn(wiki);
        when(context.getWikiId()).thenReturn("xwiki");
        when(context.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "DeploymentAdmin"));
        when(context.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn("POST");
        ExecutionContext executionContext = new ExecutionContext();
        executionContext.setProperty("xwikicontext", context);
        when(service.execution.getContext()).thenReturn(executionContext);
        when(service.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        when(service.csrf.isTokenValid("valid-token")).thenReturn(true);
    }

    @Test void administratorCanCheckComponentWithoutProgrammingRights() {
        assertEquals("luxpedia-workflow-policy-13", service.status());
        verify(service.authorization).hasAccess(eq(Right.ADMIN), any(),
            eq(new DocumentReference("xwiki", "XWiki", "XWikiPreferences")));
        verifyNoMoreInteractions(service.authorization);
    }
    @Test void nonAdministratorCannotCheckOrInitialize() throws Exception {
        when(service.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        assertEquals("POLICY_FORBIDDEN", service.status());
        assertEquals("POLICY_FORBIDDEN", service.initialize("valid-token"));
        verifyNoInteractions(wiki, listener);
    }
    @Test void getRequestCannotInitialize() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        assertEquals("POLICY_POST_REQUIRED", service.initialize("valid-token"));
        verifyNoInteractions(wiki, listener);
    }
    @Test void favoriteMutationRequiresPostAndCsrfBeforeAnyStorageAccess() {
        when(request.getMethod()).thenReturn("GET");
        assertEquals("post_required", service.manageFavorite("Knowledge.A", "add", "2.1", "valid-token").get("status"));
        when(request.getMethod()).thenReturn("POST");
        assertEquals("bad_token", service.manageFavorite("Knowledge.A", "add", "2.1", "wrong").get("status"));
        verifyNoInteractions(wiki, listener);
    }
    @Test void missingOrInvalidTokenCannotInitialize() throws Exception {
        assertEquals("POLICY_BAD_TOKEN", service.initialize(null));
        assertEquals("POLICY_BAD_TOKEN", service.initialize("wrong-token"));
        verifyNoInteractions(wiki, listener);
    }
    @Test void administratorInitializesOnlyFixedAuditFieldsThenRepairsRights() throws Exception {
        XWikiDocument original = mock(XWikiDocument.class), audit = mock(XWikiDocument.class);
        BaseClass schema = mock(BaseClass.class);
        when(wiki.getDocument(any(DocumentReference.class), eq(context))).thenReturn(original);
        when(original.clone()).thenReturn(audit);
        when(audit.getXClass()).thenReturn(schema);
        when(schema.addTextField(anyString(), anyString(), anyInt())).thenReturn(true);
        assertEquals("AUDIT_CLASS_READY", service.initialize("valid-token"));
        verify(schema).addTextField("submittedBy", "Submitted by", 60);
        verify(schema).addTextField("authoredBy", "Content authored by", 60);
        verify(schema).addTextField("submittedVersion", "Submitted version", 30);
        verify(wiki).saveDocument(audit, "Ensure review audit fields", true, context);
        verify(listener).repairExistingDraftRights(context);
        verify(listener).repairIdentityPrivacy(context);
    }
    @Test void existingAuditSchemaIsNotSavedAgain() throws Exception {
        XWikiDocument original = mock(XWikiDocument.class), audit = mock(XWikiDocument.class);
        BaseClass schema = mock(BaseClass.class);
        when(wiki.getDocument(any(DocumentReference.class), eq(context))).thenReturn(original);
        when(original.clone()).thenReturn(audit);
        when(audit.getXClass()).thenReturn(schema);
        when(schema.get(anyString())).thenReturn(new StringClass());
        assertEquals("AUDIT_CLASS_READY", service.initialize("valid-token"));
        verify(wiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any(XWikiContext.class));
        verify(listener).repairExistingDraftRights(context);
        verify(listener).repairIdentityPrivacy(context);
    }

    @Test void currentRolesUsesOnlyActingUserAndActualAdminAuthorization() throws Exception {
        when(service.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        when(listener.groups(context.getUserReference(), context)).thenReturn(java.util.Set.of(
            "xwiki:XWiki.LuxEditors", "xwiki:XWiki.LuxReviewers"));
        assertEquals("{\"editor\":true,\"reviewer\":true,\"admin\":false}", service.currentRoles());
        verify(listener).groups(context.getUserReference(), context);
        verifyNoInteractions(request);
        when(context.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "Admin"));
        when(listener.groups(context.getUserReference(), context)).thenReturn(java.util.Set.of());
        assertEquals("{\"editor\":false,\"reviewer\":false,\"admin\":false}", service.currentRoles());
    }
    @Test void guestHasNoRolesAndNonAdminDirectoryReadsExposeNothing() throws Exception {
        when(service.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(false);
        assertEquals(java.util.Map.of("allowed", false), service.users("all", "", "1"));
        when(context.getUserReference()).thenReturn(null);
        assertEquals("{\"editor\":false,\"reviewer\":false,\"admin\":false}", service.currentRoles());
        verifyNoInteractions(listener, wiki);
    }

    XWikiDocument pendingDraft(boolean allowed) throws Exception {
        DocumentReference ref = new DocumentReference("xwiki", "Drafts", "Article");
        listener.resolver = mock(DocumentReferenceResolver.class);
        when(listener.resolver.resolve(anyString(), any())).thenReturn(ref);
        XWikiDocument original = mock(XWikiDocument.class), draft = mock(XWikiDocument.class);
        when(wiki.getDocument(ref, context)).thenReturn(original);
        when(original.getVersion()).thenReturn("4.2"); when(original.clone()).thenReturn(draft);
        when(draft.getObject(WorkflowPolicyListener.WF)).thenReturn(mock(BaseObject.class));
        when(listener.canWithdraw(original, context)).thenReturn(allowed);
        return original;
    }
    @Test void withdrawalChecksTheActorAndVersionAndUsesTrustedServerExecution() throws Exception {
        XWikiDocument original = pendingDraft(true), draft = original.clone();
        when(original.getContentAuthorReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "ContentAuthor"));
        doAnswer(call -> {
            assertEquals("withdrawSubmission", new WorkflowPolicyListener().workflowOperation()); return null;
        }).when(wiki).saveDocument(eq(draft), anyString(), eq(true), eq(context));
        assertEquals("4.2", service.withdrawVersion("Drafts.Article"));
        assertEquals("WITHDRAWN", service.withdraw("Drafts.Article", "4.2", "valid-token"));
        verify(draft.getObject(WorkflowPolicyListener.WF)).set("status", "draft", context);
        verify(draft).setContentAuthorReference(original.getContentAuthorReference());
        verify(listener).normalizeDraftRights(draft, "draft", context);
        verify(draft).setHidden(false);
        verify(draft, never()).setContent(anyString());
    }
    @Test void withdrawalRejectsGetAndInvalidCsrfBeforeReadingDocuments() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        assertEquals("POLICY_POST_REQUIRED", service.withdraw("Drafts.Article", "4.2", "valid-token"));
        when(request.getMethod()).thenReturn("POST");
        assertEquals("POLICY_BAD_TOKEN", service.withdraw("Drafts.Article", "4.2", "wrong-token"));
        verifyNoInteractions(wiki, listener);
    }
    @Test void aStalePreviewCannotWithdrawANewerSubmission() throws Exception {
        pendingDraft(true);
        assertEquals("WITHDRAW_CONFLICT", service.withdraw("Drafts.Article", "3.1", "valid-token"));
        verify(wiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }
    @Test void unauthorizedActorGetsNoCapabilityAndCannotWithdraw() throws Exception {
        pendingDraft(false);
        assertEquals("", service.withdrawVersion("Drafts.Article"));
        assertEquals("WITHDRAW_FORBIDDEN", service.withdraw("Drafts.Article", "4.2", "valid-token"));
        verify(wiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }
    @Test void withdrawalCannotTargetKnowledgeOrAnotherWiki() throws Exception {
        pendingDraft(true);
        for (DocumentReference ref : java.util.List.of(new DocumentReference("xwiki", "Knowledge", "Article"),
                new DocumentReference("other", "Drafts", "Article"))) {
            when(listener.resolver.resolve(anyString(), any())).thenReturn(ref);
            assertEquals("WITHDRAW_FORBIDDEN", service.withdraw("ignored", "4.2", "valid-token"));
        }
        verify(wiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }
    @Test void leaseMutationsRejectGetAndInvalidCsrfBeforeReadingAnyDraft() {
        when(request.getMethod()).thenReturn("GET");
        assertEquals("post_required", service.manageEditLease("Drafts.Article", "claim", "valid-token").get("status"));
        when(request.getMethod()).thenReturn("POST");
        assertEquals("bad_token", service.manageEditLease("Drafts.Article", "claim", "wrong-token").get("status"));
        verifyNoInteractions(wiki);
    }

}
