// Publishes events at a fixed rate and lets the platform deliver them to a WireMock path.
//
//   docker compose run --rm k6 /load/publish.js
//
// Settings (environment variables, all optional):
//   RATE           events published per second                      (default 50)
//   DURATION       how long to publish                              (default 2m)
//   RECEIVER_PATH  /ok, /slow or /fail500 on the receiver container (default /ok)
//   ENDPOINTS      endpoints to spread the events over              (default 20)
//   P95_MS         threshold on the publish p95, in milliseconds    (default 200)
//
// Events are spread over ENDPOINTS endpoints, each subscribed to one event type, so one publish creates one
// delivery. Funnelling everything to one endpoint would measure the per-endpoint concurrency cap, not the system.
import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://api:8080";
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || "dev-admin-token";
const RECEIVER_URL = __ENV.RECEIVER_URL || "http://receiver:8080";
const RECEIVER_PATH = __ENV.RECEIVER_PATH || "/ok";
const ENDPOINTS = Number(__ENV.ENDPOINTS || 20);
const RATE = Number(__ENV.RATE || 50);
const P95_MS = Number(__ENV.P95_MS || 200);

export const options = {
  scenarios: {
    publish: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "2m",
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    // Declared before the first measurement, and changed only with a note saying why.
    http_req_duration: [`p(95)<${P95_MS}`],
    // A 429 counts as a failure here: this tool measures capacity, so a rate limited run is not a valid run.
    http_req_failed: ["rate<0.01"],
  },
};

const json = (token) => ({ headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" } });

export function setup() {
  // A tenant that cannot be rate limited during the run.
  const tenant = http.post(
    `${BASE_URL}/admin/tenants`,
    JSON.stringify({ name: `k6-${Date.now()}`, publish_rate_per_min: 100000000 }),
    json(ADMIN_TOKEN),
  ).json();
  const apiKey = tenant.api_key.key;
  for (let i = 0; i < ENDPOINTS; i++) {
    const response = http.post(
      `${BASE_URL}/v1/endpoints`,
      JSON.stringify({ url: `${RECEIVER_URL}${RECEIVER_PATH}`, event_types: [`load.${i}`] }),
      json(apiKey),
    );
    if (response.status !== 201) {
      throw new Error(`cannot create endpoint ${i}: ${response.status} ${response.body}`);
    }
  }
  return { apiKey };
}

export default function (data) {
  const type = `load.${Math.floor(Math.random() * ENDPOINTS)}`;
  const response = http.post(`${BASE_URL}/v1/events`, JSON.stringify({ type, data: { at: Date.now() } }), json(data.apiKey));
  check(response, { accepted: (r) => r.status === 202 });
}
