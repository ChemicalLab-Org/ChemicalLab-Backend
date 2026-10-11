package com.morales.chemicallab.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.util.*;

@Component
public class ClientOriginResolver {
    private final List<IpAddressMatcher> trusted;
    public ClientOriginResolver(@Value("${app.auth.trusted-proxies:}") String cidrs) {
        trusted = Arrays.stream(cidrs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(cidr -> {
            String[] parts = cidr.split("/", -1);
            if (parts.length > 2 || literal(parts[0]) == null) throw new IllegalStateException("Trusted proxies must be literal IP/CIDR ranges");
            return new IpAddressMatcher(cidr);
        }).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (peer == null) return "unknown-peer";
        if (!isTrusted(peer)) return peer;
        // Forwarded is intentionally ignored. The configured edge must append/sanitize XFF.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 2048) return peer;
        String[] chain = forwarded.split(",", -1);
        if (chain.length > 20) return peer;
        String current = peer;
        for (int i = chain.length - 1; i >= 0 && isTrusted(current); i--) {
            String next = literal(chain[i].trim());
            if (next == null) return peer;
            current = next;
        }
        return current;
    }

    private boolean isTrusted(String ip) { return trusted.stream().anyMatch(range -> range.matches(ip)); }

    static String literal(String value) {
        if (value == null || value.length() > 45) return null;
        if (!value.contains(":")) {
            if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return null;
            for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) return null;
        } else if (!value.matches("[0-9a-fA-F:.]+")) return null;
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (Exception ex) { return null; }
    }
}
