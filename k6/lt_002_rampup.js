import http from "k6/http";
import { check, sleep } from "k6";
import { Rate } from "k6/metrics";

/**
 * LT-002 Ramp-up
 * 목적: 임계점 탐색 (stage별 증가)
 */

const CHECK_FAIL = new Rate("check_fail_rate"); // 2xx 실패율 추적

export const options = {
  scenarios: {
    rampup: {
      executor: "ramping-arrival-rate",
      startRate: 5,
      timeUnit: "1s",
      preAllocatedVUs: 30,
      maxVUs: 200,
      stages: [
        { target: 10, duration: "2m" },
        { target: 20, duration: "2m" },
        { target: 40, duration: "2m" },
        { target: 60, duration: "2m" },
        { target: 80, duration: "2m" },
      ],
      tags: { lt: "LT-002", phase: "rampup" },
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.005"],
    check_fail_rate: ["rate<0.01"],
  },
};

function nowKstIsoOffset() {
  const d = new Date(Date.now() + 9 * 60 * 60 * 1000);
  return d.toISOString().replace("Z", "+09:00");
}

export default function () {
  const baseUrl = __ENV.BASE_URL;
  const token = __ENV.AUTH_TOKEN;

  const occurredAt = nowKstIsoOffset();

  const payload = JSON.stringify({
    requestId: `REQ-${__VU}-${__ITER}`,
    occurredAt: occurredAt,
    httpStatus: 401,
    exceptionClass: "org.springframework.security.authentication.BadCredentialsException",
    stacktrace: "stacktrace-sample",
    httpMethod: "GET",
    requestUri: "/api/v1/me",
    clientIp: "203.0.113.10",
    userAgent: "k6",
    userId: "test-user",
    sessionId: `S-${__VU}`,
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

  const url = `${baseUrl}/api/auth-errors`;

  const res = http.post(url, payload, {
    headers,
    tags: {
      api: "auth-errors",
      lt: "LT-002",
      app: __ENV.APP_NAME || "auth-error-automation",
      env: __ENV.ENV || "local",
    },
  });

  const ok = check(res, {
    "status is 2xx": (r) => r.status >= 200 && r.status < 300,
  });

  CHECK_FAIL.add(!ok);

  if (!ok && __ITER < 10) {
    console.error(`[k6] FAIL status=${res.status} url=${url} body=${res.body}`);
  }

  sleep(0.1);
}
