package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.entity.AccountSession;
import com.morales.chemicallab.config.RuntimeEnvironment;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs,
            Environment environment
    ) {
        byte[] keyBytes = JwtKeyValidator.validate(secret, RuntimeEnvironment.from(environment) == RuntimeEnvironment.PROD);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        if (expirationMs < 1000) throw new IllegalArgumentException("JWT expiration must be at least 1000 ms");
        this.expirationMs = expirationMs;
    }

    public String generateToken(UserAccount user, AccountSession session) {
        Date now = Date.from(session.getCreatedAt());
        Date expiration = Date.from(session.getExpiresAt());

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("cv", session.getCredentialsVersion());

        return Jwts.builder()
                .id(session.getId().toString())
                .subject(user.getUsername())
                .claims(claims)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
