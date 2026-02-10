package com.yunhwan.auth.error.app.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Tag("policy")
@Tag("non-scenario")
@DisplayName("[Policy] AuthError payload size and sanitization rules")
class AuthErrorFacadePayloadPolicyTest extends AbstractStubIntegrationTest {

    private static final int MAX_STACKTRACE_LEN = 8_000;
    private static final int MAX_MESSAGE_LEN = 1_000;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("긴 stacktrace/message 저장 시 정책에 따라 잘려서 DB에 저장된다 (stack<=8000, message<=1000)")
    void 긴_payload_저장_시_정책에_따라_잘린다() {
        // Given
        String requestId = "REQ-POLICY-" + UUID.randomUUID();
        String exceptionClass = "io.jsonwebtoken.ExpiredJwtException";
        String longMessage = repeat("m", 2_000);
        String longStacktrace = repeat(stackLine("com.example.Demo"), 2_000);

        // When
        invokeRecordLikeFlow(requestId, exceptionClass, longMessage, longStacktrace);

        // Then
        int savedMsgLen = queryLengthByCandidates(
                "auth_error",
                "message",
                "error_message",
                "exception_message",
                requestId
        );

        int savedStackLen = queryLengthByCandidates(
                "auth_error",
                "stacktrace",
                "stack_trace",
                "stack_trace_text",
                requestId
        );

        assertThat(savedMsgLen)
                .withFailMessage("message length must be <= %d but was %d", MAX_MESSAGE_LEN, savedMsgLen)
                .isLessThanOrEqualTo(MAX_MESSAGE_LEN);

        assertThat(savedStackLen)
                .withFailMessage("stacktrace length must be <= %d but was %d", MAX_STACKTRACE_LEN, savedStackLen)
                .isLessThanOrEqualTo(MAX_STACKTRACE_LEN);
    }

