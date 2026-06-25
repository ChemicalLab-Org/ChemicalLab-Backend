package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.ConceptContent;
import com.morales.chemicallab.entity.ConceptStatus;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.ConceptContentRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pruebas de integración de la persistencia de contenidos conceptuales contra la base de
 * datos real.
 *
 * <p>Reproducen y blindan la regresión por la que crear un contenido con una categoría
 * personalizada (o una clásica escrita de otra forma, como «Óxidos») devolvía HTTP 500:
 * la restricción CHECK heredada {@code concept_contents_category_check}, generada cuando
 * la categoría era una enumeración, seguía rechazando cualquier valor distinto de los
 * códigos antiguos. {@code ConceptContentSchemaMigration} la elimina al arrancar.</p>
 */
@SpringBootTest
@Transactional
class ConceptContentPersistenceDbTest {

    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private TeacherProfileRepository teacherProfileRepository;
    @Autowired
    private ConceptContentRepository conceptContentRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void noExisteLaRestriccionHeredadaDeCategoria() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'concept_contents_category_check'",
                Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void persisteContenidoConCategoriaPersonalizada() {
        TeacherProfile teacher = persistTeacher();

        ConceptContent custom = ConceptContent.builder()
                .title("Prueba enlace")
                .category("Enlace químico")
                .explanation("Explicación del enlace.")
                .createdByTeacher(teacher)
                .status(ConceptStatus.DRAFT)
                .active(true)
                .build();

        // saveAndFlush fuerza el INSERT inmediato: si la restricción heredada existiera,
        // PostgreSQL lanzaría aquí la violación de CHECK.
        assertThatCode(() -> conceptContentRepository.saveAndFlush(custom)).doesNotThrowAnyException();
        assertThat(custom.getId()).isNotNull();
    }

    @Test
    void persisteContenidoConCategoriaClasica() {
        TeacherProfile teacher = persistTeacher();

        ConceptContent legacy = ConceptContent.builder()
                .title("Prueba óxidos")
                .category("Óxidos")
                .explanation("Explicación de los óxidos.")
                .createdByTeacher(teacher)
                .status(ConceptStatus.DRAFT)
                .active(true)
                .build();

        assertThatCode(() -> conceptContentRepository.saveAndFlush(legacy)).doesNotThrowAnyException();
        assertThat(legacy.getId()).isNotNull();
    }

    private TeacherProfile persistTeacher() {
        UserAccount user = UserAccount.builder()
                .username("docente-test-" + System.nanoTime())
                .password("x")
                .role(Role.DOCENTE)
                .active(true)
                .temporaryPassword(false)
                .build();
        userAccountRepository.saveAndFlush(user);

        TeacherProfile teacher = TeacherProfile.builder()
                .user(user)
                .names("Docente")
                .lastNames("De prueba")
                .build();
        return teacherProfileRepository.saveAndFlush(teacher);
    }
}
