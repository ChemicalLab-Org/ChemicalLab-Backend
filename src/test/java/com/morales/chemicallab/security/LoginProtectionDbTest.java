package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.*;
import com.morales.chemicallab.ChemicalLabBackendApplication;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.exception.AuthenticationLimitException;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static com.morales.chemicallab.security.AuthenticationLimitStore.Bucket;

/** Two independent Spring instances/pools/listeners, one disposable PostgreSQL schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginProtectionDbTest {
    static final String PASSWORD = "Fictitious phrase with 🌊";
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final AtomicInteger sequence = new AtomicInteger();
    final byte[] key = new byte[32];
    ConfigurableApplicationContext first, second;
    String schema;
    Connection control;

    @BeforeAll void start() throws Exception {
        String url = setting("spring.datasource.url");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test"))
            throw new IllegalStateException("Disposable loopback PostgreSQL only");
        schema = "t04_" + UUID.randomUUID().toString().replace("-", "");
        new SecureRandom().nextBytes(key);
        control = DriverManager.getConnection(url, setting("spring.datasource.username"), setting("spring.datasource.password"));
        try (var sql = control.createStatement()) { sql.execute("CREATE SCHEMA " + schema); }
        first = startApp();
        second = startApp();
    }

    ConfigurableApplicationContext startApp() {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var app = new SpringApplication(ChemicalLabBackendApplication.class);
        app.setEnvironment(env);
        return app.run("--spring.profiles.active=test", "--spring.config.location=classpath:/",
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + setting("spring.datasource.url") + "?currentSchema=" + schema,
                "--spring.datasource.username=" + setting("spring.datasource.username"),
                "--spring.datasource.password=" + setting("spring.datasource.password"),
                "--spring.jpa.properties.hibernate.default_schema=" + schema, "--spring.jpa.hibernate.ddl-auto=update",
                "--app.jwt.secret=" + Base64.getEncoder().encodeToString(key),
                "--app.demo.enabled=false", "--app.bootstrap.admin.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
    }
    @BeforeEach void clearLimits() { jdbc().execute("TRUNCATE authentication_limits"); }
    @AfterAll void close() throws Exception {
        if (second != null) second.close();
        if (first != null) first.close();
        if (control != null) {
            try (var sql = control.createStatement()) { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
            control.close();
        }
    }

    @Test void uniformMatrixTimingAndActiveTemporaryAccess() throws Exception {
        var active = account(Role.ESTUDIANTE, false, false);
        var disabled = account(Role.DOCENTE, true, false);
        jdbc().update("UPDATE user_accounts SET active=false WHERE id=?", disabled.getId());
        Map<String,List<Long>> samples = new LinkedHashMap<>();
        String expected = null;
        for (int round = 0; round < 4; round++) {
            for (var pair : List.of(new String[]{"absent", "absent@example.test"},
                    new String[]{active.getUsername(), active.getEmail()}, new String[]{disabled.getUsername(), disabled.getEmail()})) {
                for (String identifier : pair) {
                    long before = System.nanoTime();
                    boolean activeAttempt = identifier.equals(active.getUsername()) || identifier.equals(active.getEmail());
                    var response = login(first, identifier, activeAttempt ? "wrong" : PASSWORD);
                    long duration = System.nanoTime() - before;
                    assertThat(response.statusCode()).isEqualTo(401);
                    if (expected == null) expected = response.body();
                    assertThat(response.body()).isEqualTo(expected);
                    samples.computeIfAbsent(identifier.contains("absent") ? "absent" : activeAttempt ? "active-wrong" : "disabled", k -> new ArrayList<>()).add(duration);
                }
            }
            clearLimits(); // Isolate the response/work matrix from the rate-limit tests.
        }
        for (var sample : samples.entrySet()) {
            var ms = sample.getValue().stream().map(n -> n / 1_000_000.0).sorted().toList();
            System.out.printf(Locale.ROOT, "T04 timing %s n=%d min=%.1f median=%.1f max=%.1f ms%n", sample.getKey(), ms.size(), ms.get(0), ms.get(ms.size()/2), ms.get(ms.size()-1));
        }
        for (Role role : Role.values()) {
            var temporary = account(role, true, false);
            var response = login(first, temporary.getEmail(), PASSWORD);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(body(response).get("temporaryPassword").asBoolean()).isTrue();
            String token = body(response).get("token").asText();
            assertThat(request(first,"GET","/api/auth/me",token,null).statusCode()).isEqualTo(200);
            assertThat(request(first,"GET","/api/chemistry/catalog/metals",token,null).statusCode()).isEqualTo(403);
        }
    }

    @Test void aliasesAndTwoInstancesShareAtomicBudget() throws Exception {
        var user = account(Role.ESTUDIANTE, false, false);
        var pool = Executors.newFixedThreadPool(12);
        try {
            var gate = new CountDownLatch(1);
            List<Future<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final int n = i;
                attempts.add(pool.submit(() -> { gate.await(); return login(n % 2 == 0 ? first : second,
                        n % 2 == 0 ? user.getUsername() : user.getEmail(), "incorrect").statusCode(); }));
            }
            gate.countDown();
            var codes = new ArrayList<Integer>();
            for (var attempt : attempts) codes.add(attempt.get(20, TimeUnit.SECONDS));
            assertThat(codes.stream().filter(n -> n == 401).count()).isEqualTo(10);
            assertThat(codes.stream().filter(n -> n == 429).count()).isEqualTo(2);
            var blocked = login(first,user.getUsername(),PASSWORD);
            assertThat(blocked.statusCode()).isEqualTo(429);
            assertThat(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L,900L);
            assertThat(jdbc().queryForObject("SELECT count(*) FROM account_sessions WHERE user_id=?", Long.class, user.getId())).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void counterConcurrencyControlledExpiryRecoveryAndSuccessDoesNotClear() throws Exception {
        var pool = Executors.newFixedThreadPool(12);
        var bucket = List.of(new Bucket("test-atomic", 10, 900));
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                var instance = i % 2 == 0 ? first : second;
                attempts.add(pool.submit(() -> {
                    try { instance.getBean(AuthenticationLimitStore.class).acquire(bucket); return true; }
                    catch (AuthenticationLimitException ex) { assertThat(ex.status()).isEqualTo(429); return false; }
                }));
            }
            int accepted=0;
            for (var attempt: attempts) if (attempt.get(15, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(10);
        } finally { pool.shutdownNow(); }
        var transaction = new TransactionTemplate(first.getBean(PlatformTransactionManager.class));
        var store = first.getBean(AuthenticationLimitStore.class);
        long base = System.currentTimeMillis();
        var controlled = List.of(new Bucket("test-clock", 2, 60));
        assertThat((long) transaction.execute(status -> store.acquireAt(controlled, base))).isZero();
        assertThat((long) transaction.execute(status -> store.acquireAt(controlled, base+10))).isZero();
        assertThat((long) transaction.execute(status -> store.acquireAt(controlled, base+1000))).isEqualTo(59);
        assertThat((long) transaction.execute(status -> store.acquireAt(controlled, base+59999))).isEqualTo(1);
        assertThat((long) transaction.execute(status -> store.acquireAt(controlled, base+60000))).isZero();
        var user = account(Role.ESTUDIANTE, false, false);
        assertThat(login(first,user.getUsername(),PASSWORD).statusCode()).isEqualTo(200);
        assertThat(login(second,user.getEmail(),PASSWORD).statusCode()).isEqualTo(200);
        assertThat(jdbc().queryForObject("SELECT attempts FROM authentication_limits WHERE bucket=?", Integer.class,
                AuthenticationLimits.bucket("login-account",user.getId().toString(),10,900).key())).isEqualTo(2);
    }

    @Test void manyStudentsAtOneIpAndForgedHeadersStayInSameOriginBucket() throws Exception {
        for (int i = 0; i < 30; i++) {
            var user = account(Role.ESTUDIANTE, false, false);
            assertThat(login(i % 2 == 0 ? first : second, user.getUsername(), PASSWORD).statusCode()).isEqualTo(200);
        }
        for (int i = 0; i < 4; i++) {
            var req = HttpRequest.newBuilder(URI.create(origin(first)+"/api/auth/login"))
                    .header("Content-Type","application/json").header("X-Forwarded-For","192.0.2."+(i+1))
                    .header("Forwarded","for=198.51.100."+(i+1))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("usernameOrEmail","nonexistent"+i,"password","wrong")))).build();
            assertThat(http.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        assertThat(jdbc().queryForObject("SELECT count(*) FROM authentication_limits WHERE bucket LIKE 'origin:%'", Long.class)).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT attempts FROM authentication_limits WHERE bucket LIKE 'origin:%'",Integer.class)).isEqualTo(34);
    }

    @Test void originGlobalAndActorLimitsRejectBeforeAnyAccountChange() throws Exception {
        var user=account(Role.ADMINISTRADOR,false,false);
        String token=body(login(first,user.getUsername(),PASSWORD)).get("token").asText();
        long expires=System.currentTimeMillis()+900000;
        var origin=AuthenticationLimits.bucket("origin","127.0.0.1",300,900);
        var actor=AuthenticationLimits.bucket("actor",user.getId().toString(),60,900);
        for (var blocked : List.of(new Bucket("global:second",20,1), new Bucket("global:minute",300,60),origin,actor)) {
            clearLimits();
            jdbc().update("INSERT INTO authentication_limits VALUES (?,?,?)",blocked.key(),blocked.limit(),expires);
            String hash=users().findById(user.getId()).orElseThrow().getPassword();
            var response=change(token,PASSWORD,"A fresh but blocked password");
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(users().findById(user.getId()).orElseThrow().getPassword()).isEqualTo(hash);
            assertThat(request(second,"GET","/api/auth/me",token,null).statusCode()).isEqualTo(200);
        }
    }

    @Test void invalidNewPasswordWrongCurrentAndThrottlingNeverMutateOrRevoke() throws Exception {
        var user = account(Role.ESTUDIANTE, true, false);
        String token = body(login(first,user.getUsername(),PASSWORD)).get("token").asText();
        String originalHash = users().findById(user.getId()).orElseThrow().getPassword();
        long version = users().findById(user.getId()).orElseThrow().getCredentialsVersion();
        for (String rejected : List.of("short", "passwordpassword", "x".repeat(129))) {
            var response = change(token,PASSWORD,rejected);
            assertThat(response.statusCode()).isEqualTo(400);
        }
        for (int i=0;i<5;i++) {
            var response=change(token,"wrong", "Another valid fictitious phrase");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(body(response).get("code").asText()).isEqualTo("CURRENT_PASSWORD_INVALID");
        }
        assertThat(change(token,PASSWORD,"Another valid fictitious phrase").statusCode()).isEqualTo(429);
        var current=users().findById(user.getId()).orElseThrow();
        assertThat(current.getPassword()).isEqualTo(originalHash);
        assertThat(current.getCredentialsVersion()).isEqualTo(version);
        assertThat(current.getTemporaryPassword()).isTrue();
        assertThat(request(second,"GET","/api/auth/me",token,null).statusCode()).isEqualTo(200);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM account_sessions WHERE user_id=?",Long.class,user.getId())).isEqualTo(1);
        // Controlled persisted expiry, then successful change and T02 revocation/renewal.
        jdbc().update("UPDATE authentication_limits SET expires_at=0 WHERE bucket LIKE 'change-account:%'");
        var changed = change(token,PASSWORD,"Another valid fictitious phrase");
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(request(second,"GET","/api/auth/me",token,null).statusCode()).isEqualTo(401);
        assertThat(request(second,"GET","/api/auth/me",body(changed).get("token").asText(),null).statusCode()).isEqualTo(200);
        assertThat(change(token,"wrong","Another valid fictitious phrase").statusCode()).isEqualTo(401);
    }

    @Test void unavailableStorageFailsClosedAndCapacityAndCleanupAreBounded() throws Exception {
        var user=account(Role.ESTUDIANTE,false,false);
        var before=users().findById(user.getId()).orElseThrow();
        jdbc().execute("ALTER TABLE authentication_limits RENAME TO authentication_limits_offline");
        try {
            var response=login(first,user.getUsername(),PASSWORD);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).contains("30");
            assertThat(users().findById(user.getId()).orElseThrow().getPassword()).isEqualTo(before.getPassword());
            assertThat(jdbc().queryForObject("SELECT count(*) FROM account_sessions WHERE user_id=?",Long.class,user.getId())).isZero();
        } finally { jdbc().execute("ALTER TABLE authentication_limits_offline RENAME TO authentication_limits"); }
        jdbc().update("INSERT INTO authentication_limits SELECT 'capacity:'||n,1,? FROM generate_series(1,?) n",System.currentTimeMillis()+900000,AuthenticationLimitStore.CAPACITY);
        assertThat(login(first,"not-recorded",PASSWORD).statusCode()).isEqualTo(503);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM authentication_limits",Long.class)).isEqualTo(AuthenticationLimitStore.CAPACITY);
        jdbc().update("UPDATE authentication_limits SET expires_at=0");
        first.getBean(AuthenticationLimitStore.class).cleanup();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM authentication_limits",Long.class)).isEqualTo(AuthenticationLimitStore.CAPACITY-5000);
        clearLimits();
        assertThat(login(first,user.getUsername(),PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test void legacyUpgradePreservesSessionsAndCannotOverwriteNewerReset() throws Exception {
        var user=account(Role.ESTUDIANTE,false,true);
        // An existing T02 session remains valid when storage representation changes.
        var transaction=new TransactionTemplate(first.getBean(PlatformTransactionManager.class));
        String token=transaction.execute(status -> {
            var managed=users().findById(user.getId()).orElseThrow();
            first.getBean(AccountSessionService.class).lock(managed);
            return first.getBean(AccountSessionService.class).issue(managed);
        });
        assertThat(login(second,user.getEmail(),"short").statusCode()).isEqualTo(200);
        var upgraded=users().findById(user.getId()).orElseThrow();
        assertThat(upgraded.getPassword()).startsWith("$argon2id$");
        assertThat(upgraded.getCredentialsVersion()).isEqualTo(user.getCredentialsVersion());
        assertThat(request(first,"GET","/api/auth/me",token,null).statusCode()).isEqualTo(200);
        var racing=account(Role.ESTUDIANTE,false,true);
        var pool=Executors.newSingleThreadExecutor();
        try (var connection=DriverManager.getConnection(setting("spring.datasource.url"),setting("spring.datasource.username"),setting("spring.datasource.password"))) {
            connection.setAutoCommit(false);
            try (var lock=connection.prepareStatement("SELECT id FROM "+schema+".user_accounts WHERE id=? FOR UPDATE")) {
                lock.setLong(1,racing.getId()); lock.executeQuery();
            }
            var attempt=pool.submit(() -> login(second,racing.getUsername(),"short"));
            // Observe the database lock wait instead of sleeping to guess the interleaving.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            boolean waiting=false;
            while (System.nanoTime()<deadline && !waiting) {
                waiting=jdbc().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%user_accounts%'",Long.class)>0;
            }
            assertThat(waiting).isTrue();
            String replacement=first.getBean(PasswordEncoder.class).encode("A newer reset wins this race");
            try (var update=connection.prepareStatement("UPDATE "+schema+".user_accounts SET password=?,credentials_version=credentials_version+1 WHERE id=?")) {
                update.setString(1,replacement); update.setLong(2,racing.getId()); update.executeUpdate();
            }
            connection.commit();
            assertThat(attempt.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(401);
            assertThat(users().findById(racing.getId()).orElseThrow().getPassword()).isEqualTo(replacement);
        } finally { pool.shutdownNow(); }
        assertThat(login(first,racing.getUsername(),"A newer reset wins this race").statusCode()).isEqualTo(200);
    }

    @Test void allPasswordWritingHttpRoutesEnforcePolicyAndResetTargetsShareBudget() throws Exception {
        var admin=account(Role.ADMINISTRADOR,false,false);
        var teacher=account(Role.DOCENTE,false,false);
        var tp=first.getBean(TeacherProfileRepository.class).saveAndFlush(TeacherProfile.builder().user(teacher).names("Fiction").lastNames("Teacher").build());
        var student=account(Role.ESTUDIANTE,false,false);
        var sp=first.getBean(StudentProfileRepository.class).saveAndFlush(StudentProfile.builder().user(student).teacher(tp)
                .studentCode(student.getUsername()).names("Fiction").lastNames("Student").grade("3").section("A").build());
        String adminToken=body(login(first,admin.getUsername(),PASSWORD)).get("token").asText();
        String teacherToken=body(login(first,teacher.getUsername(),PASSWORD)).get("token").asText();
        String studentToken=body(login(first,student.getUsername(),PASSWORD)).get("token").asText();
        String teacherRoute="/api/users/teachers/"+teacher.getId()+"/reset-password";
        String studentRoute="/api/users/teachers/"+teacher.getId()+"/students/"+sp.getId()+"/reset-password";
        for (String route:List.of(teacherRoute,studentRoute)) {
            String actor=route.equals(teacherRoute)?adminToken:teacherToken;
            assertThat(request(first,"PATCH",route,actor,Map.of("newTemporaryPassword","short","confirmTemporaryPassword","short")).statusCode()).isEqualTo(400);
        }
        assertThat(request(first,"GET","/api/auth/me",studentToken,null).statusCode()).isEqualTo(200);
        for (String route:List.of("/api/users/teachers","/api/users/teachers/"+teacher.getId()+"/students")) {
            var payload=Map.of("names","Fiction","lastNames","Created","username","fixturecreated","temporaryPassword","short","grade","3","section","A");
            long count=users().count();
            assertThat(request(first,"POST",route,route.endsWith("students")?teacherToken:adminToken,payload).statusCode()).isEqualTo(400);
            assertThat(users().count()).isEqualTo(count);
        }
        var resetPayload=Map.of("newTemporaryPassword","New fictitious reset phrase","confirmTemporaryPassword","New fictitious reset phrase");
        assertThat(request(first,"PATCH",studentRoute,teacherToken,resetPayload).statusCode()).isEqualTo(200);
        assertThat(request(first,"GET","/api/auth/me",studentToken,null).statusCode()).isEqualTo(401);
        String adminRoute="/api/admin/users/"+student.getId()+"/password/reset";
        for (int i=0;i<4;i++) {
            var response=request(second,"PATCH",adminRoute,adminToken,Map.of());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(PasswordPolicy.valid(body(response).get("temporaryPassword").asText())).isTrue();
        }
        String hash=users().findById(student.getId()).orElseThrow().getPassword();
        assertThat(request(first,"PATCH",studentRoute,teacherToken,resetPayload).statusCode()).isEqualTo(429);
        assertThat(users().findById(student.getId()).orElseThrow().getPassword()).isEqualTo(hash);
        var created=request(first,"POST","/api/admin/users",adminToken,Map.of("role","ADMINISTRADOR","username","fixturegenerated"));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(PasswordPolicy.valid(body(created).get("temporaryPassword").asText())).isTrue();
    }

    UserAccount account(Role role,boolean temporary,boolean legacy) {
        String name="fixture"+sequence.incrementAndGet();
        String hash=legacy?new BCryptPasswordEncoder().encode("short"):first.getBean(PasswordEncoder.class).encode(PASSWORD);
        return users().saveAndFlush(UserAccount.builder().username(name).email(name+"@example.test").password(hash).role(role).active(true).temporaryPassword(temporary).build());
    }
    HttpResponse<String> login(ConfigurableApplicationContext app,String identifier,String password) throws Exception {
        return request(app,"POST","/api/auth/login",null,Map.of("usernameOrEmail",identifier,"password",password));
    }
    HttpResponse<String> change(String token,String old,String next) throws Exception {
        return request(first,"PATCH","/api/auth/change-temporary-password",token,Map.of("currentPassword",old,"newPassword",next,"confirmPassword",next));
    }
    HttpResponse<String> request(ConfigurableApplicationContext app,String method,String path,String token,Object payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(origin(app)+path)).header("Content-Type","application/json");
        if (token!=null) request.header("Authorization","Bearer "+token);
        return http.send(request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload),StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    String origin(ConfigurableApplicationContext app) { return "http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port"); }
    UserAccountRepository users(){return first.getBean(UserAccountRepository.class);}
    JdbcTemplate jdbc(){return first.getBean(JdbcTemplate.class);}
    static String setting(String key){return System.getProperty(key,"");}
}
