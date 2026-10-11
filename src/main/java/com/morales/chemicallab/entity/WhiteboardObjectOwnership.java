package com.morales.chemicallab.entity;

import jakarta.persistence.*;
import lombok.*;

/** Tombstones reserve identities after delete/clear; null owner means unverifiable legacy data. */
@Entity
@Table(name = "whiteboard_object_ownership", uniqueConstraints = @UniqueConstraint(
        name = "uk_whiteboard_object", columnNames = {"board_id", "object_kind", "object_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WhiteboardObjectOwnership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "board_id", nullable = false) private WhiteboardSession board;
    @Column(name = "object_kind", nullable = false, length = 10) private String kind;
    @Column(name = "object_id", nullable = false, length = 100) private String objectId;
    @ManyToOne @JoinColumn(name = "owner_user_id") private UserAccount owner;
    @Column(nullable = false) private boolean deleted;
}
