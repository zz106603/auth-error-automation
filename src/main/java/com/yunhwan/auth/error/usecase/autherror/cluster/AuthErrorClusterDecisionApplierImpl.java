package com.yunhwan.auth.error.usecase.autherror.cluster;

import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionCommand;
import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.domain.autherror.cluster.*;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorDecisionApplier;
import com.yunhwan.auth.error.usecase.autherror.dto.ApplyClusterDecisionCommand;
import com.yunhwan.auth.error.usecase.autherror.dto.ApplyClusterDecisionResult;
import com.yunhwan.auth.error.usecase.autherror.port.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthErrorClusterDecisionApplierImpl implements AuthErrorClusterDecisionApplier {

    private final AuthErrorClusterStore clusterStore;
    private final AuthErrorClusterItemStore clusterItemStore;

    private final AuthErrorClusterDecisionStore decisionStore;
    private final AuthErrorClusterDecisionApplyStore applyStore;

    private final AuthErrorDecisionApplier authErrorDecisionApplier;

    @Override
    @Transactional
    public ApplyClusterDecisionResult apply(ApplyClusterDecisionCommand cmd) {
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) {
            throw new NonRetryableAuthErrorException("idempotencyKey is required for cluster decision");
        }

        // 1) idem이면 기존 결과 반환
        var existing = decisionStore.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            AuthErrorClusterDecision d = existing.get();
            return new ApplyClusterDecisionResult(d.getClusterId(), d.getTotalTargets(), d.getAppliedCount(), d.getSkippedCount(), d.getFailedCount());
        }

        // 2) decision history 생성
        AuthErrorClusterDecision decision = decisionStore.save(
                AuthErrorClusterDecision.open(cmd.clusterId(), cmd.idempotencyKey(), cmd.decisionType(), normalize(cmd.note()), cmd.decidedBy())
        );

        // 3) 대상 조회
        List<Long> authErrorIds = clusterItemStore.findAuthErrorIdsByClusterId(cmd.clusterId());
        int total = authErrorIds.size();

        int applied = 0;
        int skipped = 0;
        int failed = 0;

        // 4) apply_log 기반으로 멱등 fan-out
        for (Long authErrorId : authErrorIds) {
            if (applyStore.exists(decision.getId(), authErrorId)) {
                // 이미 이 decision으로 적용됨 -> 재호출 시 스킵
                continue;
            }

            try {
                authErrorDecisionApplier.apply(new ApplyAnalysisDecisionCommand(
                        authErrorId, cmd.decisionType(), cmd.note(), cmd.decidedBy()
                ));
                applyStore.save(AuthErrorClusterDecisionApply.of(decision.getId(), authErrorId, ClusterApplyOutcome.APPLIED, null));
                applied++;
            } catch (NonRetryableAuthErrorException e) {
                // 예: ANALYSIS_COMPLETED가 아님 -> 이 decision 기준으로는 적용 불가
                applyStore.save(AuthErrorClusterDecisionApply.of(decision.getId(), authErrorId, ClusterApplyOutcome.SKIPPED, safe(e.getMessage())));
                skipped++;
            } catch (Exception e) {
                applyStore.save(AuthErrorClusterDecisionApply.of(decision.getId(), authErrorId, ClusterApplyOutcome.FAILED, safe(e.getClass().getSimpleName() + ": " + e.getMessage())));
                failed++;
                log.error("[ClusterDecision] failed. decisionId={}, clusterId={}, authErrorId={}", decision.getId(), cmd.clusterId(), authErrorId, e);
            }
        }

        // 5) decision 집계 업데이트
        decision.recordResult(total, applied, skipped, failed);
        decisionStore.save(decision);

        // 6) cluster.status 업데이트 (운영 뷰 상태)
        clusterStore.findById(cmd.clusterId()).ifPresent(cluster -> {
            switch (cmd.decisionType()) {
                case IGNORE -> cluster.changeStatus(AuthErrorClusterStatus.MUTED);
                case RESOLVE -> cluster.changeStatus(AuthErrorClusterStatus.RESOLVED);
                default -> {
                    // PROCESS / RETRY / FAIL 은 OPEN 유지 (원하면 정책 추가)
                }
            }
            clusterStore.save(cluster);
        });

        return new ApplyClusterDecisionResult(cmd.clusterId(), total, applied, skipped, failed);
    }

    private static String normalize(String s) {
        return (s == null) ? "" : s.trim();
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
