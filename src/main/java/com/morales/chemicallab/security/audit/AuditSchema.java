package com.morales.chemicallab.security.audit;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component @DependsOn("entityManagerFactory")
public class AuditSchema implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final Environment environment;
    @Value("${app.audit.install-schema:false}") private boolean install;
    public AuditSchema(JdbcTemplate jdbc,PlatformTransactionManager transactions,Environment environment){this.jdbc=jdbc;this.transactions=transactions;this.environment=environment;}
    @Override public void afterPropertiesSet() throws Exception {
        if(install){
            String url=environment.getProperty("spring.datasource.url","");
            if(!Arrays.asList(environment.getActiveProfiles()).contains("test") || Arrays.asList(environment.getActiveProfiles()).contains("prod") || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test(\\?currentSchema=[a-zA-Z0-9_]+)?"))
                throw new IllegalStateException("T08 schema install is limited to disposable loopback tests.");
            String sql=new ClassPathResource("security/t08-audit.sql").getContentAsString(StandardCharsets.UTF_8);
            new TransactionTemplate(transactions).executeWithoutResult(s->{
                jdbc.execute("select pg_advisory_xact_lock(7508,hashtext(current_schema()))");
                if(jdbc.queryForObject("select to_regclass('audit_schema_version') is null",Boolean.class))jdbc.execute(sql);
            });
        }
        Integer version=jdbc.queryForObject("select max(version) from audit_schema_version",Integer.class);
        Integer guard=jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='system_logs'::regclass and tgname='audit_append_only' and tgenabled='O' and not tgisinternal",Integer.class);
        Integer budget=jdbc.queryForObject("select count(*) from audit_rejection_budget where id=1",Integer.class);
        if(version==null || version!=1 || guard!=1 || budget!=1)throw new IllegalStateException("T08 audit schema/guard/budget must be installed before serving requests.");
    }
}
