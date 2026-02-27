import http from "k6/http";
import exec from "k6/execution";
import { check } from "k6";
import { Rate } from "k6/metrics";

/**
 * LT-002 Ramp-up
 * 목적: 임계점 탐색 (stage별 증가)
 */

const CHECK_FAIL = new Rate("check_fail_rate"); // 2xx 실패율 추적

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

const RAMP_STAGES = [
  { target: 5, duration: "3m" },
  { target: 10, duration: "1m" },
  { target: 10, duration: "2m" },
  { target: 20, duration: "1m" },
  { target: 20, duration: "2m" },
  { target: 30, duration: "1m" },
  { target: 30, duration: "2m" },
  { target: 40, duration: "1m" },
  { target: 40, duration: "2m" },
  { target: 50, duration: "1m" },
  { target: 50, duration: "2m" },
  { target: 60, duration: "1m" },
  { target: 60, duration: "2m" },
  { target: 70, duration: "1m" },
  { target: 70, duration: "2m" },
  { target: 80, duration: "1m" },
  { target: 80, duration: "2m" },
];

const STAGE_BOUNDARIES_MS = (() => {
  let acc = 0;
  return RAMP_STAGES.map((s) => {
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
  return RAMP_STAGES.map((s) => {
    acc += parseDurationMs(s.duration);
    return startEpochMs + acc - parseDurationMs(s.duration);
  });
}

export const options = {
  scenarios: {
    rampup: {
      executor: "ramping-arrival-rate",
      startRate: 5,
      timeUnit: "1s",
      preAllocatedVUs: 80,
      maxVUs: 500,
      stages: RAMP_STAGES,
      tags: { lt: "LT-002", phase: "rampup" },
    },
  },
};

function nowKstIsoOffset() {
  const d = new Date(Date.now() + 9 * 60 * 60 * 1000);
  return d.toISOString().replace("Z", "+09:00");
}

export default function () {
  const baseUrl = __ENV.BASE_URL;
  const token = __ENV.AUTH_TOKEN;

  const elapsedMs = parseElapsedMs(exec.instance.currentTestRunDuration);
  const stageIndex = resolveStageIndexByElapsed(elapsedMs);
  const stageConfig = stageIndex != null ? RAMP_STAGES[stageIndex] : null;
  const stageTarget = stageConfig && typeof stageConfig.target === "number" ? stageConfig.target : null;
  const stageTag = stageTarget ? `rps-${stageTarget}` : "rps-unknown";

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
    "X-LT-Name": "LT-002",
    "X-LT-Stage": stageTag,
    "X-LT-Target-RPS": stageTarget ? String(stageTarget) : "unknown",
  };

  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const url = `${baseUrl}/api/auth-errors`;

  const res = http.post(url, payload, {
    headers,
    tags: {
      api: "auth-errors",
      lt: "LT-002",
      stage: stageTag,
      target_rps: stageTarget ? String(stageTarget) : "unknown",
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
  const runMs =
    (data && data.state && typeof data.state.testRunDurationMs === "number" && data.state.testRunDurationMs) ||
    (data && data.state && typeof data.state.testRunDuration === "number" && data.state.testRunDuration) ||
    TOTAL_TEST_MS;
  const endMs = Date.now();
  const startMs = endMs - runMs;

  const epochs = stageStartEpochs(startMs);
  for (let i = 0; i < epochs.length; i += 1) {
    const ts = new Date(epochs[i]).toISOString();
    const target = RAMP_STAGES[i] && typeof RAMP_STAGES[i].target === "number" ? RAMP_STAGES[i].target : "unknown";
    console.log(`[STAGE_START] stage_index=${i} target_rps=${target} timestamp=${ts}`);
  }
  return {};
}
