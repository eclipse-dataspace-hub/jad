#!/usr/bin/env bash
#
#  Copyright (c) 2026 Metaform Systems, Inc.
#
#  This program and the accompanying materials are made available under the
#  terms of the Apache License, Version 2.0 which is available at
#  https://www.apache.org/licenses/LICENSE-2.0
#
#  SPDX-License-Identifier: Apache-2.0
#
#  Contributors:
#       Metaform Systems, Inc. - initial API and implementation
#
#

#
# Mint a Kubernetes SA token, exchange it (RFC 8693) at the IDP, print the
# resulting access token to stdout.
#
# Usage:
#   KUBE_SA=my-sa KUBE_NS=my-ns KUBE_AUDIENCE=<idp-subject-audience> \
#   IDP_TOKEN_ENDPOINT=https://idp.example.com/oauth2/token \
#   IDP_AUDIENCE=<audience-the-ingress-expects> \
#   ./get-access-token.sh
#
#   # capture it:
#   TOKEN="$(./get-access-token.sh)"
#   curl -H "Authorization: Bearer $TOKEN" https://your-ingress/...
#
set -euo pipefail

# ---- config (override via env) -------------------------------------------
: "${KUBE_SA:=redline}"
: "${KUBE_NS:=edc-v}"
: "${KUBE_AUDIENCE:=https://kubernetes.default.svc.cluster.local}"               # audience the IDP expects on the subject token
: "${KUBE_TOKEN_DURATION:=1800s}"

: "${IDP_TOKEN_ENDPOINT:=http://jad.localhost/api/auth/token}"
: "${IDP_AUDIENCE:=edcv}" # audience the ingress expects
: "${IDP_SCOPE:=admin cfm-write cfm-read read write}"

# ---- 1. mint the SA subject token ----------------------------------------
kubectl_args=(create token "$KUBE_SA" -n "$KUBE_NS" --duration "$KUBE_TOKEN_DURATION")
if [ -n "$KUBE_AUDIENCE" ]; then kubectl_args+=(--audience "$KUBE_AUDIENCE"); fi

SUBJECT_TOKEN="$(kubectl "${kubectl_args[@]}")"

# ---- 2. RFC 8693 token exchange ------------------------------------------
curl_args=(
  --silent --show-error --fail-with-body
  --request POST "$IDP_TOKEN_ENDPOINT"
  --header "Content-Type: application/x-www-form-urlencoded"
  --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
  --data-urlencode "subject_token=${SUBJECT_TOKEN}"
  --data-urlencode "subject_token_type=urn:ietf:params:oauth:token-type:jwt"
  --data-urlencode "resource=redline"
  --data-urlencode "audience=${IDP_AUDIENCE}"
)
if [ -n "$IDP_SCOPE" ]; then curl_args+=(--data-urlencode "scope=${IDP_SCOPE}"); fi

RESPONSE="$(curl "${curl_args[@]}")"

# ---- 3. extract + print access_token -------------------------------------
if command -v jq >/dev/null 2>&1; then
  ACCESS_TOKEN="$(printf '%s' "$RESPONSE" | jq -r '.access_token // empty')"
else
  # No jq: a JWT value contains only URL-safe base64 chars, so this is safe.
  ACCESS_TOKEN="$(printf '%s' "$RESPONSE" \
    | grep -o '"access_token"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | head -n1 \
    | sed 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')"
fi

if [ -z "$ACCESS_TOKEN" ]; then
  echo "error: no access_token in IDP response:" >&2
  printf '%s\n' "$RESPONSE" >&2
  exit 1
fi

printf '%s\n' "$ACCESS_TOKEN"