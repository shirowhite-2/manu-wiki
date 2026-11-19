package org.luxpedia.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.bridge.event.DocumentUpdatingEvent;
import org.xwiki.bridge.event.DocumentDeletingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.CancelableEvent;
import org.xwiki.observation.event.Event;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.query.QueryManager;
import org.xwiki.query.Query;
import org.xwiki.model.reference.EntityReferenceSerializer;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.ObjectDiff;

/** Server-side policy for LuxPedia's single-article, two-role workflow (upstream 2.4.9). */
@Component
@Named("luxpedia-workflow-policy")
@Singleton
public class WorkflowPolicyListener implements EventListener
{
    static final String WF = "PublicationWorkflow.PublicationWorkflowClass";
    static final String AUDIT = "LuxPedia.Code.ReviewAuditClass";
    static final String LEGACY_METADATA = legacyMetadata();
    private static final Set<String> SYSTEM_NAMES = Set.of("WebPreferences", "DocumentLibrary",
        "CategoryIndex", "CategoryGuide", "CategoryAdmin");
    @Inject AuthorizationManager authorization;
    @Inject QueryManager queries;
    @Inject @Named("local") EntityReferenceSerializer<String> localSerializer;
    @Inject @Named("explicit") DocumentReferenceResolver<String> resolver;

    @Override public String getName() { return "luxpedia-workflow-policy"; }
    @Override public List<Event> getEvents() {
        return List.of(new DocumentCreatingEvent(), new DocumentUpdatingEvent(), new DocumentDeletingEvent());
    }

    @Override public void onEvent(Event event, Object source, Object data) {
        if (!(source instanceof XWikiDocument doc) || !(data instanceof XWikiContext context)) { return; }
        try {
            if (EditLease.scope(doc) || doc.getDocumentReference().getSpaceReferences().get(0).getName().equals("Knowledge")) {
                DraftWriteBoundary.guard(doc.getDocumentReference().toString());
                XWikiDocument latest = context.getWiki().getDocument(doc.getDocumentReference(), context);
                if (nativeRestore() && DraftWorkspace.admin(this, context) && latest.isNew()) {
                    doc.setOriginalDocument(latest);
                }
                XWikiDocument original = doc.getOriginalDocument();
                boolean exists = !latest.isNew();
                if (doc.getTranslation() != 0) {
                    latest = latest.getTranslatedDocument(doc.getLocale(), context);
                    exists = latest.getTranslation() != 0 && Objects.equals(latest.getLocale(), doc.getLocale()) && !latest.isNew();
                }
                require(exists != original.isNew() && (!exists ||
                    Objects.equals(latest.getVersion(), original.getVersion())),
                    "The draft changed while this request was waiting. Reload before saving.");
            }
            check(doc, context, event instanceof DocumentDeletingEvent);
        } catch (Exception error) {
            // Throwing from a listener can be swallowed by ObservationManager. Cancel explicitly.
            ((CancelableEvent) event).cancel("LuxPedia: " + error.getMessage());
        }
    }

