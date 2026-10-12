package com.morales.chemicallab.security.limits;

import com.morales.chemicallab.security.SessionPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

/** Installed after HTTP authorization; reads at most budget+1 bytes, including chunked bodies. */
@RequiredArgsConstructor
public class TransportInputFilter extends OncePerRequestFilter {
    private final TransportLimits limits;
    private final JsonInputGuard guard;
    private final AccountTrafficLimits traffic;
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        boolean telemetry = path.equals("/api/usage-metrics/events") || path.matches("/api/evaluations/student/attempts/[0-9]+/events");
        boolean board = path.startsWith("/api/whiteboards/");
        boolean sockjs = path.startsWith("/ws/") && path.endsWith("/xhr_send") && request.getMethod().equals("POST");
        if (!telemetry && !board && !sockjs) { chain.doFilter(request, response); return; }
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!sockjs && authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal)
                traffic.take(principal.userId(), telemetry ? "telemetry" : "rest");
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) { chain.doFilter(request, response); return; }
            // Do not let a different Content-Type evade byte limits on affected JSON endpoints.
            int max = sockjs ? limits.getStompBytes() * 2 : telemetry ? limits.getTelemetryBytes() : limits.getHttpBytes();
            if (request.getContentLengthLong() > max) throw InputRejectedException.size();
            byte[] bytes = request.getInputStream().readNBytes(max + 1);
            if (bytes.length > max) throw InputRejectedException.size();
            if (!sockjs && bytes.length > 0) guard.check(bytes, board && path.endsWith("/state"), telemetry);
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var source = new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        public int read() { return source.read(); }
                        public int read(byte[] b, int off, int len) { return source.read(b, off, len); }
                        public boolean isFinished() { return source.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous MVC only"); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
            }, response);
        } catch (InputRejectedException ex) {
            response.setStatus(ex.status()); response.setContentType("application/json");
            if (ex.status() == 429 || ex.status() == 503) response.setHeader("Retry-After", "1");
            // Codes/messages are fixed server constants; never include input or parser diagnostics.
            response.getWriter().write("{\"status\":" + ex.status() + ",\"code\":\"" + ex.code() + "\",\"message\":\"" + ex.getMessage() + "\"}");
        }
    }
}
