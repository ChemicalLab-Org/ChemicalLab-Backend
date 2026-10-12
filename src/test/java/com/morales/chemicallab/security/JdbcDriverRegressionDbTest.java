package com.morales.chemicallab.security;

import org.junit.jupiter.api.Test;
import org.postgresql.util.ByteStreamWriter;
import java.sql.DriverManager;
import java.util.Arrays;
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
            // A tiny value can pass on an affected driver: force buffer reuse before padding.
            byte[] supplied = new byte[200_000];
            Arrays.fill(supplied, (byte) 0x41);
            try (var insert = db.prepareStatement("INSERT INTO t09_driver_payload VALUES (?)")) {
                insert.setObject(1, new ByteStreamWriter() {
                    public int getLength() { return 300_000; }
                    public void writeTo(ByteStreamTarget target) throws java.io.IOException {
                        target.getOutputStream().write(supplied);
                    }
                });
                insert.executeUpdate();
            }
            try (var result = sql.executeQuery("SELECT payload FROM t09_driver_payload")) {
                assertThat(result.next()).isTrue();
                byte[] stored = result.getBytes(1);
                assertThat(stored).hasSize(300_000);
                assertThat(Arrays.equals(Arrays.copyOfRange(stored, 0, supplied.length), supplied)).isTrue();
                assertThat(Arrays.equals(Arrays.copyOfRange(stored, supplied.length, stored.length),
                        new byte[100_000])).as("Padding must not contain earlier buffer traffic").isTrue();
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
