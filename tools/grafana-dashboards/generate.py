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
        self.panels.append(panel)
        self.x += w
        self.row_h = max(self.row_h, h)


def prom(expr, legend="", instant=False, fmt=None, ref="A"):
    t = {"datasource": PROM, "expr": expr, "refId": ref, "legendFormat": legend or "__auto"}
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


def stat(title, target, unit="short", thresholds=None, desc="", decimals=None, ds=PROM):
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
    return p


def timeseries(title, targets, unit="short", desc="", stack=False, axis_label=""):
    custom = {"drawStyle": "line", "lineWidth": 1, "fillOpacity": 15 if stack else 5,
              "showPoints": "never", "spanNulls": True, "axisLabel": axis_label,
              "stacking": {"mode": "normal" if stack else "none", "group": "A"}}
    return {
        "type": "timeseries", "title": title, "description": desc, "datasource": targets[0]["datasource"],
        "targets": targets,
        "fieldConfig": {"defaults": {"unit": unit, "custom": custom, "color": {"mode": "palette-classic"}},
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


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    out.mkdir(parents=True, exist_ok=True)
    for name, d in (("kubernetes-cluster.json", kubernetes()), ("rabbitmq.json", rabbitmq())):
        (out / name).write_text(json.dumps(d, indent=2) + "\n")


if __name__ == "__main__":
    main()
