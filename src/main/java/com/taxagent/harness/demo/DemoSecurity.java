package com.taxagent.harness.demo;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Direct local access only; forwarded headers never establish trust. */
@Component
public final class DemoSecurity extends OncePerRequestFilter {
    static final String CSRF = DemoSecurity.class.getName() + ".csrf";
    static final String PRINCIPAL = DemoSecurity.class.getName() + ".principal";
    static String token(HttpSession session) {
        synchronized (session) {
            if (session.getAttribute(CSRF) == null) session.setAttribute(CSRF, UUID.randomUUID().toString());
            return (String) session.getAttribute(CSRF);
        }
    }
    private static boolean loopback(String address) {
        return address != null && (address.equals("::1") || address.equals("0:0:0:0:0:0:0:1")
                || address.matches("127(?:\\.(?:[0-9]{1,3})){3}"));
    }
    private static boolean localHost(String host) {
        return "localhost".equals(host) || "[::1]".equals(host) || loopback(host);
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        if (!req.getRequestURI().startsWith("/api/demo/")) {
            chain.doFilter(req, res); return;
        }
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        if (req.getQueryString() != null || !loopback(req.getRemoteAddr()) || !localHost(req.getServerName())
                || req.getHeader("Forwarded") != null || req.getHeader("X-Forwarded-For") != null
                || req.getHeader("X-Forwarded-Host") != null || "cross-site".equals(req.getHeader("Sec-Fetch-Site"))) {
            reject(res, "LOOPBACK_REQUIRED"); return;
        }
        String origin = req.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                if (!req.getScheme().equals(uri.getScheme()) || !req.getServerName().equals(uri.getHost())
                        || req.getServerPort() != port || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null
                        || (uri.getPath() != null && !uri.getPath().isEmpty())) throw new IllegalArgumentException();
            } catch (Exception e) { reject(res, "ORIGIN_DENIED"); return; }
        }
        if (!Set.of("GET", "POST", "DELETE").contains(req.getMethod())) { reject(res, "METHOD_DENIED"); return; }
        if (!"GET".equals(req.getMethod())) {
            var session = req.getSession(false);
            if (session == null || !Objects.equals(session.getAttribute(CSRF), req.getHeader("X-CSRF-Token"))
                    || req.getHeader("X-CSRF-Token") == null) { reject(res, "CSRF_REQUIRED"); return; }
        }
        chain.doFilter(req, res);
    }
    private static void reject(HttpServletResponse res, String code) throws IOException {
        res.setStatus(403); res.setContentType("application/json");
        res.getWriter().write("{\"errorCode\":\"" + code + "\"}");
    }
}
