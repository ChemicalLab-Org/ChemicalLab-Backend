package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.AccountSession;
import com.morales.chemicallab.entity.Role;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountSessionRepository extends JpaRepository<AccountSession, UUID> {
    @Query("select s from AccountSession s join fetch s.user where s.id = :id")
    Optional<AccountSession> findWithUserById(UUID id);

    @Query("""
        select count(s) > 0 from AccountSession s join s.user u
        where s.id = :id and u.id = :userId and u.username = :username and u.role = :role
            and u.active = true and (u.temporaryPassword is null or u.temporaryPassword = false)
            and s.revokedAt is null and s.expiresAt > :now
            and s.credentialsVersion = :version and u.credentialsVersion = :version
        """)
    boolean isCurrent(UUID id, Long userId, String username, Role role, long version, Instant now);

    @Modifying
    @Query("delete from AccountSession s where s.expiresAt <= :now")
    int deleteExpired(Instant now);
}
