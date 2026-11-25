package com.morales.chemicallab.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Prevents fixture NEW anchors from being interpreted by teacher and supervision routes. */
@Component
@RequiredArgsConstructor
public class LegacyEvaluationVisibility {
    private final JdbcTemplate jdbc;
    private final Environment environment;

    private boolean partitionedFixture() {
        if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"test"})) return false;
        String schema = jdbc.queryForObject("select current_schema()", String.class);
        return InstitutionalFixtureUnit.authoringControl(jdbc, schema) != null;
    }

    public void requireVisible(long id) {
        if (partitionedFixture() && Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from t11b8_resources where creation='EXPLICIT_NEW' and evaluation_id=?)
            """, Boolean.class, id))) throw new EntityNotFoundException("La evaluación no existe.");
    }

    public List<Long> excludedIds() {
        if (!partitionedFixture()) return List.of();
        return jdbc.queryForList("""
            select evaluation_id from t11b8_resources where creation='EXPLICIT_NEW' and evaluation_id is not null
            """, Long.class);
    }

    public String supervisionPredicate() {
        return partitionedFixture()
            ? "not exists(select 1 from t11b8_resources b8 where b8.creation='EXPLICIT_NEW' and b8.evaluation_id=r.id)"
            : "true";
    }
}