    void check(XWikiDocument doc, XWikiContext context, boolean deleting) throws Exception {
        DocumentReference ref = doc.getDocumentReference();
        DocumentReference user = context.getUserReference();
        String space = ref.getSpaceReferences().get(0).getName();
        XWikiDocument old = doc.getOriginalDocument();
        XWikiDocument base = doc.getTranslation() == 0 ? doc : context.getWiki().getDocument(ref, context);
        BaseObject previous = doc.getTranslation() == 0 ? old.getObject(WF) : base.getObject(WF);
        BaseObject next = base.getObject(WF);
        String leaseOperation = workflowOperation();
        if (EditLease.scope(doc) && !deleting) {
            if (EditLease.SAVING.get() == doc) { return; } // exact server clone, never an HTTP flag
            boolean admin = DraftWorkspace.admin(this, context);
            if (admin && nativeRestore()) {
                if (doc.getTranslation() == 0) { EditLease.clear(doc); }
            } else if (Set.of("createDraftDocument", "editDraft").contains(leaseOperation)) {
                require(EditLease.OPENING.get(), "Open linked drafts through the protected edit action.");
                if (doc.getTranslation() == 0) { EditLease.assign(this, doc, context); }
            } else if (old.isNew() && doc.getTranslation() == 0 && leaseOperation.isEmpty()) {
                require(doc.getObject(EditLease.CLASS) == null && !protectedObjectChanged(old, doc, context), "Editing rights and security are server-managed.");
                EditLease.assign(this, doc, context);
            } else if (leaseOperation.equals("abandonDraft") || leaseOperation.equals("startWorkflow") ||
                leaseOperation.startsWith("submitFor") || leaseOperation.isEmpty() && !commentsOnly(old, doc, context)) {
                // Administrative ACL migrations preserve content and the current lease.
                boolean migration = admin && leaseOperation.isEmpty() && !DraftWorkspace.contentChanged(old, doc) &&
                    Objects.equals(fieldLease(old), fieldLease(doc));
                if (!migration) { EditLease.requireOwner(doc.getTranslation() == 0 ? old : base, context); }
            }
            if (Set.of("submitForModeration", "submitForValidation", "validate", "publish", "refuseValidation",
                "withdrawSubmission", "abandonDraft", "restoreDraft").contains(leaseOperation)) { EditLease.clear(doc); }
        }
        boolean directory = space.equals("XWiki") && (ref.getSpaceReferences().size() == 1 && ref.getName().equals("WebHome") ||
            ref.getSpaceReferences().size() > 1 && ref.getSpaceReferences().get(1).getName().equals("Users"));
        if (directory || IdentityDirectory.isIdentity(doc) || IdentityDirectory.isIdentity(old)) {
            boolean admin = user != null && authorization.hasAccess(Right.ADMIN, user,
                new DocumentReference(context.getWikiId(), "XWiki", "XWikiPreferences"));
            boolean group = doc.getObject("XWiki.XWikiGroups") != null || old.getObject("XWiki.XWikiGroups") != null;
            require(admin || (!directory && !deleting && !group && !old.isNew() && ref.equals(user)),
                "Only administrators may manage other users or role groups.");
            if (!admin) {
                require(doc.getObject("XWiki.XWikiUsers") != null, "Your user account cannot be removed.");
                for (List<ObjectDiff> changes : doc.getObjectDiff(old, doc, context)) {
                    for (ObjectDiff change : changes) {
                        require(change.getXClassReference().equals(resolver.resolve("XWiki.XWikiUsers", ref)) ||
                            (PersonalFavorites.saving(doc, old, context) &&
                            change.getXClassReference().equals(resolver.resolve(PersonalFavorites.CLASS, ref))),
                            "Profile security and configuration are administrator-managed.");
                    }
                }
            }
            if (!deleting && doc.getTranslation() == 0) { new IdentityDirectory(this, context).normalizeView(doc); }
            return;
        }
        boolean protectedConfiguration = space.equals("LuxPedia") || space.equals("PublicationWorkflow") || space.equals("Favorites") ||
            (space.equals("XWiki") && Set.of("LuxEditors", "LuxReviewers", "LuxReaders", "LuxManagers",
                "XWikiPreferences", "XWikiAdminGroup", "XWikiAllGroup").contains(ref.getName()));
        if (!protectedConfiguration && !space.equals("Drafts") && !space.equals("Knowledge") &&
            previous == null && next == null) { return; }
        require(user != null, "Sign in to change content.");
        if (authorization.hasAccess(Right.ADMIN, user,
            new DocumentReference(ref.getWikiReference().getName(), "XWiki", "XWikiPreferences"))) {
            if (!deleting && doc.getTranslation() == 0 && space.equals("Knowledge") && old.isNew() && next == null &&
                workflowOperation().isEmpty() && validTarget(ref, context) &&
                !context.getWiki().getDocument(new DocumentReference(context.getWikiId(), List.of("LuxPedia", "Workflow"), "Config"), context).isNew()) {
                registerPublishedTarget(doc, context);
            }
            if (!deleting && space.equals("Knowledge") && workflowOperation().equals("publish")) {
                doc.removeObjects(DraftWorkspace.CLASS);
                doc.removeObjects(EditLease.CLASS);
            }
            if (!deleting && space.equals("Drafts") && next != null &&
                Set.of("publish", "editDraft", "createDraftDocument").contains(workflowOperation())) {
                doc.setHidden(status(next).equals("published"));
            }
            if (!deleting && space.equals("Drafts") && !ref.getName().equals("WebPreferences") &&
                !(ref.getSpaceReferences().size() == 1 && ref.getName().equals("WebHome"))) {
                if (DraftWorkspace.contentChanged(old, doc) || Set.of("editDraft", "createDraftDocument").contains(workflowOperation())) {
                    DraftWorkspace.track(this, doc, old, user, context,
                        Set.of("editDraft", "createDraftDocument").contains(workflowOperation()));
                }
            }
            if (!deleting && EditLease.scope(doc) && doc.getTranslation() == 0) {
                normalizeDraftRights(doc, status(next), context);
            }
            return;
        }
        if (protectedConfiguration) {
            require(false, "Only administrators may change configuration or role membership.");
        }
        if (!space.equals("Drafts") && !space.equals("Knowledge")) {
            require(previous == null && next == null, "Workflow articles must be in Drafts or Knowledge.");
            return;
        }
        require(!deleting, "Only administrators may delete articles.");
        require(authorization.hasAccess(Right.VIEW, user, ref), "You cannot change content you are not allowed to read.");
        require(!ref.getName().equals("WebPreferences"), "Only administrators may change space settings.");
        Set<String> groups = groups(user, context);
        String wiki = ref.getWikiReference().getName();
        boolean editor = groups.contains(wiki + ":XWiki.LuxEditors");
        boolean reviewer = groups.contains(wiki + ":XWiki.LuxReviewers");
        String operation = workflowOperation();
        if (space.equals("Knowledge")) {
            if (operation.equals("publish")) {
                require(reviewer && validTarget(ref, context), "Only reviewers may publish articles to Knowledge.");
                DocumentReference draftRef = findDraft(ref, context);
                require(draftRef != null && draftRef.getSpaceReferences().get(0).getName().equals("Drafts"),
                    "A matching Drafts workflow is required.");
                XWikiDocument draft = context.getWiki().getDocument(draftRef, context);
                require(authorization.hasAccess(Right.VIEW, user, draftRef), "You cannot review a draft you cannot read.");
                require(List.of("validating", "valid").contains(status(draft.getObject(WF))), "Article is not awaiting review.");
                validWorkflow(draft.getObject(WF), draftRef, context);
                require(resolver.resolve(draft.getObject(WF).getStringValue("target"), draftRef).equals(ref),
                    "Publication target does not match the draft.");
                independentReviewer(draft, user);
                // Never carry draft audit objects into published copies.
                doc.removeObjects(AUDIT);
                doc.removeObjects(DraftWorkspace.CLASS);
                doc.removeObjects(EditLease.CLASS);
                return;
            }
            require((editor || reviewer) && commentsOnly(old, doc, context),
                "Published content is read-only. Edit its draft instead.");
            return;
        }
        require(!DraftWorkspace.abandoned(old) || operation.equals("restoreDraft"),
            "Restore the abandoned draft before editing or submitting it.");
        String before = status(previous);
        String after = status(next);
        switch (operation) {
            case "startWorkflow":
                require(!(ref.getName().equals("WebHome") && ref.getSpaceReferences().size() == 1),
                    "The writing-area home is edited in place, not submitted as an article.");
                require(editor && previous == null && after.equals("draft"), "Only editors may start a draft workflow.");
                validWorkflow(next, ref, context);
                break;
            case "createDraftDocument":
                require(editor && old.isNew() && after.equals("draft"), "Only editors may create a linked draft.");
                validWorkflow(next, ref, context);
                normalizeEditorContent(doc, user);
                DraftWorkspace.track(this, doc, old, user, context, true);
                break;
            case "editDraft":
                require(editor && before.equals("published") && after.equals("draft"), "Only editors may reopen published drafts.");
                DraftWorkspace.track(this, doc, old, user, context, true);
                break;
            case "submitForModeration":
            case "submitForValidation":
                require(editor && before.equals("draft") && after.equals("validating"), "Only editors may submit drafts for review.");
                validWorkflow(next, ref, context);
                BaseObject audit = doc.getObject(AUDIT);
                if (audit == null) { audit = doc.newXObject(resolver.resolve(AUDIT, ref), context); }
                audit.set("submittedBy", user.toString(), context);
                audit.set("submittedVersion", old.getVersion(), context);
                DocumentReference contentAuthor = old.getContentAuthorReference();
                require(contentAuthor != null, "The submitted content must have a recorded author.");
                audit.set("authoredBy", contentAuthor.toString(), context);
                break;
            case "validate":
            case "publish":
            case "refuseValidation":
                require(reviewer && List.of("validating", "valid").contains(before), "Only reviewers may decide on submitted drafts.");
                independentReviewer(old, user);
                require(after.equals(operation.equals("publish") ? "published" : operation.equals("validate") ? "valid" : "draft"),
                    "Unexpected review transition.");
                break;
            case "abandonDraft":
            case "restoreDraft":
                boolean restore = operation.equals("restoreDraft");
                require(DraftWorkspace.allowed(this, old, context, restore) && before.equals(after) &&
                    DraftWorkspace.abandoned(doc) != restore, "Draft workspace operation is not allowed.");
                break;
            case "withdrawSubmission":
                require(canWithdraw(old, context) && after.equals("draft"),
                    "Only the submitter may withdraw a pending review request.");
                break;
            case "":
                require(old.isNew() || Objects.equals(old.getCreatorReference(), doc.getCreatorReference()),
                    "Article creator identity is immutable.");
                require(previous == null && next == null || previous != null && next != null,
                    "Workflow objects cannot be created or removed directly.");
                require(old.isNew() && doc.getTranslation() == 0 && EditLease.scope(doc) || !protectedObjectChanged(old, doc, context), "Workflow, audit and security objects are server-managed.");
                if (commentsOnly(old, doc, context)) { return; }
                require(editor && (previous == null || before.equals("draft")),
                    "Only editors may edit draft content; submitted content is locked.");
                normalizeEditorContent(doc, user);
                if (!(ref.getSpaceReferences().size() == 1 && ref.getName().equals("WebHome"))) {
                    DraftWorkspace.track(this, doc, old, user, context, false);
                }
                if (doc.getTranslation() == 0 && EditLease.scope(doc)) { normalizeDraftRights(doc, after, context); }
                return;
            default:
                require(false, "This workflow operation is reserved for administrators.");
        }
        // Upstream grants EDIT to validators. Replace those rules on every native transition.
        doc.setHidden(after.equals("published") || DraftWorkspace.abandoned(doc));
        if (doc.getTranslation() == 0) { normalizeDraftRights(doc, after, context); }
    }

