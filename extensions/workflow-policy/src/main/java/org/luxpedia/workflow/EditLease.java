package org.luxpedia.workflow;

import java.time.Clock;
import java.util.UUID;
import java.util.Objects;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/** A persistent, absolute 24-hour lease. Its generation fences old editor sessions. */
final class EditLease {
    static final String CLASS = "LuxPedia.Code.EditLeaseClass";
    static final long DURATION = 24 * 60 * 60 * 1000L;
    static Clock clock = Clock.systemUTC();
    static final ThreadLocal<XWikiDocument> SAVING = new ThreadLocal<>();
    static final ThreadLocal<Boolean> OPENING = ThreadLocal.withInitial(() -> false);
    static String field(XWikiDocument doc, String name) {
        BaseObject lease = doc.getObject(CLASS);
        return lease == null ? "" : Objects.toString(lease.getStringValue(name), "");
    }
    static boolean active(XWikiDocument doc) {
        try { return !field(doc, "owner").isBlank() && Long.parseLong(field(doc, "expiresAt")) > clock.millis(); }
        catch (NumberFormatException error) { return false; }
    }
    static boolean owned(XWikiDocument doc, XWikiContext context) {
        return context.getUserReference() != null && active(doc) &&
            field(doc, "owner").equals(context.getUserReference().toString());
    }
    static String supplied(XWikiContext context) {
        if (context.getRequest() == null) { return ""; }
        String value = context.getRequest().getHeader("X-LuxPedia-Edit-Lease");
        return value == null ? Objects.toString(context.getRequest().getParameter("luxpedia_edit_lease"), "") : value;
    }
    static void requireOwner(XWikiDocument doc, XWikiContext context) {
        WorkflowPolicyListener.require(owned(doc, context) &&
            !field(doc, "generation").isEmpty() && field(doc, "generation").equals(supplied(context)),
            "Editing right expired or belongs to another editor. Keep your unsaved text and explicitly resume the draft.");
    }
    static void assign(WorkflowPolicyListener policy, XWikiDocument doc, XWikiContext context) throws Exception {
        BaseObject lease = doc.getObject(CLASS);
        if (lease == null) { lease = doc.newXObject(policy.resolver.resolve(CLASS, doc.getDocumentReference()), context); }
        long start = clock.millis();
        lease.set("owner", context.getUserReference().toString(), context);
        lease.set("acquiredAt", Long.toString(start), context);
        lease.set("expiresAt", Long.toString(start + DURATION), context);
        lease.set("generation", UUID.randomUUID().toString(), context);
        DraftWorkspace.track(policy, doc, doc.getOriginalDocument(), context.getUserReference(), context, true);
    }
    static void clear(XWikiDocument doc) { doc.removeObjects(CLASS); }
    static boolean scope(XWikiDocument doc) {
        var ref = doc.getDocumentReference();
        return ref.getSpaceReferences().get(0).getName().equals("Drafts") &&
            !ref.getName().equals("WebPreferences") &&
            !(ref.getSpaceReferences().size() == 1 && ref.getName().equals("WebHome"));
    }
}
