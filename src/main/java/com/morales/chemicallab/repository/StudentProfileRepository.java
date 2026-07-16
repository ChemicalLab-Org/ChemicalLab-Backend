package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.TeacherProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentProfileRepository extends JpaRepository<StudentProfile, Long> {

    boolean existsByStudentCode(String studentCode);

    Optional<StudentProfile> findByStudentCode(String studentCode);

    Optional<StudentProfile> findByUser_Id(Long userId);

    List<StudentProfile> findByTeacher(TeacherProfile teacher);

    List<StudentProfile> findByTeacher_Id(Long teacherId);

    List<StudentProfile> findByTeacherAndGrade(TeacherProfile teacher, String grade);

    List<StudentProfile> findByTeacherAndSection(TeacherProfile teacher, String section);

    // Estudiantes de un grado y sección concretos. Se usa para determinar el conjunto de
    // estudiantes con acceso a una evaluación (mismo criterio de grado/sección que la
    // vista del estudiante en findActiveForSection): la disponibilidad de la revisión
    // depende de que todo ese grupo haya finalizado sus intentos.
    List<StudentProfile> findByGradeAndSection(String grade, String section);
}
