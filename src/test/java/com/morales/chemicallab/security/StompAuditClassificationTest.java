package com.morales.chemicallab.security;

import com.morales.chemicallab.controller.WhiteboardWebSocketController;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.audit.*;
import com.morales.chemicallab.security.storage.StorageErrors;
import com.morales.chemicallab.service.AuditLogWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.sql.SQLException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StompAuditClassificationTest {
    @Test void sqlQuotaIsExpectedRejectionWithPrivateResponseAndSafeCorrelation(){
        verifyError(new DataIntegrityViolationException("synthetic-secret",new SQLException("synthetic-sql-secret","P7501")),
            LogEventType.STOMP_DENIED,"REJECTED","STORAGE_QUOTA_EXCEEDED","STORAGE_QUOTA_EXCEEDED",StorageErrors.message());
    }
    @Test void operationalDatabaseFailureRemainsFailedWithSafePrivateResponse(){
        verifyError(new DataIntegrityViolationException("synthetic-secret",new SQLException("synthetic-sql-secret","08006")),
            LogEventType.OPERATION_FAILED,"FAILED","STOMP_FAILURE","DRAW_REJECTED","No se pudo aplicar la acción. Resincroniza la pizarra.");
    }
    void verifyError(Exception exception,LogEventType type,String outcome,String reason,String code,String error){
        var writer=mock(AuditLogWriter.class);when(writer.persistRejection(any())).thenReturn(true);
        var audit=new AuditRejections(writer,new AuditSignals(new SimpleMeterRegistry()));
        var controller=new WhiteboardWebSocketController(null,null,null,audit);
        var actor=new SessionPrincipal(7L,"synthetic-actor",UUID.randomUUID(),0L,Role.ESTUDIANTE,false);
        var headers=StompHeaderAccessor.create(StompCommand.SEND);headers.setDestination("/app/whiteboards/42/draw");
        headers.setUser(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
        headers.setNativeHeader("client-event-id","synthetic-client-event");
        headers.setNativeHeader("auditCorrelationId","synthetic-forged-correlation");
        UUID correlation=UUID.randomUUID();headers.setHeader("auditCorrelation",correlation);
        var message=MessageBuilder.createMessage("synthetic-private-body",headers.getMessageHeaders());
        var response=controller.handleError(exception,message);
        assertThat(response).containsEntry("code",code).containsEntry("error",error).containsEntry("resync",true).containsEntry("accepted",false)
            .containsEntry("clientEventId","synthetic-client-event").containsEntry("auditCorrelationId",correlation.toString());
        assertThat(response).hasSize(6);assertThat(response.toString()).doesNotContain("synthetic-secret","synthetic-sql-secret","synthetic-private-body","synthetic-forged-correlation");
        var captor=ArgumentCaptor.forClass(SystemLog.class);verify(writer).persistRejection(captor.capture());
        var event=captor.getValue();assertThat(event.getEventType()).isEqualTo(type);assertThat(event.getOutcome()).isEqualTo(outcome);
        assertThat(event.getMetadata()).isEqualTo("reason="+reason);assertThat(event.getCorrelationId()).isEqualTo(correlation);
        assertThat(event.getActorUserId()).isEqualTo(7L);assertThat(event.getActorRole()).isEqualTo(Role.ESTUDIANTE);
        assertThat(event.getTargetId()).isEqualTo(42L);assertThat(event.getTargetType()).isEqualTo("STOMP");
        assertThat(event.getDescription()).isEqualTo(type.name());assertThat(event.getAction()).isEqualTo(type.name());
        assertThat(event.getDeclaredIdentifier()).isNull();assertThat(event.getTargetLabel()).isNull();assertThat(event.getCreatedAt()).isNotNull();
        assertThat(AuditContext.correlation()).isNotEqualTo(correlation);
    }
}
