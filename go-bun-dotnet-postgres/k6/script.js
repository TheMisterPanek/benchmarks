import http from "k6/http";
import { check } from "k6";

const targetUrl = __ENV.TARGET_URL || "http://localhost:8080";
const testMode = (__ENV.TEST_MODE || "get").toLowerCase();
const vus = parseInt(__ENV.K6_VUS || "50", 10);
const duration = __ENV.K6_DURATION || "15s";

export const options = {
  vus: vus,
  duration: duration,
  thresholds: {
    http_req_failed: ["rate<0.05"],
  },
};

const payload = JSON.stringify({
  name: "David D. Patton",
  address: "1670 Stiles Street",
  phone: "412-578-3857",
});

const params = {
  headers: {
    "Content-Type": "application/json",
  },
};

export default function () {
  if (testMode === "post") {
    const res = http.post(`${targetUrl}/api/users`, payload, params);
    check(res, {
      "status is 201": (r) => r.status === 201,
    });
  } else {
    const res = http.get(`${targetUrl}/api/users`);
    check(res, {
      "status is 200": (r) => r.status === 200,
    });
  }
}
