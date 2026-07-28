{{/*
Copyright (c) 2026 Metaform Systems, Inc.
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

{{/* Trusted-issuer DID, taken verbatim from `issuer.did`.

     It MUST equal the DID the platform actually minted for the issuer participant context: it
     is written into the dataspace profile's credentialSpecs, and every issued credential is
     verified against it. A mismatch surfaces only at credential verification, far from the
     cause, so this is deliberately a plain configured value rather than something derived —
     core-platform-distribution builds the DID from its own settings (normally
     did:web:issuer.<global.host>:issuer, or pinned via edc.issuerservice.did.id), and any
     second copy of that rule silently drifts when the platform is reconfigured.

     Read the live value off the deployment with either of:
       kubectl -n <ns> get httproute issuerservice-did -o jsonpath='{.spec.hostnames[0]}'
       helm -n <ns> get values core-platform */}}
{{- define "cpd.issuerDid" -}}
{{- required "issuer.did must be set to the platform's issuer DID (see values.yaml)" .Values.issuer.did -}}
{{- end -}}

{{/* Prefix for participant DIDs (the participant name is appended). Participants are minted
     under the platform IdentityHub's did:web hostname, identity.<global.host> — IdentityHub
     resolves a DID document by the request URL, so a DID under any other authority will not
     resolve through the gateway. Override with `participants.didPrefix` when the platform uses
     a custom edc.identityhub.did.host. */}}
{{- define "jad.participantDidPrefix" -}}
{{- .Values.participants.didPrefix | default (printf "did:web:identity.%s:" .Values.global.host) -}}
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

{{/* -------------------------------------------------------------------------
     Infra service hosts. `connection.host` wins; otherwise derive the
     community sub-chart service name (<release>-postgresql / -vault / -nats).
     NOTE: this chart is a separate release from the platform, so
     `connection.host` MUST be set explicitly in values.yaml to point at the
     platform release's services rather than derived from this release's name.
     ------------------------------------------------------------------------- */}}
{{- define "cpd.pgHost" -}}
{{- $c := .Values.postgresql.connection -}}
{{- if $c.host -}}{{ $c.host -}}
{{- else -}}{{ printf "%s-postgresql.%s.%s" .Release.Name (include "cpd.namespace" .) .Values.global.clusterDomain -}}{{- end -}}
{{- end -}}

{{- define "cpd.vaultHost" -}}
{{- $c := .Values.vault.connection -}}
{{- if $c.host -}}{{ $c.host -}}
{{- else -}}{{ printf "%s-vault.%s.%s" .Release.Name (include "cpd.namespace" .) .Values.global.clusterDomain -}}{{- end -}}
{{- end -}}

{{- define "cpd.natsHost" -}}
{{- $c := .Values.nats.connection -}}
{{- if $c.host -}}{{ $c.host -}}
{{- else -}}{{ printf "%s-nats.%s.%s" .Release.Name (include "cpd.namespace" .) .Values.global.clusterDomain -}}{{- end -}}
{{- end -}}

{{/* Convenience URL builders reused across configs. */}}
{{- define "cpd.vaultUrl" -}}
{{- printf "%s://%s:%v" .Values.vault.connection.scheme (include "cpd.vaultHost" .) .Values.vault.connection.port -}}
{{- end -}}

{{- define "cpd.natsUrl" -}}
{{- printf "nats://%s:%v" (include "cpd.natsHost" .) .Values.nats.connection.port -}}
{{- end -}}

{{/* -------------------------------------------------------------------------
     Image reference.
     Usage: {{ include "cpd.image" (dict "img" .Values.edc.dataplane.image "ctx" $) }}
     A repo that already contains a registry host (has a dot or colon before the
     first slash) is used verbatim; otherwise imageRegistry is prepended.
     ------------------------------------------------------------------------- */}}
{{- define "cpd.image" -}}
{{- $repo := .img.repo -}}
{{- $tag := .img.tag | default "latest" -}}
{{- $first := splitList "/" $repo | first -}}
{{- if or (contains "." $first) (contains ":" $first) -}}
{{- printf "%s:%s" $repo $tag -}}
{{- else -}}
{{- printf "%s/%s:%s" .ctx.Values.imageRegistry $repo $tag -}}
{{- end -}}
{{- end -}}

{{/* Effective imagePullPolicy: per-component override when set, else global default. */}}
{{- define "cpd.pullPolicy" -}}
{{- .policy | default .ctx.Values.global.imagePullPolicy -}}
{{- end -}}

{{/* -------------------------------------------------------------------------
     envFrom for a standard app: its own config ConfigMap + telemetry-config.
     telemetry-config is provisioned by the platform chart in the same namespace.
     Usage: {{ include "cpd.appEnvFrom" (dict "config" "dataplane-config" "ctx" $) | nindent 12 }}
     ------------------------------------------------------------------------- */}}
{{- define "cpd.appEnvFrom" -}}
- configMapRef:
    name: {{ .config }}
{{- if .ctx.Values.telemetry.configMapEnabled }}
- configMapRef:
    name: telemetry-config
{{- end -}}
{{- end -}}
