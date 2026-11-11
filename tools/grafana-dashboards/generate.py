"""Generates the Grafana dashboards provisioned into LGTM's Grafana.

Output: infra/k8s/platform/observability/dashboards/*.json (mounted by
lgtm.yaml through the kustomization's configMapGenerator). Grafana UI saves are
disabled for these dashboards, so change them here and regenerate:

    python3 tools/grafana-dashboards/generate.py

Metric names are the Prometheus names LGTM stores for OTLP data: dots become
underscores, the unit is appended (By -> _bytes) and monotonic sums get
_total. Only the resource attributes in LGTM's promote_resource_attributes
(k8s.namespace/pod/container/node/workload names, service.name, ...) are
labels.
"""
import json
import re
import sys
from pathlib import Path

PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}


class Layout:
    def __init__(self):
        self.y = 0
        self.x = 0
        self.row_h = 0
        self.next_id = 1
        self.panels = []

    def _id(self):
        i = self.next_id
        self.next_id += 1
        return i

    def row(self, title):
        self.newline()
        self.panels.append({
            "type": "row", "title": title, "collapsed": False, "id": self._id(),
            "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}, "panels": [],
        })
        self.y += 1

    def newline(self):
        if self.x:
            self.y += self.row_h
            self.x = 0
            self.row_h = 0

    def add(self, panel, w, h):
        if self.x + w > 24:
            self.newline()
        panel["id"] = self._id()
        panel["gridPos"] = {"h": h, "w": w, "x": self.x, "y": self.y}
        if panel["type"] == "timeseries" and w < 8:
            panel["options"]["legend"] = {"displayMode": "list", "placement": "bottom", "calcs": []}
        self.panels.append(panel)
        self.x += w
        self.row_h = max(self.row_h, h)


def prom(expr, legend="", instant=False, fmt=None, ref="A"):
    t = {"datasource": PROM, "expr": only_fresh(expr), "refId": ref, "legendFormat": legend or "__auto"}
    if instant:
        t["instant"] = True
        t["range"] = False
    if fmt:
        t["format"] = fmt
    return t


def loki(expr, ref="A", instant=False, legend=""):
    t = {"datasource": LOKI, "expr": expr, "refId": ref, "queryType": "instant" if instant else "range"}
    if legend:
        t["legendFormat"] = legend
    return t


def stat(title, target, unit="short", thresholds=None, desc="", decimals=None, ds=PROM, mappings=None, no_value=None):
    steps = thresholds or [{"color": "green", "value": None}]
    p = {
        "type": "stat", "title": title, "description": desc, "datasource": ds,
        "targets": [target],
        "fieldConfig": {"defaults": {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps},
                                     "color": {"mode": "thresholds"}}, "overrides": []},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                    "colorMode": "background", "graphMode": "area", "textMode": "value",
                    "justifyMode": "center", "orientation": "auto"},
    }
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    if mappings:
        p["fieldConfig"]["defaults"]["mappings"] = mappings
    if no_value:
        p["fieldConfig"]["defaults"]["noValue"] = no_value
    return p


def timeseries(title, targets, unit="short", desc="", stack=False, axis_label="", soft_max=None):
    custom = {"drawStyle": "line", "lineWidth": 1, "fillOpacity": 15 if stack else 5,
              "showPoints": "never", "spanNulls": True, "axisLabel": axis_label,
              "stacking": {"mode": "normal" if stack else "none", "group": "A"}}
    percent_axis = {}
    if unit == "percentunit" and targets[0]["datasource"] is PROM:
        # Plotted as 0-100 with unit percent: when every point is 0, Grafana ignores
        # softMax and falls back to a 0-100 range, which for a 0-1 ratio reads 10000%.
        targets = [dict(t, expr=f"100 * ({t['expr']})") for t in targets]
        unit = "percent"
        percent_axis = {"min": 0, "softMax": 100 * (1 if soft_max is None else soft_max)}
    return {
        "type": "timeseries", "title": title, "description": desc, "datasource": targets[0]["datasource"],
        "targets": targets,
        "fieldConfig": {"defaults": dict({"unit": unit, "custom": custom, "color": {"mode": "palette-classic"}}, **percent_axis),
                        "overrides": []},
        "options": {"legend": {"displayMode": "table", "placement": "right", "calcs": ["lastNotNull", "max"]},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
    }


def table(title, targets, desc="", unit="short", rename=None, exclude=None, sort_by=None,
          value_mappings=None, thresholds=None, gauge=False):
    transformations = []
    if len(targets) > 1:
        transformations.append({"id": "merge", "options": {}})
    excl = {"Time": True}
    for e in exclude or []:
        excl[e] = True
    transformations.append({"id": "organize", "options": {"excludeByName": excl, "renameByName": rename or {}}})
    defaults = {"unit": unit, "custom": {"align": "auto", "filterable": True}}
    if value_mappings:
        defaults["mappings"] = value_mappings
    if thresholds:
        defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
        defaults["color"] = {"mode": "thresholds"}
    if gauge:
        defaults["custom"]["cellOptions"] = {"type": "gauge", "mode": "basic"}
        defaults["min"] = 0
        defaults["max"] = 1
    p = {
        "type": "table", "title": title, "description": desc, "datasource": targets[0]["datasource"],
        "targets": targets, "transformations": transformations,
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}},
    }
    if sort_by:
        p["options"]["sortBy"] = [{"displayName": sort_by, "desc": True}]
    return p


def logs(title, expr, desc=""):
    return {
        "type": "logs", "title": title, "description": desc, "datasource": LOKI,
        "targets": [loki(expr)],
        "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending",
                    "enableLogDetails": True, "dedupStrategy": "none", "prettifyLogMessage": False},
    }


def query_var(name, label, query, ds=PROM, all_value=".*", multi=True):
    return {
        "type": "query", "name": name, "label": label, "datasource": ds,
        "query": {"query": query, "refId": f"{name}-var"} if ds is PROM else query,
        "definition": query, "refresh": 2, "includeAll": True, "multi": multi,
        "allValue": all_value, "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
        "sort": 1,
    }


def dashboard(uid, title, desc, tags, panels, variables):
    return {
        "uid": uid, "title": title, "description": desc, "tags": tags,
        "editable": True, "graphTooltip": 1, "schemaVersion": 39, "version": 1,
        "time": {"from": "now-3h", "to": "now"}, "refresh": "30s", "timezone": "browser",
        "templating": {"list": variables}, "annotations": {"list": []},
        "links": [{"type": "dashboards", "tags": ["gaze"], "asDropdown": True, "title": "Gaze dashboards"}],
        "panels": panels,
    }


RED = [{"color": "green", "value": None}, {"color": "red", "value": 1}]
ORANGE = [{"color": "green", "value": None}, {"color": "orange", "value": 1}]
PCT = [{"color": "green", "value": None}, {"color": "orange", "value": 0.7}, {"color": "red", "value": 0.9}]


