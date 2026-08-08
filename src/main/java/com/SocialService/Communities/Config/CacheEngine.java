package com.SocialService.Communities.Config;

import java.util.List;
import java.util.Set;

public interface CacheEngine {
    void addToZSet(String key, String value, double score);
    Set<Object> reverseRangeZSet(String key, long start, long end);
    long getZSetSize(String key);
    void setValue(String key, Object value, long timeoutHours);
    Object getValue(String key);
    void putInHash(String key, String hashKey, Object value);
    Object getFromHash(String key, String hashKey);
    void incrementHash(String key, String hashKey, long delta);
    void leftPushList(String key, Object value);
    List<Object> rangeList(String key, long start, long end);
    void trimList(String key, long start, long end);
    void deleteKey(String key);
}