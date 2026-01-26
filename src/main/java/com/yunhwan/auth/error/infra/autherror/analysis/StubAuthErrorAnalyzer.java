package com.yunhwan.auth.error.infra.autherror.analysis;

import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorAnalyzer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class StubAuthErrorAnalyzer implements AuthErrorAnalyzer {

    @Override
    public AnalysisResult analyze(AnalysisInput in) {
        String ex = safe(in.exceptionClass());
        Integer status = in.httpStatus();

        String category = "UNKNOWN";
        String severity = "MEDIUM";
        String summary = "인증 오류가 감지되었습니다.";
        String action = "로그/토큰/권한 설정을 점검하세요.";
        BigDecimal conf = new BigDecimal("0.600");

        if (status != null) {
            if (status == 401) {
                category = "UNAUTHORIZED";
                severity = "LOW";
                summary = "401 인증 실패(토큰/자격 증명 문제)로 요청이 거절되었습니다.";
                action = "토큰 만료/발급/전달(Authorization) 여부를 확인하세요.";
                conf = new BigDecimal("0.850");
            } else if (status == 403) {
                category = "FORBIDDEN";
                severity = "MEDIUM";
                summary = "403 권한 부족으로 접근이 거부되었습니다.";
                action = "권한 정책/역할 매핑/리소스 접근 제어를 확인하세요.";
                conf = new BigDecimal("0.850");
            } else if (status >= 500) {
                category = "SERVER_ERROR";
                severity = "HIGH";
                summary = "서버 오류(5xx)로 인증 처리 중 실패가 발생했습니다.";
                action = "인증 모듈/외부 인증 서버 상태/예외 스택을 확인하세요.";
                conf = new BigDecimal("0.800");
            }
        }

        if (ex.contains("ExpiredJwt") || ex.contains("TokenExpired")) {
            category = "TOKEN_EXPIRED";
            severity = "LOW";
            summary = "JWT 토큰 만료로 인증에 실패했습니다.";
            action = "리프레시 토큰/재발급 흐름 및 시간 동기화를 확인하세요.";
            conf = new BigDecimal("0.950");
        } else if (ex.contains("Signature") || ex.contains("InvalidSignature")) {
            category = "INVALID_SIGNATURE";
            severity = "HIGH";
            summary = "토큰 서명 검증에 실패했습니다.";
            action = "서명 키/알고리즘/배포 버전 불일치를 확인하세요.";
            conf = new BigDecimal("0.900");
        }

        return new AnalysisResult(category, severity, summary, action, conf);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