# --------------------------------------------------------------------------
# Kubernetes cluster
# --------------------------------------------------------------------------
def kubernetes():
    ns = 'k8s_namespace_name=~"$namespace"'
    L = Layout()

    L.row("Overview")
    L.add(stat("Nodes ready", prom('count({__name__=~"k8s_node_condition_ready(_ratio)?"} == 1) or vector(0)', instant=True),
               desc="Nodes whose Ready condition is True (k8s_cluster)."), 4, 4)
    L.add(stat("Node CPU used", prom("sum(k8s_node_cpu_usage) / sum(k8s_node_allocatable_cpu)", instant=True),
               unit="percentunit", thresholds=PCT, desc="Actual CPU usage (kubelet) over allocatable CPU."), 4, 4)
    L.add(stat("Node memory used", prom("sum(k8s_node_memory_working_set_bytes) / sum(k8s_node_allocatable_memory_bytes)", instant=True),
               unit="percentunit", thresholds=PCT, desc="Working set (what the OOM killer looks at) over allocatable memory."), 4, 4)
    L.add(stat("Node disk used", prom("sum(k8s_node_filesystem_usage_bytes) / sum(k8s_node_filesystem_capacity_bytes)", instant=True),
               unit="percentunit", thresholds=PCT, desc="Node root filesystem (images, logs, emptyDirs, local-path volumes)."), 4, 4)
    L.add(stat("CPU requested", prom("sum(k8s_container_cpu_request) / sum(k8s_node_allocatable_cpu)", instant=True),
               unit="percentunit", thresholds=PCT, desc="Sum of container CPU requests over allocatable: what the scheduler sees as reserved."), 4, 4)
    L.add(stat("Memory requested", prom("sum(k8s_container_memory_request_bytes) / sum(k8s_node_allocatable_memory_bytes)", instant=True),
               unit="percentunit", thresholds=PCT, desc="Sum of container memory requests over allocatable."), 4, 4)
    L.add(stat("Running pods", prom(f"count(k8s_pod_phase{{{ns}}} == 2) or vector(0)", instant=True)), 4, 4)
    L.add(stat("Pending pods", prom(f"count(k8s_pod_phase{{{ns}}} == 1) or vector(0)", instant=True), thresholds=ORANGE), 4, 4)
    L.add(stat("Failed pods", prom(f"count(k8s_pod_phase{{{ns}}} == 4 or k8s_pod_phase{{{ns}}} == 5) or vector(0)", instant=True),
               thresholds=RED, desc="Pods in phase Failed or Unknown."), 4, 4)
    L.add(stat("Containers not ready", prom(
        f"count((k8s_container_ready{{{ns}}} == 0) unless on (k8s_namespace_name, k8s_pod_name) (k8s_pod_phase == 3)) or vector(0)", instant=True),
        thresholds=RED, desc="Containers failing readiness, excluding completed (Succeeded) pods."), 4, 4)
    L.add(stat("Containers with restarts", prom(f"count(k8s_container_restarts{{{ns}}} > 0) or vector(0)", instant=True),
               thresholds=ORANGE, desc="Containers whose restart count is above 0 (k8s_cluster)."), 4, 4)
    L.add(stat("Warning events", loki('sum(count_over_time({service_name="k8s-events"} | severity_text="Warning" [$__range]))', instant=True),
               thresholds=ORANGE, ds=LOKI, desc="Kubernetes Warning events in the selected time range."), 4, 4)

    L.row("Resource usage")
    L.add(timeseries("CPU usage by namespace", [prom(f"sum by (k8s_namespace_name) (k8s_pod_cpu_usage{{{ns}}})", "{{k8s_namespace_name}}")],
                     axis_label="cores", stack=True), 12, 9)
    L.add(timeseries("Memory (working set) by namespace", [prom(f"sum by (k8s_namespace_name) (k8s_pod_memory_working_set_bytes{{{ns}}})", "{{k8s_namespace_name}}")],
                     unit="bytes", stack=True), 12, 9)
    L.add(timeseries("Node CPU", [
        prom("sum by (k8s_node_name) (k8s_node_cpu_usage)", "{{k8s_node_name}} used"),
        prom("sum by (k8s_node_name) (k8s_container_cpu_request)", "{{k8s_node_name}} requested", ref="B"),
        prom("sum by (k8s_node_name) (k8s_node_allocatable_cpu)", "{{k8s_node_name}} allocatable", ref="C"),
    ], axis_label="cores"), 8, 8)
    L.add(timeseries("Node memory", [
        prom("sum by (k8s_node_name) (k8s_node_memory_working_set_bytes)", "{{k8s_node_name}} working set"),
        prom("sum by (k8s_node_name) (k8s_container_memory_request_bytes)", "{{k8s_node_name}} requested", ref="B"),
        prom("sum by (k8s_node_name) (k8s_node_allocatable_memory_bytes)", "{{k8s_node_name}} allocatable", ref="C"),
    ], unit="bytes"), 8, 8)
    L.add(timeseries("Network by namespace", [
        prom(f'sum by (k8s_namespace_name) (rate(k8s_pod_network_io_bytes_total{{{ns}, direction="receive"}}[$__rate_interval]))', "{{k8s_namespace_name}} rx"),
        prom(f'-sum by (k8s_namespace_name) (rate(k8s_pod_network_io_bytes_total{{{ns}, direction="transmit"}}[$__rate_interval]))', "{{k8s_namespace_name}} tx", ref="B"),
    ], unit="Bps", desc="Receive above zero, transmit below."), 8, 8)
    L.add(table("Top pods by CPU", [prom(f"topk(15, sum by (k8s_namespace_name, k8s_pod_name) (k8s_pod_cpu_usage{{{ns}}}))", instant=True, fmt="table")],
                rename={"k8s_namespace_name": "Namespace", "k8s_pod_name": "Pod", "Value": "CPU (cores)"}, sort_by="CPU (cores)"), 12, 9)
    L.add(table("Top pods by memory", [prom(f"topk(15, sum by (k8s_namespace_name, k8s_pod_name) (k8s_pod_memory_working_set_bytes{{{ns}}}))", instant=True, fmt="table")],
                unit="bytes", rename={"k8s_namespace_name": "Namespace", "k8s_pod_name": "Pod", "Value": "Working set"}, sort_by="Working set"), 12, 9)
    by_c = "k8s_namespace_name, k8s_pod_name, k8s_container_name"
    L.add(table("Container memory / limit (OOM risk)", [prom(
        f"sort_desc(max by ({by_c}) (container_memory_working_set_bytes{{{ns}}}) / max by ({by_c}) (k8s_container_memory_limit_bytes{{{ns}}} > 0))",
        instant=True, fmt="table")],
        unit="percentunit", gauge=True, thresholds=PCT, sort_by="Usage / limit",
        desc="Working set over the memory limit. At 100% the container is OOMKilled. Containers without a limit are not listed.",
        rename={"k8s_namespace_name": "Namespace", "k8s_pod_name": "Pod", "k8s_container_name": "Container", "Value": "Usage / limit"}), 12, 9)
    L.add(table("Container CPU / limit (throttling)", [prom(
        f"sort_desc(max by ({by_c}) (container_cpu_usage{{{ns}}}) / max by ({by_c}) (k8s_container_cpu_limit{{{ns}}} > 0))",
        instant=True, fmt="table")],
        unit="percentunit", gauge=True, thresholds=PCT, sort_by="Usage / limit",
        desc="CPU usage over the CPU limit. Near 100% the container is being throttled. Containers without a limit are not listed.",
        rename={"k8s_namespace_name": "Namespace", "k8s_pod_name": "Pod", "k8s_container_name": "Container", "Value": "Usage / limit"}), 12, 9)
    L.add(table("Persistent volume usage", [prom(
        f'sort_desc(max by (k8s_namespace_name, k8s_pod_name, k8s_volume_name) (1 - k8s_volume_available_bytes{{{ns}, k8s_volume_type="persistentVolumeClaim"}} / k8s_volume_capacity_bytes{{{ns}, k8s_volume_type="persistentVolumeClaim"}}))',
        instant=True, fmt="table")],
        unit="percentunit", gauge=True, thresholds=PCT, sort_by="Used",
        desc="PVC-backed volumes as reported by the kubelet. Provisioners that do not report capacity (e.g. k3s local-path) do not appear.",
        rename={"k8s_namespace_name": "Namespace", "k8s_pod_name": "Pod", "k8s_volume_name": "Volume", "Value": "Used"}), 24, 7)

    L.row("Workloads and failures")
    phase_map = [{"type": "value", "options": {
        "1": {"text": "Pending", "color": "orange"}, "2": {"text": "Running", "color": "green"},
        "3": {"text": "Succeeded", "color": "blue"}, "4": {"text": "Failed", "color": "red"},
        "5": {"text": "Unknown", "color": "red"}}}]
    L.add(table("Pods not running", [prom(
        f"max by (k8s_namespace_name, k8s_pod_name) (k8s_pod_phase{{{ns}}} == 1 or k8s_pod_phase{{{ns}}} == 4 or k8s_pod_phase{{{ns}}} == 5)",
        instant=True, fmt="table")], value_mappings=phase_map,
        rename={"k8s_namespace_name": "Namespace", "k8s_pod_name": "Pod", "Value": "Phase"}), 12, 8)
    L.add(table("Container waiting / terminated reasons", [prom(
        f'max by ({by_c}, k8s_container_status_reason) (k8s_container_status_reason{{{ns}, k8s_container_status_reason!="Completed"}} > 0)',
        instant=True, fmt="table")], exclude=["Value"],
        desc="CrashLoopBackOff, OOMKilled, ImagePullBackOff, CreateContainerConfigError, Error, ...",
        rename={"k8s_namespace_name": "Namespace", "k8s_pod_name": "Pod", "k8s_container_name": "Container",
                "k8s_container_status_reason": "Reason"}), 12, 8)
    L.add(table("Container restarts", [prom(
        f"sort_desc(max by ({by_c}) (k8s_container_restarts{{{ns}}} > 0))", instant=True, fmt="table")],
        thresholds=ORANGE, sort_by="Restarts",
        rename={"k8s_namespace_name": "Namespace", "k8s_pod_name": "Pod", "k8s_container_name": "Container", "Value": "Restarts"}), 12, 8)
    L.add(timeseries("Container restarts (increase)", [prom(
        f"sum by (k8s_namespace_name, k8s_pod_name, k8s_container_name) (delta(k8s_container_restarts{{{ns}}}[10m])) > 0",
        "{{k8s_namespace_name}}/{{k8s_pod_name}}/{{k8s_container_name}}")],
        desc="Restarts in the previous 10 minutes, per container."), 12, 8)
    def missing(kind, name_label, desired, current):
        by = f"k8s_namespace_name, {name_label}"
        return (f'sum by (k8s_namespace_name, workload) (label_replace(sum by ({by}) ({desired}{{{ns}}}) - sum by ({by}) ({current}{{{ns}}}) > 0, '
                f'"workload", "{kind}/$1", "{name_label}", "(.*)"))')
    L.add(table("Workloads below desired replicas", [prom(" or ".join([
        missing("deployment", "k8s_deployment_name", "k8s_deployment_desired", "k8s_deployment_available"),
        missing("statefulset", "k8s_statefulset_name", "k8s_statefulset_desired_pods", "k8s_statefulset_ready_pods"),
        missing("daemonset", "k8s_daemonset_name", "k8s_daemonset_desired_scheduled_nodes", "k8s_daemonset_ready_nodes"),
    ]), instant=True, fmt="table")], thresholds=RED, sort_by="Missing",
        rename={"k8s_namespace_name": "Namespace", "workload": "Workload", "Value": "Missing"},
        desc="Desired minus available/ready replicas, for Deployments, StatefulSets and DaemonSets."), 12, 8)
    L.add(table("Failed jobs", [prom(f"max by (k8s_namespace_name, k8s_job_name) (k8s_job_failed_pods{{{ns}}} > 0)", instant=True, fmt="table")],
                thresholds=RED,
                rename={"k8s_namespace_name": "Namespace", "k8s_job_name": "Job", "Value": "Failed pods"}), 12, 8)
    L.add(timeseries("Autoscaling (KEDA / HPA) replicas", [
        prom(f"sum by (k8s_namespace_name, k8s_hpa_name) (k8s_hpa_current_replicas{{{ns}}})", "{{k8s_namespace_name}} current"),
        prom(f"sum by (k8s_namespace_name, k8s_hpa_name) (k8s_hpa_desired_replicas{{{ns}}})", "{{k8s_namespace_name}} desired", ref="B"),
        prom(f"sum by (k8s_namespace_name, k8s_hpa_name) (k8s_hpa_max_replicas{{{ns}}})", "{{k8s_namespace_name}} max", ref="C"),
    ]), 12, 8)
    L.add(timeseries("Pods by phase", [
        prom(f'count(k8s_pod_phase{{{ns}}} == 1) or vector(0)', "Pending"),
        prom(f'count(k8s_pod_phase{{{ns}}} == 2) or vector(0)', "Running", ref="B"),
        prom(f'count(k8s_pod_phase{{{ns}}} == 4) or vector(0)', "Failed", ref="C"),
        prom(f'count(k8s_pod_phase{{{ns}}} == 5) or vector(0)', "Unknown", ref="D"),
    ]), 12, 8)

    L.row("Events and logs")
    L.add(timeseries("Warning events by reason", [loki(
        'sum by (k8s_event_reason) (count_over_time({service_name="k8s-events"} | severity_text="Warning" [$__auto]))',
        legend="{{k8s_event_reason}}")], stack=True,
        desc="BackOff, Unhealthy, FailedScheduling, FailedMount, OOMKilling, Evicted, ..."), 24, 8)
    L.add(logs("Kubernetes events", '{service_name="k8s-events"}',
               desc="All Kubernetes Events (Normal and Warning). Attributes: k8s.event.reason, k8s.object.kind/name, k8s.event.count."), 12, 12)
    L.add(logs("Error logs ($namespace)", '{k8s_namespace_name=~"$namespace"} | detected_level=~"error|fatal|critical"',
               desc="Error-level logs from every container (node agent) and every service (OTLP) in the selected namespaces."), 12, 12)

    variables = [query_var("namespace", "Namespace", "label_values(k8s_pod_phase, k8s_namespace_name)", all_value=".+")]
    return dashboard("gaze-kubernetes", "Kubernetes cluster",
                     "Node, pod and container resources (kubelet), object state (k8s_cluster), Kubernetes events and error logs.",
                     ["gaze", "kubernetes"], L.panels, variables)


