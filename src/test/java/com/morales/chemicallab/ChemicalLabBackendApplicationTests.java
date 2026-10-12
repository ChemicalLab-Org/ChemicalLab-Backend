package com.morales.chemicallab;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
class ChemicalLabBackendApplicationTests {
    @Test void contextLoadsInGuardedDisposableSchema() throws Exception {
        String url=System.getProperty("spring.datasource.url", "");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test"))
            throw new IllegalStateException("Context test requires disposable loopback PostgreSQL.");
        String schema="t07b_context_"+UUID.randomUUID().toString().replace("-", "");
        String user=System.getProperty("spring.datasource.username"),password=System.getProperty("spring.datasource.password");
        try(var c=DriverManager.getConnection(url,user,password);var statement=c.createStatement()) {
            statement.execute("create schema "+schema);
            try {
                byte[] key=new byte[48];new java.security.SecureRandom().nextBytes(key);
                var environment=new StandardEnvironment();
                environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                var app=new SpringApplication(ChemicalLabBackendApplication.class);app.setEnvironment(environment);
                try(var context=app.run("--spring.profiles.active=test","--server.address=127.0.0.1","--server.port=0",
                    "--spring.datasource.url="+url+"?currentSchema="+schema,"--spring.datasource.username="+user,"--spring.datasource.password="+password,
                    "--spring.jpa.properties.hibernate.default_schema="+schema,"--spring.jpa.hibernate.ddl-auto=update",
                    "--app.jwt.secret="+Base64.getEncoder().encodeToString(key),"--app.demo.enabled=false","--app.bootstrap.admin.enabled=false",
                    "--app.storage.install-schema=true","--spring.config.location=classpath:/","--logging.level.root=ERROR")) {
                    assertThat(context.isActive()).isTrue();
                }
            } finally {statement.execute("drop schema "+schema+" cascade");}
        }
    }
}
