{{/*
Copyright (c) 2025 Metaform Systems, Inc.
SPDX-License-Identifier: Apache-2.0

Helper names/signatures mirror the core-platform-distribution chart so the seeding
script blocks are identical across both charts.
*/}}

{{- define "cpd.namespace" -}}
{{- .Values.global.namespace | default .Release.Namespace -}}
{{- end -}}

{{- define "cpd.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* In-cluster FQDN builder. Usage: {{ include "cpd.fqdn" (dict "svc" "issuerservice" "ctx" $) }} */}}
{{- define "cpd.fqdn" -}}
{{- printf "%s.%s.%s" .svc (include "cpd.namespace" .ctx) .ctx.Values.global.clusterDomain -}}
{{- end -}}

{{/* Trusted-issuer DID (URL-encoded port, e.g. did:web:<host>%3A10016:issuer). */}}
{{- define "cpd.issuerDid" -}}
{{- printf "did:web:%s%%3A10016:issuer" (include "cpd.fqdn" (dict "svc" "issuerservice" "ctx" .)) -}}
{{- end -}}

{{- define "cpd.labels" -}}
helm.sh/chart: {{ include "cpd.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
platform: edcv
{{- end -}}

{{/* Projected jwtlet subject-token volume (RFC 8693 subject_token). */}}
{{- define "cpd.jwtletSubjectTokenVolume" -}}
- name: jwtlet-subject-token
  projected:
    sources:
      - serviceAccountToken:
          path: token
          audience: {{ .Values.global.jwtSubjectTokenAudience | quote }}
          expirationSeconds: {{ .Values.global.jwtSubjectTokenExpirationSeconds }}
{{- end -}}