# --------------------------------------------------------------------------
# RabbitMQ
# --------------------------------------------------------------------------
def rabbitmq():
    ns = 'k8s_namespace_name=~"$namespace"'
    q = f'{ns}, vhost=~"$vhost", queue=~"$queue"'
    ex = f'{ns}, vhost=~"$vhost"'
    rate = "[$__rate_interval]"
    L = Layout()

    L.row("Overview")
    L.add(stat("Brokers", prom(f"count(rabbitmq_identity_info{{{ns}}}) or vector(0)", instant=True),
               thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}]), 3, 4)
    L.add(stat("Alarms", prom(
        f"(sum(rabbitmq_alarms_memory_used_watermark{{{ns}}}) or vector(0)) + (sum(rabbitmq_alarms_free_disk_space_watermark{{{ns}}}) or vector(0)) + (sum(rabbitmq_alarms_file_descriptor_limit{{{ns}}}) or vector(0))",
        instant=True), thresholds=RED, desc="Memory, disk and file descriptor alarms. While one is raised, publishers are blocked."), 3, 4)
    L.add(stat("Connections", prom(f"sum(rabbitmq_connections{{{ns}}})", instant=True)), 3, 4)
    L.add(stat("Channels", prom(f"sum(rabbitmq_channels{{{ns}}})", instant=True)), 3, 4)
    L.add(stat("Queues", prom(f"sum(rabbitmq_queues{{{ns}}})", instant=True)), 3, 4)
    L.add(stat("Consumers", prom(f"sum(rabbitmq_consumers{{{ns}}})", instant=True)), 3, 4)
    L.add(stat("Queues without consumers", prom(f'count(rabbitmq_detailed_queue_consumers{{{q}}} == 0) or vector(0)', instant=True),
               thresholds=ORANGE, desc="Messages routed to these queues pile up until a consumer attaches."), 3, 4)
    L.add(stat("Messages ready", prom(f"sum(rabbitmq_queue_messages_ready{{{ns}}})", instant=True),
               thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1000}, {"color": "red", "value": 10000}]), 3, 4)
    L.add(stat("Messages unacked", prom(f"sum(rabbitmq_queue_messages_unacked{{{ns}}})", instant=True),
               thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 500}, {"color": "red", "value": 5000}]), 3, 4)
    L.add(stat("Memory used / watermark", prom(
        f"sum(rabbitmq_process_resident_memory_bytes{{{ns}}}) / sum(rabbitmq_resident_memory_limit_bytes{{{ns}}})", instant=True),
        unit="percentunit", thresholds=PCT, desc="At 100% the memory alarm blocks publishers (vm_memory_high_watermark)."), 3, 4)
    L.add(stat("Disk free", prom(f"min(rabbitmq_disk_space_available_bytes{{{ns}}})", instant=True), unit="bytes"), 3, 4)
    L.add(stat("Published / s", prom(f"sum(rate(rabbitmq_global_messages_received_total{{{ns}}}[5m]))", instant=True),
               unit="short", decimals=2), 3, 4)

    L.row("Throughput")
    L.add(timeseries("Message rates", [
        prom(f"sum(rate(rabbitmq_global_messages_received_total{{{ns}}}{rate}))", "published"),
        prom(f"sum(rate(rabbitmq_global_messages_routed_total{{{ns}}}{rate}))", "routed to queues", ref="B"),
        prom(f"sum(rate(rabbitmq_global_messages_delivered_total{{{ns}}}{rate}))", "delivered", ref="C"),
        prom(f"sum(rate(rabbitmq_global_messages_acknowledged_total{{{ns}}}{rate}))", "acknowledged", ref="D"),
        prom(f"sum(rate(rabbitmq_global_messages_confirmed_total{{{ns}}}{rate}))", "confirmed to publishers", ref="E"),
    ], unit="short", axis_label="msg/s"), 12, 9)
    L.add(timeseries("Problem rates", [
        prom(f"sum(rate(rabbitmq_global_messages_redelivered_total{{{ns}}}{rate}))", "redelivered (nack / consumer died)"),
        prom(f"sum(rate(rabbitmq_global_messages_unroutable_dropped_total{{{ns}}}{rate}))", "unroutable dropped", ref="B"),
        prom(f"sum(rate(rabbitmq_global_messages_unroutable_returned_total{{{ns}}}{rate}))", "unroutable returned", ref="C"),
        prom(f'sum(rate({{__name__=~"rabbitmq_global_messages_dead_lettered_.+_total", {ns}}}{rate}))', "dead-lettered", ref="D"),
    ], unit="short", axis_label="msg/s",
        desc="Redeliveries usually mean consumers failing (nack/requeue or crash). Unroutable messages were published to an exchange with no matching binding."), 12, 9)
    L.add(timeseries("Messages in queues", [
        prom(f"sum(rabbitmq_queue_messages_ready{{{ns}}})", "ready"),
        prom(f"sum(rabbitmq_queue_messages_unacked{{{ns}}})", "unacked", ref="B"),
    ], stack=True), 8, 8)
    L.add(timeseries("Connections and channels", [
        prom(f"sum(rabbitmq_connections{{{ns}}})", "connections"),
        prom(f"sum(rabbitmq_channels{{{ns}}})", "channels", ref="B"),
        prom(f"sum(rabbitmq_consumers{{{ns}}})", "consumers", ref="C"),
    ]), 8, 8)
    L.add(timeseries("Connection churn", [
        prom(f"sum(rate(rabbitmq_connections_opened_total{{{ns}}}{rate}))", "opened"),
        prom(f"sum(rate(rabbitmq_connections_closed_total{{{ns}}}{rate}))", "closed", ref="B"),
        prom(f"sum(rate(rabbitmq_channels_opened_total{{{ns}}}{rate}))", "channels opened", ref="C"),
    ], axis_label="/s", desc="Sustained churn means clients reconnecting in a loop."), 8, 8)

    L.row("Queues")
    L.add(table("Queues", [
        prom(f"sum by (vhost, queue) (rabbitmq_detailed_queue_messages_ready{{{q}}})", instant=True, fmt="table"),
        prom(f"sum by (vhost, queue) (rabbitmq_detailed_queue_messages_unacked{{{q}}})", instant=True, fmt="table", ref="B"),
        prom(f"sum by (vhost, queue) (rabbitmq_detailed_queue_consumers{{{q}}})", instant=True, fmt="table", ref="C"),
        prom(f'sum by (vhost, queue) (rate({{__name__=~"rabbitmq_detailed_queue_messages_delivered(_ack)?_total", {q}}}[5m]))', instant=True, fmt="table", ref="D"),
        prom(f"sum by (vhost, queue) (rate(rabbitmq_detailed_queue_messages_acked_total{{{q}}}[5m]))", instant=True, fmt="table", ref="E"),
        prom(f"sum by (vhost, queue) (rate(rabbitmq_detailed_queue_messages_redelivered_total{{{q}}}[5m]))", instant=True, fmt="table", ref="F"),
    ], rename={"vhost": "VHost", "queue": "Queue", "Value #A": "Ready", "Value #B": "Unacked", "Value #C": "Consumers",
               "Value #D": "Delivered/s", "Value #E": "Acked/s", "Value #F": "Redelivered/s"},
        sort_by="Ready", desc="Per-queue state (/metrics/detailed). Rates over the last 5 minutes."), 24, 10)
    L.add(timeseries("Ready messages by queue", [prom(f"sum by (queue) (rabbitmq_detailed_queue_messages_ready{{{q}}})", "{{queue}}")]), 12, 8)
    L.add(timeseries("Unacked messages by queue", [prom(f"sum by (queue) (rabbitmq_detailed_queue_messages_unacked{{{q}}})", "{{queue}}")]), 12, 8)
    L.add(timeseries("Delivery rate by queue", [prom(
        f'sum by (queue) (rate({{__name__=~"rabbitmq_detailed_queue_messages_delivered(_ack)?_total", {q}}}{rate}))', "{{queue}}")],
        axis_label="msg/s"), 8, 8)
    L.add(timeseries("Redelivery rate by queue", [prom(
        f"sum by (queue) (rate(rabbitmq_detailed_queue_messages_redelivered_total{{{q}}}{rate}))", "{{queue}}")],
        axis_label="msg/s", desc="A consumer is failing on this queue's messages (nack + requeue, or it crashed before acking)."), 8, 8)
    L.add(timeseries("Consumers by queue", [prom(f"sum by (queue) (rabbitmq_detailed_queue_consumers{{{q}}})", "{{queue}}")]), 8, 8)

    L.row("Exchanges")
    L.add(timeseries("Published by exchange", [prom(
        f'sum by (exchange) (rate(rabbitmq_detailed_exchange_messages_published_total{{{ex}, exchange!~"amq\\\\..*"}}{rate}))', "{{exchange}}")],
        axis_label="msg/s"), 12, 8)
    L.add(timeseries("Unroutable by exchange", [
        prom(f"sum by (exchange) (rate(rabbitmq_detailed_exchange_messages_unroutable_dropped_total{{{ex}}}{rate}))", "{{exchange}} dropped"),
        prom(f"sum by (exchange) (rate(rabbitmq_detailed_exchange_messages_unroutable_returned_total{{{ex}}}{rate}))", "{{exchange}} returned", ref="B"),
    ], axis_label="msg/s", desc="Messages that matched no binding: a missing queue/binding or a wrong routing key."), 12, 8)

    L.row("Broker resources")
    L.add(timeseries("Memory", [
        prom(f"sum by (k8s_pod_name) (rabbitmq_process_resident_memory_bytes{{{ns}}})", "{{k8s_pod_name}} used"),
        prom(f"sum by (k8s_pod_name) (rabbitmq_resident_memory_limit_bytes{{{ns}}})", "{{k8s_pod_name}} watermark", ref="B"),
    ], unit="bytes"), 8, 8)
    L.add(timeseries("Disk free", [
        prom(f"sum by (k8s_pod_name) (rabbitmq_disk_space_available_bytes{{{ns}}})", "{{k8s_pod_name}} free"),
        prom(f"sum by (k8s_pod_name) (rabbitmq_disk_space_available_limit_bytes{{{ns}}})", "{{k8s_pod_name}} alarm limit", ref="B"),
    ], unit="bytes"), 8, 8)
    L.add(timeseries("File descriptors and Erlang processes", [
        prom(f"sum by (k8s_pod_name) (rabbitmq_process_open_fds{{{ns}}}) / sum by (k8s_pod_name) (rabbitmq_process_max_fds{{{ns}}})", "{{k8s_pod_name}} fds"),
        prom(f"sum by (k8s_pod_name) (rabbitmq_erlang_processes_used{{{ns}}}) / sum by (k8s_pod_name) (rabbitmq_erlang_processes_limit{{{ns}}})", "{{k8s_pod_name}} processes", ref="B"),
    ], unit="percentunit"), 8, 8)

    L.row("Logs")
    L.add(logs("Broker logs", f'{{k8s_namespace_name=~"$namespace", k8s_container_name="rabbitmq"}}',
               desc="RabbitMQ container stdout, collected by the node agent."), 24, 12)

    variables = [
        query_var("namespace", "Namespace", "label_values(rabbitmq_identity_info, k8s_namespace_name)", all_value=".+"),
        query_var("vhost", "VHost", 'label_values(rabbitmq_detailed_queue_messages{k8s_namespace_name=~"$namespace"}, vhost)'),
        query_var("queue", "Queue", 'label_values(rabbitmq_detailed_queue_messages{k8s_namespace_name=~"$namespace", vhost=~"$vhost"}, queue)'),
    ]
    return dashboard("gaze-rabbitmq", "RabbitMQ",
                     "Brokers, throughput, per-queue and per-exchange metrics (rabbitmq_prometheus) and broker logs.",
                     ["gaze", "rabbitmq"], L.panels, variables)


