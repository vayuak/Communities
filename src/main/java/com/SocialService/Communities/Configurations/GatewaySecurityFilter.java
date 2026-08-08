package com.SocialService.Communities.Configurations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class GatewaySecurityFilter extends OncePerRequestFilter {

    private static final String GATEWAY_SECRET_HEADER = "X-Gateway-Secret";
    private static final String EXPECTED_SECRET = "CryptographicGhostShieldInternalTokenSignature7350_465";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String incomingSecret = request.getHeader(GATEWAY_SECRET_HEADER);

        // 🟢 FIXED: Trim the invisible YAML whitespaces
        if (incomingSecret != null) {
            incomingSecret = incomingSecret.trim();
        }

        if (incomingSecret == null || !incomingSecret.equals(EXPECTED_SECRET)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Access Denied: Direct service connection over internal ports is prohibited.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}