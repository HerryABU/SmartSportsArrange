package com.sports.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "registration",
        uniqueConstraints = @UniqueConstraint(columnNames = {"athlete_id", "event_id"}),
        indexes = {
                @Index(name = "idx_registration_athlete", columnList = "athlete_id"),
                @Index(name = "idx_registration_event", columnList = "event_id"),
                @Index(name = "idx_registration_status", columnList = "status")
        })
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "athlete_id", nullable = false)
    private Athlete athlete;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(length = 20, nullable = false)
    @Builder.Default
    private String status = "pending";

    @Column(nullable = true)
    private Long registeredBy;

    @Column(nullable = false)
    private LocalDateTime registrationTime;

    @Column(nullable = true)
    private LocalDateTime auditTime;

    @Column(nullable = true)
    private Long auditorId;

    @Column(length = 255, nullable = true)
    private String auditRemark;

    @Column(length = 255, nullable = true)
    private String remark;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (registrationTime == null) {
            registrationTime = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
