package org.luxpedia.workflow;

import java.util.Objects;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.security.authorization.Right;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/** Soft abandonment is separate from the publication workflow state. */
final class DraftWorkspace {
    static final String CLASS = "LuxPedia.Code.DraftWorkspaceClass";
    static boolean abandoned(XWikiDocument doc) {
        BaseObject workspace = doc.getObject(CLASS);
        return workspace != null && workspace.getStringValue("status").equals("abandoned");
    }
    static boolean editableState(XWikiDocument doc) {
        BaseObject flow = doc.getObject(WorkflowPolicyListener.WF);
        return flow == null || flow.getIntValue("istarget") == 0 && flow.getIntValue("includeChildren") == 0 &&
            WorkflowPolicyListener.status(flow).equals("draft");
    }
    static boolean inScope(XWikiDocument doc, XWikiContext context) {
        if (doc == null) { return false; }
        DocumentReference ref = doc.getDocumentReference();
        return !doc.isNew() && ref.getWikiReference().getName().equals(context.getWikiId()) &&
            ref.getSpaceReferences().get(0).getName().equals("Drafts") &&
            !ref.getName().equals("WebPreferences") &&
            !(ref.getSpaceReferences().size() == 1 && ref.getName().equals("WebHome"));
    }
    static boolean admin(WorkflowPolicyListener policy, XWikiContext context) {
        return context.getUserReference() != null && policy.authorization.hasAccess(Right.ADMIN,
            context.getUserReference(), new DocumentReference(context.getWikiId(), "XWiki", "XWikiPreferences"));
    }
    static boolean allowed(WorkflowPolicyListener policy, XWikiDocument doc, XWikiContext context, boolean restore)
        throws Exception {
        DocumentReference user = context.getUserReference();
        if (user == null || !inScope(doc, context) || !editableState(doc) || abandoned(doc) != restore ||
            !policy.authorization.hasAccess(Right.VIEW, user, doc.getDocumentReference())) { return false; }
        if (admin(policy, context)) { return true; }
        if (!policy.groups(user, context).contains(context.getWikiId() + ":XWiki.LuxEditors")) { return false; }
        if (restore) { return true; } // Resuming preserved work never discards anyone's content.
        BaseObject workspace = doc.getObject(CLASS);
        return workspace != null && workspace.getStringValue("owner").equals(user.toString()) &&
            workspace.getStringValue("shared").equals("0") &&
            policy.authorization.hasAccess(Right.EDIT, user, doc.getDocumentReference());
    }
    static boolean contentChanged(XWikiDocument old, XWikiDocument doc) {
        return old.isNew() || !Objects.equals(old.getContent(), doc.getContent()) ||
            !Objects.equals(old.getTitle(), doc.getTitle()) ||
            !Objects.equals(old.getAttachmentList(), doc.getAttachmentList()) ||
            !Objects.equals(old.getSyntax(), doc.getSyntax());
    }
    static void track(WorkflowPolicyListener policy, XWikiDocument doc, XWikiDocument old,
        DocumentReference actor, XWikiContext context, boolean newCycle) throws Exception {
        BaseObject workspace = doc.getObject(CLASS);
        if (workspace == null) { workspace = doc.newXObject(policy.resolver.resolve(CLASS, doc.getDocumentReference()), context); }
        if (newCycle || old.isNew()) {
            workspace.set("owner", actor.toString(), context);
            workspace.set("shared", "0", context);
            workspace.set("status", "active", context);
        } else if ((workspace.getStringValue("owner") == null || workspace.getStringValue("owner").isEmpty())) {
            // History before this feature cannot prove sole ownership; fail closed.
            workspace.set("owner", old.getCreatorReference() == null ? "" : old.getCreatorReference().toString(), context);
            workspace.set("shared", "1", context);
            workspace.set("status", "active", context);
        } else if (!workspace.getStringValue("owner").equals(actor.toString())) {
            workspace.set("shared", "1", context);
        }
    }
}
