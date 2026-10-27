package com.SocialService.Communities.Utilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user-catalog "search by handle" call is a SUBSTRING search. Callers used to take element 0, which returned the wrong
 * person's avatar/profile ("sam" -> "samuel") and forwarded whole user rows to clients. These helpers only ever expose
 * username + profilePictureUrl and only accept an exact username match.
 */
public final class SafeUsers {
    private SafeUsers() {}

    public static Map<String, Object> exact(List<Map<String, Object>> found, String handle) {
        if (found == null || handle == null) return null;
        String h = handle.replace("@", "").trim().toLowerCase();
        for (Map<String, Object> u : found) {
            Object name = u.get("username");
            if (name != null && h.equals(name.toString().trim().toLowerCase())) return sanitizeOne(u);
        }
        return null;
    }

    public static List<Map<String, Object>> sanitize(List<Map<String, Object>> found) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (found == null) return out;
        for (Map<String, Object> u : found) out.add(sanitizeOne(u));
        return out;
    }

    private static Map<String, Object> sanitizeOne(Map<String, Object> u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.get("username"));
        m.put("profilePictureUrl", u.get("profilePictureUrl"));
        return m;
    }
}
