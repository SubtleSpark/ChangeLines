package dev.subtlespark.changelines;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.TreeMap;

/** Machine-local IDE settings, not a file in the project and not Settings Sync data. */
@Service(Service.Level.APP)
@State(name = "ChangeLinesReviews", storages = @Storage(value = "ChangeLinesReviews.xml", roamingType = RoamingType.DISABLED))
public final class ReviewStore implements PersistentStateComponent<ReviewStore.Data> {
    public static final class Data {
        public Map<String, String> reviewed = new TreeMap<>();
    }

    private final Map<String, String> reviewed = new TreeMap<>();
    private final Map<String, String> temporary = new TreeMap<>();

    public synchronized String fingerprint(String scope, String file) {
        return records(scope).get(key(scope, file));
    }

    public synchronized void mark(String scope, String file, String fingerprint) {
        if (!isHash(fingerprint)) throw new IllegalArgumentException("A verified content fingerprint is required");
        records(scope).put(key(scope, file), fingerprint);
    }

    public synchronized void unmark(String scope, String file) {
        records(scope).remove(key(scope, file));
    }

    @Override public synchronized @NotNull Data getState() {
        Data copy = new Data();
        copy.reviewed.putAll(reviewed);
        return copy;
    }

    @Override public synchronized void loadState(@NotNull Data data) {
        reviewed.clear();
        if (data.reviewed != null) data.reviewed.forEach((key, value) -> {
            if (isHash(key) && isHash(value)) reviewed.put(key, value);
        });
    }

    private Map<String, String> records(String scope) {
        return scope.startsWith("temporary:") ? temporary : reviewed;
    }

    private static String key(String scope, String file) {
        return ReviewFingerprint.hash("ChangeLines.review.record.v1", scope, file);
    }

    private static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
