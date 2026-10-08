package com.morales.chemicallab.config;

import com.morales.chemicallab.repository.AccountSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SessionCleanup {
    private final AccountSessionRepository sessions;

    // Indexed, idempotent across instances. Expiration is enforced even before cleanup.
    @Scheduled(fixedDelayString = "${app.sessions.cleanup-ms:3600000}", initialDelayString = "${app.sessions.cleanup-ms:3600000}")
    @Transactional
    public void deleteExpired() { sessions.deleteExpired(Instant.now()); }
}
