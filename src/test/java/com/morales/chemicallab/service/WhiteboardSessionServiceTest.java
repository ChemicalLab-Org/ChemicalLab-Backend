package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.repository.WhiteboardParticipantRepository;
import com.morales.chemicallab.repository.WhiteboardSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de sesiones de pizarra. Se mockean los repositorios para
 * validar la lógica de negocio: creación, estados, participantes, permisos, captura final y
 * reglas de pertenencia/visibilidad por rol.
 */
@ExtendWith(MockitoExtension.class)
class WhiteboardSessionServiceTest {

    @Mock private WhiteboardSessionRepository sessionRepository;
    @Mock private WhiteboardParticipantRepository participantRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TeacherProfileRepository teacherProfileRepository;
    @Mock private StudentProfileRepository studentProfileRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private UsageMetricService usageMetricService;
    @Mock private WhiteboardBroadcastService broadcastService;

    @InjectMocks private WhiteboardSessionService service;

    // ---------------------------------------------------------------- helpers

    private TeacherProfile teacher(Long id, String username) {
        UserAccount user = UserAccount.builder()
                .id(id).username(username).role(Role.DOCENTE).active(true).build();
        return TeacherProfile.builder().id(id).user(user).names("Ana").lastNames("Quispe").build();
    }

    private StudentProfile student(Long id, String code, String grade, String section) {
        UserAccount user = UserAccount.builder()
                .id(id).username(code).role(Role.ESTUDIANTE).active(true).build();
        return StudentProfile.builder()
                .id(id).user(user).studentCode(code).names("Luis").lastNames("Torres")
                .grade(grade).section(section).build();
    }

    private WhiteboardSession session(Long id, TeacherProfile owner, WhiteboardSessionStatus status,
                                      String grade, String section) {
        return WhiteboardSession.builder()
                .id(id).name("Óxidos en vivo").teacher(owner)
                .grade(grade).section(section).status(status).interactionEnabled(false).build();
    }

    private void stubTeacher(TeacherProfile teacher) {
        when(userAccountRepository.findByUsername(teacher.getUser().getUsername()))
                .thenReturn(Optional.of(teacher.getUser()));
        when(teacherProfileRepository.findByUser(teacher.getUser())).thenReturn(Optional.of(teacher));
    }

    private void stubStudent(StudentProfile student) {
        when(userAccountRepository.findByUsername(student.getUser().getUsername()))
                .thenReturn(Optional.of(student.getUser()));
        when(studentProfileRepository.findByStudentCode(student.getStudentCode()))
                .thenReturn(Optional.of(student));
    }

    private MultipartFile png() {
        return new MockMultipartFile("snapshot", "pizarra.png", "image/png", new byte[]{1, 2, 3, 4});
    }

    // ---------------------------------------------------------------- sesiones

    @Test
    void docenteCreaSesionValida() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(sessionRepository.save(any(WhiteboardSession.class))).thenAnswer(inv -> inv.getArgument(0));

        WhiteboardSessionResponse response = service.createSession("docente1",
                new WhiteboardSessionCreateRequest("  Óxidos  ", "Clase de hoy", "3", "a"));

        assertThat(response.name()).isEqualTo("Óxidos");
        assertThat(response.grade()).isEqualTo("3");
        assertThat(response.section()).isEqualTo("A"); // normalizada a mayúscula
        assertThat(response.status()).isEqualTo(WhiteboardSessionStatus.ACTIVE);
        assertThat(response.interactionEnabled()).isFalse();
        verify(auditLogService).recordInfo(eq(LogEventType.WHITEBOARD_SESSION_CREATED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void rechazaNombreVacio() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);

        assertThatThrownBy(() -> service.createSession("docente1",
                new WhiteboardSessionCreateRequest("   ", null, "3", "A")))
                .hasMessageContaining("nombre");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void rechazaCaracteresEspecialesEnNombre() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);

