import http from "k6/http";
import exec from "k6/execution";
import { check } from "k6";
import { Rate } from "k6/metrics";

const CHECK_FAIL = new Rate("check_fail_rate");

const SLICE_PROFILES = {
  default: [
    { target: 60, duration: "120s" },
    { target: 70, duration: "30s" },
    { target: 70, duration: "150s" },
    { target: 75, duration: "30s" },
    { target: 75, duration: "150s" },
    { target: 80, duration: "30s" },
    { target: 80, duration: "150s" },
    { target: 85, duration: "30s" },
    { target: 85, duration: "150s" },
    { target: 90, duration: "30s" },
    { target: 90, duration: "150s" },
    { target: 0, duration: "30s" },
    { target: 0, duration: "180s" },
  ],
  "lower-narrow": [
    { target: 50, duration: "120s" },
    { target: 60, duration: "30s" },
    { target: 60, duration: "150s" },
    { target: 65, duration: "30s" },
    { target: 65, duration: "150s" },
    { target: 70, duration: "30s" },
    { target: 70, duration: "150s" },
    { target: 75, duration: "30s" },
    { target: 75, duration: "150s" },
    { target: 0, duration: "30s" },
    { target: 0, duration: "180s" },
  ],
};

function resolveSliceProfileName() {
  const raw = (__ENV.SLICE_PROFILE || "default").trim();
  return Object.prototype.hasOwnProperty.call(SLICE_PROFILES, raw) ? raw : "default";
}

const SLICE_PROFILE = resolveSliceProfileName();
const KNEE_STAGES = SLICE_PROFILES[SLICE_PROFILE];

let lastLoggedStageIndex = -1;

function randomIntBetweenLocal(min, max) {
  const lo = Math.ceil(min);
  const hi = Math.floor(max);
  return Math.floor(Math.random() * (hi - lo + 1)) + lo;
}

function parseDurationMs(d) {
  if (typeof d !== "string") return 0;
  const m = d.match(/^(\d+)(ms|s|m|h)$/);
  if (!m) return 0;
  const value = Number(m[1]);
  const unit = m[2];
  if (unit === "ms") return value;
  if (unit === "s") return value * 1000;
  if (unit === "m") return value * 60 * 1000;
  if (unit === "h") return value * 60 * 60 * 1000;
  return 0;
}

function parseElapsedMs(value) {
  if (typeof value === "number" && isFinite(value)) return value;
  const s = String(value);
  let total = 0;
  const re = /(\d+)(ms|s|m|h)/g;
  let match = null;
  while ((match = re.exec(s)) !== null) {
    total += parseDurationMs(match[0]);
  }
  return total;
}

const STAGE_BOUNDARIES_MS = (() => {
  let acc = 0;
  return KNEE_STAGES.map((s) => {
    acc += parseDurationMs(s.duration);
    return acc;
  });
})();

const TOTAL_TEST_MS =
  STAGE_BOUNDARIES_MS.length > 0 ? STAGE_BOUNDARIES_MS[STAGE_BOUNDARIES_MS.length - 1] : 0;

function resolveStageIndexByElapsed(elapsedMs) {
  if (typeof elapsedMs !== "number" || !isFinite(elapsedMs) || elapsedMs < 0) return null;
  for (let i = 0; i < STAGE_BOUNDARIES_MS.length; i += 1) {
    if (elapsedMs < STAGE_BOUNDARIES_MS[i]) return i;
  }
  return null;
}

function stageStartEpochs(startEpochMs) {
  let acc = 0;
  return KNEE_STAGES.map((s) => {
    acc += parseDurationMs(s.duration);
    return startEpochMs + acc - parseDurationMs(s.duration);
  });
}

function formatMetric(values, key) {
  if (!values || values[key] == null) return "n/a";
  return String(values[key]);
}

