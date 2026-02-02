package com.yunhwan.auth.error.usecase.consumer.handler;

import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.infra.messaging.consumer.parser.JacksonAuthErrorAnalysisRequestedPayloadParser;
import com.yunhwan.auth.error.usecase.autherror.analysis.AuthErrorAnalysisService;
import com.yunhwan.auth.error.usecase.autherror.cluster.AuthErrorClusterLinker;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorAnalysisRequestedPayload;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;

@Slf4j
@Component("authErrorAnalysisRequestedHandler")
@RequiredArgsConstructor
public class AuthErrorAnalysisRequestedHandlerImpl implements AuthErrorHandler{

    private final AuthErrorStore authErrorStore;
    private final AuthErrorAnalysisService analysisService;
    private final AuthErrorClusterLinker clusterLinker;
    private final JacksonAuthErrorAnalysisRequestedPayloadParser parser;

    @Override
    @Transactional
    public void handle(String payload, Map<String, Object> headers) {

        Long outboxId = requireLong(headers, "outboxId");
        requireString(headers, "eventType");
        requireString(headers, "aggregateType");

        AuthErrorAnalysisRequestedPayload parsed = parser.parse(payload, outboxId);

        AuthError authError = authErrorStore.findById(parsed.authErrorId())
                .orElseThrow(() ->
                        new NonRetryableAuthErrorException(
                                "authError not found id=" + parsed.authErrorId()));

        //  PROCESSED, FAILED, RESOLVED, IGNORED
        if (authError.getStatus().isTerminal()) {
            return;
        }

        // 1) 분석 수행 + 결과 저장 (여기까지가 analysis 단계의 책임)
        analysisService.analyzeAndSave(authError.getId());

        // 2) Step2: stack_hash 기반 cluster upsert + link
        clusterLinker.link(authError.getId(), authError.getStackHash());

        // 3) 처리 완료(PROCESSED)로 확정하지 말고, "분석 완료"로만 둔다
        authError.markAnalysisCompleted();

        log.info("[AuthErrorHandler] analysis completed. authErrorId={}, outboxId={}",
                authError.getId(), outboxId);
    }

    private Long requireLong(Map<String, Object> headers, String key) {
        Object v = headers.get(key);
        if (v == null) {
            throw new NonRetryableAuthErrorException("missing header: " + key);
        }
        try {
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            throw new NonRetryableAuthErrorException("invalid header(long): " + key + "=" + v, e);
        }
    }

    private String requireString(Map<String, Object> headers, String key) {
        Object v = headers.get(key);
        if (v == null) {
            throw new NonRetryableAuthErrorException("missing header: " + key);
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            throw new NonRetryableAuthErrorException("blank header: " + key);
        }
        return s;
    }
}
