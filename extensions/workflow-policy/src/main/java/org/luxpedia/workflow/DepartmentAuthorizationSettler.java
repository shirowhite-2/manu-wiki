package org.luxpedia.workflow;

import java.util.*;
import javax.inject.*;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.*;
import org.xwiki.security.*;
import org.xwiki.security.authorization.*;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.EventListener;
import com.xpn.xwiki.XWikiContext;

/** Native authorization extension: site decision AND current department scope. */
@Component
@Named("luxpedia-departments")
@Singleton
public class DepartmentAuthorizationSettler implements AuthorizationSettler, Initializable {
    static volatile boolean active;
    @Inject @Named("default") AuthorizationSettler delegate;
    @Inject @Named("luxpedia-workflow-policy") Provider<EventListener> listener;
    @Inject Provider<XWikiContext> contexts;
    @Override public void initialize() { active = true; }
    @Override public SecurityAccessEntry settle(UserSecurityReference user, Collection<GroupSecurityReference> groups, Deque<SecurityRuleEntry> rules) {
        var original = delegate.settle(user, groups, new ArrayDeque<>(rules));
        var document = original.getReference().getOriginalDocumentReference();
        var space = original.getReference().getOriginalSpaceReference();
        final DocumentReference reference = document == null && space != null ? new DocumentReference("WebHome", space) : document;
        if (reference == null || reference.getSpaceReferences().isEmpty() || !Set.of("Knowledge", "Drafts").contains(reference.getSpaceReferences().get(0).getName())) { return original; }
        var wikiRules = new ArrayDeque<SecurityRuleEntry>();
        for (var rule : rules) { if (rule.getReference().getSecurityType() == EntityType.WIKI || rule.getReference().getSecurityType() == SecurityReference.FARM) { wikiRules.add(rule); } }
        boolean administrator = !wikiRules.isEmpty() && delegate.settle(user, groups, wikiRules).getAccess().get(Right.ADMIN) == RuleState.ALLOW;
        if (administrator) { return original; }
        var memberships = groups.stream().map(GroupSecurityReference::getOriginalDocumentReference).filter(Objects::nonNull).toList();
        DocumentReference actor = user == null ? null : user.getOriginalReference();
        SecurityAccess narrowed = right -> {
            RuleState existing = original.getAccess().get(right);
            if (existing != RuleState.ALLOW || !Set.of(Right.VIEW, Right.EDIT, Right.COMMENT, Right.DELETE).contains(right)) { return existing; }
            try {
                return new DepartmentPolicy((WorkflowPolicyListener) listener.get(), contexts.get()).permitted(reference, actor, memberships) ? existing : RuleState.DENY;
            } catch (Exception unavailable) { return RuleState.DENY; }
        };
        // Never cache the department verdict: each read uses the current settings and draft target.
        return new SecurityAccessEntry() {
            @Override public SecurityReference getReference() { return original.getReference(); }
            @Override public UserSecurityReference getUserReference() { return original.getUserReference(); }
            @Override public SecurityAccess getAccess() { return narrowed; }
        };
    }
}