    void independentReviewer(XWikiDocument draft, DocumentReference user) {
        BaseObject audit = draft.getObject(AUDIT);
        require(audit != null && !audit.getStringValue("submittedBy").isBlank() && !audit.getStringValue("authoredBy").isBlank(),
            "Legacy pending drafts must be resubmitted by an editor before review.");
        require(!user.toString().equals(audit.getStringValue("submittedBy")) &&
            !user.toString().equals(audit.getStringValue("authoredBy")) && !user.equals(draft.getCreatorReference()),
            "You cannot approve, publish or reject your own article submission.");
    }

    private void normalizeEditorContent(XWikiDocument doc, DocumentReference user) {
        String content = doc.getContent();
        if (content == null) { return; }
        String cleaned = content.startsWith(LEGACY_METADATA) ? content.substring(LEGACY_METADATA.length()) : content;
        cleaned = cleaned.replace("{{include document=\"LuxPedia.Code.CategoryHomeTemplate\"/}}",
            "{{include reference=\"LuxPedia.Code.CategoryHomeTemplate\" author=\"target\"/}}");
        if (!cleaned.equals(content)) {
            doc.setContent(cleaned);
            doc.setContentAuthorReference(user);
        }
    }

    boolean canWithdraw(XWikiDocument draft, XWikiContext context) throws Exception {
        DocumentReference reference = draft.getDocumentReference();
        DocumentReference user = context.getUserReference();
        BaseObject flow = draft.getObject(WF);
        if (user == null || !reference.getWikiReference().getName().equals(context.getWikiId()) ||
            !reference.getSpaceReferences().get(0).getName().equals("Drafts") || flow == null ||
            flow.getIntValue("istarget") != 0 || flow.getIntValue("includeChildren") != 0 ||
            !List.of("moderating", "validating").contains(status(flow)) ||
            !authorization.hasAccess(Right.VIEW, user, reference)) { return false; }
        if (authorization.hasAccess(Right.ADMIN, user,
            new DocumentReference(context.getWikiId(), "XWiki", "XWikiPreferences"))) { return true; }
        BaseObject audit = draft.getObject(AUDIT);
        return groups(user, context).contains(context.getWikiId() + ":XWiki.LuxEditors") &&
            audit != null && user.toString().equals(audit.getStringValue("submittedBy"));
    }

