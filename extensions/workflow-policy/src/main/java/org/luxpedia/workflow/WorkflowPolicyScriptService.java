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
    static final ThreadLocal<String> WORKSPACE_OPERATION = ThreadLocal.withInitial(() -> "");
    @Inject Execution execution;
    @Inject AuthorizationManager authorization;
    @Inject CSRFToken csrf;
    @Inject org.xwiki.environment.Environment environment;
    @Inject org.xwiki.script.service.ScriptServiceManager services;
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

    public java.util.Map<String, Object> favorites(String name, String search, String page) {
        try {
            var result = new java.util.LinkedHashMap<>(new PersonalFavorites((WorkflowPolicyListener) listener, context())
                .read(name, search, page));
            if ("ok".equals(result.get("status"))) { result.put("token", csrf.getToken()); }
            return result;
        }
        catch (Exception error) { return java.util.Map.of("status", "unavailable"); }
    }

    public java.util.Map<String, Object> manageFavorite(String name, String operation, String version, String token) {
        XWikiContext context = context();
        if (context.getRequest() == null || !"POST".equals(context.getRequest().getMethod())) {
            return java.util.Map.of("status", "post_required");
        }
        if (!csrf.isTokenValid(token)) { return java.util.Map.of("status", "bad_token"); }
        try { return new PersonalFavorites((WorkflowPolicyListener) listener, context).change(name, operation, version); }
        catch (Exception error) { return java.util.Map.of("status", "unavailable"); }
    }

    /** JSON-compatible result; all references are resolved and authorized again on the server. */
    public java.util.Map<String, Object> editLease(String name) {
        try { return new EditLeaseManager((WorkflowPolicyListener) listener, context(), services).read(name); }
        catch (Exception error) { return java.util.Map.of("status", "unavailable"); }
    }
    public java.util.Map<String, Object> manageEditLease(String name, String operation, String token) {
        XWikiContext context = context();
        if (context.getRequest() == null || !"POST".equals(context.getRequest().getMethod())) {
            return java.util.Map.of("status", "post_required");
        }
        if (!csrf.isTokenValid(token)) { return java.util.Map.of("status", "bad_token"); }
        try { return new EditLeaseManager((WorkflowPolicyListener) listener, context, services).change(name, operation); }
        catch (Exception error) { return java.util.Map.of("status", "unavailable"); }
    }

    /** Native Solr result rows are secured by XWiki; suppress global facets/highlights and counts in its UI. */
    public boolean departmentSearchRestricted() {
        XWikiContext context = context();
        if (administrator(context)) { return false; }
        try { return Boolean.TRUE.equals(new DepartmentPolicy((WorkflowPolicyListener) listener, context).settings(context.getWikiId()).get("enabled")); }
        catch (Exception unavailable) { return true; }
    }

    public java.util.Map<String, Object> adminRead(String operation, String search, String area, String page, String days) {
        XWikiContext context = context();
        if (!administrator(context)) { return java.util.Map.of("status", "forbidden"); }
        try {
            var policy = (WorkflowPolicyListener) listener; var admin = new AdminOperations(policy, context, services); admin.environment = environment;
            java.util.Map<String, Object> result = switch (operation == null ? "" : operation) {
                case "pages" -> admin.pages(search, area, page);
                case "trash" -> admin.trash(page);
                case "departments" -> new DepartmentPolicy(policy, context).read();
                case "department-users" -> {
                    var users = new java.util.LinkedHashMap<String, Object>(new IdentityDirectory(policy, context).users("all", search, page));
                    users.put("status", "ok"); yield users;
                }
                case "settings" -> new NavigationSettings(policy, context).read();
                case "categories" -> java.util.Map.of("status", "ok", "items", new NavigationSettings(policy, context).categories());
                case "rag-overview" -> new RagSync(policy, context).overview();
                default -> admin.dashboard(days);
            };
            var response = new java.util.LinkedHashMap<>(result); response.put("token", csrf.getToken()); return response;
        } catch (Exception error) { return java.util.Map.of("status", "unavailable"); }
    }
    public java.util.Map<String, Object> adminWrite(String operation, String payload, String token) {
        XWikiContext context = context();
        if (!administrator(context)) { return java.util.Map.of("status", "forbidden"); }
        if (context.getRequest() == null || !"POST".equals(context.getRequest().getMethod())) { return java.util.Map.of("status", "post_required"); }
        if (!csrf.isTokenValid(token)) { return java.util.Map.of("status", "bad_token"); }
        try {
            var policy = (WorkflowPolicyListener) listener; var admin = new AdminOperations(policy, context, services); admin.environment = environment;
            return switch (operation == null ? "" : operation) {
                case "preview-delete" -> admin.preview("delete", payload);
                case "preview-restore" -> admin.preview("restore", payload);
                case "execute" -> admin.execute(payload);
                case "save-department", "save-department-settings" -> {
                    WorkflowPolicyListener.require(payload != null && payload.length() <= 250000, "Department data is too large.");
                    var input = AdminOperations.JSON.readValue(payload, java.util.Map.class);
                    var departments = new DepartmentPolicy(policy, context);
                    yield operation.equals("save-department") ? departments.saveDepartment(input) : departments.saveSettings(input);
                }
                case "save-settings" -> new NavigationSettings(policy, context).save(payload);
                case "rag-save-settings" -> rag(() -> new RagSettings(policy, context).save(payload, new RagSync(policy, context).categoryReferences()));
                case "rag-test" -> rag(() -> new RagSync(policy, context).test(payload));
                case "rag-preview" -> rag(() -> new RagSync(policy, context).preview());
                case "rag-run" -> rag(() -> new RagSync(policy, context).start(payload));
                case "rag-continue" -> rag(() -> new RagSync(policy, context).resume(payload));
                default -> java.util.Map.of("status", "invalid_operation");
            };
        } catch (SecurityException conflict) { return java.util.Map.of("status", "conflict"); }
        catch (Exception error) { return java.util.Map.of("status", "unavailable"); }
    }
    /** Sync refusals carry a fixed server-side reason (for example an unconfigured address) back to the administrator. */
    static java.util.Map<String, Object> rag(java.util.concurrent.Callable<java.util.Map<String, Object>> operation) throws Exception {
        try { return operation.call(); }
        catch (SecurityException refused) { return java.util.Map.of("status", "refused", "reason", String.valueOf(refused.getMessage())); }
    }
    public java.util.Map<String, Object> presentation(String name) {
        try { return new NavigationSettings((WorkflowPolicyListener) listener, context()).presentation(name); }
        catch (Exception error) { return java.util.Map.of("status", "unavailable"); }
    }

    public String initialize(String token) throws Exception {
        XWikiContext context = context();
        if (!administrator(context)) { return "POLICY_FORBIDDEN"; }
        if (context.getRequest() == null || !"POST".equals(context.getRequest().getMethod())) {
            return "POLICY_POST_REQUIRED";
        }
        if (!csrf.isTokenValid(token)) { return "POLICY_BAD_TOKEN"; }
        DraftWriteBoundary.guard("policy-setup:" + context.getWikiId());
        DocumentReference reference = new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Code"), "ReviewAuditClass");
        XWikiDocument audit = context.getWiki().getDocument(reference, context).clone();
        BaseClass schema = audit.getXClass();
        boolean changed = false;
        if (schema.get("submittedBy") == null) { changed |= schema.addTextField("submittedBy", "Submitted by", 60); }
        if (schema.get("authoredBy") == null) { changed |= schema.addTextField("authoredBy", "Content authored by", 60); }
        if (schema.get("submittedVersion") == null) { changed |= schema.addTextField("submittedVersion", "Submitted version", 30); }
        if (changed) { context.getWiki().saveDocument(audit, "Ensure review audit fields", true, context); }
        XWikiDocument workspaceSchema = context.getWiki().getDocument(
            new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Code"), "DraftWorkspaceClass"), context).clone();
        BaseClass workspaceClass = workspaceSchema.getXClass();
        boolean workspaceChanged = false;
        for (String field : List.of("owner", "shared", "status", "changedBy", "changedAt")) {
            if (workspaceClass.get(field) == null) { workspaceChanged |= workspaceClass.addTextField(field, field, 100); }
        }
        if (workspaceChanged) { context.getWiki().saveDocument(workspaceSchema, "Ensure draft workspace fields", true, context); }
        XWikiDocument leaseSchema = context.getWiki().getDocument(new DocumentReference(context.getWikiId(),
            List.of("LuxPedia", "Code"), "EditLeaseClass"), context).clone();
        boolean leaseChanged = false;
        for (String field : List.of("owner", "acquiredAt", "expiresAt", "generation")) {
            if (leaseSchema.getXClass().get(field) == null) { leaseChanged |= leaseSchema.getXClass().addTextField(field, field, 100); }
        }
        if (leaseChanged) { context.getWiki().saveDocument(leaseSchema, "Ensure persistent editing lease fields", true, context); }
        XWikiDocument adminSchema = context.getWiki().getDocument(new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Code"), "AdminSettingsClass"), context).clone();
        if (adminSchema.getXClass().get("payload") == null && adminSchema.getXClass().addTextAreaField("payload", "Settings", 80, 20)) {
            context.getWiki().saveDocument(adminSchema, "Ensure administrator settings fields", true, context);
        }
        for (String className : List.of("DepartmentClass", "DepartmentSettingsClass")) {
            var classRef = new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Code"), className);
            var classDoc = context.getWiki().getDocument(classRef, context).clone();
            if (classDoc.getXClass().get("payload") == null) {
                classDoc.getXClass().addTextAreaField("payload", "Server-managed department configuration", 80, 10);
                context.getWiki().saveDocument(classDoc, "Initialize department role schema", true, context);
            }
        }
        ((WorkflowPolicyListener) listener).repairExistingDraftRights(context);
        ((WorkflowPolicyListener) listener).registerExistingKnowledgeTargets(context);
        ((WorkflowPolicyListener) listener).repairIdentityPrivacy(context);
        return "AUDIT_CLASS_READY";
    }

    XWikiDocument workspaceDraft(String name) throws Exception {
        XWikiContext context = context();
        if (name == null || name.isBlank() || name.length() > 1024 || context.getUserReference() == null) { return null; }
        var policy = (WorkflowPolicyListener) listener;
        DocumentReference ref = policy.resolver.resolve(name, new WikiReference(context.getWikiId()));
        if (!ref.getWikiReference().getName().equals(context.getWikiId()) ||
            !ref.getSpaceReferences().get(0).getName().equals("Drafts")) { return null; }
        XWikiDocument doc = context.getWiki().getDocument(ref, context);
        if (!DraftWorkspace.inScope(doc, context) || !policy.authorization.hasAccess(Right.VIEW,
            context.getUserReference(), ref)) { return null; }
        return doc;
    }

    public String draftWorkspace(String name) throws Exception {
        XWikiDocument doc = workspaceDraft(name);
        if (doc == null) { return "{\"abandoned\":false,\"abandon\":false,\"restore\":false,\"version\":\"\"}"; }
        var policy = (WorkflowPolicyListener) listener;
        String version = doc.getVersion();
        if (!version.matches("[0-9]+\\.[0-9]+")) { version = ""; }
        return "{\"abandoned\":" + DraftWorkspace.abandoned(doc) + ",\"abandon\":" +
            DraftWorkspace.allowed(policy, doc, context(), false) + ",\"restore\":" +
            DraftWorkspace.allowed(policy, doc, context(), true) + ",\"version\":\"" + version + "\"}";
    }

    public String manageDraft(String name, String version, String operation, String token) throws Exception {
        XWikiContext context = context();
        if (context.getRequest() == null || !"POST".equals(context.getRequest().getMethod())) { return "POLICY_POST_REQUIRED"; }
        if (!csrf.isTokenValid(token)) { return "POLICY_BAD_TOKEN"; }
        if (!List.of("abandon", "restore").contains(operation == null ? "" : operation)) { return "WORKSPACE_FORBIDDEN"; }
        DocumentReference locked = ((WorkflowPolicyListener) listener).resolver.resolve(name, new WikiReference(context.getWikiId()));
        DraftWriteBoundary.guard(locked.toString());
        XWikiDocument original = workspaceDraft(name);
        var policy = (WorkflowPolicyListener) listener;
        boolean restore = operation.equals("restore");
        if (original == null || !DraftWorkspace.allowed(policy, original, context, restore)) { return "WORKSPACE_FORBIDDEN"; }
        if (!original.getVersion().equals(version)) { return "WORKSPACE_CONFLICT"; }
        if (!restore) { EditLease.requireOwner(original, context); }
        XWikiDocument draft = original.clone();
        EditLease.clear(draft);
        var workspace = draft.getObject(DraftWorkspace.CLASS);
        if (workspace == null) {
            DraftWorkspace.track(policy, draft, original, context.getUserReference(), context, false);
            workspace = draft.getObject(DraftWorkspace.CLASS);
        }
        workspace.set("status", restore ? "active" : "abandoned", context);
        workspace.set("changedBy", context.getUserReference().toString(), context);
        workspace.set("changedAt", java.time.Instant.now().toString(), context);
        policy.normalizeDraftRights(draft, WorkflowPolicyListener.status(draft.getObject(WorkflowPolicyListener.WF)), context);
        draft.setHidden(!restore);
        draft.setContentAuthorReference(original.getContentAuthorReference());
        WORKSPACE_OPERATION.set(restore ? "restoreDraft" : "abandonDraft");
        try { context.getWiki().saveDocument(draft, restore ? "Restore abandoned draft" : "Abandon draft without deleting content", true, context); }
        finally { WORKSPACE_OPERATION.remove(); }
        return restore ? "DRAFT_RESTORED" : "DRAFT_ABANDONED";
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
        DocumentReference locked = ((WorkflowPolicyListener) listener).resolver.resolve(name, new WikiReference(context.getWikiId()));
        DraftWriteBoundary.guard(locked.toString());
        XWikiDocument original = withdrawalDraft(name, context);
        if (original == null) { return "WITHDRAW_FORBIDDEN"; }
        if (!original.getVersion().equals(version)) { return "WITHDRAW_CONFLICT"; }
        XWikiDocument draft = original.clone();
        draft.getObject(WorkflowPolicyListener.WF).set("status", "draft", context);
        EditLease.clear(draft);
        ((WorkflowPolicyListener) listener).normalizeDraftRights(draft, "draft", context);
        draft.setHidden(false);
        draft.setContentAuthorReference(original.getContentAuthorReference());
        context.getWiki().saveDocument(draft, "Withdraw publication review request", true, context);
        return "WITHDRAWN";
    }
}
