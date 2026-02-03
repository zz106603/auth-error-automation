package com.yunhwan.auth.error.usecase.autherror;


import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionCommand;
import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionResult;
import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.infra.logging.AuthErrorEventLogger;
import com.yunhwan.auth.error.usecase.autherror.dto.*;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("ops")
@ConditionalOnProperty(name = "auth-error.ops.decision.enabled", havingValue = "true")
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthErrorDecisionApplierImpl implements AuthErrorDecisionApplier {

    private final AuthErrorStore authErrorStore;
    private final AuthErrorEventLogger eventLogger;

    @Override
    @Transactional
    public ApplyAnalysisDecisionResult apply(ApplyAnalysisDecisionCommand cmd) {

        AuthError authError = authErrorStore.findById(cmd.authErrorId())
                .orElseThrow(() -> new NonRetryableAuthErrorException(
                        "auth_error not found. authErrorId=" + cmd.authErrorId()
                ));

        AuthErrorStatus from  = authError.getStatus();
        if (from  == null) {
            throw new NonRetryableAuthErrorException("auth_error status is null. authErrorId=" + cmd.authErrorId());
        }

        // 핵심: 분석 완료 상태에서만 "결정 반영" 허용
        if (from  != AuthErrorStatus.ANALYSIS_COMPLETED) {
            throw new NonRetryableAuthErrorException("decision not allowed. status=" + from + ", authErrorId=" + cmd.authErrorId());
        }

        String note = normalizeNote(cmd);

        switch (cmd.decisionType()) {
            case PROCESS -> authError.markProcessed(noteWithActor("process", note, cmd.decidedBy()));
            case RETRY -> authError.markRetry();
            case IGNORE -> authError.markIgnored(noteWithActor("ignore", note, cmd.decidedBy()));
            case RESOLVE -> authError.resolve(noteWithActor("resolve", note, cmd.decidedBy()));
            case FAIL -> authError.markFailed(noteWithActor("fail", note, cmd.decidedBy()));
            default -> throw new NonRetryableAuthErrorException("unsupported decisionType=" + cmd.decisionType());
        }

        AuthErrorStatus to = authError.getStatus();

        // ELK용 구조 로그
        eventLogger.decisionApplied(
                authError,
                from,
                to,
                cmd.decisionType().name(),
                cmd.decidedBy().name(),
                note
        );

        log.info("[AuthErrorDecision] applied. authErrorId={}, from={}, to={}, actor={}, decisionType={}",
                authError.getId(), from, to, cmd.decidedBy(), cmd.decisionType());

        return new ApplyAnalysisDecisionResult(authError.getId(), to);
    }

    private static String normalizeNote(ApplyAnalysisDecisionCommand cmd) {
        String n = cmd.note();
        return (n == null) ? "" : n.trim();
    }

    private static String noteWithActor(String action, String note, DecisionActor actor) {
        String prefix = "[" + actor + "/" + action + "] ";
        if (note.isBlank()) return prefix.trim();
        return prefix + note;
    }
}