    void validWorkflow(BaseObject object, DocumentReference source, XWikiContext context) throws Exception {
        require(object != null && object.getIntValue("istarget") == 0 && object.getIntValue("includeChildren") == 0,
            "Only a single draft article can be submitted.");
        require(resolver.resolve(object.getStringValue("workflow"), source).equals(
            new DocumentReference(source.getWikiReference().getName(), List.of("LuxPedia", "Workflow"), "Config")),
            "Use the LuxPedia workflow configuration.");
        require(!object.getStringValue("target").isBlank(), "Choose a publication category.");
        DocumentReference target = resolver.resolve(object.getStringValue("target"), source);
        require(target.getWikiReference().equals(source.getWikiReference()) && validTarget(target, context),
            "Publication is limited to articles in this wiki's Knowledge categories.");
        if (workflowOperation().equals("startWorkflow")) {
            XWikiDocument existing = context.getWiki().getDocument(target, context);
            boolean landing = existing.getObject("LuxPedia.Code.CategoryClass") != null ||
                (target.getSpaceReferences().size() == 1 &&
                    (target.getName().equals("WebHome") || SYSTEM_NAMES.contains(target.getName())));
            require(!landing, "Open the existing content page's linked draft instead of replacing it with a new article.");
        }
        require(authorization.hasAccess(Right.VIEW, context.getUserReference(), target),
            "You cannot publish into a category you cannot read.");
    }

