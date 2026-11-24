package org.luxpedia.workflow;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.query.Query;
import org.xwiki.script.service.ScriptServiceManager;

/** Explicit acquire/release; reading a draft never acquires or renews a lease. */
class EditLeaseManager {
    final WorkflowPolicyListener policy;
    final XWikiContext context;
    final ScriptServiceManager services;
    EditLeaseManager(WorkflowPolicyListener policy, XWikiContext context, ScriptServiceManager services) {
        this.policy = policy; this.context = context; this.services = services;
    }
    DocumentReference resolve(String name) {
        WorkflowPolicyListener.require(name != null && !name.isBlank() && name.length() <= 2048 &&
            context.getUserReference() != null, "Sign in first.");
        DocumentReference ref = policy.resolver.resolve(name, new WikiReference(context.getWikiId()));
        WorkflowPolicyListener.require(ref.getWikiReference().getName().equals(context.getWikiId()) &&
            policy.authorization.hasAccess(Right.VIEW, context.getUserReference(), ref), "You cannot read this page.");
        return ref;
    }
    boolean editor() throws Exception {
        return DraftWorkspace.admin(policy, context) ||
            policy.groups(context.getUserReference(), context).contains(context.getWikiId() + ":XWiki.LuxEditors");
    }
    Map<String, Object> read(String name) throws Exception {
        XWikiDocument doc = context.getWiki().getDocument(resolve(name), context);
        WorkflowPolicyListener.require(DraftWorkspace.inScope(doc, context), "Not an existing draft.");
        return describe(doc);
    }
    Map<String, Object> describe(XWikiDocument doc) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        boolean editable = DraftWorkspace.editableState(doc) && !DraftWorkspace.abandoned(doc);
        boolean active = EditLease.active(doc);
        result.put("status", editable ? active ? EditLease.owned(doc, context) ? "owned" : "busy" : "free" : "locked");
        result.put("reference", policy.localSerializer.serialize(doc.getDocumentReference()));
        result.put("owner", active ? EditLease.field(doc, "owner") : "");
        result.put("expiresAt", active ? EditLease.field(doc, "expiresAt") : "");
        result.put("generation", active && (EditLease.owned(doc, context) || DraftWorkspace.admin(policy, context)) ?
            EditLease.field(doc, "generation") : "");
        result.put("canClaim", editable && editor() && (!active || EditLease.owned(doc, context)));
        result.put("canForce", editable && active && DraftWorkspace.admin(policy, context));
        result.put("viewURL", context.getWiki().getURL(doc.getDocumentReference(), "view", context));
        result.put("editURL", context.getWiki().getURL(doc.getDocumentReference(), "edit", context));
        return result;
    }
    Object nativeCall(String method, DocumentReference ref) throws Exception {
        var service = services.get("publicationworkflow");
        WorkflowPolicyListener.require(service != null, "Publication workflow is unavailable.");
        return service.getClass().getMethod(method, DocumentReference.class).invoke(service, ref);
    }
    Map<String, Object> change(String name, String operation) throws Exception {
        DocumentReference ref = resolve(name);
        WorkflowPolicyListener.require(editor(), "Only editors or administrators may acquire editing rights.");
        if ("open".equals(operation)) { return open(ref); }
        WorkflowPolicyListener.require(List.of("claim", "release", "force-release").contains(operation), "Unknown operation.");
        DraftWriteBoundary.guard(ref.toString());
        XWikiDocument original = context.getWiki().getDocument(ref, context);
        WorkflowPolicyListener.require(DraftWorkspace.inScope(original, context) &&
            DraftWorkspace.editableState(original) && !DraftWorkspace.abandoned(original), "This draft is not editable.");
        if (operation.equals("claim")) {
            if (EditLease.active(original)) { return describe(original); } // No renewal, even for the owner.
            return assign(original);
        }
        if (operation.equals("force-release")) {
            WorkflowPolicyListener.require(DraftWorkspace.admin(policy, context) &&
                EditLease.field(original, "generation").equals(EditLease.supplied(context)), "Administrator and current lease required.");
        } else { EditLease.requireOwner(original, context); }
        XWikiDocument doc = original.clone(); EditLease.clear(doc); save(doc, original);
        XWikiDocument stored = context.getWiki().getDocument(ref, context);
        WorkflowPolicyListener.require(EditLease.field(stored, "generation").isEmpty(), "Could not persist release.");
        return describe(stored);
    }
    Map<String, Object> assign(XWikiDocument original) throws Exception {
        XWikiDocument doc = original.clone();
        EditLease.assign(policy, doc, context); save(doc, original);
        XWikiDocument stored = context.getWiki().getDocument(doc.getDocumentReference(), context);
        WorkflowPolicyListener.require(EditLease.owned(stored, context) &&
            EditLease.field(stored, "generation").equals(EditLease.field(doc, "generation")), "Could not persist editing rights.");
        return describe(stored);
    }
    void save(XWikiDocument doc, XWikiDocument original) throws Exception {
        policy.normalizeDraftRights(doc, WorkflowPolicyListener.status(doc.getObject(WorkflowPolicyListener.WF)), context);
        doc.setContentAuthorReference(original.getContentAuthorReference());
        EditLease.SAVING.set(doc);
        try { context.getWiki().saveDocument(doc, "Change draft editing right without changing saved content", true, context); }
        finally { EditLease.SAVING.remove(); }
    }
    Map<String, Object> open(DocumentReference target) throws Exception {
        WorkflowPolicyListener.require(target.getSpaceReferences().get(0).getName().equals("Knowledge") &&
            policy.validTarget(target, context), "Only Knowledge articles have linked drafts.");
        DraftWriteBoundary.guard("target:" + target); // find/create is one operation, not a check followed by an unguarded copy
        XWikiDocument published = context.getWiki().getDocument(target, context);
        WorkflowPolicyListener.require(!published.isNew() && published.getObject(WorkflowPolicyListener.WF) != null &&
            published.getObject(WorkflowPolicyListener.WF).getIntValue("istarget") == 1 &&
            WorkflowPolicyListener.status(published.getObject(WorkflowPolicyListener.WF)).equals("published"), "Register this Knowledge article with the workflow first.");
        WorkflowPolicyListener.require(DraftWorkspace.admin(policy, context) || published.getObject(WorkflowPolicyListener.CATEGORY) == null,
            "Editors cannot open category home pages as article drafts.");
        List<String> matches = policy.queries.createQuery("from doc.object(PublicationWorkflow.PublicationWorkflowClass) as wf " +
            "where wf.istarget = 0 and (wf.target = :local or wf.target = :full)", Query.XWQL)
            .setWiki(context.getWikiId()).bindValue("local", policy.localSerializer.serialize(target))
            .bindValue("full", target.toString()).execute();
        WorkflowPolicyListener.require(matches.size() <= 1, "Multiple linked drafts need administrator repair.");
        DocumentReference draftRef = matches.isEmpty() ? null : policy.resolver.resolve(matches.get(0), new WikiReference(context.getWikiId()));
        EditLease.OPENING.set(true);
        try {
            if (draftRef == null || context.getWiki().getDocument(draftRef, context).isNew()) {
                draftRef = (DocumentReference) nativeCall("createDraftDocument", target);
                WorkflowPolicyListener.require(draftRef != null, "Could not create a linked draft.");
            }
            WorkflowPolicyListener.require(draftRef.getWikiReference().equals(target.getWikiReference()) &&
                draftRef.getSpaceReferences().get(0).getName().equals("Drafts"), "Invalid draft location.");
            DraftWriteBoundary.guard(draftRef.toString());
            XWikiDocument draft = context.getWiki().getDocument(draftRef, context);
            WorkflowPolicyListener.require(!draft.isNew() && policy.authorization.hasAccess(Right.VIEW,
                context.getUserReference(), draftRef), "Linked draft is unavailable.");
            if (WorkflowPolicyListener.status(draft.getObject(WorkflowPolicyListener.WF)).equals("published")) {
                WorkflowPolicyListener.require(Boolean.TRUE.equals(nativeCall("editDraft", draftRef)), "Could not reopen the linked draft.");
                draft = context.getWiki().getDocument(draftRef, context);
            }
            if (!DraftWorkspace.editableState(draft) || DraftWorkspace.abandoned(draft)) { return describe(draft); }
            return EditLease.active(draft) ? describe(draft) : assign(draft);
        } finally { EditLease.OPENING.remove(); }
    }
}
