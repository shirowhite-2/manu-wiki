package org.luxpedia.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.web.XWikiRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DraftWorkspaceTest {
    WorkflowPolicyListenerTest.Subject policy;
    WorkflowPolicyScriptService service;
    XWikiContext context;
    XWiki wiki;
    XWikiRequest request;
    XWikiDocument original, draft;
    DocumentReference user = new DocumentReference("xwiki", "XWiki", "owner");
    DocumentReference reference = new DocumentReference("xwiki", List.of("Drafts", "Article"), "WebHome");
    BaseObject workspace, nextWorkspace, flow;

    BaseObject object(Map<String, String> values) throws Exception {
        BaseObject object = spy(new BaseObject());
        values.forEach(object::setStringValue);
        doAnswer(call -> { object.setStringValue(call.getArgument(0), call.getArgument(1)); return null; })
            .when(object).set(anyString(), anyString(), any(XWikiContext.class));
        return object;
    }
    @BeforeEach void setup() throws Exception {
        policy = new WorkflowPolicyListenerTest.Subject();
        policy.resolver = new WorkflowPolicyListenerTest().realResolver();
        policy.authorization = mock(AuthorizationManager.class);
        policy.roleGroups = Set.of("xwiki:XWiki.LuxEditors");
        when(policy.authorization.hasAccess(eq(Right.VIEW), any(), any())).thenReturn(true);
        when(policy.authorization.hasAccess(eq(Right.EDIT), any(), any())).thenReturn(true);
        context = mock(XWikiContext.class); wiki = mock(XWiki.class); request = mock(XWikiRequest.class);
        when(context.getUserReference()).thenReturn(user); when(context.getWikiId()).thenReturn("xwiki");
        when(context.getWiki()).thenReturn(wiki); when(context.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn("POST");
        workspace = object(Map.of("owner", user.toString(), "shared", "0", "status", "active"));
        nextWorkspace = object(Map.of("owner", user.toString(), "shared", "0", "status", "active"));
        flow = new BaseObject(); flow.setStringValue("status", "draft");
        original = mock(XWikiDocument.class); draft = mock(XWikiDocument.class);
        for (XWikiDocument doc : List.of(original, draft)) {
            when(doc.getDocumentReference()).thenReturn(reference);
            when(doc.getObject(WorkflowPolicyListener.WF)).thenReturn(flow);
            when(doc.getCreatorReference()).thenReturn(user);
        }
        when(original.getObject(DraftWorkspace.CLASS)).thenReturn(workspace);
        when(draft.getObject(DraftWorkspace.CLASS)).thenReturn(nextWorkspace);
        when(original.getVersion()).thenReturn("4.2"); when(original.clone()).thenReturn(draft);
        when(original.getContentAuthorReference()).thenReturn(user);
        when(wiki.getDocument(reference, context)).thenReturn(original);
        when(draft.newXObject(any(DocumentReference.class), eq(context))).thenReturn(mock(BaseObject.class));
        service = new WorkflowPolicyScriptService(); service.listener = policy; service.authorization = policy.authorization;
        service.csrf = mock(CSRFToken.class); when(service.csrf.isTokenValid("valid")).thenReturn(true);
        service.execution = mock(Execution.class); var execution = new ExecutionContext();
        execution.setProperty("xwikicontext", context); when(service.execution.getContext()).thenReturn(execution);
    }
    @Test void ownerCanAbandonWithVersionPostAndCsrfWithoutTouchingContent() throws Exception {
        doAnswer(call -> {
            assertEquals("abandonDraft", new WorkflowPolicyListener().workflowOperation()); return null;
        }).when(wiki).saveDocument(eq(draft), anyString(), eq(true), eq(context));
        assertTrue(service.draftWorkspace("Drafts.Article.WebHome").contains("\"abandon\":true"));
        assertEquals("DRAFT_ABANDONED", service.manageDraft("Drafts.Article.WebHome", "4.2", "abandon", "valid"));
        assertEquals("abandoned", nextWorkspace.getStringValue("status"));
        verify(draft).setHidden(true); verify(draft).setContentAuthorReference(user);
        verify(draft, never()).setContent(anyString()); verify(draft, never()).setAttachmentList(any());
        assertEquals("", WorkflowPolicyScriptService.WORKSPACE_OPERATION.get());
    }
    @Test void anotherEditorAndSharedOrLegacyDraftRequireAnAdministratorToAbandon() throws Exception {
        when(context.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "other"));
        assertFalse(DraftWorkspace.allowed(policy, original, context, false));
        when(context.getUserReference()).thenReturn(user); workspace.setStringValue("shared", "1");
        assertFalse(DraftWorkspace.allowed(policy, original, context, false));
        when(original.getObject(DraftWorkspace.CLASS)).thenReturn(null);
        assertFalse(DraftWorkspace.allowed(policy, original, context, false));
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        assertTrue(DraftWorkspace.allowed(policy, original, context, false));
    }
    @Test void pendingAndPublishedDraftsCannotBeAbandonedEvenByAnAdministrator() throws Exception {
        when(policy.authorization.hasAccess(eq(Right.ADMIN), any(), any())).thenReturn(true);
        for (String state : List.of("validating", "moderating", "valid", "published", "archived")) {
            flow.setStringValue("status", state);
            assertEquals("WORKSPACE_FORBIDDEN", service.manageDraft("Drafts.Article.WebHome", "4.2", "abandon", "valid"));
        }
        verify(wiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }
    @Test void restoreReenablesPreservedWorkForAnEditorWithoutEditOnAnAbandonedPage() throws Exception {
        workspace.setStringValue("status", "abandoned"); nextWorkspace.setStringValue("status", "abandoned");
        when(context.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "other"));
        when(policy.authorization.hasAccess(eq(Right.EDIT), any(), any())).thenReturn(false);
        assertEquals("DRAFT_RESTORED", service.manageDraft("Drafts.Article.WebHome", "4.2", "restore", "valid"));
        verify(draft).setHidden(false); assertEquals("active", nextWorkspace.getStringValue("status"));
        assertEquals(user.toString(), nextWorkspace.getStringValue("owner"));
        verify(draft, never()).setContent(anyString());
    }
    @Test void staleVersionGetBadTokenAndUnknownOperationNeverSave() throws Exception {
        assertEquals("WORKSPACE_CONFLICT", service.manageDraft("Drafts.Article.WebHome", "4.1", "abandon", "valid"));
        assertEquals("POLICY_BAD_TOKEN", service.manageDraft("Drafts.Article.WebHome", "4.2", "abandon", "bad"));
        assertEquals("WORKSPACE_FORBIDDEN", service.manageDraft("Drafts.Article.WebHome", "4.2", "delete", "valid"));
        when(request.getMethod()).thenReturn("GET");
        assertEquals("POLICY_POST_REQUIRED", service.manageDraft("Drafts.Article.WebHome", "4.2", "abandon", "valid"));
        verify(wiki, never()).saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());
    }
    @Test void scopeAndViewAndBusinessRoleAreEnforced() throws Exception {
        for (String name : List.of("Knowledge.Article", "other:Drafts.Article", "Drafts.WebHome", "Drafts.WebPreferences")) {
            assertEquals("WORKSPACE_FORBIDDEN", service.manageDraft(name, "4.2", "abandon", "valid"));
        }
        when(policy.authorization.hasAccess(eq(Right.VIEW), any(), any())).thenReturn(false);
        assertFalse(service.draftWorkspace("Drafts.Article.WebHome").contains("4.2"));
        when(policy.authorization.hasAccess(eq(Right.VIEW), any(), any())).thenReturn(true);
        policy.roleGroups = Set.of("xwiki:XWiki.LuxReviewers");
        assertFalse(DraftWorkspace.allowed(policy, original, context, false));
    }
    @Test void ownershipTrackingNeverForgetsAnotherContributorsSavedChanges() throws Exception {
        DraftWorkspace.track(policy, draft, original, user, context, true);
        assertEquals("0", nextWorkspace.getStringValue("shared"));
        DraftWorkspace.track(policy, draft, original, new DocumentReference("xwiki", "XWiki", "other"), context, false);
        assertEquals("1", nextWorkspace.getStringValue("shared"));
        DraftWorkspace.track(policy, draft, original, user, context, false);
        assertEquals("1", nextWorkspace.getStringValue("shared"));
        DraftWorkspace.track(policy, draft, original, user, context, true);
        assertEquals("0", nextWorkspace.getStringValue("shared"));
    }
    @Test void savingFailureAlwaysClearsTheTrustedOperation() throws Exception {
        doThrow(new RuntimeException("storage failed")).when(wiki).saveDocument(eq(draft), anyString(), eq(true), eq(context));
        assertThrows(RuntimeException.class, () -> service.manageDraft("Drafts.Article.WebHome", "4.2", "abandon", "valid"));
        assertEquals("", WorkflowPolicyScriptService.WORKSPACE_OPERATION.get());
    }
}