    boolean validTarget(DocumentReference ref, XWikiContext context) throws Exception {
        boolean root = ref.getSpaceReferences().size() == 1;
        return ref.getSpaceReferences().get(0).getName().equals("Knowledge") &&
            !ref.getName().equals("WebPreferences") &&
            !(root && ref.getName().equals("CategoryAdmin"));
    }

    boolean protectedObjectChanged(XWikiDocument old, XWikiDocument doc, XWikiContext context) throws Exception {
        for (List<ObjectDiff> changes : doc.getObjectDiff(old, doc, context)) {
            for (ObjectDiff change : changes) {
                DocumentReference cls = change.getXClassReference();
                boolean article = cls.equals(resolver.resolve("LuxPedia.Code.ArticleClass", doc.getDocumentReference()));
                boolean tagOrComment = cls.equals(resolver.resolve("XWiki.TagClass", doc.getDocumentReference())) ||
                    cls.equals(resolver.resolve("XWiki.XWikiComments", doc.getDocumentReference()));
                if (!article && !tagOrComment) { return true; }
            }
        }
        return false;
    }

    boolean commentsOnly(XWikiDocument old, XWikiDocument doc, XWikiContext context) throws Exception {
        if (old.isNew() || !Objects.equals(old.getContent(), doc.getContent()) ||
            !Objects.equals(old.getTitle(), doc.getTitle()) || !Objects.equals(old.getSyntax(), doc.getSyntax()) ||
            !Objects.equals(old.getCreatorReference(), doc.getCreatorReference()) ||
            !Objects.equals(old.getDefaultLocale(), doc.getDefaultLocale()) ||
            !Objects.equals(old.getParentReference(), doc.getParentReference()) || old.isHidden() != doc.isHidden() ||
            !Objects.equals(old.getAttachmentList(), doc.getAttachmentList())) { return false; }
        boolean comments = false;
        for (List<ObjectDiff> changes : doc.getObjectDiff(old, doc, context)) {
            for (ObjectDiff change : changes) {
                if (!change.getXClassReference().equals(resolver.resolve("XWiki.XWikiComments", doc.getDocumentReference()))) {
                    return false;
                }
                comments = true;
            }
        }
        return comments;
    }

    void normalizeDraftRights(XWikiDocument doc, String state, XWikiContext context) throws Exception {
        doc.removeObjects("XWiki.XWikiRights");
        boolean editable = (state.isEmpty() || state.equals("draft")) && !DraftWorkspace.abandoned(doc);
        right(doc, "XWiki.LuxEditors", "view,comment", true, context);
        if (editable && EditLease.active(doc)) {
            BaseObject holder = doc.newXObject(resolver.resolve("XWiki.XWikiRights", doc.getDocumentReference()), context);
            holder.set("users", EditLease.field(doc, "owner"), context);
            holder.set("levels", "edit", context); holder.set("allow", 1, context);
        }
        right(doc, "XWiki.LuxReviewers", "view,comment", true, context);
        // A page-level grant from upstream or creator rights must never expose deletion.
        right(doc, "XWiki.LuxEditors,XWiki.LuxReviewers", "delete", false, context);
        if (!editable || !EditLease.active(doc)) {
            right(doc, "XWiki.LuxEditors,XWiki.LuxReviewers", "edit", false, context);
        }
        // No explicit edit DENY for reviewers: that would also block an editor who has both roles.
        right(doc, "XWiki.LuxReaders", "view,comment,edit,delete", false, context);
        BaseObject guest = doc.newXObject(resolver.resolve("XWiki.XWikiRights", doc.getDocumentReference()), context);
        guest.set("users", "XWiki.XWikiGuest", context);
        guest.set("levels", "view,comment,edit,delete", context);
        guest.set("allow", 0, context);
    }

