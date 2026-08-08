package com.SocialService.Communities.Controllers;

import com.SocialService.Communities.Models.Post;
import com.SocialService.Communities.Repositories.PostRepository;
import com.SocialService.Communities.Clients.UserCatalogClient;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostRepository postRepository;
    private final UserCatalogClient userCatalogClient;

    @GetMapping("/search")
    public ResponseEntity<?> globalSearchMatrix(@RequestParam String query) {
        String input = query.trim();
        if (input.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Search query is vacant."));
        }

        Map<String, Object> targetPayload = new HashMap<>();

        // 🟢 PATHWAY A: Proxy User Discovery across the network boundary
        if (input.startsWith("@")) {
            String targetHandle = input.substring(1);

            // Outbound network sweep to USER-CATALOG-SERVICE stamped with PermanentSecret999
            List<Map<String, Object>> matchedUsers = userCatalogClient.searchUsersByHandle(targetHandle);

            targetPayload.put("type", "USERS");
            targetPayload.put("results", matchedUsers);
            return ResponseEntity.ok(targetPayload);
        }

        // 🟢 PATHWAY B: Standard local post keyword matrix sweep
        Pageable pageable = PageRequest.of(0, 20);
        List<Post> matchedPosts = postRepository.searchGlobalScams(input.toLowerCase(), pageable).getContent();

        targetPayload.put("type", "POSTS");
        targetPayload.put("results", matchedPosts);
        return ResponseEntity.ok(targetPayload);
    }
}