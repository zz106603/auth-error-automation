import http from "k6/http";
import { check } from "k6";
import { Counter, Rate } from "k6/metrics";
import crypto from "k6/crypto";

const CHECK_FAIL = new Rate("check_fail_rate");
const DOMAIN_MIX_TOTAL = new Counter("domain_mix_auth_error_total");
const RUN_ID = __ENV.TEST_ID || `DM-001-${Date.now()}`;

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

const TARGET_RPS = parsePositiveNumber(__ENV.TARGET_RPS, 5, "TARGET_RPS");
const DEMO_DURATION = __ENV.DEMO_DURATION || "5m";

export const options = {
  scenarios: {
    domain_mix: {
      executor: "constant-arrival-rate",
      rate: TARGET_RPS,
      timeUnit: "1s",
      duration: DEMO_DURATION,
      preAllocatedVUs: 30,
      maxVUs: 120,
      tags: { lt: "DM-001", phase: "domain-mix" },
    },
  },
};

const MIX = [
  {
    upTo: 60,
    errorType: "INVALID_CREDENTIALS",
    provider: "INTERNAL_AUTH",
    clientType: "WEB",
    endpoint: "/api/login",
    httpStatus: 401,
    exceptionClass: "org.springframework.security.authentication.BadCredentialsException",
    rootCauseClass: "com.example.auth.InvalidCredentialsException",
    message: "Invalid credentials",
  },
  {
    upTo: 75,
    errorType: "TOKEN_EXPIRED",
    provider: "INTERNAL_AUTH",
    clientType: "WEB",
    endpoint: "/api/token/refresh",
    httpStatus: 401,
    exceptionClass: "io.jsonwebtoken.ExpiredJwtException",
    rootCauseClass: "io.jsonwebtoken.ExpiredJwtException",
    message: "JWT expired",
  },
  {
    upTo: 85,
    errorType: "TOKEN_INVALID_SIGNATURE",
    provider: "INTERNAL_AUTH",
    clientType: "API",
    endpoint: "/api/token/validate",
    httpStatus: 401,
    exceptionClass: "io.jsonwebtoken.security.SignatureException",
    rootCauseClass: "io.jsonwebtoken.security.SignatureException",
    message: "JWT signature validation failed",
  },
  {
    upTo: 90,
    errorType: "ACCOUNT_LOCKED",
    provider: "INTERNAL_AUTH",
    clientType: "WEB",
    endpoint: "/api/login",
    httpStatus: 423,
    exceptionClass: "org.springframework.security.authentication.LockedException",
    rootCauseClass: "com.example.auth.AccountLockedException",
    message: "Account is locked",
  },
  {
    upTo: 95,
    errorType: "AUTH_PROVIDER_TIMEOUT",
    provider: "OAUTH_PROVIDER",
    clientType: "MOBILE",
    endpoint: "/api/mobile/login",
    httpStatus: 504,
    exceptionClass: "java.net.SocketTimeoutException",
    rootCauseClass: "java.net.SocketTimeoutException",
    message: "Auth provider timeout",
  },
  {
    upTo: 100,
    errorType: "UNKNOWN_AUTH_ERROR",
    provider: "GATEWAY",
    clientType: "API",
    endpoint: "/api/auth/callback",
    httpStatus: 500,
    exceptionClass: "java.lang.IllegalStateException",
    rootCauseClass: "java.lang.IllegalStateException",
    message: "Unclassified auth error",
  },
];

function pickCase(rand) {
  const bucket = rand % 100;
  for (const item of MIX) {
    if (bucket < item.upTo) return item;
  }
  return MIX[MIX.length - 1];
}

function sha256Hex(value) {
  return crypto.sha256(value, "hex");
}

export default function () {
  const baseUrl = __ENV.BASE_URL;
  const token = __ENV.AUTH_TOKEN;
  const occurredAt = nowKstIsoOffset();
  const rand = Math.floor(Math.random() * 900000) + 100000;
  const item = pickCase(rand);

  const requestId = `REQ-${RUN_ID}-${__VU}-${__ITER}-${rand}`;
  const principalHash = sha256Hex(`principal:${item.errorType}:${__VU % 30}`);
  const ipHash = sha256Hex(`ip:${item.provider}:${rand % 20}`);
  const requestUri = `${item.endpoint}?demoRequest=${rand}`;

  const payload = JSON.stringify({
    requestId,
    occurredAt,
    httpStatus: item.httpStatus,
    exceptionClass: item.exceptionClass,
    stacktrace: `at ${item.exceptionClass}.domainMix(DomainMixDemo.java:42)\nat com.example.auth.DemoAuthService.handle(DemoAuthService.java:77)`,

    errorType: item.errorType,
    provider: item.provider,
    clientType: item.clientType,
    endpoint: item.endpoint,
    principalHash,
    ipHash,
    userAgentFamily: item.clientType === "MOBILE" ? "ANDROID" : "CHROME",

    httpMethod: "POST",
    requestUri,
    clientIp: "203.0.113.10",
    userAgent: "k6-domain-mix",
    userId: `raw-user-${__VU % 30}`,
    sessionId: `S-${RUN_ID}-${__VU}-${__ITER}`,
    exceptionMessage: item.message,
    rootCauseClass: item.rootCauseClass,
    rootCauseMessage: item.message,
  });

  const headers = {
    "Content-Type": "application/json",
    "X-App-Name": __ENV.APP_NAME || "auth-error-automation",
    "X-Env": __ENV.ENV || "local",
    "X-LT-Name": "DM-001",
    "X-LT-Stage": `domain-mix-${TARGET_RPS}`,
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
      lt: "DM-001",
      phase: "domain-mix",
      target_rps: String(TARGET_RPS),
      error_type: item.errorType,
      provider: item.provider,
      client_type: item.clientType,
      app: __ENV.APP_NAME || "auth-error-automation",
      env: __ENV.ENV || "local",
    },
  });

  DOMAIN_MIX_TOTAL.add(1, {
    error_type: item.errorType,
    provider: item.provider,
    client_type: item.clientType,
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
  lines.push("# DM-001 Domain Mix Summary");
  lines.push(`test_id=${__ENV.TEST_ID || "DM-001"}`);
  lines.push(`generated_at=${new Date(endMs).toISOString()}`);
  lines.push(`duration_ms=${runMs}`);
  lines.push("");
  lines.push("[domain_mix]");
  lines.push(`[WINDOW] target_rps=${TARGET_RPS} duration=${DEMO_DURATION} timestamp=${new Date(startMs).toISOString()}`);
  lines.push("distribution=INVALID_CREDENTIALS:60,TOKEN_EXPIRED:15,TOKEN_INVALID_SIGNATURE:10,ACCOUNT_LOCKED:5,AUTH_PROVIDER_TIMEOUT:5,UNKNOWN_AUTH_ERROR:5");
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
  const testId = __ENV.TEST_ID || `DM-001-${new Date().toISOString().replace(/[:.]/g, "-")}`;
  const fileName = `dm_001_domain_mix-${testId}.log`;

  return {
    stdout: summaryText,
    [`${resultsDir}/${fileName}`]: summaryText,
  };
}
