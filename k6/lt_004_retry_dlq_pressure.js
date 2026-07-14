import http from "k6/http";
import { check } from "k6";
import { Rate } from "k6/metrics";

const CHECK_FAIL = new Rate("check_fail_rate");
const RUN_ID = __ENV.TEST_ID || `LT-004C-${Date.now()}`;

function formatMetric(values, key) {
  if (!values || values[key] == null) return "n/a";
  return String(values[key]);
}

function nowKstIsoOffset() {
  const d = new Date(Date.now() + 9 * 60 * 60 * 1000);
  return d.toISOString().replace("Z", "+09:00");
}

function parsePositiveNumber(value, fallback, name) {
  const parsed = Number(value || fallback);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive number. value=${value}`);
  }
  return parsed;
}

function parsePercent(value, fallback, name) {
  const parsed = Number(value || fallback);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 100) {
    throw new Error(`${name} must be a number from 0 to 100. value=${value}`);
  }
  return parsed;
}

const TARGET_RPS = parsePositiveNumber(__ENV.TARGET_RPS, 20, "TARGET_RPS");
const STEADY_DURATION = __ENV.STEADY_DURATION || "6m";
const FAILURE_MODE = __ENV.FAILURE_MODE || "retry-once";
const EXPECTED_FAILURE_PERCENT = parsePercent(__ENV.EXPECTED_FAILURE_PERCENT, 20, "EXPECTED_FAILURE_PERCENT");
const EXPECTED_FAIL_UNTIL_RETRY_COUNT = parsePositiveNumber(
  __ENV.EXPECTED_FAIL_UNTIL_RETRY_COUNT,
  1,
  "EXPECTED_FAIL_UNTIL_RETRY_COUNT"
);

export const options = {
  scenarios: {
    retry_dlq_pressure: {
      executor: "constant-arrival-rate",
      rate: TARGET_RPS,
      timeUnit: "1s",
      duration: STEADY_DURATION,
      preAllocatedVUs: 120,
      maxVUs: 600,
      tags: { lt: "LT-004C", phase: "retry-dlq-pressure", failure_mode: FAILURE_MODE },
    },
  },
};

export default function () {
  const baseUrl = __ENV.BASE_URL;
  const token = __ENV.AUTH_TOKEN;

  const occurredAt = nowKstIsoOffset();
  const rand = Math.floor(Math.random() * 900000) + 100000;

  const payload = JSON.stringify({
    requestId: `REQ-${RUN_ID}-${__VU}-${__ITER}-${rand}`,
    occurredAt,
    httpStatus: 401,
    exceptionClass: "org.springframework.security.authentication.BadCredentialsException",
    stacktrace: "stacktrace-sample",
    httpMethod: "GET",
    requestUri: "/api/v1/me",
    clientIp: "203.0.113.10",
    userAgent: "k6",
    userId: `retry-pressure-user-${__VU}-${__ITER}-${rand}`,
    sessionId: `S-${__VU}-${__ITER}-${rand}`,
    exceptionMessage: "Unauthorized",
    rootCauseClass: "io.jsonwebtoken.ExpiredJwtException",
    rootCauseMessage: "JWT expired",
  });

  const headers = {
    "Content-Type": "application/json",
    "X-App-Name": __ENV.APP_NAME || "auth-error-automation",
    "X-Env": __ENV.ENV || "local",
    "X-LT-Name": "LT-004C",
    "X-LT-Stage": `retry-dlq-pressure-${TARGET_RPS}`,
    "X-LT-Target-RPS": String(TARGET_RPS),
    "X-LT-Failure-Mode": FAILURE_MODE,
    "X-LT-Expected-Failure-Percent": String(EXPECTED_FAILURE_PERCENT),
    "X-LT-Expected-Fail-Until-Retry-Count": String(EXPECTED_FAIL_UNTIL_RETRY_COUNT),
  };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const url = `${baseUrl}/api/auth-errors`;
  const res = http.post(url, payload, {
    headers,
    tags: {
      api: "auth-errors",
      lt: "LT-004C",
      phase: "retry-dlq-pressure",
      target_rps: String(TARGET_RPS),
      failure_mode: FAILURE_MODE,
      expected_failure_percent: String(EXPECTED_FAILURE_PERCENT),
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
  lines.push("# LT-004C Retry / DLQ Pressure Summary");
  lines.push(`test_id=${__ENV.TEST_ID || "LT-004C"}`);
  lines.push(`generated_at=${new Date(endMs).toISOString()}`);
  lines.push(`duration_ms=${runMs}`);
  lines.push("");
  lines.push("[retry_dlq_pressure]");
  lines.push(
    `[WINDOW] target_rps=${TARGET_RPS} duration=${STEADY_DURATION} failure_mode=${FAILURE_MODE} expected_failure_percent=${EXPECTED_FAILURE_PERCENT} expected_fail_until_retry_count=${EXPECTED_FAIL_UNTIL_RETRY_COUNT} timestamp=${new Date(startMs).toISOString()}`
  );
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
  const testId = __ENV.TEST_ID || `LT-004C-${new Date().toISOString().replace(/[:.]/g, "-")}`;
  const fileName = `lt_004_retry_dlq_pressure-${testId}.log`;

  return {
    stdout: summaryText,
    [`${resultsDir}/${fileName}`]: summaryText,
  };
}
