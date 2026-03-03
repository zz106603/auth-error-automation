import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

/**
 * LT-001 Baseline
 * DTO Validation(@NotBlank/@NotNull) 통과를 최우선으로 payload를 "계약 그대로" 구성
 */

const CLIENT_INGEST = new Trend("client_ingest_latency_ms", true); // 서버 E2E 아님
const CHECK_FAIL = new Rate("check_fail_rate");

export const options = {
  scenarios: {
    baseline: {
      executor: "constant-arrival-rate",
      rate: 5, // 1~5 RPS 중 baseline은 5로 시작(필요 시 1로 낮춰도 됨)
      timeUnit: "1s",
      duration: "3m",
      preAllocatedVUs: 20,
      maxVUs: 50,
      tags: { lt: "LT-001", phase: "baseline" },
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.002"],      // 임시 가드레일(베이스라인 확보 후 조정)
    http_req_duration: ["p(95)<500"],     // 임시 가드레일
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
    tags: {
      api: "auth-errors",
      lt: "LT-001",
      app: __ENV.APP_NAME || "auth-error-automation",
      env: __ENV.ENV || "local",
    },
  });

  const ok = check(res, {
    "status is 2xx": (r) => r.status >= 200 && r.status < 300,
  });

  CHECK_FAIL.add(!ok);

  // 실패 원인 추적을 위해 처음 몇 건만 status/body 출력 (너무 많이 찍히면 로그 폭발)
  if (!ok && __ITER < 10) {
    console.error(`[k6] FAIL status=${res.status} url=${url} body=${res.body}`);
  }

  // 참고용 지표(체크리스트 E2E와 무관)
  CLIENT_INGEST.add(Date.now() - Date.parse(occurredAt));
  sleep(0.1);
}

function formatMetric(values, key) {
  if (!values || values[key] == null) return "n/a";
  return String(values[key]);
}

function buildSummary(data) {
  const endMs = Date.now();
  const metrics = (data && data.metrics) || {};
  const checkFail = metrics.check_fail_rate && metrics.check_fail_rate.values;
  const httpReqFailed = metrics.http_req_failed && metrics.http_req_failed.values;
  const httpReqDuration = metrics.http_req_duration && metrics.http_req_duration.values;
  const httpReqs = metrics.http_reqs && metrics.http_reqs.values;
  const iterations = metrics.iterations && metrics.iterations.values;
  const clientIngest = metrics.client_ingest_latency_ms && metrics.client_ingest_latency_ms.values;
  const lines = [];

  lines.push("# LT-001 Baseline Summary");
  lines.push(`test_id=${__ENV.TEST_ID || "LT-001"}`);
  lines.push(`generated_at=${new Date(endMs).toISOString()}`);
  lines.push("");
  lines.push("[summary]");
  lines.push(`iterations=${formatMetric(iterations, "count")}`);
  lines.push(`http_reqs=${formatMetric(httpReqs, "count")}`);
  lines.push(`http_req_duration_avg=${formatMetric(httpReqDuration, "avg")}`);
  lines.push(`http_req_duration_p95=${formatMetric(httpReqDuration, "p(95)")}`);
  lines.push(`http_req_duration_p99=${formatMetric(httpReqDuration, "p(99)")}`);
  lines.push(`http_req_duration_max=${formatMetric(httpReqDuration, "max")}`);
  lines.push(`http_req_failed_rate=${formatMetric(httpReqFailed, "rate")}`);
  lines.push(`check_fail_rate=${formatMetric(checkFail, "rate")}`);
  lines.push(`client_ingest_latency_ms_avg=${formatMetric(clientIngest, "avg")}`);
  lines.push(`client_ingest_latency_ms_p95=${formatMetric(clientIngest, "p(95)")}`);
  lines.push(`client_ingest_latency_ms_p99=${formatMetric(clientIngest, "p(99)")}`);
  lines.push(`client_ingest_latency_ms_max=${formatMetric(clientIngest, "max")}`);
  return `${lines.join("\n")}\n`;
}

export function handleSummary(data) {
  const summaryText = buildSummary(data);
  const resultsDir = __ENV.RESULTS_DIR || "/scripts/results";
  const testId = __ENV.TEST_ID || `LT-001-${new Date().toISOString().replace(/[:.]/g, "-")}`;
  const fileName = `lt_001_baseline-${testId}.log`;

  return {
    stdout: summaryText,
    [`${resultsDir}/${fileName}`]: summaryText,
  };
}
