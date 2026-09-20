package dev.subtlespark.changelines;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class ReviewFingerprintTest {
    @Test
    public void fingerprintIsStableAndSensitiveToEveryInput() {
        String identity = ReviewFingerprint.identity("/repo", "/repo/A.java", "abc");
        assertEquals(64, identity.length());
        assertNotEquals(identity, ReviewFingerprint.identity("/repo", "/repo/A.java", "def"));

        String base = ReviewFingerprint.calculate("/repo", "/repo/A.java", "abc", "before", "after");
        assertEquals(base, ReviewFingerprint.calculate("/repo", "/repo/A.java", "abc", "before", "after"));
        assertNotEquals(base, ReviewFingerprint.calculate("/repo2", "/repo/A.java", "abc", "before", "after"));
        assertNotEquals(base, ReviewFingerprint.calculate("/repo", "/repo/B.java", "abc", "before", "after"));
        assertNotEquals(base, ReviewFingerprint.calculate("/repo", "/repo/A.java", "def", "before", "after"));
        assertNotEquals(base, ReviewFingerprint.calculate("/repo", "/repo/A.java", "abc", "before2", "after"));
        assertNotEquals(base, ReviewFingerprint.calculate("/repo", "/repo/A.java", "abc", "before", "after2"));
    }
}
