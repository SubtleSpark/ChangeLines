package dev.subtlespark.changelines;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

@Service(Service.Level.PROJECT)
@State(name = "ChangeLinesReviewStore", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ReviewStore implements PersistentStateComponent<ReviewStore.Data> {
    public static final class Data {
        public Map<String, String> reviewed = new HashMap<>();
    }

    private Data data = new Data();

    @Override
    public synchronized @NotNull Data getState() {
        return data;
    }

    @Override
    public synchronized void loadState(@NotNull Data state) {
        Data copy = new Data();
        XmlSerializerUtil.copyBean(state, copy);
        if (copy.reviewed == null) copy.reviewed = new HashMap<>();
        data = copy;
    }

    synchronized String fingerprint(String key) {
        return data.reviewed.get(key);
    }

    synchronized boolean contains(String key) {
        return data.reviewed.containsKey(key);
    }

    synchronized void mark(String key, String fingerprint) {
        data.reviewed.put(key, fingerprint);
    }

    synchronized void unmark(String key) {
        data.reviewed.remove(key);
    }

    synchronized void clearReviews() {
        data.reviewed.clear();
    }

    synchronized int size() {
        return data.reviewed.size();
    }
}
