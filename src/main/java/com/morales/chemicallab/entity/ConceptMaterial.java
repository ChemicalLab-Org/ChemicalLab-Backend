package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Material de apoyo asociado a un {@link ConceptContent}. Permite que un contenido
 * conceptual se complemente con un archivo (PDF, diapositivas o imagen) o con enlaces
 * externos de apoyo.
 *
 * <p>Para esta primera versión (MVP) los archivos se almacenan como bytes en la propia
 * base de datos PostgreSQL ({@code bytea}), con un límite estricto de tamaño. Se evita
 * depender del sistema de archivos local porque el despliegue en Render no garantiza un
 * filesystem persistente entre reinicios o redespliegues. Para una escala mayor podría
 * migrarse a un almacenamiento externo (S3, Cloudinary, Supabase Storage…), pero no se
 * implementa ahora.</p>
 *
 * <p>Los bytes ({@link #fileData}) nunca deben devolverse en listados ni en respuestas de
 * metadata: se cargan únicamente en el endpoint de descarga/visualización.</p>
 */
@Entity
@Table(name = "concept_materials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConceptMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "concept_content_id", nullable = false)
    private ConceptContent conceptContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MaterialType type;

    // Título o descripción breve del material. Opcional para archivos, recomendable para enlaces.
    @Column(length = 150)
    private String title;

    // Metadatos del archivo (solo para type = FILE).
    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    // Contenido binario del archivo almacenado en PostgreSQL (bytea). Hibernate 6 mapea
    // byte[] a bytea por defecto. Es @Basic LAZY para no arrastrar los bytes salvo que se
    // acceda explícitamente; aun así, los listados usan proyecciones que no lo seleccionan.
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_data")
    private byte[] fileData;

    // URL del enlace externo (solo para type = LINK).
    @Column(length = 2048)
    private String url;

    // Nombre de usuario del docente que subió o registró el material (no es dato sensible).
    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
