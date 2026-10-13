package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.institution.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** Read mapping only; all application mutations go through audited maintenance. */
@Repository
@RequiredArgsConstructor
public class AcademicRepository {
    private final JdbcTemplate jdbc;
    public AcademicPeriod period(long id) {
        return jdbc.queryForObject("select * from academic_periods where id=?", (r,n)->new AcademicPeriod(
            r.getLong("id"),r.getLong("institution_id"),r.getString("local_code"),r.getString("name"),
            instant(r,"valid_from"),instant(r,"valid_until"),AcademicPeriod.Status.valueOf(r.getString("status")),
            instant(r,"state_since"),r.getLong("version")),id);
    }
    public AcademicClass academicClass(long id) {
        return jdbc.queryForObject("select * from academic_classes where id=?", (r,n)->new AcademicClass(
            r.getLong("id"),r.getLong("institution_id"),r.getLong("period_id"),r.getString("local_code"),r.getString("name"),
            r.getString("course"),r.getString("grade"),r.getString("section"),AcademicClass.Status.valueOf(r.getString("status")),
            instant(r,"state_since"),r.getLong("version")),id);
    }
    public TeachingAssignment assignment(long id) {
        return jdbc.queryForObject("select * from teaching_assignments where id=?", (r,n)->new TeachingAssignment(
            r.getLong("id"),r.getLong("institution_id"),r.getLong("class_id"),r.getLong("membership_id"),
            instant(r,"valid_from"),instant(r,"valid_until"),instant(r,"activated_at"),instant(r,"ended_at"),
            TeachingAssignment.Status.valueOf(r.getString("status")),instant(r,"state_since"),r.getLong("version")),id);
    }
    public Enrollment enrollment(long id) {
        return jdbc.queryForObject("select * from enrollments where id=?", (r,n)->new Enrollment(
            r.getLong("id"),r.getLong("institution_id"),r.getLong("class_id"),r.getLong("membership_id"),r.getInt("episode_number"),
            instant(r,"valid_from"),instant(r,"valid_until"),instant(r,"activated_at"),instant(r,"ended_at"),
            Enrollment.Status.valueOf(r.getString("status")),instant(r,"state_since"),r.getLong("version")),id);
    }
    private static Instant instant(ResultSet r,String column) throws SQLException {
        var value=r.getTimestamp(column); return value==null?null:value.toInstant();
    }
}
