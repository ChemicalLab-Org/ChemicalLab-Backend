package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    long countByRole(Role role);

    long countByActive(Boolean active);

    long countByRoleAndActive(Role role, Boolean active);

    // Últimos usuarios registrados (actividad reciente del panel administrativo).
    List<UserAccount> findTop8ByOrderByCreatedAtDesc();
}
