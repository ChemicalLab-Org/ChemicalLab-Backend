package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.WhiteboardDrawEventRequest;
import com.morales.chemicallab.dto.WhiteboardDrawEventResponse;
import com.morales.chemicallab.dto.WhiteboardDrawEventType;
import com.morales.chemicallab.dto.WhiteboardDrawTool;
import com.morales.chemicallab.dto.WhiteboardPoint;
import com.morales.chemicallab.dto.WhiteboardTextRun;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.repository.WhiteboardParticipantRepository;
import com.morales.chemicallab.repository.WhiteboardSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas de la validación de eventos de dibujo entrantes por WebSocket: estado de la sesión,
 * permiso efectivo del actor, regla de CLEAR y acotación del payload.
 */
@ExtendWith(MockitoExtension.class)
class WhiteboardDrawEventServiceTest {

    @Mock private WhiteboardSessionRepository sessionRepository;
    @Mock private WhiteboardParticipantRepository participantRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TeacherProfileRepository teacherProfileRepository;
    @Mock private StudentProfileRepository studentProfileRepository;
    @Mock private WhiteboardBroadcastService broadcastService;

    @InjectMocks private WhiteboardDrawEventService service;

    private TeacherProfile teacher(Long id, String username) {
        UserAccount user = UserAccount.builder()
                .id(id).username(username).role(Role.DOCENTE).active(true).build();
        return TeacherProfile.builder().id(id).user(user).names("Ana").lastNames("Quispe").build();
    }

    private StudentProfile student(Long id, String code) {
        UserAccount user = UserAccount.builder()
                .id(id).username(code).role(Role.ESTUDIANTE).active(true).build();
        return StudentProfile.builder()
                .id(id).user(user).studentCode(code).names("Luis").lastNames("Torres")
                .grade("3").section("A").build();
    }

    private WhiteboardSession session(Long id, TeacherProfile owner, WhiteboardSessionStatus status,
                                      boolean interactionEnabled) {
        return WhiteboardSession.builder()
                .id(id).name("Sesión").teacher(owner).grade("3").section("A")
                .status(status).interactionEnabled(interactionEnabled).build();
    }

    private WhiteboardDrawEventRequest drawRequest() {
        return new WhiteboardDrawEventRequest(
                WhiteboardDrawEventType.DRAW, WhiteboardDrawTool.PEN, "#112233", 2.0, null,
                List.of(new WhiteboardPoint(1.0, 2.0), new WhiteboardPoint(3.0, 4.0)), "c-1", null, null, null);
    }

