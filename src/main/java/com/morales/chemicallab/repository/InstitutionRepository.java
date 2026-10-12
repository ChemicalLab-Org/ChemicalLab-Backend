package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.institution.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** Read mapping only: mutations must use the commit-coupled internal service. */
@Repository
@RequiredArgsConstructor
public class InstitutionRepository {
    private final JdbcTemplate jdbc;

    public Institution institution(long id) {
        return jdbc.queryForObject("select * from institutions where id=?", (r,n)->new Institution(
            r.getLong("id"),r.getString("platform_code"),r.getString("name"),r.getString("timezone"),
            Institution.Status.valueOf(r.getString("status")),r.getLong("access_version"),
            instant(r,"created_at"),instant(r,"updated_at")),id);
    }
    public InstitutionMembership membership(long id) {
        return jdbc.queryForObject("select * from institution_memberships where id=?",(r,n)->new InstitutionMembership(
            r.getLong("id"),r.getLong("institution_id"),r.getLong("account_id"),
            InstitutionMembership.Status.valueOf(r.getString("status")),instant(r,"valid_from"),
            instant(r,"valid_until"),instant(r,"state_since"),r.getLong("access_version")),id);
    }
    public MembershipRoleGrant grant(long id) {
        return jdbc.queryForObject("select * from membership_role_grants where id=?",(r,n)->new MembershipRoleGrant(
            r.getLong("id"),r.getLong("membership_id"),r.getLong("institution_id"),
            MembershipRoleGrant.InstitutionalRole.valueOf(r.getString("role")),
            MembershipRoleGrant.Status.valueOf(r.getString("status")),instant(r,"valid_from"),instant(r,"valid_until"),
            r.getObject("granted_by",Long.class),r.getObject("revoked_by",Long.class),instant(r,"revoked_at"),
            r.getString("revocation_reason")),id);
    }
    private static Instant instant(ResultSet r,String column) throws SQLException {
        var value=r.getTimestamp(column); return value==null?null:value.toInstant();
    }
}
