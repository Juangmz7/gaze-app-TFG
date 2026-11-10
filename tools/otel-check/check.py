#!/usr/bin/env python3
"""Check that every Gaze service ships traces, metrics and logs to Grafana LGTM.

Queries Tempo, Prometheus and Loki through Grafana's datasource proxy, prints
one row per service and exits non-zero when a required service is missing a
signal. Standard library only, so it runs unchanged on Windows and Linux.

    python tools/otel-check/check.py                      # local compose stack
    kubectl -n observability port-forward svc/lgtm 3000   # k8s, then the same command
    python tools/otel-check/check.py --env dev --since 30m

Exit codes: 0 all required services OK, 1 a required service is missing a
signal, 2 Grafana or a datasource could not be queried.
"""

from __future__ import annotations

import argparse
import base64
import itertools
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_REQUIRED = [
    "post-command-service",
    "social-service",
    "post-query-service",
    "recommendation-service",
]

# Optional components: reported when missing, never fail the run. Each one is
# matched by regex against the service names found in any backend, because
# their service.name is not under our control (Envoy Gateway names its traces
# "<gateway>.<namespace>", for example).
OPTIONAL = [
    # (label, service-name regex, signals that count)
    ("gateway (envoy)", r"envoy|gateway", ("traces", "metrics", "logs")),
    ("keycloak", r"keycloak", ("traces", "metrics", "logs")),
    ("rabbitmq (metrics)", r"rabbit", ("metrics",)),
]

# Self-monitoring series of the collectors inside/around LGTM: not services.
IGNORED = {"otelcol-contrib", "opentelemetry-collector"}

TRACE_LIMIT = 50        # traces fetched per Tempo search
CROSS_FETCH_LIMIT = 20   # full traces fetched per service when serviceStats is absent
SIGNALS = ("traces", "metrics", "logs")


class CheckError(Exception):
    pass


# --------------------------------------------------------------------------- #
# Grafana client
# --------------------------------------------------------------------------- #
class Grafana:
    def __init__(self, url: str, user: str, password: str, timeout: float):
        self.url = url.rstrip("/")
        self.timeout = timeout
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.auth = f"Basic {token}" if user else None

    def get(self, path: str, params: dict | None = None):
        url = self.url + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        try:
            return self._get(url, self.auth)
        except urllib.error.HTTPError as e:
            # Wrong credentials give 401 even when anonymous access is on:
            # fall back to anonymous once and keep using it.
            if e.code == 401 and self.auth:
                self.auth = None
                return self._get(url, None)
            raise

    def _get(self, url: str, auth: str | None):
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        if auth:
            req.add_header("Authorization", auth)
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            body = resp.read()
        return json.loads(body) if body else None

    def proxy(self, ds_uid: str, path: str, params: dict | None = None):
        return self.get(f"/api/datasources/proxy/uid/{ds_uid}{path}", params)


def http_error_text(e: Exception) -> str:
    if isinstance(e, urllib.error.HTTPError):
        try:
            detail = e.read().decode(errors="replace").strip()
        except Exception:
            detail = ""
        return f"HTTP {e.code} {e.reason}" + (f": {detail[:300]}" if detail else "")
    if isinstance(e, urllib.error.URLError):
        return f"connection failed: {e.reason}"
    return f"{type(e).__name__}: {e}"


def find_datasources(gf: Grafana) -> dict:
    """Map tempo/loki/prometheus to a datasource uid, preferring the default one."""
    try:
        sources = gf.get("/api/datasources")
    except Exception as e:
        raise CheckError(f"GET {gf.url}/api/datasources failed: {http_error_text(e)}")
    found = {}
    for ds in sorted(sources or [], key=lambda d: not d.get("isDefault")):
        kind = ds.get("type")
        if kind in ("tempo", "loki", "prometheus") and kind not in found:
            found[kind] = {"uid": ds["uid"], "name": ds.get("name")}
    return found


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def parse_duration(text: str) -> int:
    m = re.fullmatch(r"\s*(\d+)\s*([smhd])\s*", text)
    if not m:
        raise argparse.ArgumentTypeError(f"invalid duration {text!r} (use e.g. 30s, 15m, 2h, 1d)")
    return int(m.group(1)) * {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]


def prom_duration(seconds: int) -> str:
    return f"{seconds}s"


