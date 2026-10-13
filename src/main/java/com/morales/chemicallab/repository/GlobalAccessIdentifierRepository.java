package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.institution.GlobalAccessIdentifier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal persistence reads; deliberately no login resolution method. */
@Repository
@RequiredArgsConstructor
public class GlobalAccessIdentifierRepository {
    private final JdbcTemplate jdbc;
    public GlobalAccessIdentifier identifier(long id) {
        return jdbc.queryForObject("select * from t11b7_identifiers where id=?", (r,n)->new GlobalAccessIdentifier(
            r.getLong("id"),r.getLong("account_id"),GlobalAccessIdentifier.Kind.valueOf(r.getString("kind")),r.getString("value"),
            GlobalAccessIdentifier.Status.valueOf(r.getString("status")),r.getLong("version"),r.getObject("operation_id",UUID.class),
            time(r,"activated_at"),time(r,"terminated_at"),r.getObject("proof_id",Long.class),r.getObject("replaces_id",Long.class),r.getObject("replaces_version",Long.class)),id);
    }
    public List<Map<String,Object>> history(long identifierId) {
        return jdbc.queryForList("select * from t11b7_events where target_type='t11b7_identifiers' and target_id=? order by id",identifierId);
    }
    private static Instant time(ResultSet r,String column) throws SQLException {
        var timestamp=r.getTimestamp(column);return timestamp==null?null:timestamp.toInstant();
    }
}