    private void right(XWikiDocument doc, String group, String levels, boolean allow, XWikiContext context) throws Exception {
        BaseObject object = doc.newXObject(resolver.resolve("XWiki.XWikiRights", doc.getDocumentReference()), context);
        object.set("groups", group, context); object.set("levels", levels, context); object.set("allow", allow ? 1 : 0, context);
    }

    /** Trust Java API execution, never an HTTP action parameter or a client-set flag. */
    String workflowOperation() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
            .filter(frame -> (frame.getDeclaringClass() == WorkflowPolicyScriptService.class && Set.of("withdraw", "manageDraft").contains(frame.getMethodName())) ||
                (frame.getClassName().equals("org.xwiki.workflowpublication.internal.DefaultPublicationWorkflow") &&
                Set.of("startWorkflow", "startWorkflowAsTarget", "createDraftDocument", "editDraft",
                "submitForModeration", "submitForValidation", "validate", "publish", "refuseValidation",
                "refuseModeration", "archive", "unpublish", "unarchive", "publishFromArchive").contains(frame.getMethodName())))
            .map(frame -> frame.getDeclaringClass() == WorkflowPolicyScriptService.class ?
                (frame.getMethodName().equals("withdraw") ? "withdrawSubmission" : workspaceOperation()) : frame.getMethodName())
            .findFirst().orElse(""));
    }

    private String workspaceOperation() {
        return WorkflowPolicyScriptService.WORKSPACE_OPERATION.get();
    }

    static String status(BaseObject object) { return object == null ? "" : object.getStringValue("status"); }
    Set<String> groups(DocumentReference user, XWikiContext context) throws Exception {
        Set<String> result = new HashSet<>();
        Set<DocumentReference> visited = new HashSet<>();
        var pending = new ArrayDeque<DocumentReference>(); pending.add(user);
        while (!pending.isEmpty()) {
            DocumentReference member = pending.remove();
            if (!visited.add(member)) { continue; }
            for (DocumentReference group : context.getWiki().getGroupService(context)
                .getAllGroupsReferencesForMember(member, 0, 0, context)) {
                result.add(group.toString()); pending.add(group);
            }
        }
        return result;
    }
    DocumentReference findDraft(DocumentReference target, XWikiContext context) throws Exception {
        List<String> drafts = queries.createQuery("from doc.object(PublicationWorkflow.PublicationWorkflowClass) as wf " +
            "where wf.istarget = 0 and (wf.target = :local or wf.target = :full)", Query.XWQL)
            .setWiki(target.getWikiReference().getName())
            .bindValue("local", localSerializer.serialize(target)).bindValue("full", target.toString()).execute();
        require(drafts.size() == 1, "A unique matching draft workflow is required.");
        return resolver.resolve(drafts.get(0), target);
    }
    public void repairIdentityPrivacy(XWikiContext context) throws Exception {
        new IdentityDirectory(this, context).initialize();
    }

    boolean nativeRestore() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames.anyMatch(frame ->
            frame.getDeclaringClass() == com.xpn.xwiki.XWiki.class && frame.getMethodName().equals("restoreFromRecycleBin")));
    }
    private String fieldLease(XWikiDocument doc) {
        return EditLease.field(doc, "owner") + ":" + EditLease.field(doc, "generation") + ":" + EditLease.field(doc, "expiresAt");
    }
    public String getPolicyVersion() { return DraftWriteBoundary.present() ? "luxpedia-workflow-policy-12" : "POLICY_WRITE_BOUNDARY_MISSING"; }
    /** Deployment migration: remove historical validator EDIT grants without re-authoring content. */
    public int repairExistingDraftRights(XWikiContext context) throws Exception {
        require(authorization.hasAccess(Right.ADMIN, context.getUserReference(),
            new DocumentReference(context.getWikiId(), "XWiki", "XWikiPreferences")), "Administrator required.");
        List<String> drafts = queries.createQuery("from doc.object(PublicationWorkflow.PublicationWorkflowClass) as wf " +
            "where wf.istarget = 0 and (doc.space = 'Drafts' or doc.space like 'Drafts.%')", Query.XWQL)
            .setWiki(context.getWikiId()).execute();
        int repaired = 0;
        WikiReference wikiReference = new WikiReference(context.getWikiId());
        for (String name : drafts) {
            DocumentReference reference = resolver.resolve(name, wikiReference);
            require(reference.getWikiReference().equals(wikiReference) &&
                reference.getSpaceReferences().get(0).getName().equals("Drafts"),
                "Draft permission migration must stay within the current wiki's Drafts space.");
            // Serialize against editor writes before reading the version used for this migration.
            DraftWriteBoundary.guard(reference.toString());
            XWikiDocument original = context.getWiki().getDocument(reference, context);
            if (original.isNew()) { continue; } // A query result can disappear before we obtain its guard.
            XWikiDocument draft = original.clone();
            draft.setOriginalDocument(original);
            normalizeDraftRights(draft, status(draft.getObject(WF)), context);
            draft.setHidden(status(draft.getObject(WF)).equals("published") || DraftWorkspace.abandoned(draft));
            draft.setContentAuthorReference(original.getContentAuthorReference());
            // Attachment clones have different identities, so contentChanged cannot identify an ACL-only
            // migration. Trust only this exact server clone during its synchronous save; never an HTTP flag.
            XWikiDocument previousSave = EditLease.SAVING.get();
            EditLease.SAVING.set(draft);
            try {
                context.getWiki().saveDocument(draft, "Normalize LuxPedia workflow role permissions", true, context);
            } finally {
                if (previousSave == null) { EditLease.SAVING.remove(); }
                else { EditLease.SAVING.set(previousSave); }
            }
            repaired++;
        }
        return repaired;
    }
    /** Register legacy articles at every Knowledge depth so all editors can open a linked draft. */
    public int registerExistingKnowledgeTargets(XWikiContext context) throws Exception {
        require(authorization.hasAccess(Right.ADMIN, context.getUserReference(),
            new DocumentReference(context.getWikiId(), "XWiki", "XWikiPreferences")), "Administrator required.");
        List<String> articles = queries.createQuery("where (doc.space = 'Knowledge' or doc.space like 'Knowledge.%') " +
            "and doc.name <> 'WebPreferences' and doc.language = ''", Query.XWQL).setWiki(context.getWikiId()).execute();
        WikiReference wikiReference = new WikiReference(context.getWikiId());
        int registered = 0;
        for (String name : articles) {
            DocumentReference reference = resolver.resolve(name, wikiReference);
            require(reference.getWikiReference().equals(wikiReference) &&
                reference.getSpaceReferences().get(0).getName().equals("Knowledge"), "Knowledge migration scope exceeded.");
            XWikiDocument original = context.getWiki().getDocument(reference, context);
            if (original.isNew() || original.getObject(WF) != null || !validTarget(reference, context)) { continue; }
            XWikiDocument article = original.clone();
            registerPublishedTarget(article, context);
            article.setContentAuthorReference(original.getContentAuthorReference());
            context.getWiki().saveDocument(article, "Register existing article for reviewed changes", true, context);
            registered++;
        }
        return registered;
    }
    private void registerPublishedTarget(XWikiDocument article, XWikiContext context) throws Exception {
        DocumentReference reference = article.getDocumentReference();
        BaseObject flow = article.newXObject(resolver.resolve(WF, reference), context);
        flow.set("status", "published", context);
        flow.set("workflow", "LuxPedia.Workflow.Config", context);
        flow.set("target", localSerializer.serialize(reference), context);
        flow.set("istarget", 1, context);
        flow.set("includeChildren", 0, context);
    }
    private static String legacyMetadata() {
        try (var stream = WorkflowPolicyListener.class.getResourceAsStream("/legacy-article-metadata.xwiki")) {
            if (stream == null) { throw new IllegalStateException("Missing legacy metadata template"); }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    static void require(boolean condition, String message) {
        if (!condition) { throw new SecurityException(message); }
    }
}
