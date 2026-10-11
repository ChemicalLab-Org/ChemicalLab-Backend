package com.morales.chemicallab.security;

import com.morales.chemicallab.dto.LoginRequest;
import com.morales.chemicallab.repository.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.morales.chemicallab.security.AuthenticationLimitStore.Bucket;

@Component
@RequiredArgsConstructor
public class AuthenticationLimits {
    private final AuthenticationLimitStore store;
    private final ClientOriginResolver origins;
    private final UserAccountRepository users;
    private final StudentProfileRepository students;

    public void request(HttpServletRequest request) {
        var buckets = new ArrayList<>(List.of(new Bucket("global:second", 20, 1), new Bucket("global:minute", 300, 60),
                bucket("origin", origins.resolve(request), 300, 900)));
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            buckets.add(bucket("actor", principal.userId().toString(), 60, 900));
        }
        store.acquire(buckets);
    }

    public void login(LoginRequest request) {
        String identifier = request.usernameOrEmail().trim();
        store.acquire(List.of(bucket("identifier", identifier, 10, 900)));
        var account = users.findByUsername(identifier);
        if (account.isEmpty() && identifier.contains("@")) account = users.findByEmail(identifier);
        account.ifPresent(user -> store.acquire(List.of(bucket("login-account", user.getId().toString(), 10, 900))));
    }

    public void change() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            store.acquire(List.of(bucket("change-account", principal.userId().toString(), 5, 900)));
        }
    }

    public void resetUser(Long id) { store.acquire(List.of(bucket("reset-account", id.toString(), 5, 900))); }
    public void resetStudent(Long id) {
        // Use the account ID so admin and teacher routes share the same target limit.
        var student = students.findById(id);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (student.isPresent() && authentication != null
                && authentication.getPrincipal() instanceof SessionPrincipal principal
                && student.get().getTeacher().getUser().getId().equals(principal.userId())) {
            resetUser(student.get().getUser().getId());
        }
        else store.acquire(List.of(bucket("reset-missing-student", id.toString(), 5, 900)));
    }

    static Bucket bucket(String scope, String identity, int limit, int seconds) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            return new Bucket(scope + ":" + digest, limit, seconds);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
