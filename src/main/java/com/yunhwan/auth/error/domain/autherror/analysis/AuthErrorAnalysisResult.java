package com.yunhwan.auth.error.domain.autherror.analysis;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "auth_error_analysis_result",
        indexes = {
                @Index(name = "ix_auth_error_analysis_result_auth_error_id", columnList = "auth_error_id"),
                @Index(name = "ix_auth_error_analysis_result_created_at", columnList = "created_at")
        }
)
public class AuthErrorAnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auth_error_id", nullable = false)
    private Long authErrorId;

    @Column(name = "analysis_version", nullable = false, length = 30)
    private String analysisVersion;

    @Column(name = "model", nullable = false, length = 100)
    private String model;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "severity", length = 10)
    private String severity;

    @Column(name = "summary")
    private String summary;

    @Column(name = "suggested_action")
    private String suggestedAction;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
