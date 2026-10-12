package com.morales.chemicallab.security.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Production only verifies. Explicit local test installation is guarded by DB name and loopback. */
@Component @DependsOn("entityManagerFactory") @RequiredArgsConstructor
public class StorageSchema implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final Environment environment;
    @Value("${app.storage.install-schema:false}") private boolean install;
    @Override public void afterPropertiesSet() throws Exception {
        if(install) {
            String url=environment.getProperty("spring.datasource.url","");
            if(Arrays.asList(environment.getActiveProfiles()).contains("prod") || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test(\\?currentSchema=[a-zA-Z0-9_]+)?"))
                throw new IllegalStateException("T07B schema install is limited to disposable loopback tests.");
            String sql=new ClassPathResource("security/t07b-storage.sql").getContentAsString(StandardCharsets.UTF_8);
            new TransactionTemplate(transactions).executeWithoutResult(s->{
                jdbc.execute("select pg_advisory_xact_lock(7507, hashtext(current_schema()))");
                if(jdbc.queryForObject("select to_regclass('storage_schema_version') is null",Boolean.class)) jdbc.execute(sql);
            });
        }
        Integer version=jdbc.queryForObject("select max(version) from storage_schema_version",Integer.class);
        if(version==null || version!=1)throw new IllegalStateException("T07B storage schema must be installed before serving requests.");
        Integer triggers=jdbc.queryForObject("select count(*) from pg_trigger where tgname='storage_quota' and tgenabled='O' and not tgisinternal and tgrelid in ('concept_materials'::regclass,'whiteboard_sessions'::regclass,'whiteboard_object_ownership'::regclass,'usage_events'::regclass,'evaluation_attempt_events'::regclass)",Integer.class);
        if(triggers!=5)throw new IllegalStateException("T07B storage quota triggers are missing or disabled.");
        Integer guards=jdbc.queryForObject("select count(*) from pg_trigger where tgname='storage_owner_guard' and tgenabled='O' and not tgisinternal and tgrelid in ('teacher_profiles'::regclass,'student_profiles'::regclass,'concept_contents'::regclass,'evaluation_attempts'::regclass)",Integer.class);
        if(guards!=4)throw new IllegalStateException("T07B storage owner guards are missing or disabled.");
    }
}
