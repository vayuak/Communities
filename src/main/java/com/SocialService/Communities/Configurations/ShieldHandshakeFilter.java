package com.SocialService.Communities.Configurations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class ShieldHandshakeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ShieldHandshakeFilter.class);
    private static final String SHIELD_KEY = "PermanentSecret999";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String incomingKey = request.getHeader("X-Ghost-Shield-Key");

        // 🟢 FIXED: Trim the invisible YAML whitespaces
        if (incomingKey != null) {
            incomingKey = incomingKey.trim();
        }

        if (SHIELD_KEY.equals(incomingKey)) {
            filterChain.doFilter(request, response);
        } else {
            log.error("INTRUDER ALERT: Direct access to Social Service from IP: {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("Ghost System: Use the Gateway.");
        }
    }
}