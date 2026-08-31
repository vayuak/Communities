package com.SocialService.Communities.Configurations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ShieldHandshakeFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ShieldHandshakeFilter.class);

    // 🟢 SECURE PRACTICE: No fallback allowed.
    @Value("${ghost.shield.key}")
    private String shieldKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incomingKey = request.getHeader("X-Ghost-Shield-Key");

        if (incomingKey != null) {
            incomingKey = incomingKey.trim();
        }

        if (shieldKey.equals(incomingKey)) {
            filterChain.doFilter(request, response);
        } else {
            log.error("INTRUDER ALERT: Direct access to Social Service from IP: {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("Ghost System: Use the Gateway.");
        }
    }
}