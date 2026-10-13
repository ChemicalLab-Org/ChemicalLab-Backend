package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.institution.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only preparation observations; never an authentication resolver. */
@Repository
@RequiredArgsConstructor
public class IdentityPreparationRepository {
    private final JdbcTemplate jdbc;

    public List<Map<String,Object>> inventory() {
        return jdbc.queryForList("select * from t11b5_identifier_inventory order by account_id,kind,origin");
    }
    public List<Map<String,Object>> membershipTimeline(long membershipId) {
        return jdbc.queryForList("select * from t11b5_membership_timeline where membership_id=? order by access_version",membershipId);
    }
    public PlatformRoleGrant grant(long id) {
        return jdbc.queryForObject("select * from t11b5_platform_role_grants where id=?",(r,n)->new PlatformRoleGrant(
            r.getLong("id"),r.getLong("account_id"),PlatformRoleGrant.PlatformRole.valueOf(r.getString("role")),
            PlatformRoleGrant.Status.valueOf(r.getString("status")),instant(r,"valid_from"),instant(r,"valid_until"),
            r.getLong("proof_id"),r.getObject("granted_by",Long.class),instant(r,"revoked_at"),r.getObject("revoked_by",Long.class),r.getString("revocation_reason")),id);
    }
    public AccountLoginIdentifier identifier(long id) {
        return jdbc.queryForObject("select * from t11b5_identifier_observations where id=?",(r,n)->new AccountLoginIdentifier(
            r.getLong("id"),r.getLong("account_id"),AccountLoginIdentifier.Kind.valueOf(r.getString("kind")),r.getString("value"),
            r.getString("comparison_value"),r.getBoolean("verified"),r.getString("status"),r.getBoolean("source_current")),id);
    }
    public AcademicCodeReservation code(long id) {
        return jdbc.queryForObject("select * from t11b5_academic_codes where id=?",(r,n)->new AcademicCodeReservation(
            r.getLong("id"),r.getLong("institution_id"),r.getLong("membership_id"),r.getLong("account_id"),r.getString("code"),instant(r,"reserved_at")),id);
    }
    private static Instant instant(ResultSet r,String column) throws SQLException {
        var t=r.getTimestamp(column);return t==null?null:t.toInstant();
    }
}
