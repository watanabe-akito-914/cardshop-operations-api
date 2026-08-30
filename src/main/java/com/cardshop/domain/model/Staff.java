package com.cardshop.domain.model;

import java.time.LocalDateTime;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "staffs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Staff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // スタッフID

    @Column(name = "login_code_hash", nullable = false, unique = true)
    private String loginCodeHash; // ログイン用のバーコードハッシュ

    @Column(name = "name", nullable = false, length = 50)
    private String name; // スタッフ名

    @Column(name = "role", nullable = false, length = 20)
    private String role; // ADMIN, STAFF, NEWBIE

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}