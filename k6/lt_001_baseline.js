import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

/**
 * LT-001 Baseline
 * DTO Validation(@NotBlank/@NotNull) 통과를 최우선으로 payload를 "계약 그대로" 구성
 */

const E2E = new Trend("e2e_latency_ms", true); // EN: client-side proxy / KR: 보조 관측
const CHECK_FAIL = new Rate("check_fail_rate");

export const options = {
  scenarios: {
    baseline: {
      executor: "constant-arrival-rate",
      rate: 5, // KR: 1~5 RPS 중 baseline은 5로 시작(필요 시 1로 낮춰도 됨)
      timeUnit: "1s",
      duration: "3m",
      preAllocatedVUs: 20,
      maxVUs: 50,
      tags: { lt: "LT-001", phase: "baseline" },
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.002"],      // KR: 임시 가드레일(베이스라인 확보 후 조정)
    http_req_duration: ["p(95)<500"],     // KR: 임시 가드레일
    check_fail_rate: ["rate<0.01"],
  },
};

/**
 * OffsetDateTime 파싱 안전하게 하려고 항상 +09:00 형태로 보냄
 */
function nowKstIsoOffset() {
  const d = new Date(Date.now() + 9 * 60 * 60 * 1000);
  return d.toISOString().replace("Z", "+09:00");
}

export default function () {
  const baseUrl = __ENV.BASE_URL; // e.g. http://host.docker.internal:8080
  const token = __ENV.AUTH_TOKEN; // optional

  const occurredAt = nowKstIsoOffset();

  // AuthErrorRecordRequest DTO 필수 필드 5개는 반드시 포함해야 함. :contentReference[oaicite:2]{index=2}
  const payload = JSON.stringify({
    // required
    requestId: `REQ-${__VU}-${__ITER}`,
    occurredAt: occurredAt, // OffsetDateTime
    httpStatus: 401,
    exceptionClass: "org.springframework.security.authentication.BadCredentialsException",
    stacktrace: "stacktrace-sample",

    // optional context (있으면 좋음)
    httpMethod: "GET",
    requestUri: "/api/v1/me",
    clientIp: "203.0.113.10",
    userAgent: "k6",
    userId: "test-user",
    sessionId: `S-${__VU}`,

    // optional exception detail
    exceptionMessage: "Unauthorized",
    rootCauseClass: "io.jsonwebtoken.ExpiredJwtException",
    rootCauseMessage: "JWT expired",
  });

  const headers = {
    "Content-Type": "application/json",
    "X-App-Name": __ENV.APP_NAME || "auth-error-automation",
    "X-Env": __ENV.ENV || "local",
  };

  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  // AuthErrorController: @RequestMapping("/api/auth-errors") + @PostMapping
  const url = `${baseUrl}/api/auth-errors`;

  const res = http.post(url, payload, {
    headers,
    tags: { api: "auth-errors", lt: "LT-001" },
  });

  const ok = check(res, {
    "status is 2xx": (r) => r.status >= 200 && r.status < 300,
  });

  CHECK_FAIL.add(!ok);

  // 실패 원인 추적을 위해 처음 몇 건만 status/body 출력 (너무 많이 찍히면 로그 폭발)
  if (!ok && __ITER < 10) {
    console.error(`[k6] FAIL status=${res.status} url=${url} body=${res.body}`);
  }

  E2E.add(Date.now() - Date.parse(occurredAt));
  sleep(0.1);
}
