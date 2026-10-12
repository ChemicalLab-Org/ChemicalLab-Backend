package com.morales.chemicallab.security;

import org.junit.jupiter.api.Test;
import org.postgresql.util.ByteStreamWriter;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

/** Driver-specific regressions, using only a connection-local temporary table. */
class JdbcDriverRegressionDbTest {
    private java.sql.Connection connection() throws Exception {
        String url = System.getProperty("spring.datasource.url");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test"))
            throw new IllegalStateException("Disposable loopback PostgreSQL only");
        return DriverManager.getConnection(url, System.getProperty("spring.datasource.username"),
                System.getProperty("spring.datasource.password"));
    }

    @Test void binaryWriterPadsWithZerosInsteadOfEarlierStatements() throws Exception {
        try (var db = connection(); var sql = db.createStatement()) {
            sql.execute("CREATE TEMP TABLE t09_driver_payload (payload bytea)");
            sql.execute("SELECT 'FICTITIOUS_PREVIOUS_TRAFFIC_MARKER'");
            try (var insert = db.prepareStatement("INSERT INTO t09_driver_payload VALUES (?)")) {
                insert.setObject(1, new ByteStreamWriter() {
                    public int getLength() { return 8; }
                    public void writeTo(ByteStreamTarget target) throws java.io.IOException {
                        target.getOutputStream().write(new byte[] {1, 2});
                    }
                });
                insert.executeUpdate();
            }
            try (var result = sql.executeQuery("SELECT payload FROM t09_driver_payload")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBytes(1)).containsExactly(1, 2, 0, 0, 0, 0, 0, 0);
            }
        }
    }

    @Test void invalidAuthenticationExclusionFailsClosed() throws Exception {
        try (var ignored = connection()) { /* verify guard and availability first */ }
        String url = System.getProperty("spring.datasource.url")
                + "?requireAuth=!password,!md5,!gss,!sspi,!scram-sha-256,!none";
        assertThatThrownBy(() -> {
            try (var ignored = DriverManager.getConnection(url, System.getProperty("spring.datasource.username"),
                    System.getProperty("spring.datasource.password"))) { }
        }).isInstanceOf(java.sql.SQLException.class)
                .extracting(ex -> ((java.sql.SQLException) ex).getSQLState()).isEqualTo("08004");
    }
}
