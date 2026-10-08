package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.AccountSession;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountSessionRepository extends JpaRepository<AccountSession, UUID> {
    @Query("select s from AccountSession s join fetch s.user where s.id = :id")
    Optional<AccountSession> findWithUserById(UUID id);

    @Modifying
    @Query("delete from AccountSession s where s.expiresAt <= :now")
    int deleteExpired(Instant now);
}
