package com.SocialService.Communities.Config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Profile({"default", "dev", "test"}) // 🟢 Runs locally without downloading Redis jars
public class LocalCacheEngineImpl implements CacheEngine {

    private final Map<String, Object> simpleStorage = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> hashStorage = new ConcurrentHashMap<>();
    private final Map<String, List<Object>> listStorage = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> zsetStorage = new ConcurrentHashMap<>();

    @Override
    public void addToZSet(String key, String value, double score) {
        zsetStorage.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(value, score);
    }

    @Override
    public Set<Object> reverseRangeZSet(String key, long start, long end) {
        Map<String, Double> map = zsetStorage.get(key);
        if (map == null) return Collections.emptySet();
        return map.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .skip(start)
                .limit(end - start + 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public long getZSetSize(String key) {
        Map<String, Double> map = zsetStorage.get(key);
        return map == null ? 0L : map.size();
    }

    @Override
    public void setValue(String key, Object value, long timeoutHours) {
        simpleStorage.put(key, value);
    }

    @Override
    public Object getValue(String key) {
        return simpleStorage.get(key);
    }

    @Override
    public void putInHash(String key, String hashKey, Object value) {
        hashStorage.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(hashKey, value);
    }

    @Override
    public Object getFromHash(String key, String hashKey) {
        Map<String, Object> map = hashStorage.get(key);
        return map == null ? null : map.get(hashKey);
    }

    @Override
    public void incrementHash(String key, String hashKey, long delta) {
        Map<String, Object> map = hashStorage.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        long current = ((Number) map.getOrDefault(hashKey, 0)).longValue();
        map.put(hashKey, current + delta);
    }

    @Override
    public void leftPushList(String key, Object value) {
        listStorage.computeIfAbsent(key, k -> Collections.synchronizedList(new LinkedList<>())).add(0, value);
    }

    @Override
    public List<Object> rangeList(String key, long start, long end) {
        List<Object> list = listStorage.get(key);
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            long limit = Math.min(end + 1, list.size());
            if (start >= list.size()) return Collections.emptyList();
            return new ArrayList<>(list.subList((int) start, (int) limit));
        }
    }

    @Override
    public void trimList(String key, long start, long end) {
        List<Object> list = listStorage.get(key);
        if (list != null) {
            synchronized (list) {
                if (list.size() > (end + 1)) {
                    list.subList((int) end + 1, list.size()).clear();
                }
            }
        }
    }

    @Override
    public void deleteKey(String key) {
        simpleStorage.remove(key);
        hashStorage.remove(key);
        listStorage.remove(key);
        zsetStorage.remove(key);
    }
}