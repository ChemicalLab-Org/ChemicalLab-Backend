package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "student_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private UserAccount user;

    @ManyToOne
    @JoinColumn(name = "teacher_id", nullable = false)
    private TeacherProfile teacher;

    @Column(unique = true, nullable = false)
    private String studentCode;

    @Column(nullable = false)
    private String names;

    @Column(nullable = false)
    private String lastNames;

    @Column(nullable = false)
    private String grade;

    @Column(nullable = false)
    private String section;
}
