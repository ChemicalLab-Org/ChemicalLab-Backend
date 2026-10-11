package com.morales.chemicallab.security;

import com.morales.chemicallab.exception.AuthenticationLimitException;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** Shared PostgreSQL counters, committed before controller/account transactions start. */
@Component
public class AuthenticationLimitStore {
    public record Bucket(String key, int limit, int seconds) { }
    static final int CAPACITY = 50_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AuthenticationLimitStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(3);
    }

    public void acquire(List<Bucket> buckets) {
        long delay;
        try {
            delay = transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '1500ms'");
                jdbc.execute("SET LOCAL lock_timeout = '1000ms'");
                // One short critical section, never held while hashing or locking an account.
                jdbc.execute("SELECT pg_advisory_xact_lock(14004001)");
                long now = jdbc.queryForObject("SELECT floor(extract(epoch from clock_timestamp()) * 1000)::bigint", Long.class);
                return acquireAt(buckets, now);
            });
        } catch (RuntimeException ex) {
            // No SQL values, identifiers, secrets or driver exception text in diagnostics.
            LoggerFactory.getLogger(getClass()).warn("Authentication limits unavailable ({})", ex.getClass().getSimpleName());
            throw new AuthenticationLimitException(503, 30);
        }
        // Throw after commit: failed attempts must not disappear with the caller's rollback.
        if (delay > 0) throw new AuthenticationLimitException(429, delay);
    }

    long acquireAt(List<Bucket> buckets, long now) {
        jdbc.update("DELETE FROM authentication_limits WHERE bucket IN (SELECT bucket FROM authentication_limits WHERE expires_at <= ? ORDER BY expires_at LIMIT 256)", now);
        for (Bucket bucket : buckets) {
            var rows = jdbc.query("SELECT attempts, expires_at FROM authentication_limits WHERE bucket = ?",
                    (rs, row) -> new long[]{rs.getInt(1), rs.getLong(2)}, bucket.key());
            if (!rows.isEmpty() && rows.get(0)[1] > now && rows.get(0)[0] >= bucket.limit()) {
                return Math.max(1, (rows.get(0)[1] - now + 999) / 1000);
            }
            if (rows.isEmpty() && jdbc.queryForObject("SELECT count(*) FROM authentication_limits", Long.class) >= CAPACITY) {
                throw new IllegalStateException("Counter capacity reached");
            }
            jdbc.update("""
                    INSERT INTO authentication_limits(bucket, attempts, expires_at) VALUES (?, 1, ?)
                    ON CONFLICT (bucket) DO UPDATE SET
                      attempts = CASE WHEN authentication_limits.expires_at <= ? THEN 1 ELSE authentication_limits.attempts + 1 END,
                      expires_at = CASE WHEN authentication_limits.expires_at <= ? THEN EXCLUDED.expires_at ELSE authentication_limits.expires_at END
                    """, bucket.key(), now + bucket.seconds() * 1000L, now, now);
        }
        return 0;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void cleanup() {
        try {
            jdbc.update("DELETE FROM authentication_limits WHERE bucket IN (SELECT bucket FROM authentication_limits WHERE expires_at <= floor(extract(epoch from clock_timestamp()) * 1000)::bigint ORDER BY expires_at LIMIT 5000)");
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).warn("Authentication limit cleanup unavailable");
        }
    }
}
