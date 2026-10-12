package com.morales.chemicallab.security.limits;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** One bounded registry keyed by server account, shared by all its sessions on this instance.
 * Disconnect must NOT erase a budget: reconnect cannot refill it. Idle expiry restores memory. */
@Component @RequiredArgsConstructor
public class AccountTrafficLimits {
    private final TransportLimits limits;
    private final Map<Long, Account> accounts = new HashMap<>();
    private static class Account { long last; final Map<String, Bucket> buckets = new HashMap<>(); }
    private static class Bucket { double tokens; long time; Bucket(int burst, long now) { tokens = burst; time = now; } }
    public void take(long account, String category) { take(account, category, System.nanoTime()); }
    synchronized void take(long id, String category, long now) {
        expire(now);
        Account account = accounts.get(id);
        if (account == null) {
            if (accounts.size() >= limits.getRateAccounts()) throw new InputRejectedException(503, "TRAFFIC_UNAVAILABLE", "El control de tráfico está ocupado. Reintenta más tarde.");
            account = new Account(); accounts.put(id, account);
        }
        int rate, burst;
        switch (category) {
            case "draw" -> { rate = limits.getDrawPerSecond(); burst = limits.getDrawBurst(); }
            case "frame" -> { rate = limits.getFramePerSecond(); burst = limits.getFrameBurst(); }
            case "presence" -> { rate = limits.getPresencePerSecond(); burst = limits.getPresenceBurst(); }
            case "control" -> { rate = limits.getControlPerSecond(); burst = limits.getControlBurst(); }
            case "telemetry" -> { rate = limits.getTelemetryPerSecond(); burst = limits.getTelemetryBurst(); }
            case "rest" -> { rate = limits.getRestPerSecond(); burst = limits.getRestBurst(); }
            default -> throw new IllegalArgumentException("Unknown internal traffic category");
        }
        account.last = now;
        var bucket = account.buckets.computeIfAbsent(category, ignored -> new Bucket(burst, now));
        bucket.tokens = Math.min(burst, bucket.tokens + Math.max(0, now - bucket.time) / 1_000_000_000d * rate);
        bucket.time = now;
        if (bucket.tokens < 1) throw new InputRejectedException(429, "TRAFFIC_RATE_LIMITED", "Demasiadas acciones. Espera un segundo y reintenta.");
        bucket.tokens--;
    }
    public synchronized void expire() { expire(System.nanoTime()); }
    private void expire(long now) { accounts.values().removeIf(a -> now - a.last >= limits.getRateIdleSeconds() * 1_000_000_000L); }
    public synchronized int size() { return accounts.size(); }
}
