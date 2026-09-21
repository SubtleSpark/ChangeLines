package dev.subtlespark.changelines;

import com.intellij.util.xmlb.XmlSerializer;
import org.junit.Test;

import static org.junit.Assert.*;

public final class ReviewStoreTest {
    @Test public void contentFingerprintIsOrderedAndUnambiguous() {
        assertNotEquals(ReviewFingerprint.content(null, ""), ReviewFingerprint.content("", null));
        assertNotEquals(ReviewFingerprint.content("a", "b"), ReviewFingerprint.content("b", "a"));
        assertNotEquals(ReviewFingerprint.hash("a", "bc"), ReviewFingerprint.hash("ab", "c"));
        assertNotEquals(ReviewFingerprint.content("a\n", "x\n"), ReviewFingerprint.content("a\n", "y\n"));
        assertNotEquals(ReviewFingerprint.content("a", "a\n"), ReviewFingerprint.content("a", "a"));
        assertNotEquals(ReviewFingerprint.hash("\uD800"), ReviewFingerprint.hash("?"));
        assertEquals(ReviewFingerprint.content("中文\n", "猫\n"), ReviewFingerprint.content("中文\n", "猫\n"));
    }

    @Test public void approvalsSurviveRealXmlSerializationWithoutSourceContent() {
        ReviewStore store = new ReviewStore();
        String hash = ReviewFingerprint.content("private-before", "private-after");
        store.mark("comparison-a", "file-a", hash);
        var xml = XmlSerializer.serialize(store.getState());
        ReviewStore restored = new ReviewStore();
        restored.loadState(XmlSerializer.deserialize(xml, ReviewStore.Data.class));
        assertEquals(hash, restored.fingerprint("comparison-a", "file-a"));
        assertNull(restored.fingerprint("comparison-b", "file-a"));
        assertNull(restored.fingerprint("comparison-a", "file-b"));
        assertFalse(new org.jdom.output.XMLOutputter().outputString(xml).contains("private-before"));
        assertFalse(new org.jdom.output.XMLOutputter().outputString(xml).contains("private-after"));
    }

    @Test public void unmarkAndDefensiveStateCopiesDoNotAffectOtherRecords() {
        ReviewStore store = new ReviewStore();
        String one = ReviewFingerprint.content("old", "one");
        String two = ReviewFingerprint.content("old", "two");
        store.mark("scope", "a", one);
        store.mark("scope", "b", two);
        store.getState().reviewed.clear();
        assertEquals(one, store.fingerprint("scope", "a"));
        store.unmark("scope", "a");
        assertNull(store.fingerprint("scope", "a"));
        assertEquals(two, store.fingerprint("scope", "b"));
    }

    @Test public void temporaryScopesAndInvalidRecordsAreNotPersisted() {
        ReviewStore store = new ReviewStore();
        store.mark("temporary:test", "a", ReviewFingerprint.content("a", "b"));
        assertTrue(store.getState().reviewed.isEmpty());
        ReviewStore.Data invalid = new ReviewStore.Data();
        invalid.reviewed.put("invalid", "invalid");
        store.loadState(invalid);
        assertTrue(store.getState().reviewed.isEmpty());
        try { store.mark("scope", "file", null); fail("Unverified content must not be marked"); }
        catch (IllegalArgumentException expected) { }
    }
}
