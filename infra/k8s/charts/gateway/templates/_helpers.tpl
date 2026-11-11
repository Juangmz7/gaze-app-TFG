{{- define "gateway.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: gaze
gaze.app/env: {{ .Values.environment }}
{{- end }}

{{/* True ("true") when any OTLP export to the collector is enabled. */}}
{{- define "gateway.otlpEnabled" -}}
{{- $t := .Values.telemetry -}}
{{- if and $t.enabled (or $t.tracing.enabled $t.metrics.enabled $t.accessLog.otel.enabled) -}}
true
{{- end -}}
{{- end }}

{{/* OTLP resource attributes shared by metrics and access logs. */}}
{{- define "gateway.otlpResourceAttributes" -}}
service.name: {{ .Values.telemetry.serviceName | quote }}
deployment.environment.name: {{ .Values.environment | quote }}
{{- end }}

{{- define "gateway.targetRef" -}}
targetRefs:
  - group: gateway.networking.k8s.io
    kind: Gateway
    name: {{ .Values.gatewayName }}
{{- end }}
