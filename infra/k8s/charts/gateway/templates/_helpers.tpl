{{- define "gateway.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: gaze
gaze.app/env: {{ .Values.environment }}
{{- end }}

{{- define "gateway.targetRef" -}}
targetRefs:
  - group: gateway.networking.k8s.io
    kind: Gateway
    name: {{ .Values.gatewayName }}
{{- end }}
