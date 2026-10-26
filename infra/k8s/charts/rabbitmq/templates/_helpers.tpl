{{- define "rabbitmq.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: gaze
gaze.app/env: {{ .Values.environment }}
{{- end }}

{{- define "rabbitmq.clusterRef" -}}
rabbitmqClusterReference:
  name: {{ .Values.clusterName }}
{{- end }}
