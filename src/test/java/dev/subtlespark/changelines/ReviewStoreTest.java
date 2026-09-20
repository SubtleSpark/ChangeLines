package dev.subtlespark.changelines;

import com.intellij.testFramework.LightPlatformTestCase;

public final class ReviewStoreTest extends LightPlatformTestCase {
    public void testMarkUnmarkAndLoadState() {
        ReviewStore store = getProject().getService(ReviewStore.class);
        store.clearReviews();
        store.mark("root\0A.java", "fp1");
        assertEquals("fp1", store.fingerprint("root\0A.java"));
        assertTrue(store.contains("root\0A.java"));
        assertEquals(1, store.size());

        ReviewStore.Data state = new ReviewStore.Data();
        state.reviewed.put("root\0B.java", "fp2");
        store.loadState(state);
        assertNull(store.fingerprint("root\0A.java"));
        assertEquals("fp2", store.fingerprint("root\0B.java"));

        store.unmark("root\0B.java");
        assertFalse(store.contains("root\0B.java"));
    }
}
