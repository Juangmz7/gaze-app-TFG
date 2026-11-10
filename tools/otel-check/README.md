# otel-check

Checks, per service, whether **traces, metrics and logs** reach Grafana LGTM
(`grafana/otel-lgtm`), and whether **traces cross service boundaries**. It only
uses the Python standard library (Python 3.8+), so it runs the same way on
Windows and Linux.

```
service                                               | traces | metrics | logs    | cross-service partners
------------------------------------------------------+--------+---------+---------+-------------------------
post-command-service                                  | 1      | 2       | 1       | social-service
social-service                                        | 1      | 2       | 1       | post-command-service
post-query-service                                    | 1      | MISSING | MISSING | none
recommendation-service                                | 2      | 2       | 1       | gaze-gateway.gateway-dev
gateway (envoy) (optional) = gaze-gateway.gateway-dev | 1      | MISSING | MISSING | recommendation-service
keycloak (optional) = keycloak                        | 1      | MISSING | MISSING | -
rabbitmq (metrics) (optional) = rabbitmq              | -      | 2       | -       | -

Counts: traces = traces found (search capped at 50), metrics = series, logs = log lines.
Cross-service traces: 2
  gaze-gateway.gateway-dev <-> recommendation-service: 1 trace(s)
  post-command-service <-> social-service: 1 trace(s)
WARN  optional gateway (envoy): missing metrics, logs
WARN  optional keycloak: missing metrics, logs
FAIL  post-query-service: missing metrics, logs
RESULT: FAILED
```

(`MISSING` = nothing in the window; `-` = signal not checked for that row.)

## Run it

### Local compose stack

Start the stack as usual (`infra/local`); Grafana is published on
`127.0.0.1:3000`. Generate some traffic, then from the repo root:

```sh
python tools/otel-check/check.py
```

### Kubernetes

LGTM runs in the `observability` namespace and is not exposed. Port-forward it
in one terminal and run the same command in another:

```sh
kubectl -n observability port-forward svc/lgtm 3000
python tools/otel-check/check.py --env dev     # or --env prod
```

Every environment sends to the same LGTM, so use `--env` to look at one of
them (services set `deployment.environment.name` through
`OTEL_RESOURCE_ATTRIBUTES`). Prod samples traces at 10%, so give it a longer
window there (`--since 1h`).

If local port 3000 is taken (for example by the compose stack), forward to
another port: `kubectl -n observability port-forward svc/lgtm 13000:3000`
and pass `--grafana-url http://localhost:13000`.

## Options

| option | default | meaning |
|---|---|---|
| `--grafana-url` | `http://localhost:3000` | Grafana base URL |
| `--user` / `--password` | `admin` / `admin` | basic auth; on a 401 it retries anonymously (otel-lgtm enables anonymous access). `--user ""` forces anonymous |
| `--since` | `15m` | look-back window: `30s`, `15m`, `2h`, `1d` |
| `--env` | none | only data whose `deployment.environment.name` equals this |
| `--require` | the four services below | comma list of required `service.name` values, replaces the default |
| `--json` | off | machine-readable output instead of the table |
| `--timeout` | `30` | per-request timeout, in seconds |

Exit codes: **0** every required service has all three signals, **1** a
required service is missing at least one, **2** Grafana or one of its
datasources could not be queried.

## What it checks

Required (a missing signal fails the run): `post-command-service`,
`social-service`, `post-query-service`, `recommendation-service`.

Optional (reported with `WARN`, never fail the run). They are matched by regex
against every service name found in any backend, because their names are not
set by us:

| row | matches `service.name` / job | signals checked |
|---|---|---|
| gateway (envoy) | `envoy` or `gateway` (Envoy Gateway names its traces `<gateway>.<namespace>`) | traces, metrics, logs |
| keycloak | `keycloak` | traces, metrics, logs |
| rabbitmq (metrics) | `rabbit`, or any job exporting `rabbitmq_*` metrics | metrics |

Any other service found in a backend gets its own row, so typos in
`service.name` show up too. The self-monitoring series of LGTM's embedded
collector (`otelcol-contrib`, `opentelemetry-collector`) are left out.

Cross-service partners are not part of the pass/fail decision: they are there
to show that context propagation works. A required service with no partner is
shown as `none`; the list of service pairs and the number of traces that join
them is printed under the table.

## How it queries

Datasources are discovered with `GET /api/datasources` (the first `tempo`,
`loki` and `prometheus` datasource, preferring the default one), and every
query goes through Grafana's proxy,
`/api/datasources/proxy/uid/<uid>/...`, so only Grafana has to be reachable.

- **Traces (Tempo)**: service names from
  `/api/v2/search/tag/resource.service.name/values`, then per service
  `/api/search?q={resource.service.name="<svc>"}` (up to 50 traces; `50+`
  means the cap was hit).
- **Cross-service (Tempo)**: per service
  `{resource.service.name="<svc>"} && {resource.service.name!="<svc>"}`, i.e.
  traces that also hold a span from some other service. The services of each
  trace come from the search result's `serviceStats`, or from fetching the
  trace when that field is missing.
- **Logs (Loki)**: `sum by (service_name) (count_over_time({service_name=~".+"}[<since>]))`.
  Loki's OTLP ingest turns `service.name` into the `service_name` index label.
  With `--env` the selector gets `| deployment_environment_name="<env>"`, which
  works whether Loki stores that attribute as a label or as structured metadata.
- **Metrics (Prometheus)**:
  `count by (service_name, job) (last_over_time({__name__=~".+", job=~".+"}[<since>]))`,
  plus `, deployment_environment_name="<env>"` in the selector with `--env`.
  otel-lgtm 0.38.0 configures Prometheus' OTLP receiver with
  `promote_resource_attributes`, so every series carries `service_name`,
  `service_namespace` and `deployment_environment_name`, and `job` is
  `<service.namespace>/<service.name>`. If a Prometheus does not promote them,
  the service name falls back to the part of `job` after the last `/`, and the
  environment is taken from `target_info{deployment_environment_name="<env>"}`
  (where resource attributes always end up). RabbitMQ is also detected by any
  `rabbitmq_*` metric, whatever its job is called.

Versions this was checked against (`grafana/otel-lgtm:0.38.0`): Grafana
13.2.4, Tempo 3.1.0, Loki 3.7.8, Prometheus 3.15.0. The local compose file uses
`grafana/otel-lgtm:latest`; if a newer image changes these APIs, the check
reports an `ERROR <backend>` line and exits 2 instead of silently passing.

## Troubleshooting

- `ERROR: ... connection failed`: Grafana is not reachable: is the stack up /
  the port-forward running, and is `--grafana-url` right?
- `ERROR loki: HTTP 404 ... Unable to find datasource plugin` right after the
  LGTM container started: Grafana 13 ships Loki as a separate plugin that is
  still loading (first start also downloads plugins, which can take minutes).
  Wait and run again.
- A service shows `MISSING` for one signal only: look at that exporter's
  settings (for the Spring services, `MANAGEMENT_OPENTELEMETRY_*` /
  `MANAGEMENT_OTLP_METRICS_EXPORT_URL`; for the Go service, `OTEL_EXPORTER_OTLP_*`).
  Metrics are pushed on an interval (often 60s), so a service that has just
  started may not have any yet.
- Everything is `MISSING` with `--env`: check the value matches the
  `deployment.environment.name` the services actually send.