DEFAULT_OUT = Path(__file__).resolve().parents[2] / "infra/k8s/platform/observability/dashboards"


# --------------------------------------------------------------------------
# Microservices: one dashboard per runtime (Java, Go, Python)
# --------------------------------------------------------------------------
# The three share one layout, so a service reads the same whatever it is written
# in: overview, startup, server, CPU and memory, runtime, database pool,
# RabbitMQ, logs. Only the metric names differ, because each runtime's
# instrumentation names them its own way.
SVC = 'service_name=~"$service", deployment_environment_name=~"$env"'
LOGS = '{service_name=~"$service", deployment_environment_name=~"$env"}'
# Kubernetes metrics come from the kubelet and k8s_cluster, not from the service:
# they carry the container name (the chart names it after the service) and the
# namespace (<service>-<env>), not service_name. Empty on the local compose stack.
K8S = 'k8s_container_name=~"$service", k8s_namespace_name=~".+-($env)"'
K8S_POD = 'k8s_pod_name=~"($service)-.+", k8s_namespace_name=~".+-($env)"'
RATE = "[$__rate_interval]"

# Pushed (OTLP) series get no staleness marker when their process dies: Prometheus
# keeps answering with the last sample for 5 minutes, so after a restart or a
# rolling deploy the old instance's gauges would be summed (and counted) next to
# the new one's. Every instant selector on SVC therefore drops the series that did
# not export within about two export intervals (60 s by default in every SDK).
# Range selectors (rate, increase) are left alone: a dead instance adds nothing to
# a rate.
FRESH_SECONDS = 150
_SVC_SELECTOR = re.compile(r"[a-zA-Z_:][\w:]*\{" + re.escape(SVC) + r"[^}]*\}(?!\[)")


