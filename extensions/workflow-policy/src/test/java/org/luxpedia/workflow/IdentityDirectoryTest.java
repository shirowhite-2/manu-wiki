package org.luxpedia.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityDirectoryTest {
    WorkflowPolicyListener policy;
    XWikiContext context;
    IdentityDirectory directory;
    List<Query> queries;
    List<Object[]> graph;
    List<Object[]> people;
    long total;

    @BeforeEach void setup() throws Exception {
        policy = new WorkflowPolicyListener();
        policy.resolver = new WorkflowPolicyListenerTest().realResolver();
        policy.localSerializer = mock(EntityReferenceSerializer.class);
        when(policy.localSerializer.serialize(any())).thenAnswer(call ->
            call.getArgument(0).toString().replaceFirst("^xwiki:", ""));
        context = mock(XWikiContext.class); when(context.getWikiId()).thenReturn("xwiki");
        policy.queries = mock(QueryManager.class);
        queries = new ArrayList<>(); graph = new ArrayList<>(); people = new ArrayList<>(); total = 0;
        when(policy.queries.createQuery(anyString(), eq(Query.XWQL))).thenAnswer(call -> {
            String statement = call.getArgument(0);
            new org.xwiki.query.jpql.internal.JPQLParser().parse(statement);
            Query query = mock(Query.class, RETURNS_SELF); queries.add(query);
            if (statement.startsWith("select doc.fullName, membership.member")) {
                when(query.execute()).thenAnswer(ignored -> graph);
            } else if (statement.startsWith("select count(")) {
                when(query.execute()).thenAnswer(ignored -> List.of(total));
            } else { when(query.execute()).thenAnswer(ignored -> people); }
            return query;
        });
        directory = new IdentityDirectory(policy, context);
    }

    @Test void nestedAndMultipleRolesAreIncludedAndCyclesTerminate() throws Exception {
        graph.add(new Object[]{"XWiki.LuxEditors", "xwiki:XWiki.Engineers"});
        graph.add(new Object[]{"XWiki.Engineers", "XWiki.alice"});
        graph.add(new Object[]{"XWiki.Engineers", "XWiki.LuxEditors"});
        graph.add(new Object[]{"XWiki.LuxReviewers", "XWiki.alice"});
        graph.add(new Object[]{"XWiki.XWikiAdminGroup", "XWiki.bob"});
        graph.add(new Object[]{"XWiki.LuxReaders", "foreign:XWiki.remote"});
        var roles = directory.memberships();
        assertTrue(roles.get("editors").contains("XWiki.alice"));
        assertTrue(roles.get("reviewers").contains("XWiki.alice"));
        assertTrue(roles.get("managers").contains("XWiki.bob"));
        assertFalse(roles.get("readers").contains("XWiki.remote"));
    }

    @Test void directoryBindsSearchAndRoleAndBoundsPageSizeAndOffset() throws Exception {
        graph.add(new Object[]{"XWiki.LuxEditors", "XWiki.alice"});
        total = 501;
        people.add(new Object[]{"XWiki.alice", "<script>Alice</script>", null});
        var result = directory.users("editors", "  A_%' OR 1=1  ", "2147483647");
        assertEquals(21, result.get("page")); assertEquals(21L, result.get("pages"));
        assertEquals(501L, result.get("total"));
        verify(queries.get(1)).bindValue("search", "%a!_!%' or 1=1%");
        verify(queries.get(1)).bindValue(eq("members"), argThat(value -> ((List<?>) value).contains("XWiki.alice")));
        verify(queries.get(2)).setLimit(25); verify(queries.get(2)).setOffset(500);
        verify(policy.queries, never()).createQuery(contains("OR 1=1"), anyString());
        var users = (List<Map<String, Object>>) result.get("users");
        assertEquals("XWiki.alice", users.get(0).get("reference"));
        assertEquals("<script>Alice</script>", users.get(0).get("label"));
        assertEquals(List.of("editors"), users.get(0).get("roles"));
    }

    @Test void emptyRoleDoesNotAccidentallyReturnEveryUserAndInvalidInputIsSafe() throws Exception {
        var empty = directory.users("reviewers", null, "-999");
        assertEquals(0L, empty.get("total")); assertEquals(List.of(), empty.get("users"));
        assertEquals(3, queries.size());
        verify(queries.get(1)).bindValue(eq("members"), argThat(value ->
            new java.util.HashSet<>((List<?>) value).equals(Set.of("XWiki.LuxReviewers"))));
        var unknown = directory.users("editors') or 1=1", "x".repeat(200), "not-a-number");
        assertEquals("all", unknown.get("role")); assertEquals(100, unknown.get("search").toString().length());
        assertEquals(1, unknown.get("page"));
    }

    @Test void unassignedFilterExcludesEveryBusinessRole() throws Exception {
        graph.add(new Object[]{"XWiki.LuxReaders", "XWiki.reader"});
        directory.users("unassigned", "", null);
        verify(policy.queries, times(2)).createQuery(contains("doc.fullName not in (:members)"), eq(Query.XWQL));
        verify(queries.get(1)).bindValue(eq("members"), argThat(value -> ((List<?>) value).contains("XWiki.reader")));
    }

    BaseObject rule(String groups, String users, String levels, int allow) throws Exception {
        BaseObject object = spy(new BaseObject());
        for (var entry : Map.of("groups", groups, "users", users, "levels", levels).entrySet()) {
            object.setStringListValue(entry.getKey(), entry.getValue().isEmpty() ? List.of() : List.of(entry.getValue().split(",")));
        }
        object.setIntValue("allow", allow);
        doAnswer(call -> {
            String key = call.getArgument(0); Object value = call.getArgument(1);
            if (key.equals("allow")) { object.setIntValue(key, (int) value); }
            else { object.setStringListValue(key, value.toString().isEmpty() ? List.of() : List.of(value.toString().split(","))); }
            return null;
        }).when(object).set(anyString(), any(), eq(context));
        return object;
    }

    XWikiDocument identity(boolean profile, List<BaseObject> rights) throws Exception {
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getDocumentReference()).thenReturn(new DocumentReference("xwiki", "XWiki", profile ? "alice" : "LuxEditors"));
        when(doc.getObject("XWiki.XWikiUsers")).thenReturn(profile ? mock(BaseObject.class) : null);
        when(doc.getXObjects(any(DocumentReference.class))).thenReturn(rights);
        when(doc.newXObject(any(DocumentReference.class), eq(context))).thenAnswer(call -> {
            BaseObject object = rule("", "", "", 0); rights.add(object); return object;
        });
        when(doc.removeXObject(any(BaseObject.class))).thenAnswer(call -> rights.remove(call.getArgument(0)));
        return doc;
    }

    @Test void profilePrivacyPreservesOtherRightsAndIsIdempotentWithRealListProperties() throws Exception {
        var rights = new ArrayList<BaseObject>();
        rights.add(rule("XWiki.XWikiAllGroup", "", "view,edit", 1));
        BaseObject programming = rule("", "XWiki.Developer", "programming", 1); rights.add(programming);
        XWikiDocument doc = identity(true, rights);
        assertTrue(directory.normalizeView(doc));
        assertEquals(List.of("edit"), rights.get(0).getListValue("levels"));
        assertTrue(rights.contains(programming)); verify(programming, never()).set(anyString(), any(), any());
        assertEquals(List.of("xwiki:XWiki.alice"), rights.get(3).getListValue("users"));
        assertEquals(Set.of("XWiki.LuxManagers", "XWiki.XWikiAdminGroup"), new java.util.HashSet<>(rights.get(2).getListValue("groups")));
        assertFalse(directory.normalizeView(doc));
        verify(doc, never()).setDocumentReference(any());
    }

    @Test void groupsAreVisibleOnlyToAdminsWithoutOwnerException() throws Exception {
        var rights = new ArrayList<BaseObject>(); rights.add(rule("XWiki.LuxEditors", "", "view", 1));
        XWikiDocument doc = identity(false, rights);
        assertTrue(directory.normalizeView(doc)); assertEquals(1, rights.size());
        assertTrue(rights.get(0).getListValue("users").isEmpty());
        assertFalse(directory.normalizeView(doc));
    }

    @Test void initializationRepairsExistingProfilesOnceWithoutReauthoringContent() throws Exception {
        Query profiles = mock(Query.class, RETURNS_SELF), groups = mock(Query.class, RETURNS_SELF);
        when(policy.queries.createQuery(contains("as profile where"), eq(Query.XWQL))).thenReturn(profiles);
        when(policy.queries.createQuery(contains("as membership where"), eq(Query.XWQL))).thenReturn(groups);
        when(profiles.execute()).thenReturn(List.of("XWiki.alice")); when(groups.execute()).thenReturn(List.of());
        var rights = new ArrayList<BaseObject>();
        XWikiDocument doc = identity(true, rights), original = mock(XWikiDocument.class);
        when(original.clone()).thenReturn(doc);
        DocumentReference author = new DocumentReference("xwiki", "XWiki", "originalAuthor");
        when(original.getContentAuthorReference()).thenReturn(author);
        var wiki = mock(com.xpn.xwiki.XWiki.class); when(context.getWiki()).thenReturn(wiki);
        when(wiki.getDocument(new DocumentReference("xwiki", "XWiki", "alice"), context)).thenReturn(original);
        XWikiDocument absent = mock(XWikiDocument.class); when(absent.isNew()).thenReturn(true);
        when(wiki.getDocument(new DocumentReference("xwiki", "XWiki", "WebHome"), context)).thenReturn(absent);
        directory.initialize(); directory.initialize();
        verify(wiki).saveDocument(doc, "Restrict account directory visibility", true, context);
        verify(doc).setContentAuthorReference(author);
        verify(doc, never()).setContent(anyString());
    }

    BaseObject nativeRule(String groups, String users, String levels, int allow) throws Exception {
        BaseObject object = spy(new BaseObject());
        // Real XWiki field classes produce StringProperty, even though the fields
        // conceptually hold lists. Keep the core parser in the fixture's write path.
        for (var entry : Map.of("groups", groups, "users", users, "levels", levels).entrySet()) {
            object.safeput(entry.getKey(), nativeAclProperty(entry.getKey(), entry.getValue()));
        }
        object.setIntValue("allow", allow);
        doAnswer(call -> {
            String key = call.getArgument(0); Object value = call.getArgument(1);
            if (key.equals("allow")) { object.setIntValue(key, (int) value); }
            else { object.safeput(key, nativeAclProperty(key, value.toString())); }
            return null;
        }).when(object).set(anyString(), any(), eq(context));
        return object;
    }

    com.xpn.xwiki.objects.BaseProperty<?> nativeAclProperty(String field, String value) throws Exception {
        return switch (field) {
            case "groups" -> new com.xpn.xwiki.objects.classes.GroupsClass().fromString(value);
            case "users" -> new com.xpn.xwiki.objects.classes.UsersClass().fromString(value);
            case "levels" -> new com.xpn.xwiki.objects.classes.LevelsClass().fromString(value);
            default -> throw new IllegalArgumentException(field);
        };
    }

    @Test void nativeAclInitializationSurvivesTheSecondNormalizationDuringSave() throws Exception {
        var rights = new ArrayList<BaseObject>();
        XWikiDocument doc = identity(false, rights), original = mock(XWikiDocument.class);
        DocumentReference ref = new DocumentReference("xwiki", "XWiki", "LuxManagers");
        when(doc.getDocumentReference()).thenReturn(ref); when(original.clone()).thenReturn(doc);
        when(doc.newXObject(any(DocumentReference.class), eq(context))).thenAnswer(call -> {
            BaseObject object = nativeRule("", "", "", 0); rights.add(object); return object;
        });
        Query profiles = mock(Query.class, RETURNS_SELF), groups = mock(Query.class, RETURNS_SELF);
        when(policy.queries.createQuery(contains("as profile where"), eq(Query.XWQL))).thenReturn(profiles);
        when(policy.queries.createQuery(contains("as membership where"), eq(Query.XWQL))).thenReturn(groups);
        when(profiles.execute()).thenReturn(List.of()); when(groups.execute()).thenReturn(List.of("XWiki.LuxManagers"));
        var wiki = mock(com.xpn.xwiki.XWiki.class); when(context.getWiki()).thenReturn(wiki);
        when(wiki.getDocument(ref, context)).thenReturn(original);
        XWikiDocument absent = mock(XWikiDocument.class); when(absent.isNew()).thenReturn(true);
        when(wiki.getDocument(new DocumentReference("xwiki", "XWiki", "WebHome"), context)).thenReturn(absent);
        doAnswer(call -> {
            // initialize() prepares the ACL, then the save listener reads it again.
            assertInstanceOf(com.xpn.xwiki.objects.StringProperty.class, rights.get(0).safeget("levels"));
            assertThrows(ClassCastException.class, () -> rights.get(0).getListValue("levels"));
            assertFalse(directory.normalizeView(doc));
            return null;
        }).when(wiki).saveDocument(eq(doc), anyString(), eq(true), eq(context));
        directory.initialize(); directory.initialize();
        verify(wiki).saveDocument(doc, "Restrict account directory visibility", true, context);
        assertEquals("view", rights.get(0).getStringValue("levels"));
        assertEquals(IdentityDirectory.ADMINS, rights.get(0).getStringValue("groups"));
    }

    @Test void mixedNativeStringAndLegacyListAclFieldsKeepNonViewRightsAndProfileOwnership() throws Exception {
        var rights = new ArrayList<BaseObject>();
        BaseObject nativeGrant = nativeRule("XWiki.Team With Spaces", "", "view,edit", 1);
        BaseObject programming = nativeRule("", "XWiki.Developer", "programming", 1);
        rights.add(nativeGrant); rights.add(programming);
        rights.add(rule("", "XWiki.other", "view,comment", 1));
        XWikiDocument doc = identity(true, rights);
        assertTrue(directory.normalizeView(doc));
        assertEquals("edit", nativeGrant.getStringValue("levels"));
        assertEquals(List.of("XWiki.Team With Spaces"), IdentityDirectory.rightsValues(nativeGrant, "groups"));
        assertEquals(List.of("comment"), IdentityDirectory.rightsValues(rights.get(2), "levels"));
        verify(programming, never()).set(anyString(), any(), any());
        assertTrue(rights.stream().anyMatch(rule -> IdentityDirectory.rightsValues(rule, "users")
            .equals(List.of("xwiki:XWiki.alice")) && IdentityDirectory.rightsValues(rule, "levels").equals(List.of("view"))));
        assertFalse(directory.normalizeView(doc));
    }

    @Test void absentAndEmptyAclValuesAreSupportedAndUnexpectedTypesStopBeforeMutation() throws Exception {
        BaseObject empty = new BaseObject();
        assertEquals(List.of(), IdentityDirectory.rightsValues(empty, "groups"));
        BaseObject nativeEmpty = nativeRule("", "", "", 0);
        for (String field : List.of("levels", "groups", "users")) {
            assertEquals(List.of(), IdentityDirectory.rightsValues(nativeEmpty, field));
        }
        BaseObject malformed = new BaseObject(); malformed.setIntValue("levels", 9);
        var rights = new ArrayList<BaseObject>(); rights.add(malformed);
        XWikiDocument doc = identity(false, rights);
        assertThrows(IllegalArgumentException.class, () -> directory.normalizeView(doc));
        assertEquals(9, malformed.getIntValue("levels"));
        verify(doc, never()).newXObject(any(DocumentReference.class), any());
        verify(doc, never()).removeXObject(any(BaseObject.class));
    }
}
