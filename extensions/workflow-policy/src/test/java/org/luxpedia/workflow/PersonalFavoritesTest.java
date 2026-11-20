package org.luxpedia.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.MockedStatic;
import com.xpn.xwiki.web.Utils;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;
import org.xwiki.user.UserReferenceSerializer;
import org.xwiki.model.reference.DocumentReferenceResolver;

class PersonalFavoritesTest {
    WorkflowPolicyListener policy;
    XWikiContext context;
    XWiki wiki;
    PersonalFavorites favorites;
    DocumentReference actor;
    Map<DocumentReference, XWikiDocument> documents;
    List<XWikiDocument> saved;
    MockedStatic<Utils> utils;

    @AfterEach void closeComponents() { if (utils != null) { utils.close(); } }

    DocumentReference ref(String value) { return policy.resolver.resolve(value, new WikiReference("xwiki")); }
    BaseObject object(String name) {
        BaseObject object = new BaseObject(); object.setXClassReference(ref(name)); return object;
    }
    XWikiDocument page(String name, String title) {
        XWikiDocument page = new XWikiDocument(ref(name)); page.setNew(false); page.setTitle(title); page.setVersion("2.1");
        documents.put(page.getDocumentReference(), page); return page;
    }
    void pages(String... names) { documents.get(actor).getObject(PersonalFavorites.CLASS).setStringListValue("pages", List.of(names)); }

    @BeforeEach void setup() throws Exception {
        policy = new WorkflowPolicyListener(); policy.resolver = new WorkflowPolicyListenerTest().realResolver();
        utils = mockStatic(Utils.class, CALLS_REAL_METHODS);
        UserReferenceResolver<DocumentReference> users = mock(UserReferenceResolver.class);
        UserReferenceSerializer<DocumentReference> userDocuments = mock(UserReferenceSerializer.class);
        Map<DocumentReference, UserReference> references = new HashMap<>();
        Map<UserReference, DocumentReference> reverse = new HashMap<>();
        when(users.resolve(any(DocumentReference.class))).thenAnswer(call -> {
            DocumentReference doc = call.getArgument(0);
            UserReference user = references.computeIfAbsent(doc, ignored -> mock(UserReference.class));
            reverse.put(user, doc); return user;
        });
        when(userDocuments.serialize(any(UserReference.class))).thenAnswer(call -> reverse.get(call.getArgument(0)));
        utils.when(() -> Utils.getComponent(UserReferenceResolver.TYPE_DOCUMENT_REFERENCE, "document")).thenReturn(users);
        utils.when(() -> Utils.getComponent(UserReferenceSerializer.TYPE_DOCUMENT_REFERENCE, "document")).thenReturn(userDocuments);
        for (String hint : List.of("currentmixed", "explicit", "current")) {
            utils.when(() -> Utils.getComponent(DocumentReferenceResolver.TYPE_STRING, hint)).thenReturn(policy.resolver);
        }
        DocumentReferenceResolver<EntityReference> entityDocuments = mock(DocumentReferenceResolver.class);
        when(entityDocuments.resolve(any(EntityReference.class), any(Object[].class))).thenAnswer(call -> {
            EntityReference value = call.getArgument(0);
            List<String> spaces = new ArrayList<>(); String wikiName = "xwiki";
            for (EntityReference parent = value.getParent(); parent != null; parent = parent.getParent()) {
                if (parent.getType() == EntityType.SPACE) { spaces.add(0, parent.getName()); }
                if (parent.getType() == EntityType.WIKI) { wikiName = parent.getName(); }
            }
            return new DocumentReference(wikiName, spaces, value.getName());
        });
        utils.when(() -> Utils.getComponent(DocumentReferenceResolver.TYPE_REFERENCE, "current")).thenReturn(entityDocuments);
        utils.when(() -> Utils.getComponent(EntityReferenceSerializer.TYPE_STRING, "local"))
            .thenAnswer(ignored -> policy.localSerializer);
        policy.authorization = mock(AuthorizationManager.class);
        when(policy.authorization.hasAccess(eq(Right.VIEW), any(), any())).thenReturn(true);
        policy.localSerializer = mock(EntityReferenceSerializer.class);
        when(policy.localSerializer.serialize(any())).thenAnswer(call -> call.getArgument(0).toString().replaceFirst("^xwiki:", ""));
        context = mock(XWikiContext.class); wiki = mock(XWiki.class);
        when(context.getWikiId()).thenReturn("xwiki"); when(context.getWiki()).thenReturn(wiki);
        actor = ref("XWiki.reader"); when(context.getUserReference()).thenReturn(actor);
        documents = new HashMap<>(); saved = new ArrayList<>();
        when(wiki.getDocument(any(DocumentReference.class), eq(context))).thenAnswer(call ->
            documents.getOrDefault(call.getArgument(0), new XWikiDocument(call.getArgument(0))));
        when(wiki.getURL(any(DocumentReference.class), eq("view"), eq(context))).thenAnswer(call ->
            "/bin/view/" + call.getArgument(0).toString().replace("xwiki:", "").replace('.', '/'));
        XWikiDocument profile = page("XWiki.reader", "Private profile");
        profile.setContent("Profile body"); profile.setContentAuthorReference(ref("XWiki.originalAuthor"));
        profile.addXObject(object("XWiki.XWikiUsers"));
        BaseObject favorite = object(PersonalFavorites.CLASS); favorite.setStringListValue("pages", List.of());
        favorite.setStringListValue("pinnedPages", List.of("Knowledge.Pinned")); profile.addXObject(favorite);
        doAnswer(call -> {
            XWikiDocument doc = call.getArgument(0);
            assertEquals(actor, PersonalFavorites.SAVING.get());
            assertEquals(actor, doc.getDocumentReference());
            assertEquals("Profile body", doc.getContent());
            assertEquals(ref("XWiki.originalAuthor"), doc.getContentAuthorReference());
            assertEquals(List.of("Knowledge.Pinned"), doc.getObject(PersonalFavorites.CLASS).getListValue("pinnedPages"));
            saved.add(doc); doc.setVersion("2.2"); documents.put(actor, doc);
            return null;
        }).when(wiki).saveDocument(any(), anyString(), eq(true), eq(context));
        favorites = new PersonalFavorites(policy, context);
    }

