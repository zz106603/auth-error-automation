import http from "k6/http";
import { check } from "k6";
import { Rate } from "k6/metrics";

const CHECK_FAIL = new Rate("check_fail_rate");

function formatMetric(values, key) {
  if (!values || values[key] == null) return "n/a";
  return String(values[key]);
}

function nowKstIsoOffset() {
  const d = new Date(Date.now() + 9 * 60 * 60 * 1000);
  return d.toISOString().replace("Z", "+09:00");
}

const TARGET_RPS = Number(__ENV.TARGET_RPS || 85);
const STEADY_DURATION = __ENV.STEADY_DURATION || "15m";

export const options = {
  scenarios: {
    steady: {
      executor: "constant-arrival-rate",
      rate: TARGET_RPS,
      timeUnit: "1s",
      duration: STEADY_DURATION,
      preAllocatedVUs: 120,
      maxVUs: 600,
      tags: { lt: "LT-003", phase: "steady" },
    },
  },
};

export default function () {
  const baseUrl = __ENV.BASE_URL;
  const token = __ENV.AUTH_TOKEN;

  const occurredAt = nowKstIsoOffset();
  const rand = Math.floor(Math.random() * 900000) + 100000;

  const payload = JSON.stringify({
    requestId: `REQ-${__VU}-${__ITER}-${rand}`,
    occurredAt,
    httpStatus: 401,
    exceptionClass: "org.springframework.security.authentication.BadCredentialsException",
    stacktrace: "stacktrace-sample",
    httpMethod: "GET",
    requestUri: "/api/v1/me",
    clientIp: "203.0.113.10",
    userAgent: "k6",
    userId: `steady-user-${__VU}-${__ITER}-${rand}`,
    sessionId: `S-${__VU}-${__ITER}-${rand}`,
    exceptionMessage: "Unauthorized",
    rootCauseClass: "io.jsonwebtoken.ExpiredJwtException",
    rootCauseMessage: "JWT expired",
  });

  const headers = {
    "Content-Type": "application/json",
    "X-App-Name": __ENV.APP_NAME || "auth-error-automation",
    "X-Env": __ENV.ENV || "local",
    "X-LT-Name": "LT-003",
    "X-LT-Stage": `steady-${TARGET_RPS}`,
    "X-LT-Target-RPS": String(TARGET_RPS),
  };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const url = `${baseUrl}/api/auth-errors`;
  const res = http.post(url, payload, {
    headers,
    tags: {
      api: "auth-errors",
      lt: "LT-003",
      phase: "steady",
      target_rps: String(TARGET_RPS),
      app: __ENV.APP_NAME || "auth-error-automation",
      env: __ENV.ENV || "local",
    },
  });

  const ok =
    res &&
    check(res, {
      "status is 2xx": (r) => r.status >= 200 && r.status < 300,
    });

  CHECK_FAIL.add(!ok);

  if (!ok && __ITER < 10) {
    const status = res ? res.status : "no_response";
    const body = res ? res.body : "null_response";
    console.error(`[k6] FAIL status=${status} url=${url} body=${body}`);
  }
}

function buildSummary(data) {
  const runMs =
    (data && data.state && typeof data.state.testRunDurationMs === "number" && data.state.testRunDurationMs) ||
    (data && data.state && typeof data.state.testRunDuration === "number" && data.state.testRunDuration) ||
    0;

  const endMs = Date.now();
  const startMs = endMs - runMs;

  const metrics = (data && data.metrics) || {};
  const checkFail = metrics.check_fail_rate && metrics.check_fail_rate.values;
  const httpReqFailed = metrics.http_req_failed && metrics.http_req_failed.values;
  const httpReqDuration = metrics.http_req_duration && metrics.http_req_duration.values;
  const httpReqs = metrics.http_reqs && metrics.http_reqs.values;
  const iterations = metrics.iterations && metrics.iterations.values;

  const lines = [];
  lines.push("# LT-003 Steady Summary");
  lines.push(`test_id=${__ENV.TEST_ID || "LT-003"}`);
  lines.push(`generated_at=${new Date(endMs).toISOString()}`);
  lines.push(`duration_ms=${runMs}`);
  lines.push("");
  lines.push("[steady]");
  lines.push(`[WINDOW] target_rps=${TARGET_RPS} duration=${STEADY_DURATION} timestamp=${new Date(startMs).toISOString()}`);
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
  return `${lines.join("\n")}\n`;
}

export function handleSummary(data) {
  const summaryText = buildSummary(data);
  const resultsDir = __ENV.RESULTS_DIR || "/scripts/results";
  const testId = __ENV.TEST_ID || `LT-003-${new Date().toISOString().replace(/[:.]/g, "-")}`;
  const fileName = `lt_003_steady-${testId}.log`;

  return {
    stdout: summaryText,
    [`${resultsDir}/${fileName}`]: summaryText,
  };
}