def quote(value: str) -> str:
    """Double-quoted string literal valid in TraceQL, LogQL and PromQL."""
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def service_from_job(job: str) -> str:
    # Prometheus' OTLP receiver sets job to "<service.namespace>/<service.name>"
    # (or just service.name when there is no namespace).
    return job.rsplit("/", 1)[-1]


# --------------------------------------------------------------------------- #
# Tempo
# --------------------------------------------------------------------------- #
class Tempo:
    def __init__(self, gf: Grafana, uid: str, start: int, end: int, env: str | None):
        self.gf, self.uid, self.start, self.end, self.env = gf, uid, start, end, env
        self.trace_services_cache: dict[str, set] = {}

    def _env_cond(self) -> str:
        return f" && resource.deployment.environment.name={quote(self.env)}" if self.env else ""

    def search(self, traceql: str, limit: int = TRACE_LIMIT) -> list:
        data = self.gf.proxy(self.uid, "/api/search", {
            "q": traceql, "start": self.start, "end": self.end, "limit": limit,
        })
        return (data or {}).get("traces") or []

    def service_names(self) -> list:
        """service.name values seen in the window (Tempo v2 tag API, v1 fallback)."""
        q = "{" + self._env_cond().replace(" && ", "", 1) + "}" if self.env else "{}"
        try:
            data = self.gf.proxy(self.uid, "/api/v2/search/tag/resource.service.name/values",
                                 {"q": q, "start": self.start, "end": self.end})
            return sorted(v["value"] for v in (data or {}).get("tagValues") or [] if v.get("value"))
        except urllib.error.HTTPError:
            data = self.gf.proxy(self.uid, "/api/search/tag/service.name/values")
            return sorted((data or {}).get("tagValues") or [])

    def traces_of(self, service: str) -> list:
        return self.search("{resource.service.name=" + quote(service) + self._env_cond() + "}")

    def cross_traces_of(self, service: str) -> dict:
        """trace id -> set of service names, for traces of `service` that also
        contain a span from some other service."""
        s = quote(service)
        q = ("{resource.service.name=" + s + self._env_cond() + "}"
             " && {resource.service.name!=" + s + self._env_cond() + "}")
        result = {}
        fetched = 0
        for tr in self.search(q):
            tid = tr.get("traceID")
            stats = tr.get("serviceStats")
            if stats:
                services = set(stats)
            elif fetched < CROSS_FETCH_LIMIT:
                services = self.trace_services(tid)
                fetched += 1
            else:
                continue
            if len(services) >= 2:
                result[tid] = services
        return result

    def trace_services(self, trace_id: str) -> set:
        if trace_id in self.trace_services_cache:
            return self.trace_services_cache[trace_id]
        try:
            data = self.gf.proxy(self.uid, f"/api/v2/traces/{trace_id}")
            data = (data or {}).get("trace") or data
        except urllib.error.HTTPError:
            data = self.gf.proxy(self.uid, f"/api/traces/{trace_id}")
        services = set()
        for rs in (data or {}).get("resourceSpans") or (data or {}).get("batches") or []:
            for attr in (rs.get("resource") or {}).get("attributes") or []:
                if attr.get("key") == "service.name":
                    services.add((attr.get("value") or {}).get("stringValue"))
        services.discard(None)
        self.trace_services_cache[trace_id] = services
        return services


# --------------------------------------------------------------------------- #
# Loki / Prometheus
# --------------------------------------------------------------------------- #
def loki_counts(gf: Grafana, uid: str, since: int, end: int, env: str | None) -> dict:
    selector = '{service_name=~".+"}'
    if env:
        # Works whether deployment_environment_name is an index label or
        # structured metadata (Loki's OTLP ingest picks one or the other).
        selector += f" | deployment_environment_name={quote(env)}"
    query = f"sum by (service_name) (count_over_time({selector} [{prom_duration(since)}]))"
    data = gf.proxy(uid, "/loki/api/v1/query", {"query": query, "time": end})
    out = {}
    for r in ((data or {}).get("data") or {}).get("result") or []:
        name = r["metric"].get("service_name")
        if name:
            out[name] = out.get(name, 0) + int(float(r["value"][1]))
    return out


def prom_query(gf: Grafana, uid: str, query: str, end: int) -> list:
    data = gf.proxy(uid, "/api/v1/query", {"query": query, "time": end})
    return ((data or {}).get("data") or {}).get("result") or []