        assertThatThrownBy(() -> service.createSession("docente1",
                new WhiteboardSessionCreateRequest("Clase #1", null, "3", "A")))
                .hasMessageContaining("letras, números y espacios");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void rechazaGradoInvalido() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);

        assertThatThrownBy(() -> service.createSession("docente1",
                new WhiteboardSessionCreateRequest("Sesión", null, "7", "A")))
                .hasMessageContaining("grado");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void rechazaSeccionInvalida() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);

        assertThatThrownBy(() -> service.createSession("docente1",
                new WhiteboardSessionCreateRequest("Sesión", null, "3", "AB")))
                .hasMessageContaining("sección");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void docenteListaSoloSusSesiones() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(sessionRepository.findByTeacherOrderByCreatedAtDesc(docente))
                .thenReturn(List.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A")));

        List<WhiteboardSessionResponse> result = service.listTeacherSessions("docente1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(10L);
    }

    @Test
    void estudianteListaSesionesDeSuSeccion() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        when(sessionRepository.findByGradeAndSectionAndStatusInOrderByCreatedAtDesc(
                eq("3"), eq("A"), anyCollection()))
                .thenReturn(List.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A")));

        List<WhiteboardStudentSessionResponse> result = service.listActiveForStudent("EST0001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(10L);
    }

    @Test
    void estudianteNoVeSesionDeOtraSeccion() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A")));

        assertThatThrownBy(() -> service.getStudentSessionDetail("EST0002", 10L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---------------------------------------------------------------- estados

    @Test
    void pausarSesionActiva() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WhiteboardSessionResponse response = service.pauseSession("docente1", 10L);

        assertThat(response.status()).isEqualTo(WhiteboardSessionStatus.PAUSED);
        verify(broadcastService).broadcastControl(any());
    }

    @Test
    void reanudarSesionPausada() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.PAUSED, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WhiteboardSessionResponse response = service.resumeSession("docente1", 10L);

        assertThat(response.status()).isEqualTo(WhiteboardSessionStatus.ACTIVE);
    }

    @Test
    void cerrarSesionActivaConPng() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WhiteboardSessionResponse response = service.closeSession("docente1", 10L, png());

        assertThat(response.status()).isEqualTo(WhiteboardSessionStatus.CLOSED);
        assertThat(response.snapshotAvailable()).isTrue();
        assertThat(response.closedBy()).isEqualTo("docente1");
        assertThat(s.getFinalSnapshotContentType()).isEqualTo("image/png");
    }

    @Test
    void cerrarSesionPausadaConJpg() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.PAUSED, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MultipartFile jpg = new MockMultipartFile("snapshot", "p.jpg", "image/jpeg", new byte[]{9, 9});
        WhiteboardSessionResponse response = service.closeSession("docente1", 10L, jpg);

        assertThat(response.status()).isEqualTo(WhiteboardSessionStatus.CLOSED);
    }

    @Test
    void rechazaReanudarSesionCerrada() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.resumeSession("docente1", 10L))
                .hasMessageContaining("finalizada");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void rechazaPausarSesionCerrada() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.pauseSession("docente1", 10L))
                .hasMessageContaining("finalizada");
    }

    @Test
    void cerradaEsTerminalNoSeReabre() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.closeSession("docente1", 10L, png()))
                .hasMessageContaining("reabrirse");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void rechazaCambiarInteraccionEnSesionCerrada() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.updateGlobalInteraction("docente1", 10L,
                new WhiteboardGlobalInteractionRequest(true)))
                .hasMessageContaining("finalizada");
    }

    // ---------------------------------------------------------------- participantes

    @Test
    void estudianteSeUneASesionDeSuSeccion() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(participantRepository.findBySessionAndStudent(s, alumno)).thenReturn(Optional.empty());
        when(participantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.joinSession("EST0001", 10L);

        verify(participantRepository).save(any(WhiteboardParticipant.class));
        // La primera unión avisa al canal para refrescar el panel de participantes del docente.
        ArgumentCaptor<WhiteboardControlEventResponse> ctrl =
                ArgumentCaptor.forClass(WhiteboardControlEventResponse.class);
        verify(broadcastService).broadcastControl(ctrl.capture());
        assertThat(ctrl.getValue().eventType()).isEqualTo(WhiteboardControlEventType.PARTICIPANT_JOINED);
        assertThat(ctrl.getValue().targetStudentId()).isEqualTo(5L);
    }

    @Test
    void estudianteNoSeUneASesionDeOtraSeccion() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A")));

        assertThatThrownBy(() -> service.joinSession("EST0002", 10L))
                .isInstanceOf(EntityNotFoundException.class);
        verify(participantRepository, never()).save(any());
    }

    @Test
    void estudianteNoSeUneASesionCerrada() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A")));

        assertThatThrownBy(() -> service.joinSession("EST0001", 10L))
                .hasMessageContaining("finalizada");
        verify(participantRepository, never()).save(any());
    }

    @Test
    void joinDuplicadoNoCreaParticipanteNuevo() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        WhiteboardParticipant existing = WhiteboardParticipant.builder()
                .id(99L).session(s).student(alumno)
                .interactionOverride(WhiteboardInteractionOverride.FOLLOW_GLOBAL).build();
        when(participantRepository.findBySessionAndStudent(s, alumno)).thenReturn(Optional.of(existing));
        when(participantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.joinSession("EST0001", 10L);

        ArgumentCaptor<WhiteboardParticipant> captor = ArgumentCaptor.forClass(WhiteboardParticipant.class);
        verify(participantRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(99L); // se reutiliza, no se crea otro
        // Re-unión idempotente: no se emite PARTICIPANT_JOINED de nuevo.
        verify(broadcastService, never()).broadcastControl(any());
    }

    // ---------------------------------------------------------------- estado del lienzo

    @Test
    void docenteGuardaEstadoDelLienzo() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WhiteboardBoardStateResponse response = service.saveBoardState("docente1", 10L,
                new WhiteboardBoardStateRequest("{\"strokes\":[],\"texts\":[]}"));

        assertThat(response.stateJson()).contains("strokes");
        assertThat(s.getCurrentStateJson()).contains("texts");
        assertThat(s.getStateUpdatedAt()).isNotNull();
    }

    @Test
    void noGuardaEstadoEnSesionCerrada() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.saveBoardState("docente1", 10L,
                new WhiteboardBoardStateRequest("{}")))
                .hasMessageContaining("finalizada");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void rechazaEstadoDemasiadoGrande() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        String big = "x".repeat(2_000_001);
        assertThatThrownBy(() -> service.saveBoardState("docente1", 10L,
                new WhiteboardBoardStateRequest(big)))
                .hasMessageContaining("tamaño");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void estudianteDeLaSeccionObtieneEstadoActual() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        s.setCurrentStateJson("{\"strokes\":[1]}");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        WhiteboardBoardStateResponse response = service.getStudentBoardState("EST0001", 10L);

        assertThat(response.stateJson()).contains("strokes");
        assertThat(response.status()).isEqualTo(WhiteboardSessionStatus.ACTIVE);
    }

    @Test
    void estudianteDeOtraSeccionNoObtieneEstado() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A")));

        assertThatThrownBy(() -> service.getStudentBoardState("EST0002", 10L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---------------------------------------------------------------- interacción

    @Test
    void actualizarInteraccionGlobal() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WhiteboardSessionResponse response = service.updateGlobalInteraction("docente1", 10L,
                new WhiteboardGlobalInteractionRequest(true));

        assertThat(response.interactionEnabled()).isTrue();
        verify(broadcastService).broadcastControl(any());
    }

    @Test
    void bloquearAlumnoIndividualAunqueGlobalActivo() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        s.setInteractionEnabled(true); // global activo
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        WhiteboardParticipant participant = WhiteboardParticipant.builder()
                .id(99L).session(s).student(alumno)
                .interactionOverride(WhiteboardInteractionOverride.FOLLOW_GLOBAL).build();
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));
        when(participantRepository.findBySession_IdAndStudent_Id(10L, 5L)).thenReturn(Optional.of(participant));
        when(participantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WhiteboardParticipantResponse response = service.updateParticipantInteraction("docente1", 10L, 5L,
                new WhiteboardParticipantInteractionRequest(WhiteboardInteractionOverride.BLOCKED));

        assertThat(response.interactionOverride()).isEqualTo(WhiteboardInteractionOverride.BLOCKED);
        assertThat(response.effectiveInteraction()).isFalse(); // bloqueado pese al global activo
    }

    // ---------------------------------------------------------------- snapshot

    @Test
    void rechazaSnapshotVacio() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        MultipartFile empty = new MockMultipartFile("snapshot", "p.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> service.closeSession("docente1", 10L, empty))
                .hasMessageContaining("captura");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void rechazaSnapshotTipoInvalido() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        MultipartFile pdf = new MockMultipartFile("snapshot", "p.pdf", "application/pdf", new byte[]{1, 2});
        assertThatThrownBy(() -> service.closeSession("docente1", 10L, pdf))
                .hasMessageContaining("PNG o JPG");
    }

    @Test
    void rechazaSnapshotDemasiadoGrande() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.ACTIVE, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        byte[] big = new byte[5 * 1024 * 1024 + 1];
        MultipartFile huge = new MockMultipartFile("snapshot", "p.png", "image/png", big);
        assertThatThrownBy(() -> service.closeSession("docente1", 10L, huge))
                .hasMessageContaining("5 MB");
    }

    @Test
    void docentePropietarioObtieneSnapshot() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A");
        s.setFinalSnapshotData(new byte[]{1, 2, 3});
        s.setFinalSnapshotContentType("image/png");
        s.setFinalSnapshotFileName("pizarra.png");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        WhiteboardSnapshotDownload download = service.getTeacherSnapshot("docente1", 10L);

        assertThat(download.data()).hasSize(3);
        assertThat(download.contentType()).isEqualTo("image/png");
    }

    @Test
    void estudianteAsignadoObtieneSnapshot() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A");
        s.setFinalSnapshotData(new byte[]{7});
        s.setFinalSnapshotContentType("image/jpeg");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        WhiteboardSnapshotDownload download = service.getStudentSnapshot("EST0001", 10L);

        assertThat(download.data()).hasSize(1);
    }

    @Test
    void estudianteNoAsignadoNoObtieneSnapshot() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        WhiteboardSession s = session(10L, docente, WhiteboardSessionStatus.CLOSED, "3", "A");
        when(sessionRepository.findById(10L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.getStudentSnapshot("EST0002", 10L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---------------------------------------------------------------- seguridad

    @Test
    void docenteNoControlaSesionDeOtroDocente() {
        TeacherProfile docente = teacher(1L, "docente1");
        TeacherProfile otro = teacher(2L, "docente2");
        stubTeacher(docente);
        when(sessionRepository.findById(10L))
                .thenReturn(Optional.of(session(10L, otro, WhiteboardSessionStatus.ACTIVE, "3", "A")));

        assertThatThrownBy(() -> service.pauseSession("docente1", 10L))
                .hasMessageContaining("permiso");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void adminResumenDevuelveConteos() {
        when(sessionRepository.count()).thenReturn(5L);
        when(sessionRepository.countByStatus(WhiteboardSessionStatus.ACTIVE)).thenReturn(2L);
        when(sessionRepository.countByStatus(WhiteboardSessionStatus.PAUSED)).thenReturn(1L);
        when(sessionRepository.countByStatus(WhiteboardSessionStatus.CLOSED)).thenReturn(2L);
        when(participantRepository.count()).thenReturn(8L);
        when(sessionRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        WhiteboardSummaryResponse summary = service.getAdminSummary();

        assertThat(summary.totalSessions()).isEqualTo(5L);
        assertThat(summary.activeSessions()).isEqualTo(2L);
        assertThat(summary.pausedSessions()).isEqualTo(1L);
        assertThat(summary.closedSessions()).isEqualTo(2L);
        assertThat(summary.totalParticipants()).isEqualTo(8L);
    }

    // Evita el aviso de import sin uso de Set en algunos entornos.
    @Test
    void liveStatusesIncluyeActivaYPausada() {
        assertThat(Set.of(WhiteboardSessionStatus.ACTIVE, WhiteboardSessionStatus.PAUSED))
                .doesNotContain(WhiteboardSessionStatus.CLOSED);
    }
}
