package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.audit.*;
import com.morales.chemicallab.service.AuditLogWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditSafetyTest {
    @Test void productionCannotInstallAuditSchemaEvenOnDisposableNamedUrl(){
        var env=mock(org.springframework.core.env.Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(env.getProperty("spring.datasource.url","")).thenReturn("jdbc:postgresql://127.0.0.1:55479/chemicallab_t01_test");
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var schema=new AuditSchema(jdbc,tx,env);org.springframework.test.util.ReflectionTestUtils.setField(schema,"install",true);
        assertThatThrownBy(schema::afterPropertiesSet).isInstanceOf(IllegalStateException.class);verifyNoInteractions(jdbc,tx);
    }
    @Test void allowlistRejectsSecretFieldsFreeTextControlCharactersAndExcess(){
        assertThat(AuditSafety.metadata("token=secret;password=secret;cookie=secret;body=secret;role=ESTUDIANTE;attemptId=42;override=BLOCKED;participantUserId=7")).isEqualTo("role=ESTUDIANTE;attemptId=42;override=BLOCKED;participantUserId=7");
        assertThat(AuditSafety.metadata("role=ESTUDIANTE\nFAKE_LOG;attemptId=-1;override=secret;category=secret")).isNull();
        assertThat(AuditSafety.metadata("a".repeat(2049))).isNull();
        assertThat(AuditSafety.text("a\r\nb\u202ec\u2028d",100)).isEqualTo("abcd");
        assertThat(AuditSafety.identifierFingerprint("pasted-credential")).startsWith("sha256:").doesNotContain("pasted-credential");
    }
    @Test void failedDestinationIsObservableAndDbAttemptsStopAtLocalBudget(){
        var writer=mock(AuditLogWriter.class);var registry=new SimpleMeterRegistry();
        when(writer.persistRejection(any())).thenThrow(new RuntimeException("secret-token-must-never-be-logged"));
        var audit=new AuditRejections(writer,new AuditSignals(registry));
        for(int i=0;i<250;i++)audit.record(LogEventType.REST_DENIED,null,"REST",null,"reason=HTTP_403",null);
        verify(writer,times(120)).persistRejection(any());
        assertThat(registry.counter("security.audit","signal","rejection_persist_failed").count()).isEqualTo(120);
        assertThat(registry.counter("security.audit","signal","rejection_suppressed_local").count()).isEqualTo(130);
    }
    @Test void declaredIdentityNeverBecomesActorAndCorrelationCannotContainInjectedText(){
        var writer=mock(AuditLogWriter.class);when(writer.persistRejection(any())).thenReturn(true);
        var audit=new AuditRejections(writer,new AuditSignals(new SimpleMeterRegistry()));
        audit.record(LogEventType.LOGIN_FAILED,null,"UserAccount",null,"reason=PASSWORD_MISMATCH","synthetic\r\nsecret");
        var captor=ArgumentCaptor.forClass(SystemLog.class);verify(writer).persistRejection(captor.capture());
        var saved=captor.getValue();assertThat(saved.getActorUsername()).isNull();assertThat(saved.getActorUserId()).isNull();assertThat(saved.getActorRole()).isNull();
        assertThat(saved.getCorrelationId()).isNotNull();assertThat(saved.getDeclaredIdentifier()).doesNotContain("synthetic","secret");assertThat(saved.getOutcome()).isEqualTo("REJECTED");
    }
    @Test void scopedCorrelationRestoresPreviousContextAndDoesNotLeakBetweenRequests(){
        var first=java.util.UUID.randomUUID();var second=java.util.UUID.randomUUID();
        try(var outer=AuditContext.open(first)){
            assertThat(AuditContext.correlation()).isEqualTo(first);
            try(var inner=AuditContext.open(second)){assertThat(AuditContext.correlation()).isEqualTo(second);}
            assertThat(AuditContext.correlation()).isEqualTo(first);
        }
        assertThat(AuditContext.correlation()).isNotEqualTo(first);
    }
}