function buildSummary(data) {
  const runMs =
    (data && data.state && typeof data.state.testRunDurationMs === "number" && data.state.testRunDurationMs) ||
    (data && data.state && typeof data.state.testRunDuration === "number" && data.state.testRunDuration) ||
    TOTAL_TEST_MS;
  const endMs = Date.now();
  const startMs = endMs - runMs;
  const epochs = stageStartEpochs(startMs);
  const lines = [];
  const metrics = (data && data.metrics) || {};
  const checkFail = metrics.check_fail_rate && metrics.check_fail_rate.values;
  const httpReqFailed = metrics.http_req_failed && metrics.http_req_failed.values;
  const httpReqDuration = metrics.http_req_duration && metrics.http_req_duration.values;
  const httpReqs = metrics.http_reqs && metrics.http_reqs.values;
  const iterations = metrics.iterations && metrics.iterations.values;

  lines.push("# LT-002E Knee Slice Summary");
  lines.push(`test_id=${__ENV.TEST_ID || "LT-002E"}`);
  lines.push(`slice_profile=${SLICE_PROFILE}`);
  lines.push(`generated_at=${new Date(endMs).toISOString()}`);
  lines.push(`duration_ms=${runMs}`);
  lines.push("");
  lines.push("[slices]");
  for (let i = 0; i < KNEE_STAGES.length; i += 1) {
    const stage = KNEE_STAGES[i];
    lines.push(
      `[SLICE_START] slice_index=${i} target_rps=${stage.target} duration=${stage.duration} timestamp=${new Date(epochs[i]).toISOString()}`
    );
  }
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

function nowKstIsoOffset() {
  const d = new Date(Date.now() + 9 * 60 * 60 * 1000);
  return d.toISOString().replace("Z", "+09:00");
}

function logStageStart(stageIndex, stageConfig) {
  if (__VU !== 1) return;
  if (stageIndex == null || !stageConfig) return;
  if (stageIndex === lastLoggedStageIndex) return;
  lastLoggedStageIndex = stageIndex;
  console.log(
    `[SLICE_START] slice_index=${stageIndex} target_rps=${stageConfig.target} duration=${stageConfig.duration} timestamp=${new Date().toISOString()}`
  );
}

export const options = {
  scenarios: {
    knee_slice: {
      executor: "ramping-arrival-rate",
      startRate: KNEE_STAGES[0].target,
      timeUnit: "1s",
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: KNEE_STAGES,
      tags: { lt: "LT-002E", phase: "knee-slice", slice_profile: SLICE_PROFILE },
    },
  },
};

export default function () {
  const baseUrl = __ENV.BASE_URL;
  const token = __ENV.AUTH_TOKEN;

  const elapsedMs = parseElapsedMs(exec.instance.currentTestRunDuration);
  const stageIndex = resolveStageIndexByElapsed(elapsedMs);
  const stageConfig = stageIndex != null ? KNEE_STAGES[stageIndex] : null;
  const stageTarget = stageConfig && typeof stageConfig.target === "number" ? stageConfig.target : null;
  const stageTag = stageTarget != null ? `rps-${stageTarget}` : "rps-unknown";

  logStageStart(stageIndex, stageConfig);

  const occurredAt = nowKstIsoOffset();
  const rand = randomIntBetweenLocal(100000, 999999);
  const payload = JSON.stringify({
    requestId: `REQ-${__VU}-${__ITER}-${rand}`,
    occurredAt: occurredAt,
    httpStatus: 401,
    exceptionClass: "org.springframework.security.authentication.BadCredentialsException",
    stacktrace: "stacktrace-sample",
    httpMethod: "GET",
    requestUri: "/api/v1/me",
    clientIp: "203.0.113.10",
    userAgent: "k6",
    userId: `test-user-${__VU}-${__ITER}-${rand}`,
    sessionId: `S-${__VU}-${__ITER}-${rand}`,
    exceptionMessage: "Unauthorized",
    rootCauseClass: "io.jsonwebtoken.ExpiredJwtException",
    rootCauseMessage: "JWT expired",
  });

  const headers = {
    "Content-Type": "application/json",
    "X-App-Name": __ENV.APP_NAME || "auth-error-automation",
    "X-Env": __ENV.ENV || "local",
    "X-LT-Name": "LT-002E",
    "X-LT-Slice-Profile": SLICE_PROFILE,
    "X-LT-Stage": stageTag,
    "X-LT-Target-RPS": stageTarget != null ? String(stageTarget) : "unknown",
  };

  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const url = `${baseUrl}/api/auth-errors`;
  const res = http.post(url, payload, {
    headers,
    tags: {
      api: "auth-errors",
      lt: "LT-002E",
      phase: "knee-slice",
      slice_profile: SLICE_PROFILE,
      stage: stageTag,
      target_rps: stageTarget != null ? String(stageTarget) : "unknown",
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

export function handleSummary(data) {
  const summaryText = buildSummary(data);
  const resultsDir = __ENV.RESULTS_DIR || "/scripts/results";
  const testId = __ENV.TEST_ID || `LT-002E-${new Date().toISOString().replace(/[:.]/g, "-")}`;
  const fileName = `lt_002_slice_knee-${testId}.log`;

  return {
    stdout: summaryText,
    [`${resultsDir}/${fileName}`]: summaryText,
  };
}
