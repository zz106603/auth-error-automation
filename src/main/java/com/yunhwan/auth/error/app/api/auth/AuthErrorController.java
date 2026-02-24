package com.yunhwan.auth.error.app.api.auth;

import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordRequest;
import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordResponse;
import com.yunhwan.auth.error.app.autherror.AuthErrorFacade;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth-errors")
public class AuthErrorController {

    private final AuthErrorFacade authErrorFacade;
    private final MeterRegistry meterRegistry;

    @PostMapping
    public ResponseEntity<AuthErrorRecordResponse> record(@Valid @RequestBody AuthErrorRecordRequest req) {
        try {
            AuthErrorRecordResponse res = authErrorFacade.record(req);
            // 요청 유입(ingest_rate) 기준선 산출용
            ingestCounter(MetricsConfig.RESULT_SUCCESS).increment();
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            // 5xx/예외 시 ingest 실패 집계
            ingestCounter(MetricsConfig.RESULT_ERROR).increment();
            throw e;
        }
    }

    private Counter ingestCounter(String result) {
        // api+result만 사용 (요청ID 등 금지)
        return Counter.builder(MetricsConfig.METRIC_INGEST)
                .tag(MetricsConfig.TAG_RESULT, result)
                .tag("api", "/api/auth-errors")
                .register(meterRegistry);
    }
}
