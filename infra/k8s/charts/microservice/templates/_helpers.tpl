{{/*
Chart name, truncated to the 63 chars DNS label limit.
*/}}
{{- define "microservice.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified name. The release name is the service name (set by the Argo
CD ApplicationSet), so resources are named after the service.
*/}}
{{- define "microservice.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "microservice.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Selector labels: immutable once the Deployment exists.
*/}}
{{- define "microservice.selectorLabels" -}}
app.kubernetes.io/name: {{ include "microservice.fullname" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "microservice.labels" -}}
helm.sh/chart: {{ include "microservice.chart" . }}
{{ include "microservice.selectorLabels" . }}
{{- /* Image Updater sets the tag to latest@sha256:<digest>; labels can't hold the digest. */}}
app.kubernetes.io/version: {{ splitList "@" .Values.image.tag | first | trunc 63 | trimSuffix "-" | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: gaze
gaze.app/env: {{ .Values.environment }}
{{- end }}

{{- define "microservice.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "microservice.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Secret holding the sensitive env vars: either the rendered one or an
existing one (External Secrets / Vault).
*/}}
{{- define "microservice.secretName" -}}
{{- default (include "microservice.fullname" .) .Values.existingSecret }}
{{- end }}

{{- define "microservice.hasSecret" -}}
{{- if or .Values.existingSecret .Values.secretEnv }}true{{- end }}
{{- end }}

{{- define "microservice.image" -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag }}
{{- end }}

{{/*
envFrom block shared by the Deployment and the migration Job.
*/}}
{{- define "microservice.envFrom" -}}
{{- if .Values.env }}
- configMapRef:
    name: {{ include "microservice.fullname" . }}
{{- end }}
{{- if include "microservice.hasSecret" . }}
- secretRef:
    name: {{ include "microservice.secretName" . }}
{{- end }}
{{- end }}

{{/*
Volumes backing writableDirs.
*/}}
{{- define "microservice.volumes" -}}
{{- range $i, $dir := .Values.writableDirs }}
- name: writable-{{ $i }}
  emptyDir: {}
{{- end }}
{{- end }}

{{- define "microservice.volumeMounts" -}}
{{- range $i, $dir := .Values.writableDirs }}
- name: writable-{{ $i }}
  mountPath: {{ $dir }}
{{- end }}
{{- end }}
