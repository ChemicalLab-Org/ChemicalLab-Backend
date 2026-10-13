package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.institution.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** Read mapping only. No legacy consumer uses these records for authorization. */
@Repository
@RequiredArgsConstructor
public class DelegationRepository {
    private final JdbcTemplate jdbc;
    public CoTeachingAssignment coTeacher(long id) {
        return jdbc.queryForObject("select * from t11b6_co_assignments where id=?",(r,n)->new CoTeachingAssignment(
            r.getLong("id"),r.getLong("institution_id"),r.getLong("class_id"),r.getLong("membership_id"),
            instant(r,"valid_from"),instant(r,"valid_until"),instant(r,"activated_at"),instant(r,"ended_at"),
            TeachingAssignment.Status.valueOf(r.getString("status")),instant(r,"state_since"),r.getLong("version")),id);
    }
    public ResourceManagerGrant grant(long id) {
        return jdbc.queryForObject("select * from t11b6_resource_grants where id=?",(r,n)->new ResourceManagerGrant(
            r.getLong("id"),r.getLong("institution_id"),r.getObject("concept_id")==null?ResourceManagerGrant.Kind.EVALUATION:ResourceManagerGrant.Kind.CONCEPT,
            r.getObject("concept_id")==null?r.getLong("evaluation_id"):r.getLong("concept_id"),r.getLong("membership_id"),
            ResourceManagerGrant.Faculty.valueOf(r.getString("faculty")),ResourceManagerGrant.Status.valueOf(r.getString("status")),
            instant(r,"valid_from"),instant(r,"valid_until"),instant(r,"granted_at"),r.getLong("granted_by"),r.getLong("grantor_membership_id"),
            instant(r,"revoked_at"),(Long)r.getObject("revoked_by"),(Long)r.getObject("revoker_membership_id"),r.getString("reason")),id);
    }
    private static Instant instant(ResultSet r,String column) throws SQLException {
        var value=r.getTimestamp(column);return value==null?null:value.toInstant();
    }
}
