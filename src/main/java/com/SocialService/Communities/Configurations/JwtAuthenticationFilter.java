package com.SocialService.Communities.Configurations;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        try {
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String jwt = authHeader.substring(7);

                if (jwtUtils.validateJwtToken(jwt)) {
                    Claims claims = jwtUtils.getClaimsFromToken(jwt);

                    Object idObj = claims.get("userId");
                    if (idObj == null) idObj = claims.get("id");
                    Long userId = idObj != null ? Long.valueOf(idObj.toString()) : 0L;

                    // 🟢 ROBUST IDENTIFICATION: Matches API Gateway exact logic
                    String username;
                    if (claims.get("username") != null && !claims.get("username").toString().trim().isEmpty()) {
                        username = claims.get("username").toString().trim().toLowerCase();
                    } else if (claims.getSubject() != null && !claims.getSubject().trim().isEmpty() && !claims.getSubject().startsWith("user_")) {
                        username = claims.getSubject().trim().toLowerCase();
                    } else {
                        username = "user_" + userId;
                    }

                    Boolean isPremium = claims.get("isPremium", Boolean.class);
                    if (isPremium == null) isPremium = false;

                    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                        request.setAttribute("userId", userId);
                        request.setAttribute("username", username);
                        request.setAttribute("isPremium", isPremium);

                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(username, null, new ArrayList<>());
                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        log.info("✅ Auth context successfully established for Node: {}", username);
                    }
                }
            }
        } catch (Exception e) {
            log.error("🚨 JWT Parsing Exception intercepted: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}