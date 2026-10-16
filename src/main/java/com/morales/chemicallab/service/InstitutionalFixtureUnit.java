package com.morales.chemicallab.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Fixture protocol only. Take the T05 evaluation table before any other table/row.
 * EXCLUSIVE also waits for legacy SELECT FOR UPDATE before taking dependent locks.
 * B8/B10 start with evaluations too; B reads never upgrade a SHARE lock to write.
 */
final class InstitutionalFixtureUnit {
    private InstitutionalFixtureUnit() {}
    static String control(String schema) { return "t12c_" + schema.substring(4); }
    static String lifecycleControl(String schema) { return "t12d_" + schema.substring(4); }
    static String reviewControl(String schema) { return "t12e_" + schema.substring(4); }
    static String writeControl(JdbcTemplate jdbc,String schema) {
        String review=reviewControl(schema);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select to_regprocedure(?::text) is not null",Boolean.class,review+".fresh(bigint)")))return review;
        String lifecycle=lifecycleControl(schema);
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regprocedure(?::text) is not null",Boolean.class,lifecycle+".fresh(bigint)"))?lifecycle:control(schema);
    }
    static void lock(JdbcTemplate jdbc, String approvedSchema, boolean writing) {
        String schema=jdbc.queryForObject("select current_schema()",String.class);
        if(schema==null || !schema.matches("t03_[a-f0-9]{32}") || !schema.equals(approvedSchema)
                || !Boolean.TRUE.equals(jdbc.queryForObject("select current_database()='chemicallab_t01_test' and inet_server_addr()='127.0.0.1'::inet",Boolean.class)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Contexto no autorizado.");
        jdbc.execute("select t11b5_fixture()");
        jdbc.execute("set local lock_timeout='5s'");
        jdbc.execute("lock table evaluations in "+(writing?"exclusive":"share")+" mode");
        List<String> tables=jdbc.queryForList("""
            select format('%I.%I',schemaname,tablename) from pg_tables
            where schemaname=current_schema() and tablename<>'evaluations' order by tablename
            """,String.class);
        if(!tables.isEmpty()) jdbc.execute("lock table "+String.join(",",tables)+" in "+(writing?"share row exclusive":"share")+" mode");
    }
}
