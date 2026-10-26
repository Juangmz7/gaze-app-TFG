{{- define "keycloak.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: gaze
gaze.app/env: {{ .Values.environment }}
{{- end }}

{{- define "keycloak.operatorLabels" -}}
app.kubernetes.io/name: keycloak-operator
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
