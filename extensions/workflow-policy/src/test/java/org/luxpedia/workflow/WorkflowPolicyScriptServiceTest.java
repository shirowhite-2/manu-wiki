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
import com.xpn.xwiki.web.XWikiRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowPolicyScriptServiceTest {
    WorkflowPolicyScriptService service;
    XWikiContext context;
    XWiki wiki;
    XWikiRequest request;
    WorkflowPolicyListener listener;

    @BeforeEach void setup() {
        service = new WorkflowPolicyScriptService();
        service.execution = mock(Execution.class);
        service.authorization = mock(AuthorizationManager.class);
        service.csrf = mock(CSRFToken.class);
        listener = mock(WorkflowPolicyListener.class); service.listener = listener;
        when(listener.getPolicyVersion()).thenReturn("luxpedia-workflow-policy-1");
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
        assertEquals("luxpedia-workflow-policy-1", service.status());
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
    }
}
