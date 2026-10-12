package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.ConceptMaterialResponse;
import com.morales.chemicallab.dto.CreateMaterialLinkRequest;
import com.morales.chemicallab.dto.MaterialDownload;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.ConceptContentRepository;
import com.morales.chemicallab.repository.ConceptMaterialRepository;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.morales.chemicallab.security.files.FileValidationService;

import java.util.Locale;

/**
 * Lógica de negocio de los materiales de apoyo de los contenidos conceptuales: archivos
 * (PDF, diapositivas e imágenes) y enlaces externos.
 *
 * <p>Decisiones y garantías de seguridad:</p>
 * <ul>
 *   <li><strong>Almacenamiento (MVP):</strong> los archivos se guardan como bytes en
 *       PostgreSQL ({@code bytea}) con un límite estricto de 10&nbsp;MB. No se usa el
 *       sistema de archivos local porque el despliegue en Render no garantiza persistencia.</li>
 *   <li><strong>Tipos permitidos:</strong> formato real validado en JVM acotada; imágenes normalizadas.</li>
 *   <li><strong>Pertenencia:</strong> el docente solo opera sobre sus propios contenidos.</li>
 *   <li><strong>Visibilidad:</strong> el estudiante solo descarga materiales de contenidos
 *       publicados y asignados a su grado/sección; el administrador accede en solo lectura.</li>
 *   <li><strong>Un archivo por contenido:</strong> subir un archivo reemplaza el anterior
 *       (se actualiza su identidad y se contabiliza la diferencia de bytes). Los enlaces sí pueden ser varios.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ConceptMaterialService {

    private final ConceptMaterialRepository conceptMaterialRepository;
    private final ConceptContentRepository conceptContentRepository;
    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final AuditLogService auditLogService;
    private final FileValidationService fileValidationService;
    private final com.morales.chemicallab.security.storage.QueryLimits queryLimits;
    private final EntityManager entityManager;

    private static final String TARGET_CONCEPT = "ConceptContent";

    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_TITLE_LENGTH = 150;

    // =========================================================================
    // DOCENTE — gestión de materiales
    // =========================================================================

    /**
     * Sube (o reemplaza) el archivo de apoyo de un contenido del docente. Para el MVP se
     * admite un único archivo por contenido: si ya existe uno, se elimina para no dejar
     * bytes huérfanos y se registra como reemplazo.
     */
    public ConceptMaterialResponse addFileMaterial(String username, Long conceptId,
                                                   MultipartFile file, String rawTitle) {
        TeacherProfile teacher = requireTeacher(username);
        ConceptContent content = requireOwnedConcept(conceptId, teacher);

        var validated = fileValidationService.material(file);
        String contentType = validated.contentType();
        String fileName = validated.fileName();
        byte[] data = validated.data();

        // Validate expensive bytes first; serialize the read/replace decision across API processes.
        lockOwnedConcept(content, teacher);
        if (conceptMaterialRepository.countByConceptContentIdAndTypeAndActiveTrue(conceptId, MaterialType.FILE) > 1) {
            throw new com.morales.chemicallab.security.limits.InputRejectedException(409,
                    "MATERIAL_STATE_CONFLICT", "El contenido tiene varios archivos activos. Solicita una revisión antes de reemplazarlos.");
        }

        // Reemplazo: se elimina el archivo previo (si lo hay) para mantener un solo adjunto.
        var existing = conceptMaterialRepository
                .findFirstByConceptContentIdAndTypeAndActiveTrue(conceptId, MaterialType.FILE);
        boolean replaced = existing.isPresent();


        ConceptMaterial material = existing.orElseGet(() -> ConceptMaterial.builder().conceptContent(content).type(MaterialType.FILE).active(true).build());
        material.setTitle(cleanTitle(rawTitle));material.setOriginalFileName(fileName);material.setContentType(contentType);
        material.setFileSize((long)data.length);material.setFileData(data);material.setUploadedBy(username);
        conceptMaterialRepository.save(material);

        LogEventType eventType = replaced
                ? LogEventType.CONCEPT_MATERIAL_REPLACED
                : LogEventType.CONCEPT_MATERIAL_ADDED;
        String verb = replaced ? "Se reemplazó" : "Se agregó";
        auditLogService.recordInfo(eventType, TARGET_CONCEPT, content.getId(), content.getTitle(),
                replaced ? "Reemplazar material" : "Agregar material",
                verb + " un archivo de apoyo en el contenido «" + content.getTitle() + "».",
                "type=FILE;contentType=" + contentType + ";size=" + data.length);

        return toResponse(conceptId, material);
    }

    /** Agrega un enlace externo de apoyo a un contenido del docente. */
    public ConceptMaterialResponse addLinkMaterial(String username, Long conceptId,
                                                   CreateMaterialLinkRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        ConceptContent content = requireOwnedConcept(conceptId, teacher);

        String url = validateUrl(request.url());
        String title = cleanTitle(request.title());
        lockOwnedConcept(content, teacher);

        ConceptMaterial material = ConceptMaterial.builder()
                .conceptContent(content)
                .type(MaterialType.LINK)
                .title(title)
                .url(url)
                .uploadedBy(username)
                .active(true)
                .build();
        conceptMaterialRepository.save(material);

        auditLogService.recordInfo(LogEventType.CONCEPT_LINK_ADDED, TARGET_CONCEPT,
                content.getId(), content.getTitle(), "Agregar enlace",
                "Se agregó un enlace de apoyo al contenido «" + content.getTitle() + "».",
                "type=LINK");

        return toResponse(conceptId, material);
    }

    /** Retira un material (archivo o enlace) de un contenido del docente. */
    public void removeMaterial(String username, Long conceptId, Long materialId) {
        TeacherProfile teacher = requireTeacher(username);
        ConceptContent content = requireOwnedConcept(conceptId, teacher);

        lockOwnedConcept(content, teacher);

        ConceptMaterial material = conceptMaterialRepository
                .findByIdAndConceptContentIdAndActiveTrue(materialId, conceptId)
                .orElseThrow(() -> new EntityNotFoundException("El material no existe en este contenido."));

        boolean isLink = material.getType() == MaterialType.LINK;
        // Se elimina por completo para no conservar bytes huérfanos en la base de datos.
        conceptMaterialRepository.delete(material);

        LogEventType eventType = isLink
                ? LogEventType.CONCEPT_LINK_REMOVED
                : LogEventType.CONCEPT_MATERIAL_REMOVED;
        auditLogService.recordInfo(eventType, TARGET_CONCEPT, content.getId(), content.getTitle(),
                "Retirar material",
                "Se retiró un material de apoyo del contenido «" + content.getTitle() + "».",
                "type=" + material.getType());
    }

    // =========================================================================
    // DESCARGA / VISUALIZACIÓN — control de acceso por rol
    // =========================================================================

    /**
     * Devuelve los bytes de un material de tipo archivo aplicando el control de acceso por
     * rol: el docente sobre sus contenidos; el estudiante solo sobre contenidos publicados y
     * asignados a su grado/sección; el administrador en solo lectura. Los enlaces no se
     * descargan por aquí (se abren directamente desde su URL).
     */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,timeout=15)
    public com.morales.chemicallab.dto.QueryPage<ConceptMaterialResponse> listMaterials(String username,Long conceptId,int page,int size) {
        var pageable=queryLimits.page(page,size);
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El usuario no existe."));

        switch (user.getRole()) {
            case DOCENTE -> requireOwnedConcept(conceptId, requireTeacher(username));
            case ESTUDIANTE -> requireConceptVisibleToStudent(username, conceptId);
            case ADMINISTRADOR -> requireExistingConcept(conceptId);
            default -> throw new IllegalArgumentException("El rol del usuario no permite esta operación.");
        }

        var rows=conceptMaterialRepository.findMetadataByConcept(conceptId,pageable).stream()
            .map(v->ConceptMaterialResponse.fromView(conceptId,v)).toList();
        return com.morales.chemicallab.dto.QueryPage.of(rows,page,size,conceptMaterialRepository.countByConceptContentIdAndActiveTrue(conceptId));
    }

    @Transactional(readOnly = true)
    public MaterialDownload downloadMaterial(String username, Long conceptId, Long materialId) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El usuario no existe."));

        switch (user.getRole()) {
            case DOCENTE -> requireOwnedConcept(conceptId, requireTeacher(username));
            case ESTUDIANTE -> requireConceptVisibleToStudent(username, conceptId);
            case ADMINISTRADOR -> requireExistingConcept(conceptId);
            default -> throw new IllegalArgumentException("El rol del usuario no permite esta operación.");
        }

        ConceptMaterial material = conceptMaterialRepository
                .findByIdAndConceptContentIdAndActiveTrue(materialId, conceptId)
                .orElseThrow(() -> new EntityNotFoundException("El material no existe en este contenido."));

        if (material.getType() != MaterialType.FILE || material.getFileData() == null) {
            throw new IllegalArgumentException("Este material no es un archivo descargable.");
        }

        var validated = fileValidationService.historical(material.getFileData(),
                material.getContentType(), material.getOriginalFileName(), false);
        return new MaterialDownload(
                validated.data(), validated.contentType(), validated.fileName(), validated.image());
    }

    // =========================================================================
    // VALIDACIONES
    // =========================================================================

    /** Valida y normaliza una URL de enlace externo: solo http/https, sin esquemas peligrosos. */
    private String validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("La URL es obligatoria.");
        }
        String url = rawUrl.trim();
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("La URL no puede superar " + MAX_URL_LENGTH + " caracteres.");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("La URL debe iniciar con http:// o https://.");
        }
        // Defensa en profundidad: aunque http/https ya los excluye, se rechazan explícitamente.
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("file:")) {
            throw new IllegalArgumentException("La URL contiene un esquema no permitido.");
        }
        return url;
    }

    private String cleanTitle(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_TITLE_LENGTH ? trimmed.substring(0, MAX_TITLE_LENGTH) : trimmed;
    }

    // =========================================================================
    // RESOLUCIÓN DE USUARIOS Y CONTENIDOS
    // =========================================================================

    private TeacherProfile requireTeacher(String username) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
        if (user.getRole() != Role.DOCENTE) {
            throw new IllegalArgumentException("El usuario autenticado no es un docente.");
        }
        return teacherProfileRepository.findByUser(user)
                .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
    }

    /** Carga un contenido y valida que pertenezca al docente indicado. */
    private ConceptContent requireOwnedConcept(Long conceptId, TeacherProfile teacher) {
        ConceptContent content = conceptContentRepository.findById(conceptId)
                .orElseThrow(() -> new EntityNotFoundException("El contenido conceptual no existe."));
        if (!content.getCreatedByTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("No tienes permiso para modificar este contenido.");
        }
        return content;
    }

    private ConceptContent requireExistingConcept(Long conceptId) {
        return conceptContentRepository.findById(conceptId)
                .orElseThrow(() -> new EntityNotFoundException("El contenido conceptual no existe."));
    }

    private void lockOwnedConcept(ConceptContent content, TeacherProfile teacher) {
        entityManager.refresh(content, LockModeType.PESSIMISTIC_WRITE);
        if (!content.getCreatedByTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("No tienes permiso para modificar este contenido.");
        }
    }

    /** Verifica que el contenido esté publicado y asignado al grado/sección del estudiante. */
    private void requireConceptVisibleToStudent(String username, Long conceptId) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
        StudentProfile student = studentProfileRepository.findByStudentCode(user.getUsername())
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
        conceptContentRepository
                .findPublishedForSectionById(conceptId, student.getGrade(), student.getSection(),
                        ConceptStatus.PUBLISHED)
                .orElseThrow(() -> new EntityNotFoundException(
                        "El contenido no está disponible para tu grado o sección."));
    }

    // =========================================================================
    // MAPEO
    // =========================================================================

    private ConceptMaterialResponse toResponse(Long conceptId, ConceptMaterial material) {
        return ConceptMaterialResponse.fromView(conceptId,
                new com.morales.chemicallab.dto.ConceptMaterialView(
                        material.getId(), material.getType(), material.getTitle(),
                        material.getOriginalFileName(), material.getContentType(),
                        material.getFileSize(), material.getUrl()));
    }
}