def only_fresh(expr):
    return _SVC_SELECTOR.sub(lambda m: f"({m[0]} unless time() - timestamp({m[0]}) > {FRESH_SECONDS})", expr)
ERROR_LEVELS = 'detected_level=~"(?i)error|fatal|critical"'
PROBLEM_LEVELS = 'detected_level=~"(?i)error|fatal|critical|warn|warning"'
ERR_PCT = [{"color": "green", "value": None}, {"color": "orange", "value": 0.01}, {"color": "red", "value": 0.05}]
UP = [{"color": "red", "value": None}, {"color": "green", "value": 1}]


def custom_var(name, label, values):
    return {
        "type": "custom", "name": name, "label": label, "query": ",".join(values),
        "options": [{"text": v, "value": v, "selected": True} for v in values],
        "multi": True, "includeAll": False, "current": {"selected": True, "text": values, "value": values},
    }


def textbox_var(name, label):
    return {"type": "textbox", "name": name, "label": label, "query": "",
            "current": {"text": "", "value": ""}, "options": [{"selected": True, "text": "", "value": ""}]}


def quantiles(bucket, sel, by=""):
    """p50, p95 and p99 of a histogram, one series per value of by when it is given."""
    group = f"le, {by}" if by else "le"
    legend = f"{{{{{by}}}}} " if by else ""
    return [prom(f"histogram_quantile({q}, sum by ({group}) (rate({bucket}{{{sel}}}{RATE})))", f"{legend}p{int(q * 100)}", ref=ref)
            for q, ref in ((0.5, "A"), (0.95, "B"), (0.99, "C"))]


class Server:
    """A service's request histogram (HTTP or gRPC): rate, error ratio and latency."""

    def __init__(self, title, metric, unit, route, code, errors, extra=""):
        self.title, self.unit, self.route, self.code = title, unit, route, code
        self.count, self.bucket = f"{metric}_count", f"{metric}_bucket"
        self.sel = SVC + (", " + extra if extra else "")
        self.err_sel = f'{self.sel}, {code}=~"{errors}"'

    def rate(self, sel=None, by=""):
        group = f" by ({by})" if by else ""
        return f"sum{group} (rate({self.count}{{{sel or self.sel}}}{RATE}))"

    def error_ratio(self, by=""):
        # "or 0 *": without errors there is no error series; the ratio must read 0, not "No data"
        return f"({self.rate(self.err_sel, by)} or 0 * {self.rate(by=by)}) / {self.rate(by=by)}"

    def p99(self, by=""):
        group = f"le, {by}" if by else "le"
        return f"histogram_quantile(0.99, sum by ({group}) (rate({self.bucket}{{{self.sel}}}{RATE})))"


# A ratio or a quantile over zero requests is NaN, which the stat's lastNotNull
# skips, leaving the panel blank: say why instead.
NO_TRAFFIC = "no traffic"


def overview_row(L, server, cpu, memory, pool_used, pool_max, instance_metric):
    L.row("Overview")
    instances = f"count({instance_metric})"
    L.add(stat("Instances reporting", prom(f"{instances} or vector(0)", instant=True), thresholds=UP,
               desc="Instances that sent metrics in the last 2.5 minutes. 0 means the service is down or cannot reach the collector."), 3, 4)
    L.add(stat("Requests / s", prom(f"{server.rate()} or vector(0)", instant=True), unit="reqps", decimals=2,
               desc=f"{server.title} requests per second, health probes excluded."), 3, 4)
    L.add(stat("Error ratio", prom(f"{server.error_ratio()} or vector(0)", instant=True), unit="percentunit", thresholds=ERR_PCT,
               decimals=2, no_value=NO_TRAFFIC,
               desc="Share of requests that failed on the server side (HTTP 5xx, or the gRPC server error codes)."), 3, 4)
    L.add(stat("Latency p99", prom(server.p99(), instant=True), unit=server.unit, decimals=1, no_value=NO_TRAFFIC,
               desc="99% of the requests were faster than this."), 3, 4)
    for title, expr, unit, desc in (cpu, memory):
        L.add(stat(title, prom(expr, instant=True), unit=unit, thresholds=PCT if unit == "percentunit" else None, decimals=2, desc=desc), 3, 4)
    L.add(stat("DB pool in use", prom(f"sum({pool_used}) / sum({pool_max})", instant=True), unit="percentunit", thresholds=PCT,
               decimals=1, desc="Connections in use over the pool maximum. At 100% new operations wait for a connection."), 3, 4)
    L.add(stat("Error logs (5 m)", loki(f"sum(count_over_time({LOGS} | {ERROR_LEVELS} [5m])) or vector(0)", instant=True), ds=LOKI,
               thresholds=ORANGE, desc="Log lines at error level or above in the last 5 minutes."), 3, 4)


