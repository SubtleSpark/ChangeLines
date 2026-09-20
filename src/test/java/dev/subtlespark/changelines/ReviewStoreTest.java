package dev.subtlespark.changelines;

import com.intellij.testFramework.LightPlatformTestCase;

public final class ReviewStoreTest extends LightPlatformTestCase {
    public void testMarkUnmarkAndLoadState() {
        ReviewStore store = getProject().getService(ReviewStore.class);
        store.clearReviews();
        store.mark("key-a", "fp1");
        assertEquals("fp1", store.fingerprint("key-a"));
        assertTrue(store.contains("key-a"));
        assertEquals(1, store.size());

        ReviewStore.Data state = new ReviewStore.Data();
        state.reviewed.put("key-b", "fp2");
        store.loadState(state);
        assertNull(store.fingerprint("key-a"));
        assertEquals("fp2", store.fingerprint("key-b"));

        store.unmark("key-b");
        assertFalse(store.contains("key-b"));
    }
}