    @Test
    void docentePropietarioPuedeDibujarSiActiva() {
        TeacherProfile docente = teacher(1L, "docente1");
        when(userAccountRepository.findByUsername("docente1")).thenReturn(Optional.of(docente.getUser()));
        when(teacherProfileRepository.findByUser(docente.getUser())).thenReturn(Optional.of(docente));
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, false)));

        WhiteboardDrawEventResponse response = service.processDrawEvent("docente1", 10L, drawRequest());

        assertThat(response.actorRole()).isEqualTo(Role.DOCENTE);
        assertThat(response.points()).hasSize(2);
        verify(broadcastService).broadcastDraw(any());
    }

    @Test
    void estudianteConPermisoEfectivoPuedeDibujar() {
        TeacherProfile docente = teacher(1L, "docente1");
        StudentProfile alumno = student(5L, "EST0001");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, true); // global activo
        when(userAccountRepository.findByUsername("EST0001")).thenReturn(Optional.of(alumno.getUser()));
        when(studentProfileRepository.findByStudentCode("EST0001")).thenReturn(Optional.of(alumno));
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(participantRepository.findBySessionAndStudent(s, alumno))
                .thenReturn(Optional.of(WhiteboardParticipant.builder()
                        .id(99L).session(s).student(alumno)
                        .interactionOverride(WhiteboardInteractionOverride.FOLLOW_GLOBAL).build()));

        WhiteboardDrawEventResponse response = service.processDrawEvent("EST0001", 10L, drawRequest());

        assertThat(response.actorRole()).isEqualTo(Role.ESTUDIANTE);
        verify(broadcastService).broadcastDraw(any());
    }

    @Test
    void estudianteSinPermisoNoPuedeDibujar() {
        TeacherProfile docente = teacher(1L, "docente1");
        StudentProfile alumno = student(5L, "EST0001");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, false); // global inactivo
        when(userAccountRepository.findByUsername("EST0001")).thenReturn(Optional.of(alumno.getUser()));
        when(studentProfileRepository.findByStudentCode("EST0001")).thenReturn(Optional.of(alumno));
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(participantRepository.findBySessionAndStudent(s, alumno))
                .thenReturn(Optional.of(WhiteboardParticipant.builder()
                        .id(99L).session(s).student(alumno)
                        .interactionOverride(WhiteboardInteractionOverride.FOLLOW_GLOBAL).build()));

        assertThatThrownBy(() -> service.processDrawEvent("EST0001", 10L, drawRequest()))
                .hasMessageContaining("permiso");
        verify(broadcastService, never()).broadcastDraw(any());
    }

    @Test
    void estudianteNoUnidoNoPuedeDibujar() {
        TeacherProfile docente = teacher(1L, "docente1");
        StudentProfile alumno = student(5L, "EST0001");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, true);
        when(userAccountRepository.findByUsername("EST0001")).thenReturn(Optional.of(alumno.getUser()));
        when(studentProfileRepository.findByStudentCode("EST0001")).thenReturn(Optional.of(alumno));
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(participantRepository.findBySessionAndStudent(s, alumno)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processDrawEvent("EST0001", 10L, drawRequest()))
                .hasMessageContaining("unirte");
    }

    @Test
    void sesionPausadaRechazaDibujo() {
        TeacherProfile docente = teacher(1L, "docente1");
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.PAUSED, true)));

        assertThatThrownBy(() -> service.processDrawEvent("docente1", 10L, drawRequest()))
                .hasMessageContaining("no está activa");
        verify(broadcastService, never()).broadcastDraw(any());
    }

    @Test
    void sesionCerradaRechazaDibujo() {
        TeacherProfile docente = teacher(1L, "docente1");
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.CLOSED, true)));

        assertThatThrownBy(() -> service.processDrawEvent("docente1", 10L, drawRequest()))
                .hasMessageContaining("no está activa");
    }

    @Test
    void clearSoloDocente() {
        TeacherProfile docente = teacher(1L, "docente1");
        StudentProfile alumno = student(5L, "EST0001");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, true);
        when(userAccountRepository.findByUsername("EST0001")).thenReturn(Optional.of(alumno.getUser()));
        when(studentProfileRepository.findByStudentCode("EST0001")).thenReturn(Optional.of(alumno));
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(participantRepository.findBySessionAndStudent(s, alumno))
                .thenReturn(Optional.of(WhiteboardParticipant.builder()
                        .id(99L).session(s).student(alumno)
                        .interactionOverride(WhiteboardInteractionOverride.ALLOWED).build()));

        WhiteboardDrawEventRequest clear = new WhiteboardDrawEventRequest(
                WhiteboardDrawEventType.CLEAR, WhiteboardDrawTool.CLEAR, null, null, null, null, "c-2", null, null, null);

        assertThatThrownBy(() -> service.processDrawEvent("EST0001", 10L, clear))
                .hasMessageContaining("docente");
    }

    @Test
    void docentePuedeLimpiarPizarra() {
        TeacherProfile docente = teacher(1L, "docente1");
        when(userAccountRepository.findByUsername("docente1")).thenReturn(Optional.of(docente.getUser()));
        when(teacherProfileRepository.findByUser(docente.getUser())).thenReturn(Optional.of(docente));
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, false)));

        WhiteboardDrawEventRequest clear = new WhiteboardDrawEventRequest(
                WhiteboardDrawEventType.CLEAR, WhiteboardDrawTool.CLEAR, null, null, null, null, "c-3", null, null, null);

        WhiteboardDrawEventResponse response = service.processDrawEvent("docente1", 10L, clear);

        assertThat(response.eventType()).isEqualTo(WhiteboardDrawEventType.CLEAR);
        assertThat(response.points()).isEmpty();
    }

    @Test
    void rechazaPayloadConDemasiadosPuntos() {
        TeacherProfile docente = teacher(1L, "docente1");
        when(userAccountRepository.findByUsername("docente1")).thenReturn(Optional.of(docente.getUser()));
        when(teacherProfileRepository.findByUser(docente.getUser())).thenReturn(Optional.of(docente));
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, false)));

        List<WhiteboardPoint> many = new ArrayList<>();
        IntStream.range(0, 1001).forEach(i -> many.add(new WhiteboardPoint((double) i, (double) i)));
        WhiteboardDrawEventRequest huge = new WhiteboardDrawEventRequest(
                WhiteboardDrawEventType.DRAW, WhiteboardDrawTool.PEN, "#000000", 1.0, null, many, "c-4", null, null, null);

        assertThatThrownBy(() -> service.processDrawEvent("docente1", 10L, huge))
                .hasMessageContaining("máximo");
        verify(broadcastService, never()).broadcastDraw(any());
    }

    @Test
    void rechazaPuntoSinCoordenadas() {
        TeacherProfile docente = teacher(1L, "docente1");
        when(userAccountRepository.findByUsername("docente1")).thenReturn(Optional.of(docente.getUser()));
        when(teacherProfileRepository.findByUser(docente.getUser())).thenReturn(Optional.of(docente));
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, false)));

        WhiteboardDrawEventRequest bad = new WhiteboardDrawEventRequest(
                WhiteboardDrawEventType.DRAW, WhiteboardDrawTool.PEN, "#000000", 1.0, null,
                List.of(new WhiteboardPoint(1.0, null)), "c-5", null, null, null);

        assertThatThrownBy(() -> service.processDrawEvent("docente1", 10L, bad))
                .hasMessageContaining("coordenadas");
    }

    @Test
    void docentePuedeEnviarTextoEnVivo() {
        TeacherProfile docente = teacher(1L, "docente1");
        when(userAccountRepository.findByUsername("docente1")).thenReturn(Optional.of(docente.getUser()));
        when(teacherProfileRepository.findByUser(docente.getUser())).thenReturn(Optional.of(docente));
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, false)));

        WhiteboardDrawEventRequest text = new WhiteboardDrawEventRequest(
                WhiteboardDrawEventType.TEXT, WhiteboardDrawTool.TEXT, "#1d9e75", null, null,
                List.of(new WhiteboardPoint(120.0, 80.0)), "t-1", "txt-1", 32.0,
                List.of(new WhiteboardTextRun("Agua", true, false, false)));

        WhiteboardDrawEventResponse response = service.processDrawEvent("docente1", 10L, text);

        assertThat(response.eventType()).isEqualTo(WhiteboardDrawEventType.TEXT);
        assertThat(response.textId()).isEqualTo("txt-1");
        assertThat(response.fontSize()).isEqualTo(32.0);
        assertThat(response.runs()).hasSize(1);
        assertThat(response.runs().get(0).text()).isEqualTo("Agua");
        assertThat(response.points()).hasSize(1);
        verify(broadcastService).broadcastDraw(any());
    }

    @Test
    void docentePuedeEliminarTexto() {
        TeacherProfile docente = teacher(1L, "docente1");
        when(userAccountRepository.findByUsername("docente1")).thenReturn(Optional.of(docente.getUser()));
        when(teacherProfileRepository.findByUser(docente.getUser())).thenReturn(Optional.of(docente));
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, false)));

        WhiteboardDrawEventRequest del = new WhiteboardDrawEventRequest(
                WhiteboardDrawEventType.TEXT_DELETE, WhiteboardDrawTool.TEXT, null, null, null,
                null, "t-2", "txt-1", null, null);

        WhiteboardDrawEventResponse response = service.processDrawEvent("docente1", 10L, del);

        assertThat(response.eventType()).isEqualTo(WhiteboardDrawEventType.TEXT_DELETE);
        assertThat(response.textId()).isEqualTo("txt-1");
        verify(broadcastService).broadcastDraw(any());
    }

    @Test
    void estudianteNoPuedeEnviarTexto() {
        TeacherProfile docente = teacher(1L, "docente1");
        StudentProfile alumno = student(5L, "EST0001");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, true);
        when(userAccountRepository.findByUsername("EST0001")).thenReturn(Optional.of(alumno.getUser()));
        when(studentProfileRepository.findByStudentCode("EST0001")).thenReturn(Optional.of(alumno));
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(participantRepository.findBySessionAndStudent(s, alumno))
                .thenReturn(Optional.of(WhiteboardParticipant.builder()
                        .id(99L).session(s).student(alumno)
                        .interactionOverride(WhiteboardInteractionOverride.ALLOWED).build()));

        WhiteboardDrawEventRequest text = new WhiteboardDrawEventRequest(
                WhiteboardDrawEventType.TEXT, WhiteboardDrawTool.TEXT, "#1d9e75", null, null,
                List.of(new WhiteboardPoint(120.0, 80.0)), "t-3", "txt-9", 32.0,
                List.of(new WhiteboardTextRun("Hola", false, false, false)));

        assertThatThrownBy(() -> service.processDrawEvent("EST0001", 10L, text))
                .hasMessageContaining("docente");
        verify(broadcastService, never()).broadcastDraw(any());
    }
}