# The service's Kubernetes events: scheduling, image pulls, failed probes, BackOff,
# OOMKilled. The node agent leaves the services' stdout out of Loki on purpose
# (some loggers are stdout-only), so for a crash before OTLP logging starts these
# events are what Grafana shows; the output itself is in kubectl logs --previous.
K8S_EVENTS = '{service_name="k8s-events"} | k8s_namespace_name=~"($service)-($env)"'


def startup_row(L, marker, startup_lines, extra=()):
    """marker: regex of the line a service logs once it is up. startup_lines: regex of the
    lines that tell how a start went: configuration, connections, migrations, failures."""
    L.row("Startup and restarts")
    L.add(stat("Starts in range", loki(f"sum(count_over_time({LOGS} |~ `{marker}` [$__range])) or vector(0)", instant=True), ds=LOKI,
               thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 2}],
               desc="Times the service logged that it finished starting in the selected range. More than one per instance means it restarted."), 4, 4)
    L.add(stat("Container restarts", prom(f"sum(k8s_container_restarts{{{K8S}}})", instant=True), thresholds=ORANGE,
               desc="Restart count of the service's containers (Kubernetes). It grows on crashes, OOM kills and failed liveness probes."), 4, 4)
    # Red only for a real 0: outside Kubernetes the stat reads "No data" in neutral green
    L.add(stat("Containers ready", prom(f"sum(k8s_container_ready{{{K8S}}})", instant=True),
               mappings=[{"type": "value", "options": {"0": {"color": "red", "index": 0}}}],
               desc="Containers passing their readiness probe (Kubernetes)."), 4, 4)
    for panel in extra:
        L.add(panel, 4, 4)
    L.newline()
    L.add(timeseries("Starts over time", [loki(f"sum by (service_name) (count_over_time({LOGS} |~ `{marker}` [$__auto]))", legend="{{service_name}}")],
                     desc="Each point is a completed start: find when the service restarted, then read the startup log next to it."), 8, 10)
    L.add(logs("Startup log", f"{LOGS} |~ `{startup_lines}`",
               desc="The lines a start produces: configuration, connections, migrations, listeners, and the failure when it did not come up."), 16, 10)
    L.add(logs("Kubernetes events", K8S_EVENTS,
               desc="Events of the service's pods: scheduling, image pulls, failed probes, BackOff, OOMKilled. A crash before the service "
                    "could send its logs only shows up here; read its output with kubectl logs --previous."), 24, 8)


def server_row(L, server):
    L.row(f"{server.title} server")
    L.add(timeseries("Requests by status", [prom(server.rate(by=server.code), f"{{{{{server.code}}}}}")], unit="reqps", stack=True), 8, 8)
    L.add(timeseries("Error ratio", [prom(server.error_ratio(), "server errors")], unit="percentunit", soft_max=0.05,
                     desc="Share of requests that failed on the server side."), 8, 8)
    L.add(timeseries("Latency p50 / p95 / p99", quantiles(server.bucket, server.sel), unit=server.unit), 8, 8)
    by = server.route
    L.add(table("By route", [
        prom(server.rate(by=by), instant=True, fmt="table", ref="A"),
        prom(server.error_ratio(by), instant=True, fmt="table", ref="B"),
        prom(server.p99(by), instant=True, fmt="table", ref="C"),
    ], rename={by: "Route", "Value #A": "Requests / s", "Value #B": "Error ratio", "Value #C": f"p99 ({server.unit})"},
        sort_by="Requests / s", desc="Rate, error ratio and p99 of each route over the last minutes."), 14, 9)
    L.add(timeseries("Latency p99 by route", [prom(server.p99(by), f"{{{{{by}}}}}")], unit=server.unit), 10, 9)


def kubernetes_resources_row(L):
    L.row("CPU and memory of the containers (Kubernetes)")
    L.add(timeseries("CPU", [
        prom(f"sum by (k8s_pod_name) (container_cpu_usage{{{K8S}}})", "{{k8s_pod_name}}"),
        prom(f"max(k8s_container_cpu_request{{{K8S}}})", "request", ref="B"),
        prom(f"max(k8s_container_cpu_limit{{{K8S}}})", "limit", ref="C"),
    ], axis_label="cores", desc="CPU cores each pod uses, with the container's request and limit. At the limit the container is throttled."), 8, 8)
    L.add(timeseries("Memory", [
        prom(f"sum by (k8s_pod_name) (container_memory_working_set_bytes{{{K8S}}})", "{{k8s_pod_name}}"),
        prom(f"max(k8s_container_memory_request_bytes{{{K8S}}})", "request", ref="B"),
        prom(f"max(k8s_container_memory_limit_bytes{{{K8S}}})", "limit", ref="C"),
    ], unit="bytes", desc="Working set of each pod: what the OOM killer compares with the limit."), 8, 8)
    L.add(timeseries("Restarts (10 m)", [prom(f"sum by (k8s_pod_name) (delta(k8s_container_restarts{{{K8S}}}[10m]))", "{{k8s_pod_name}}")]), 4, 8)
    L.add(timeseries("Network", [
        prom(f'sum(rate(k8s_pod_network_io_bytes_total{{{K8S_POD}, direction="receive"}}{RATE}))', "receive"),
        prom(f'-sum(rate(k8s_pod_network_io_bytes_total{{{K8S_POD}, direction="transmit"}}{RATE}))', "transmit", ref="B"),
    ], unit="Bps", desc="Receive above zero, transmit below."), 4, 8)


def pool_row(L, title, used, idle, maximum, pending=None, wait_bucket=None, wait_unit="s", timeouts=None, extra=()):
    L.row(title)
    targets = [prom(f"sum({used})", "in use"), prom(f"sum({idle})", "idle", ref="B"), prom(f"sum({maximum})", "max", ref="C")]
    if pending:
        targets.append(prom(f"sum({pending})", "waiting operations", ref="D"))
    L.add(timeseries("Connections", targets, desc="In use, idle and the pool maximum. Waiting operations mean the pool is exhausted."), 8, 8)
    L.add(timeseries("Pool in use", [prom(f"sum({used}) / sum({maximum})", "in use / max")], unit="percentunit",
                     desc="At 100% every new operation waits for a connection."), 4 if wait_bucket else 16, 8)
    if wait_bucket:
        L.add(timeseries("Wait for a connection p50 / p95 / p99", quantiles(wait_bucket, SVC), unit=wait_unit,
                         desc="How long an operation waited to get a connection. It should stay near zero."), 8, 8)
    if timeouts:
        L.add(timeseries("Connection timeouts", [prom(f"sum(increase({timeouts}{{{SVC}}}{RATE})) or vector(0)", "timeouts")],
                         desc="Operations that gave up waiting for a connection."), 4, 8)
    for panel, w in extra:
        L.add(panel, w, 8)


def consumers_row(L, metric, queue_label, failed, unit="s"):
    """A consumer timer with one measurement per message: the metric, its queue label and
    the label matcher of a failed message."""
    L.row("RabbitMQ consumers")
    legend = "{{" + queue_label + "}}"
    L.add(timeseries("Messages processed by queue", [prom(f"sum by ({queue_label}) (rate({metric}_count{{{SVC}}}{RATE}))", legend)], unit="ops"), 8, 8)
    L.add(timeseries("Failed messages by queue", [prom(f'sum by ({queue_label}) (rate({metric}_count{{{SVC}, {failed}}}{RATE}))', legend)],
                     unit="ops", desc="Messages whose handling failed after the retries: they went to the queue's DLQ."), 8, 8)
    L.add(timeseries("Processing time p50 / p95 / p99", quantiles(f"{metric}_bucket", SVC), unit=unit,
                     desc="Time to handle a message, retries included."), 8, 8)