def prom_counts(gf: Grafana, uid: str, since: int, end: int, env: str | None) -> dict:
    """service name -> number of metric series pushed in the window.

    Prometheus' OTLP receiver sets job="<service.namespace>/<service.name>".
    otel-lgtm (0.38) also promotes service.name and deployment.environment.name
    to the service_name / deployment_environment_name labels on every series.
    When they are not promoted (another Prometheus config), the name comes from
    job and the environment from the target_info series, which always carries
    the resource attributes.
    """
    rng = prom_duration(since)

    def series_by_service(extra_matcher: str = "", metric: str = ".+") -> dict:
        q = (f'count by (service_name, job) (last_over_time('
             f'{{__name__=~{quote(metric)}, job=~".+"{extra_matcher}}}[{rng}]))')
        out = {}
        for r in prom_query(gf, uid, q, end):
            lbl = r["metric"]
            name = lbl.get("service_name") or service_from_job(lbl.get("job", ""))
            if name:
                out[name] = out.get(name, 0) + int(float(r["value"][1]))
        return out

    allowed = None
    if env:
        counts = series_by_service(f", deployment_environment_name={quote(env)}")
        if not counts:
            sel = f"target_info{{deployment_environment_name={quote(env)}}}"
            rows = prom_query(gf, uid, f"count by (job) (last_over_time({sel}[{rng}]))", end)
            allowed = {service_from_job(r["metric"].get("job", "")) for r in rows}
            counts = {k: v for k, v in series_by_service().items() if k in allowed}
    else:
        counts = series_by_service()

    # RabbitMQ scraped through a collector's prometheus receiver is named after
    # the scrape job, which may not mention rabbit: detect it by metric name.
    env_matcher = f", deployment_environment_name={quote(env)}" if env and allowed is None else ""
    for name, n in series_by_service(env_matcher, "rabbitmq_.+").items():
        if allowed is not None and name not in allowed:
            continue
        if not re.search("rabbit", name, re.I):
            counts.setdefault(f"rabbitmq [{name}]", n)
    return counts


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def run(args) -> dict:
    gf = Grafana(args.grafana_url, args.user, args.password, args.timeout)
    ds = find_datasources(gf)
    end = int(time.time())
    start = end - args.since
    errors: dict = {}
    for kind in ("tempo", "prometheus", "loki"):
        if kind not in ds:
            errors[kind] = "no datasource of this type in Grafana"

    traces: dict = {}
    partners: dict = {}
    pairs: dict = {}
    cross_ids: set = set()
    logs: dict = {}
    metrics: dict = {}

    if "loki" in ds:
        try:
            logs = loki_counts(gf, ds["loki"]["uid"], args.since, end, args.env)
        except Exception as e:
            errors["loki"] = http_error_text(e)
    if "prometheus" in ds:
        try:
            metrics = prom_counts(gf, ds["prometheus"]["uid"], args.since, end, args.env)
        except Exception as e:
            errors["prometheus"] = http_error_text(e)

    tempo_services: list = []
    if "tempo" in ds:
        tempo = Tempo(gf, ds["tempo"]["uid"], start, end, args.env)
        try:
            tempo_services = tempo.service_names()
        except Exception as e:
            errors["tempo"] = http_error_text(e)
        if "tempo" not in errors:
            names = sorted(set(tempo_services) | set(args.require))
            try:
                for svc in names:
                    found = tempo.traces_of(svc)
                    if found:
                        traces[svc] = len(found)
                    if not found:
                        continue
                    for tid, services in tempo.cross_traces_of(svc).items():
                        partners.setdefault(svc, set()).update(services - {svc})
                        if tid in cross_ids:
                            continue
                        cross_ids.add(tid)
                        for a, b in itertools.combinations(sorted(services), 2):
                            pairs[(a, b)] = pairs.get((a, b), 0) + 1
            except Exception as e:
                errors["tempo"] = http_error_text(e)

    all_seen = (set(traces) | set(logs) | set(metrics) | set(tempo_services)) - IGNORED

    def row(name, kind, signals=SIGNALS, matched=None):
        names = matched if matched is not None else [name]
        t = sum(traces.get(n, 0) for n in names)
        m = sum(metrics.get(n, 0) for n in names)
        lg = sum(logs.get(n, 0) for n in names)
        p = sorted(set().union(*(partners.get(n, set()) for n in names)) - set(names)) if names else []
        values = {"traces": t, "metrics": m, "logs": lg}
        missing = [s for s in signals if not values[s]]
        return {
            "service": name, "kind": kind, "matched": names,
            "traces": t, "traces_capped": any(traces.get(n, 0) >= TRACE_LIMIT for n in names),
            "metrics": m, "logs": lg, "partners": p,
            "checked": list(signals), "missing": missing,
        }

    rows = [row(s, "required") for s in args.require]
    claimed = set(args.require)
    for label, pattern, signals in OPTIONAL:
        matched = sorted(n for n in all_seen if re.search(pattern, n, re.I) and n not in claimed)
        claimed.update(matched)
        rows.append(row(label, "optional", signals, matched))
    for name in sorted(all_seen - claimed):
        rows.append(row(name, "other"))

    failed = [r["service"] for r in rows if r["kind"] == "required" and r["missing"]]
    return {
        "grafana_url": gf.url,
        "window_seconds": args.since,
        "environment": args.env,
        "datasources": ds,
        "errors": errors,
        "services": rows,
        "cross_service": {
            "trace_count": len(cross_ids),
            "pairs": [{"services": list(k), "traces": v} for k, v in sorted(pairs.items())],
        },
        "failed_required": failed,
        "ok": not failed and not errors,
    }


