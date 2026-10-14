package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.institution.AcademicRevision;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AcademicRevisionRepository {
    private final JdbcTemplate jdbc;

    public AcademicRevision revision(long id) {
        return jdbc.queryForObject("select * from t11b8_revisions where id=?", (rs, n) ->
            new AcademicRevision(rs.getLong("id"), rs.getLong("resource_id"), rs.getLong("institution_id"),
                rs.getObject("parent_id", Long.class), rs.getInt("revision_number"), rs.getLong("version"),
                rs.getString("status"), rs.getString("payload"), rs.getString("payload_checksum"),
                rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant()), id);
    }
}
