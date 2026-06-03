{{- define "protogemcouch.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "protogemcouch.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "protogemcouch.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "protogemcouch.labels" -}}
app.kubernetes.io/name: {{ include "protogemcouch.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "protogemcouch.selectorLabels" -}}
app.kubernetes.io/name: {{ include "protogemcouch.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Name of the Secret holding cb-username / cb-password (existing or chart-created). */}}
{{- define "protogemcouch.secretName" -}}
{{- if .Values.couchbase.existingSecret -}}
{{- .Values.couchbase.existingSecret -}}
{{- else -}}
{{- printf "%s-couchbase" (include "protogemcouch.fullname" .) -}}
{{- end -}}
{{- end -}}
