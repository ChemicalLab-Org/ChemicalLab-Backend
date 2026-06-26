package com.morales.chemicallab.repository;

import com.morales.chemicallab.dto.ConceptMaterialView;
import com.morales.chemicallab.entity.ConceptMaterial;
import com.morales.chemicallab.entity.MaterialType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConceptMaterialRepository extends JpaRepository<ConceptMaterial, Long> {

    /**
     * Metadata de los materiales activos de un contenido, sin cargar los bytes del archivo.
     * Se proyecta a {@link ConceptMaterialView} para garantizar que {@code fileData} nunca
     * viaje en listados. Los archivos van primero y, dentro de cada tipo, por antigüedad.
     */
    @Query("""
            select new com.morales.chemicallab.dto.ConceptMaterialView(
                m.id, m.type, m.title, m.originalFileName, m.contentType, m.fileSize, m.url)
            from ConceptMaterial m
            where m.conceptContent.id = :conceptId
              and m.active = true
            order by m.type asc, m.createdAt asc
            """)
    List<ConceptMaterialView> findMetadataByConcept(@Param("conceptId") Long conceptId);

    // Carga la entidad completa (incluidos los bytes) para descargar/visualizar un archivo.
    Optional<ConceptMaterial> findByIdAndConceptContentIdAndActiveTrue(Long id, Long conceptContentId);

    // Material activo de un tipo concreto (se usa para localizar el archivo a reemplazar).
    Optional<ConceptMaterial> findFirstByConceptContentIdAndTypeAndActiveTrue(Long conceptContentId, MaterialType type);

    // Conteos para la supervisión administrativa y reglas de negocio (sin tocar los bytes).
    long countByConceptContentIdAndActiveTrue(Long conceptContentId);

    long countByConceptContentIdAndTypeAndActiveTrue(Long conceptContentId, MaterialType type);

    boolean existsByConceptContentIdAndActiveTrue(Long conceptContentId);
}