    /**
     * facade/writer/service의 record 계열 메서드를 reflection으로 호출.
     */
    private void invokeRecordLikeFlow(String requestId, String exceptionClass, String message, String stacktrace) {
        Object recorder = findFirstBeanBySimpleClassName(
                // 도메인/유스케이스 쪽이 먼저 잡히도록 우선순위
                "AuthErrorFacade",
                "AuthErrorWriter",
                "AuthErrorRecordFacade",
                "AuthErrorRecordService",
                "AuthErrorFacadeImpl",
                // controller가 있으면 마지막에
                "AuthErrorController"
        );

        Method recordMethod = findBestRecordLikeMethod(recorder.getClass(),
                "record",
                "recordAuthError",
                "save",
                "register"
        );

        Class<?> paramType = recordMethod.getParameterTypes()[0];

        Object cmdOrReq = instantiateByObjectMapper(paramType, requestId, exceptionClass, message, stacktrace);

        try {
            recordMethod.setAccessible(true);
            recordMethod.invoke(recorder, cmdOrReq);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to invoke record flow. recorder="
                    + recorder.getClass().getName() + ", paramType=" + paramType.getName()
                    + ", method=" + recordMethod.getName(), e);
        }
    }

    /**
     * Controller의 *Request DTO* 파라미터 메서드는 후순위로 밀고, 가능하면 usecase command 계열을 먼저 선택한다.
     */
    private Method findBestRecordLikeMethod(Class<?> type, String... methodNames) {
        List<Method> candidates = new ArrayList<>();

        // public methods
        for (Method m : type.getMethods()) {
            if (isNameMatch(m, methodNames) && m.getParameterCount() == 1) {
                candidates.add(m);
            }
        }
        // declared methods
        for (Method m : type.getDeclaredMethods()) {
            if (isNameMatch(m, methodNames) && m.getParameterCount() == 1) {
                candidates.add(m);
            }
        }

        if (candidates.isEmpty()) {
            fail("No suitable record-like method found in " + type.getName()
                    + ", tried=" + Arrays.toString(methodNames));
        }

        // 1) Request DTO 아닌 것 우선
        for (Method m : candidates) {
            if (!m.getParameterTypes()[0].getSimpleName().endsWith("Request")) {
                return m;
            }
        }
        // 2) 없으면 그냥 첫 번째
        return candidates.get(0);
    }

    private boolean isNameMatch(Method m, String... names) {
        for (String n : names) {
            if (m.getName().equals(n)) return true;
        }
        return false;
    }

    /**
     * 핵심: NoArgsConstructor가 없어도, Lombok @Builder-only / Java record 여도 생성 가능.
     * - Jackson은 record canonical ctor도 이름 기반으로 호출 가능
     */
    private Object instantiateByObjectMapper(Class<?> type,
                                             String requestId,
                                             String exceptionClass,
                                             String message,
                                             String stacktrace) {
        Map<String, Object> payload = new HashMap<>();

        // --- existing fields (생략 없이 유지) ---
        payload.put("requestId", requestId);
        payload.put("request_id", requestId);
        payload.put("reqId", requestId);
        payload.put("req_id", requestId);

        payload.put("exceptionClass", exceptionClass);
        payload.put("exception_class", exceptionClass);
        payload.put("exceptionName", exceptionClass);
        payload.put("exception_name", exceptionClass);

        payload.put("message", message);
        payload.put("errorMessage", message);
        payload.put("error_message", message);
        payload.put("exceptionMessage", message);
        payload.put("exception_message", message);

        payload.put("stacktrace", stacktrace);
        payload.put("stackTrace", stacktrace);
        payload.put("stack_trace", stacktrace);
        payload.put("stackTraceText", stacktrace);
        payload.put("stack_trace_text", stacktrace);

        // --- 필수 NOT NULL 컬럼 대응 ---
        OffsetDateTime now = OffsetDateTime.now();

        // occurred_at (NOT NULL)
        payload.put("occurredAt", now);
        payload.put("occurred_at", now);

        // received_at (혹시 NOT NULL이면 같이)
        payload.put("receivedAt", now);
        payload.put("received_at", now);

        // (선택) 환경/소스 기본값이 비어있어서 다른 NOT NULL이 터지면 여기도 추가
        // payload.put("sourceService", "auth-error-automation");
        // payload.put("environment", "local");

        try {
            return objectMapper.convertValue(payload, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot instantiate param object by ObjectMapper. type=" + type.getName(), e);
        }
    }

    private Object findFirstBeanBySimpleClassName(String... simpleNames) {
        Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);
        for (String simple : simpleNames) {
            for (Object bean : allBeans.values()) {
                if (bean.getClass().getSimpleName().equals(simple)) {
                    return bean;
                }
            }
        }
        fail("No recorder bean found. Tried simpleNames=" + Arrays.toString(simpleNames));
        return null;
    }

    private int queryLengthByCandidates(String table, String... columnCandidatesAndRequestId) {
        String requestId = columnCandidatesAndRequestId[columnCandidatesAndRequestId.length - 1];
        String[] columns = Arrays.copyOf(columnCandidatesAndRequestId, columnCandidatesAndRequestId.length - 1);

        String[] requestIdCols = {"request_id", "requestId", "req_id", "reqId"};

        for (String col : columns) {
            for (String reqCol : requestIdCols) {
                try {
                    Integer len = jdbcTemplate.queryForObject(
                            "select length(" + col + ") from " + table + " where " + reqCol + " = ?",
                            Integer.class,
                            requestId
                    );
                    if (len != null) {
                        return len;
                    }
                } catch (BadSqlGrammarException ignored) {
                    // continue
                }
            }
        }

        throw new IllegalStateException("Failed to query length. table=" + table
                + ", triedColumns=" + Arrays.toString(columns)
                + ", requestId=" + requestId);
    }

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static String stackLine(String cls) {
        return "at " + cls + ".method(Demo.java:123)\n";
    }
}