def logs_row(L, by_message=False):
    """by_message groups the error table by message instead of logger: Go's slog
    bridge gives every line the same scope, so the logger says nothing there."""
    L.row("Logs")
    L.add(timeseries("Log lines by level", [loki(f"sum by (detected_level) (count_over_time({LOGS} [$__auto]))", legend="{{detected_level}}")],
                     stack=True, desc="Log volume by level. A jump in error or warn is usually the first sign of a problem."), 12, 8)
    if by_message:
        L.add(table("Most frequent errors", [loki(
            f"topk(10, sum by (error_message) (count_over_time({LOGS} | {ERROR_LEVELS}"
            f" | regexp `^(?P<error_message>.{{1,100}})` [$__range])))", instant=True)],
            rename={"error_message": "Message", "Value #A": "Errors"}, sort_by="Errors",
            desc="Error messages (first 100 characters) by how often they were logged in the selected range."), 12, 8)
    else:
        L.add(table("Loggers with most errors", [loki(
            f"topk(10, sum by (scope_name) (count_over_time({LOGS} | {ERROR_LEVELS} [$__range])))", instant=True)],
            rename={"scope_name": "Logger", "Value #A": "Errors"}, sort_by="Errors",
            desc="Which class or package logged the errors in the selected range."), 12, 8)
    L.add(logs("Errors and warnings", f"{LOGS} | {PROBLEM_LEVELS}",
               desc="Warn level and above. Open a line to see its trace id and jump to the trace in Tempo."), 24, 10)
    L.add(logs("All logs (filter with Log search at the top)", f"{LOGS} |~ `(?i)$search`",
               desc="Every log line of the service. Type in Log search to filter; the match is case-insensitive."), 24, 12)


def service_dashboard(uid, title, desc, services, build):
    L = Layout()
    build(L)
    variables = [custom_var("service", "Service", services),
                 query_var("env", "Environment", "label_values(target_info, deployment_environment_name)"),
                 textbox_var("search", "Log search")]
    d = dashboard(uid, title, desc, ["gaze", "service"], L.panels, variables)
    d["time"] = {"from": "now-1h", "to": "now"}
    return d


# ---- Java: Spring Boot, Micrometer over OTLP -------------------------------
def java_services():
    server = Server("HTTP", "http_server_requests_milliseconds", "ms", "uri", "status", "5..", 'uri!~"/actuator.*"')
    hikari = lambda name: f"hikaricp_connections_{name}{{{SVC}}}"  # noqa: E731
    rabbit = lambda name: f"sum(rate(rabbitmq_{name}_total{{{SVC}}}{RATE}))"  # noqa: E731

    def build(L):
        overview_row(
            L, server,
            cpu=("Process CPU", f"max(process_cpu_usage{{{SVC}}})", "percentunit", "CPU used by the JVM, as a share of the CPUs it can use."),
            memory=("Heap used", f'sum(jvm_memory_used_bytes{{{SVC}, area="heap"}}) / sum(jvm_memory_max_bytes{{{SVC}, area="heap"}} > 0)',
                    "percentunit", "Heap in use over the maximum heap. It rises and falls with GC; a floor that keeps rising is a leak."),
            pool_used=hikari("active"), pool_max=hikari("max"), instance_metric=f"process_uptime_milliseconds{{{SVC}}}")
        startup_row(
            L, marker=r"Started \w+Application in",
            startup_lines=r"Starting \w+Application|Started \w+Application|profile is active|profiles are active|HikariPool-\d+ - (Start|Shutdown)"
                          r"|Tomcat (initialized|started)|[Mm]igrat|APPLICATION FAILED TO START|Application run failed|Error starting"
                          r"|[Gg]raceful shutdown|Commencing graceful",
            extra=(stat("Time to ready", prom(f"max(application_ready_time_milliseconds{{{SVC}}})", instant=True), unit="ms",
                        desc="How long the last start took until the service could take traffic."),
                   stat("Uptime", prom(f"min(process_uptime_milliseconds{{{SVC}}})", instant=True), unit="ms",
                        desc="Time since the JVM started (the youngest instance).")))
        server_row(L, server)
        kubernetes_resources_row(L)

        L.row("JVM")
        L.add(timeseries("CPU", [prom(f"max by (service_name) (process_cpu_usage{{{SVC}}})", "{{service_name}} JVM"),
                                 prom(f"max by (service_name) (system_cpu_usage{{{SVC}}})", "{{service_name}} container", ref="B")],
                         unit="percentunit", desc="CPU of the JVM and of its whole container, as a share of the CPUs available."), 8, 8)
        L.add(timeseries("Heap", [
            prom(f'sum by (service_name) (jvm_memory_used_bytes{{{SVC}, area="heap"}})', "{{service_name}} used"),
            prom(f'sum by (service_name) (jvm_memory_committed_bytes{{{SVC}, area="heap"}})', "{{service_name}} committed", ref="B"),
            prom(f'sum by (service_name) (jvm_memory_max_bytes{{{SVC}, area="heap"}} > 0)', "{{service_name}} max", ref="C"),
        ], unit="bytes"), 8, 8)
        L.add(timeseries("Non-heap by pool", [prom(f'sum by (id) (jvm_memory_used_bytes{{{SVC}, area="nonheap"}})', "{{id}}")],
                         unit="bytes", stack=True, desc="Metaspace, code cache and class space."), 8, 8)
        L.add(timeseries("GC pauses", [
            prom(f"histogram_quantile(0.99, sum by (le) (rate(jvm_gc_pause_milliseconds_bucket{{{SVC}}}{RATE})))", "p99"),
            prom(f"sum by (gc) (rate(jvm_gc_pause_milliseconds_sum{{{SVC}}}{RATE})) / sum by (gc) (rate(jvm_gc_pause_milliseconds_count{{{SVC}}}{RATE}))",
                 "avg {{gc}}", ref="B"),
        ], unit="ms", desc="Stop-the-world time of the garbage collections."), 6, 8)
        L.add(timeseries("GC overhead", [prom(f"max by (service_name) (jvm_gc_overhead{{{SVC}}})", "{{service_name}}")], unit="percentunit",
                         desc="Share of CPU time spent in GC. Above 10% for long, the heap is too small."), 6, 8)
        L.add(timeseries("Threads by state", [prom(f"sum by (state) (jvm_threads_states{{{SVC}}})", "{{state}}")], stack=True,
                         desc="Many blocked or waiting threads point to a lock or a slow dependency."), 6, 8)
        L.add(timeseries("Open files", [prom(f"sum by (service_name) (process_files_open{{{SVC}}})", "{{service_name}} open"),
                                        prom(f"max(process_files_max{{{SVC}}})", "limit", ref="B")]), 6, 8)

        pool_row(
            L, "Database connection pool (HikariCP)", used=hikari("active"), idle=hikari("idle"), maximum=hikari("max"),
            pending=hikari("pending"), wait_bucket="hikaricp_connections_acquire_milliseconds_bucket", wait_unit="ms",
            timeouts="hikaricp_connections_timeout_total",
            extra=((timeseries("Time a connection is held p50 / p95 / p99", quantiles("hikaricp_connections_usage_milliseconds_bucket", SVC),
                               unit="ms", desc="How long the code keeps a connection before returning it. Long holds exhaust the pool."), 12),
                   (timeseries("Repository calls p99", [prom(
                       f"histogram_quantile(0.99, sum by (le, repository) (rate(spring_data_repository_invocations_milliseconds_bucket{{{SVC}}}{RATE})))",
                       "{{repository}}")], unit="ms", desc="The slowest Spring Data repositories."), 12)))

        L.row("RabbitMQ client")
        L.add(timeseries("Messages", [prom(rabbit("consumed"), "consumed"), prom(rabbit("published"), "published", ref="B"),
                                      prom(rabbit("acknowledged"), "acknowledged", ref="C")], unit="ops"), 8, 8)
        L.add(timeseries("Failures", [prom(rabbit("rejected"), "rejected (to DLQ)"), prom(rabbit("failed_to_publish"), "failed to publish", ref="B"),
                                      prom(rabbit("unrouted_published"), "unroutable", ref="C"),
                                      prom(rabbit("not_acknowledged_published"), "nacked by the broker", ref="D")], unit="ops",
                         desc="Rejected messages went to their DLQ. Failed or nacked publishes stay in the outbox and are retried."), 8, 8)
        L.add(timeseries("Connections and channels", [prom(f"sum(rabbitmq_connections{{{SVC}}})", "connections"),
                                                      prom(f"sum(rabbitmq_channels{{{SVC}}})", "channels", ref="B")]), 8, 8)
        consumers_row(L, "spring_rabbitmq_listener_milliseconds", "queue", 'result="failure"', unit="ms")
        logs_row(L)

    return service_dashboard("gaze-services-java", "Services: Java",
                             "post-command-service and social-service (Spring Boot, Micrometer over OTLP). Pick the service at the top.",
                             ["post-command-service", "social-service"], build)


