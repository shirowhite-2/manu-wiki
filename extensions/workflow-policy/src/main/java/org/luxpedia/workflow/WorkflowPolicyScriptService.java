package org.luxpedia.workflow;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.EventListener;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;
import java.util.List;

/** Fixed authorized workflow operations; callers never receive a raw context. */
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

    /** Only the acting user's flags, never an arbitrary user's memberships. */
    public String currentRoles() throws Exception {
        XWikiContext context = context();
        boolean admin = administrator(context);
        var groups = context.getUserReference() == null ? java.util.Set.<String>of() :
            ((WorkflowPolicyListener) listener).groups(context.getUserReference(), context);
        String prefix = context.getWikiId() + ":XWiki.Lux";
        return "{\"editor\":" + groups.contains(prefix + "Editors") + ",\"reviewer\":" +
            groups.contains(prefix + "Reviewers") + ",\"admin\":" + admin + "}";
    }

    /** Paginated private directory. Authorization is independent of the page ACL. */
    public java.util.Map<String, Object> users(String role, String search, String page) throws Exception {
        XWikiContext context = context();
        if (!administrator(context)) { return java.util.Map.of("allowed", false); }
        return new IdentityDirectory((WorkflowPolicyListener) listener, context).users(role, search, page);
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
        ((WorkflowPolicyListener) listener).registerExistingKnowledgeTargets(context);
        ((WorkflowPolicyListener) listener).repairIdentityPrivacy(context);
        return "AUDIT_CLASS_READY";
    }

    XWikiDocument withdrawalDraft(String name, XWikiContext context) throws Exception {
        if (name == null || name.isBlank() || context.getUserReference() == null) { return null; }
        var policy = (WorkflowPolicyListener) listener;
        DocumentReference ref = policy.resolver.resolve(name, new WikiReference(context.getWikiId()));
        if (!ref.getWikiReference().getName().equals(context.getWikiId()) ||
            !ref.getSpaceReferences().get(0).getName().equals("Drafts")) { return null; }
        XWikiDocument draft = context.getWiki().getDocument(ref, context);
        return policy.canWithdraw(draft, context) ? draft : null;
    }

    public String withdrawVersion(String name) throws Exception {
        XWikiDocument draft = withdrawalDraft(name, context());
        return draft == null ? "" : draft.getVersion();
    }

    public String withdraw(String name, String version, String token) throws Exception {
        XWikiContext context = context();
        if (context.getRequest() == null || !"POST".equals(context.getRequest().getMethod())) {
            return "POLICY_POST_REQUIRED";
        }
        if (!csrf.isTokenValid(token)) { return "POLICY_BAD_TOKEN"; }
        XWikiDocument original = withdrawalDraft(name, context);
        if (original == null) { return "WITHDRAW_FORBIDDEN"; }
        if (!original.getVersion().equals(version)) { return "WITHDRAW_CONFLICT"; }
        XWikiDocument draft = original.clone();
        draft.getObject(WorkflowPolicyListener.WF).set("status", "draft", context);
        ((WorkflowPolicyListener) listener).normalizeDraftRights(draft, "draft", context);
        draft.setHidden(false);
        draft.setContentAuthorReference(original.getContentAuthorReference());
        context.getWiki().saveDocument(draft, "Withdraw publication review request", true, context);
        return "WITHDRAWN";
    }
}
