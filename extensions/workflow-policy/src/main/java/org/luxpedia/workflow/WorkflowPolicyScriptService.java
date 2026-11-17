package org.luxpedia.workflow;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.EventListener;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;
import java.util.List;

/** Fixed administrator operations; callers never need Groovy/programming rights or a raw context. */
@Component
@Named("luxpediaWorkflowPolicy")
@Singleton
public class WorkflowPolicyScriptService implements ScriptService
{
    @Inject Execution execution;
    @Inject AuthorizationManager authorization;
    @Inject CSRFToken csrf;
    @Inject @Named("luxpedia-workflow-policy") EventListener listener;

    XWikiContext context() {
        return (XWikiContext) execution.getContext().getProperty("xwikicontext");
    }

    boolean administrator(XWikiContext context) {
        return context.getUserReference() != null && authorization.hasAccess(Right.ADMIN,
            context.getUserReference(), new DocumentReference(context.getWikiId(), "XWiki", "XWikiPreferences"));
    }

    public String status() {
        XWikiContext context = context();
        return administrator(context) ? ((WorkflowPolicyListener) listener).getPolicyVersion() : "POLICY_FORBIDDEN";
    }

    public String initialize(String token) throws Exception {
        XWikiContext context = context();
        if (!administrator(context)) { return "POLICY_FORBIDDEN"; }
        if (context.getRequest() == null || !"POST".equals(context.getRequest().getMethod())) {
            return "POLICY_POST_REQUIRED";
        }
        if (!csrf.isTokenValid(token)) { return "POLICY_BAD_TOKEN"; }
        DocumentReference reference = new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Code"), "ReviewAuditClass");
        XWikiDocument audit = context.getWiki().getDocument(reference, context).clone();
        BaseClass schema = audit.getXClass();
        boolean changed = false;
        if (schema.get("submittedBy") == null) { changed |= schema.addTextField("submittedBy", "Submitted by", 60); }
        if (schema.get("authoredBy") == null) { changed |= schema.addTextField("authoredBy", "Content authored by", 60); }
        if (schema.get("submittedVersion") == null) { changed |= schema.addTextField("submittedVersion", "Submitted version", 30); }
        if (changed) { context.getWiki().saveDocument(audit, "Ensure review audit fields", true, context); }
        ((WorkflowPolicyListener) listener).repairExistingDraftRights(context);
        return "AUDIT_CLASS_READY";
    }
}