    @Test void nativeStorageIsPrivateAndOnlyViewableExistingPagesAreReturned() throws Exception {
        page("Knowledge.A", "Visible <script>title</script>"); page("Drafts.Hidden", "SECRET");
        when(policy.authorization.hasAccess(Right.VIEW, actor, ref("Drafts.Hidden"))).thenReturn(false);
        pages("Knowledge.A", "Drafts.Hidden", "Knowledge.Deleted", "foreign:Knowledge.Other", "xwiki:Knowledge.A");
        var result = favorites.read("Knowledge.A", "", "1");
        assertEquals("ok", result.get("status")); assertEquals(1, result.get("total")); assertEquals(true, result.get("favorite"));
        assertTrue(result.toString().contains("Visible <script>title</script>"));
        assertFalse(result.toString().contains("SECRET")); assertFalse(result.toString().contains("Hidden"));
        assertFalse(result.toString().contains("Deleted")); assertFalse(result.toString().contains("foreign:"));
        verify(wiki, never()).getDocument(ref("Drafts.Hidden"), context);
        verify(wiki, never()).saveDocument(any(), anyString(), anyBoolean(), any());
    }

    @Test void listSupportsSearchPaginationAndFiveMostRecentlyAddedFavorites() throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 60; i++) { String name = "Knowledge.Page" + i; names.add(name); page(name, "Article " + i); }
        pages(names.toArray(String[]::new));
        var result = favorites.read("Knowledge.Page59", "", "999999999");
        assertEquals(3, result.get("page")); assertEquals(3, result.get("pages")); assertEquals(60, result.get("total"));
        assertEquals(10, ((List<?>) result.get("items")).size());
        var recent = (List<Map<String, Object>>) result.get("recent");
        assertEquals(5, recent.size()); assertEquals("Article 59", recent.get(0).get("title"));
        var search = favorites.read("", " aRTicle 59 ", "1");
        assertEquals(1, search.get("total")); assertEquals(5, ((List<?>) search.get("recent")).size());
        assertEquals(0, favorites.read("", "%' or 1=1", "1").get("total"));
        assertEquals(1, favorites.read("", "x".repeat(200), "-1").get("page"));
    }

    @Test void readerCanAddAndRemoveWithoutChangingPinnedFavoritesOrProfileContent() throws Exception {
        page("Knowledge.A", "A");
        assertEquals("ok", favorites.change("Knowledge.A", "add", "2.1").get("status"));
        assertEquals(List.of("Knowledge.A"), PersonalFavorites.stored(documents.get(actor)));
        assertEquals(1, saved.size()); assertNull(PersonalFavorites.SAVING.get());
        assertEquals("ok", favorites.change("Knowledge.A", "add", "2.2").get("status"));
        assertEquals(1, saved.size());
        assertEquals("ok", favorites.change("Knowledge.A", "remove", "2.2").get("status"));
        assertEquals(List.of(), PersonalFavorites.stored(documents.get(actor))); assertEquals(2, saved.size());
    }

    @Test void firstFavoriteUsesNativeObjectAndNeverCreatesAnAlternateUserRecord() throws Exception {
        page("Knowledge.A", "A");
        XWikiDocument original = spy(documents.get(actor)); original.removeObjects(PersonalFavorites.CLASS);
        XWikiDocument copy = spy(original.clone());
        doReturn(copy).when(original).clone(); documents.put(actor, original);
        doAnswer(call -> {
            BaseObject created = object(PersonalFavorites.CLASS);
            created.setStringListValue("pinnedPages", List.of("Knowledge.Pinned")); copy.addXObject(created); return created;
        }).when(copy).newXObject(eq(ref(PersonalFavorites.CLASS)), eq(context));
        assertEquals("ok", favorites.change("Knowledge.A", "add", "2.1").get("status"));
        assertEquals(List.of("Knowledge.A"), PersonalFavorites.stored(documents.get(actor)));
        verify(copy).newXObject(ref(PersonalFavorites.CLASS), context);
    }

    @Test void invalidStateOrLostViewCannotAddAndStaleVersionsCannotOverwriteAnotherChange() throws Exception {
        page("Knowledge.A", "A");
        assertEquals("conflict", favorites.change("Knowledge.A", "add", "1.1").get("status"));
        assertEquals("forbidden", favorites.change("Knowledge.Missing", "add", "2.1").get("status"));
        assertEquals("forbidden", favorites.change("foreign:Knowledge.A", "add", "2.1").get("status"));
        assertEquals("forbidden", favorites.change("XWiki.reader", "add", "2.1").get("status"));
        assertEquals("invalid", favorites.change("Knowledge.A", "toggle", "2.1").get("status"));
        when(policy.authorization.hasAccess(Right.VIEW, actor, ref("Knowledge.A"))).thenReturn(false);
        assertEquals("forbidden", favorites.change("Knowledge.A", "add", "2.1").get("status"));
        assertEquals(0, saved.size());
    }

    @Test void removingAnExistingFavoriteDoesNotRequireReadingItsNowPrivateDocument() throws Exception {
        pages("Knowledge.Private");
        when(policy.authorization.hasAccess(Right.VIEW, actor, ref("Knowledge.Private"))).thenReturn(false);
        assertEquals("ok", favorites.change("Knowledge.Private", "remove", "2.1").get("status"));
        verify(wiki, never()).getDocument(ref("Knowledge.Private"), context);
    }

    @Test void canceledSaveAndExceptionsAreNotReportedAsSuccessAndScopeAlwaysClears() throws Exception {
        page("Knowledge.A", "A");
        doNothing().when(wiki).saveDocument(any(), anyString(), eq(true), eq(context));
        assertEquals("error", favorites.change("Knowledge.A", "add", "2.1").get("status"));
        assertNull(PersonalFavorites.SAVING.get());
        doThrow(new RuntimeException("save failed")).when(wiki).saveDocument(any(), anyString(), eq(true), eq(context));
        assertThrows(RuntimeException.class, () -> favorites.change("Knowledge.A", "add", "2.1"));
        assertNull(PersonalFavorites.SAVING.get());
    }

    @Test void unsupportedNativePropertyStorageFailsWithoutErasingFavorites() throws Exception {
        var broken = new com.xpn.xwiki.objects.StringProperty(); broken.setName("pages"); broken.setValue("Knowledge.A Knowledge.B");
        documents.get(actor).getObject(PersonalFavorites.CLASS).safeput("pages", broken);
        assertThrows(IllegalStateException.class, () -> favorites.read("", "", "1"));
        assertThrows(IllegalStateException.class, () -> favorites.change("Knowledge.A", "remove", "2.1"));
        assertTrue(saved.isEmpty());
    }

    @Test void guestAndMissingProfilesAreDeniedWithoutReadingOtherAccounts() throws Exception {
        when(context.getUserReference()).thenReturn(null);
        assertEquals(false, favorites.read("Knowledge.A", "", "1").get("authenticated"));
        when(context.getUserReference()).thenReturn(ref("XWiki.XWikiGuest"));
        assertEquals("forbidden", favorites.change("Knowledge.A", "add", "2.1").get("status"));
        when(context.getUserReference()).thenReturn(ref("XWiki.missing"));
        assertEquals("forbidden", favorites.read("", "", "1").get("status"));
        assertTrue(saved.isEmpty());
    }
}
