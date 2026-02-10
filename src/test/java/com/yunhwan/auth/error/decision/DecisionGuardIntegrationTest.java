package com.yunhwan.auth.error.decision;

import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionCommand;
import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorDecisionApplier;
import com.yunhwan.auth.error.usecase.autherror.dto.DecisionActor;
import com.yunhwan.auth.error.usecase.autherror.dto.DecisionType;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"test", "ops"})
@TestPropertySource(properties = "auth-error.ops.decision.enabled=true")
@DisplayName("[TS-06] Decision Guard 통합 테스트")
class DecisionGuardIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AuthErrorDecisionApplier decisionApplier;

    @Autowired
    AuthErrorStore authErrorStore;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("[TS-06] ANALYSIS_COMPLETED가 아니면 decision 적용은 거부되고 부작용이 없다")
    void analysis_completed_아닌_상태에서는_decision_거부_및_무부작용() {
        AuthError authError = createAuthError(AuthErrorStatus.ANALYSIS_REQUESTED);

        long beforeOutbox = countOutboxByAggregateId(authError.getId());

        ApplyAnalysisDecisionCommand cmd = new ApplyAnalysisDecisionCommand(
                authError.getId(),
                DecisionType.PROCESS,
                "test",
                DecisionActor.HUMAN
        );

        assertThatThrownBy(() -> decisionApplier.apply(cmd))
                .isInstanceOf(NonRetryableAuthErrorException.class);

        AuthError reloaded = authErrorStore.findById(authError.getId()).orElseThrow();
        long afterOutbox = countOutboxByAggregateId(authError.getId());

        assertThat(reloaded.getStatus())
                .withFailMessage("ANALYSIS_COMPLETED가 아니면 상태가 변경되면 안 됩니다.")
                .isEqualTo(AuthErrorStatus.ANALYSIS_REQUESTED);
        assertThat(afterOutbox)
                .withFailMessage("Decision 거부 시 outbox가 생성되면 안 됩니다.")
                .isEqualTo(beforeOutbox);
    }

    private AuthError createAuthError(AuthErrorStatus status) {
        String requestId = "REQ-TS06-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        AuthError authError = AuthError.record(requestId, now, now, "test", "test");
        if (status == AuthErrorStatus.ANALYSIS_REQUESTED) {
            authError.markAnalysisRequested();
        }
        if (status == AuthErrorStatus.ANALYSIS_COMPLETED) {
            authError.markAnalysisCompleted();
        }
        return authErrorStore.save(authError);
    }

    private long countOutboxByAggregateId(Long authErrorId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_message where aggregate_id = ?",
                Long.class,
                String.valueOf(authErrorId)
        );
        return count == null ? 0L : count;
    }
}