def fmt_count(n: int, capped: bool = False) -> str:
    if not n:
        return "MISSING"
    return f"{n}+" if capped else str(n)


def print_table(res: dict) -> None:
    head = ["service", "traces", "metrics", "logs", "cross-service partners"]
    lines = []
    for r in res["services"]:
        cells = []
        for sig in SIGNALS:
            if sig not in r["checked"]:
                cells.append("-")
            else:
                cells.append(fmt_count(r[sig], sig == "traces" and r["traces_capped"]))
        label = r["service"]
        if r["kind"] == "optional":
            label += " (optional)"
            if r["matched"]:
                label += " = " + ",".join(r["matched"])
        partners = ", ".join(r["partners"]) or "-"
        if r["kind"] == "required" and not r["partners"]:
            partners = "none"
        lines.append([label] + cells + [partners])
    widths = [max(len(head[i]), *(len(l[i]) for l in lines)) for i in range(len(head))]

    def out(cells):
        print(" | ".join(c.ljust(w) for c, w in zip(cells, widths)).rstrip())

    env = f", env={res['environment']}" if res["environment"] else ""
    print(f"Grafana {res['grafana_url']}, last {res['window_seconds']}s{env}")
    print("Datasources: " + ", ".join(f"{k}={v['uid']}" for k, v in sorted(res["datasources"].items())))
    print()
    out(head)
    print("-+-".join("-" * w for w in widths))
    for l in lines:
        out(l)
    print()
    print("Counts: traces = traces found (search capped at %d), metrics = series, logs = log lines."
          % TRACE_LIMIT)
    cs = res["cross_service"]
    print(f"Cross-service traces: {cs['trace_count']}")
    for p in cs["pairs"]:
        print(f"  {p['services'][0]} <-> {p['services'][1]}: {p['traces']} trace(s)")
    for kind, err in sorted(res["errors"].items()):
        print(f"ERROR {kind}: {err}")
    for r in res["services"]:
        if r["kind"] == "optional" and r["missing"]:
            print(f"WARN  optional {r['service']}: missing {', '.join(r['missing'])}")
    for r in res["services"]:
        if r["kind"] == "required" and r["missing"]:
            print(f"FAIL  {r['service']}: missing {', '.join(r['missing'])}")
    print("RESULT: " + ("OK" if res["ok"] else "FAILED"))


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--grafana-url", default="http://localhost:3000")
    p.add_argument("--user", default="admin", help="Grafana user (empty for anonymous)")
    p.add_argument("--password", default="admin")
    p.add_argument("--since", type=parse_duration, default="15m", help="look-back window, e.g. 15m, 1h")
    p.add_argument("--env", help="only data with deployment.environment.name = ENV")
    p.add_argument("--require", type=lambda s: [x.strip() for x in s.split(",") if x.strip()],
                   default=DEFAULT_REQUIRED, help="comma list of required service.name values")
    p.add_argument("--json", action="store_true", help="print the result as JSON")
    p.add_argument("--timeout", type=float, default=30, help="per-request timeout in seconds")
    args = p.parse_args(argv)

    try:
        res = run(args)
    except CheckError as e:
        if args.json:
            print(json.dumps({"ok": False, "errors": {"grafana": str(e)}}, indent=2))
        else:
            print(f"ERROR: {e}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(res, indent=2))
    else:
        print_table(res)
    if res["errors"]:
        return 2
    return 1 if res["failed_required"] else 0


if __name__ == "__main__":
    sys.exit(main())