# ---- Go: OpenTelemetry Go SDK -----------------------------------------------
def go_services():
    server = Server("HTTP", "http_server_request_duration_seconds", "s", "http_route", "http_response_status_code", "5..")
    conns = lambda state: f'db_client_connection_count{{{SVC}, db_client_connection_state="{state}"}}'  # noqa: E731

    def build(L):
        overview_row(
            L, server,
            cpu=("CPU / limit", f"sum(container_cpu_usage{{{K8S}}}) / sum(k8s_container_cpu_limit{{{K8S}}} > 0)", "percentunit",
                 "CPU used over the container's CPU limit (Kubernetes): the Go runtime does not report CPU itself."),
            memory=("Runtime memory", f"sum(go_memory_used_bytes{{{SVC}}})", "bytes",
                    "Memory the Go runtime uses: heap, stacks and runtime structures."),
            pool_used=conns("used"), pool_max=f"db_client_connection_max{{{SVC}}}", instance_metric=f"go_goroutine_count{{{SVC}}}")
        startup_row(
            L, marker="HTTP server listening",
            startup_lines="telemetry initialized|connected to mongodb|consuming rabbitmq events|HTTP server listening"
                          "|application failed|shutting down|shutdown|bootstrap rabbitmq")
        server_row(L, server)
        kubernetes_resources_row(L)

        L.row("Go runtime")
        L.add(timeseries("Memory by type", [prom(f"sum by (go_memory_type) (go_memory_used_bytes{{{SVC}}})", "{{go_memory_type}}"),
                                            prom(f"sum(go_memory_gc_goal_bytes{{{SVC}}})", "GC goal", ref="B")], unit="bytes",
                         desc="Memory in use by type. The next garbage collection starts when the heap reaches the GC goal."), 8, 8)
        L.add(timeseries("Goroutines", [prom(f"sum(go_goroutine_count{{{SVC}}})", "goroutines")],
                         desc="A count that only grows is a goroutine leak."), 8, 8)
        L.add(timeseries("Allocation rate", [prom(f"sum(rate(go_memory_allocated_bytes_total{{{SVC}}}{RATE}))", "allocated")], unit="Bps",
                         desc="Heap bytes allocated per second: the work handed to the garbage collector."), 8, 8)

        pool_row(L, "Database connection pool (MongoDB)", used=conns("used"), idle=conns("idle"),
                 maximum=f"db_client_connection_max{{{SVC}}}", pending=f"db_client_connection_pending_requests{{{SVC}}}",
                 wait_bucket="db_client_connection_wait_time_seconds_bucket", timeouts="db_client_connection_timeouts_total")
        consumers_row(L, "messaging_process_duration_seconds", "messaging_destination_name", 'error_type!=""')
        logs_row(L, by_message=True)

    return service_dashboard("gaze-services-go", "Services: Go", "post-query-service (OpenTelemetry Go SDK).", ["post-query-service"], build)


# ---- Python: opentelemetry-instrument, plus our gRPC and pool instruments ---
def python_services():
    # Server-side gRPC codes: the caller's own mistakes (INVALID_ARGUMENT, NOT_FOUND, ...) are not errors of the service
    server = Server("gRPC", "rpc_server_call_duration_seconds", "s", "rpc_method", "rpc_grpc_status_code",
                    "UNKNOWN|DEADLINE_EXCEEDED|RESOURCE_EXHAUSTED|UNIMPLEMENTED|INTERNAL|UNAVAILABLE|DATA_LOSS")
    conns = lambda state: f'db_client_connection_count{{{SVC}, db_client_connection_state="{state}"}}'  # noqa: E731

    def build(L):
        overview_row(
            L, server,
            cpu=("Process CPU", f"sum(process_cpu_utilization_ratio{{{SVC}}})", "percentunit",
                 "CPU used by the Python process (user plus system), as a share of the CPUs available."),
            memory=("Process memory", f"sum(process_memory_usage_bytes{{{SVC}}})", "bytes",
                    "Resident memory of the service, the embedding model included (plus a few MiB for multiprocessing's"
                    " resource tracker, a child process that reports its own process metrics)."),
            # Not a process.* metric: the resource tracker child reports those too, under
            # its own instance id. Only the main process has the database pool.
            pool_used=conns("used"), pool_max=f"db_client_connection_max{{{SVC}}}", instance_metric=f"db_client_connection_max{{{SVC}}}")
        startup_row(
            L, marker="gRPC server started",
            startup_lines="gRPC server (started|stopped)|Invalid LOG_LEVEL|[Oo]utbox relay|Traceback|failed|[Ee]rror")
        server_row(L, server)
        kubernetes_resources_row(L)

        L.row("Python process")
        L.add(timeseries("CPU by mode", [prom(f"sum by (type) (rate(process_cpu_time_seconds_total{{{SVC}}}{RATE}))", "{{type}}")],
                         axis_label="cores", stack=True, desc="CPU seconds per second in user and system mode."), 8, 8)
        L.add(timeseries("Memory", [prom(f"sum(process_memory_usage_bytes{{{SVC}}})", "resident"),
                                    prom(f"sum(process_memory_virtual_bytes{{{SVC}}})", "virtual", ref="B")], unit="bytes",
                         desc="Resident memory is what counts against the container limit. Includes multiprocessing's resource"
                              " tracker, a small child process."), 8, 8)
        L.add(timeseries("Threads and open files", [prom(f"sum(process_thread_count{{{SVC}}})", "threads"),
                                                    prom(f"sum(process_open_file_descriptor_count{{{SVC}}})", "open files", ref="B")]), 8, 8)
        L.add(timeseries("GC collections by generation", [prom(f"sum by (cpython_gc_generation) (rate(cpython_gc_collections_total{{{SVC}}}{RATE}))",
                                                               "gen {{cpython_gc_generation}}")], unit="ops"), 12, 8)
        L.add(timeseries("Context switches", [prom(f"sum by (type) (rate(process_context_switches_total{{{SVC}}}{RATE}))",
                                                   "{{type}}")], unit="ops",
                         desc="Many involuntary switches mean the process is short of CPU."), 12, 8)

        pool_row(L, "Database connection pool (SQLAlchemy)", used=conns("used"), idle=conns("idle"),
                 maximum=f"db_client_connection_max{{{SVC}}}")
        consumers_row(L, "messaging_process_duration_seconds", "messaging_destination_publish_name", 'error_type!=""')
        logs_row(L)

    return service_dashboard("gaze-services-python", "Services: Python",
                             "recommendation-service (opentelemetry-instrument; gRPC and pool metrics from src/).",
                             ["recommendation-service"], build)


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    out.mkdir(parents=True, exist_ok=True)
    for name, d in (("kubernetes-cluster.json", kubernetes()), ("rabbitmq.json", rabbitmq()),
                    ("services-java.json", java_services()), ("services-go.json", go_services()),
                    ("services-python.json", python_services())):
        (out / name).write_text(json.dumps(d, indent=2) + "\n")


if __name__ == "__main__":
    main()
