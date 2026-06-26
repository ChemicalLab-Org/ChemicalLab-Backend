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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lógica de negocio de los materiales de apoyo de los contenidos conceptuales: archivos
 * (PDF, diapositivas e imágenes) y enlaces externos.
 *
 * <p>Decisiones y garantías de seguridad:</p>
 * <ul>
 *   <li><strong>Almacenamiento (MVP):</strong> los archivos se guardan como bytes en
 *       PostgreSQL ({@code bytea}) con un límite estricto de 10&nbsp;MB. No se usa el
 *       sistema de archivos local porque el despliegue en Render no garantiza persistencia.</li>
 *   <li><strong>Tipos permitidos:</strong> lista blanca de {@code Content-Type} con su
 *       extensión compatible. Cualquier otro tipo (ejecutables, comprimidos, scripts…) se
 *       rechaza. No se confía solo en la extensión.</li>
 *   <li><strong>Pertenencia:</strong> el docente solo opera sobre sus propios contenidos.</li>
 *   <li><strong>Visibilidad:</strong> el estudiante solo descarga materiales de contenidos
 *       publicados y asignados a su grado/sección; el administrador accede en solo lectura.</li>
 *   <li><strong>Un archivo por contenido:</strong> subir un archivo reemplaza el anterior
 *       (se elimina para no dejar bytes huérfanos). Los enlaces sí pueden ser varios.</li>
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

    private static final String TARGET_CONCEPT = "ConceptContent";

    // Límite estricto de tamaño por archivo (10 MB), validado además del límite multipart.
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_TITLE_LENGTH = 150;

    // Lista blanca: Content-Type permitido -> extensiones compatibles. Lo que no esté aquí
    // (ejecutables, comprimidos, scripts, HTML, SVG…) se rechaza.
    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.of(
            "application/pdf", Set.of("pdf"),
            "application/vnd.ms-powerpoint", Set.of("ppt"),
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", Set.of("pptx"),
            "image/png", Set.of("png"),
            "image/jpeg", Set.of("jpg", "jpeg"));

    // Tipos que se muestran en línea (preview); el resto se descarga como adjunto.
    private static final Set<String> INLINE_CONTENT_TYPES =
            Set.of("application/pdf", "image/png", "image/jpeg");

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

        validateFile(file);
        String contentType = file.getContentType();
        String fileName = sanitizeFileName(file.getOriginalFilename());
        validateContentType(contentType, fileName);

        byte[] data = readBytes(file);

        // Reemplazo: se elimina el archivo previo (si lo hay) para mantener un solo adjunto.
        var existing = conceptMaterialRepository
                .findFirstByConceptContentIdAndTypeAndActiveTrue(conceptId, MaterialType.FILE);
        boolean replaced = existing.isPresent();
        existing.ifPresent(conceptMaterialRepository::delete);

        ConceptMaterial material = ConceptMaterial.builder()
                .conceptContent(content)
                .type(MaterialType.FILE)
                .title(cleanTitle(rawTitle))
                .originalFileName(fileName)
                .contentType(contentType)
                .fileSize((long) data.length)
                .fileData(data)
                .uploadedBy(username)
                .active(true)
                .build();
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

        boolean inline = material.getContentType() != null
                && INLINE_CONTENT_TYPES.contains(material.getContentType());

        return new MaterialDownload(
                material.getFileData(),
                material.getContentType(),
                material.getOriginalFileName(),
                inline);
    }

    // =========================================================================
    // VALIDACIONES
    // =========================================================================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Debes adjuntar un archivo.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("El archivo supera el tamaño máximo permitido (10 MB).");
        }
    }

    private void validateContentType(String contentType, String fileName) {
        String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        Set<String> allowedExtensions = ALLOWED_TYPES.get(normalizedType);
        if (allowedExtensions == null) {
            throw new IllegalArgumentException(
                    "Tipo de archivo no permitido. Solo se aceptan PDF, diapositivas (PPT/PPTX) e imágenes (PNG/JPG).");
        }
        // No se confía solo en la extensión, pero debe ser coherente con el Content-Type.
        String extension = extensionOf(fileName);
        if (extension == null || !allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException(
                    "La extensión del archivo no coincide con su tipo permitido.");
        }
    }

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

    /**
     * Sanitiza el nombre original del archivo: descarta cualquier componente de ruta
     * (previene path traversal) y caracteres de control que permitirían inyectar cabeceras.
     */
    private String sanitizeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "archivo";
        }
        // Toma solo el último segmento tras / o \ y elimina secuencias "..".
        String name = rawName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replace("..", "").replaceAll("[\\r\\n\"]", "").trim();
        if (name.isEmpty()) {
            return "archivo";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw new IllegalArgumentException("No se pudo leer el archivo adjunto.");
        }
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
