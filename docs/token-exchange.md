# Service-to-service authentication: token exchange with jwtlet

> **Note:** JAD now deploys via Helm rather than raw Kubernetes manifests. The manifests this document references have
> moved into the **Core Platform Distribution** chart; paths shown below as `templates/…` are relative to that chart
> (see the [decision record](https://github.com/eclipse-cfm/.github/blob/main/docs/developer/decision-records/2026-07-01-core_platform_distro/README.md)).
> The dataspace-specific issuer credential seeding lives in this repo under `charts/jad-dataspace-profile/`.

<!-- TOC -->
* [Service-to-service authentication: token exchange with jwtlet](#service-to-service-authentication-token-exchange-with-jwtlet)
  * [Introduction](#introduction)
  * [1. Why token exchange?](#1-why-token-exchange)
  * [2. The token-exchange mechanism (RFC 8693)](#2-the-token-exchange-mechanism-rfc-8693)
    * [The exchange request](#the-exchange-request)
    * [The resulting token](#the-resulting-token)
    * [⚠️ The two audiences (common pitfall)](#-the-two-audiences-common-pitfall)
  * [3. The jwtlet application and its APIs](#3-the-jwtlet-application-and-its-apis)
    * [3.1 Token-exchange API (port 8080)](#31-token-exchange-api-port-8080)
    * [3.2 Management API (port 8081, base path `/api/v1`)](#32-management-api-port-8081-base-path-apiv1)
    * [3.3 Configuration (`jwtlet.toml`)](#33-configuration-jwtlettoml)
  * [4. Scope mechanisms](#4-scope-mechanisms)
    * [4.1 jwtlet management scopes — *who may manage/manipulate jwtlet*](#41-jwtlet-management-scopes--who-may-managemanipulate-jwtlet)
    * [4.2 EDC API scopes — *what an exchanged token may do*](#42-edc-api-scopes--what-an-exchanged-token-may-do)
      * [Narrow scopes (preferred)](#narrow-scopes-preferred)
      * [Legacy coarse tiers (transition)](#legacy-coarse-tiers-transition)
      * [Scope grammar and implication](#scope-grammar-and-implication)
      * [Enforcement at the gateway](#enforcement-at-the-gateway)
    * [4.3 Endpoint → scope reference (external access)](#43-endpoint--scope-reference-external-access)
      * [Control plane Management API — external base `/api/management/v5beta`](#control-plane-management-api--external-base-apimanagementv5beta)
      * [IdentityHub Identity API — external base `/api/identity`](#identityhub-identity-api--external-base-apiidentity)
      * [IssuerService Issuer Admin API — external base `/api/issuer/admin`](#issuerservice-issuer-admin-api--external-base-apiissueradmin)
      * [CFM Tenant Manager — external base `/api/tm`](#cfm-tenant-manager--external-base-apitm)
      * [CFM Provision Manager — external base `/api/pm`](#cfm-provision-manager--external-base-apipm)
      * [Siglet — external base `/api/siglet`](#siglet--external-base-apisiglet)
  * [5. Onboard a new client application](#5-onboard-a-new-client-application)
    * [Step 1 — Create a ServiceAccount](#step-1--create-a-serviceaccount)
    * [Step 2 — Project a subject token into the pod](#step-2--project-a-subject-token-into-the-pod)
    * [Step 3 — Register a mapping in `jwtlet`](#step-3--register-a-mapping-in-jwtlet)
    * [Step 4 — Make sure the scope mappings exist](#step-4--make-sure-the-scope-mappings-exist)
    * [Step 5 — Configure the app to exchange tokens](#step-5--configure-the-app-to-exchange-tokens)
    * [End-to-end example](#end-to-end-example)
  * [6. Vault authentication via token exchange](#6-vault-authentication-via-token-exchange)
    * [How the control plane authenticates to Vault](#how-the-control-plane-authenticates-to-vault)
    * [What onboarding (CFM) must do per participant](#what-onboarding-cfm-must-do-per-participant)
<!-- TOC -->

## Introduction

With recent development iterations, JAD has adopted the concept of workload identifiers over client-based
authentication. In practice, this means that each client app that wants to use any of EDC's administrative APIs must be
registered with Kuberentes to receive a workload token. It then exchanges this workload token for a participant-bound
token using OAuth2 Token Exchange (RFC 8693) via the `jwtlet` application.

This removes the need to store client credentials in every client app, such as Bruno, Redline, or others.

JAD has **two** complementary authentication paths:

| Path                   | For                                                                                       | Mechanism                                                                                                                       | Where it's documented                          |
|------------------------|-------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------|
| **External / human**   | UI users and operators reaching the APIs through the `jad.localhost` gateway              | Traefik `ForwardAuth` → `clearglass` validates the Bearer token against **Keycloak** (RFC 7662 introspection) and checks scopes | [README → Clearglass](../README.md#clearglass) |
| **Internal / machine** | In-cluster workloads (CFM agents, seed jobs, your own apps) calling the EDC APIs directly | **jwtlet** exchanges a Kubernetes ServiceAccount token for a short-lived, scoped EDC token (RFC 8693)                           | **this document**                              |

This document covers the **machine path**: the token-exchange mechanism, the `jwtlet` application and its APIs, the
scope model, and how to onboard a new client application.

---

## 1. Why token exchange?

Every in-cluster workload already has an identity: its **Kubernetes ServiceAccount**, presented as a projected
ServiceAccount JWT. Rather than provisioning and rotating a static OAuth2 client secret for each workload, JAD lets a
workload **exchange** its ServiceAccount identity for a narrowly-scoped, short-lived EDC access token.

`jwtlet` (`ghcr.io/eclipse-cfm/jwtlet`, a small Rust service from the [eclipse-cfm](https://github.com/eclipse-cfm)
project) is the OAuth2 **issuer** that the EDC components trust. The control plane, IdentityHub, and IssuerService are
all configured with jwtlet as their issuer and JWKS source — for example in
`templates/edc/controlplane-config.yaml`:

```yaml
edc.iam.oauth2.issuer: "http://jwtlet.edc-v.svc.cluster.local:8080"
edc.iam.oauth2.jwks.url: "http://jwtlet.edc-v.svc.cluster.local:8080/.well-known/jwks.json"
```

(The same two settings appear in `identityhub-config.yaml` and `issuerservice-config.yaml`.) Each service therefore
verifies incoming tokens against jwtlet's published keys, reads the caller identity from the `sub` claim, and authorizes
the request against the `scope` claim.

---

## 2. The token-exchange mechanism (RFC 8693)

```mermaid
sequenceDiagram
    participant W as Workload (pod)
    participant K as Kubernetes API
    participant J as jwtlet (:8080)
    participant E as EDC API (control plane / IdentityHub / IssuerService)

    Note over W: Pod has a projected SA token<br/>(aud = cluster issuer)
    W->>J: POST /token (grant_type=token-exchange,<br/>subject_token=<SA JWT>, resource, scope, audience)
    J->>K: TokenReview(subject_token)
    K-->>J: valid → "system:serviceaccount:<ns>:<sa>"
    Note over J: look up mapping for that client,<br/>expand requested scope(s),<br/>sign token with Vault key
    J-->>W: 200 { "access_token": "<scoped JWT>" }
    W->>E: GET/POST … (Authorization: Bearer <scoped JWT>)
    E->>J: GET /.well-known/jwks.json (cached)
    Note over E: verify signature + issuer,<br/>identity = sub, permissions = scope
    E-->>W: 200 / 401 / 403
```

### The exchange request

The canonical example lives in
`templates/hooks/issuerservice-seed-job.yaml`:

```shell
curl -X POST "http://jwtlet.edc-v.svc.cluster.local:8080/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
  --data-urlencode "subject_token=${SA_TOKEN}" \
  --data-urlencode "resource=issuer" \
  --data-urlencode "scope=admin" \
  --data-urlencode "audience=edcv"
# → { "access_token": "<scoped JWT>", ... }
```

| Parameter       | Meaning                                                                                                     |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| `grant_type`    | always `urn:ietf:params:oauth:grant-type:token-exchange`                                                    |
| `subject_token` | the workload's **projected Kubernetes ServiceAccount token**                                                |
| `resource`      | selects which of the caller's mappings to use; its `participantContext` becomes the issued token's `sub`    |
| `scope`         | one or more scope identifiers, space-separated — **narrow scopes** like `management-api:assets:read` (preferred) or a legacy coarse tier (`read`, `write`, `admin`, …); each is expanded per §4 |
| `audience`      | the audience to mint the token for; must match jwtlet's configured `[token].audience` (`edcv`)              |

### The resulting token

The exchanged JWT is signed by jwtlet (key material from Vault) and carries:

- `iss` — jwtlet (`http://jwtlet…:8080`)
- `sub` — the `participantContextID` from the matching mapping (the EDC participant-context identity), the `resource`
  parameter from before
- `aud` — the requested `audience` (`edcv`)
- `scope` — the union of the **expansions** of all requested scope identifiers. Narrow scopes expand 1:1
  (`scope=management-api:assets:read` → `"scope": "management-api:assets:read"`); legacy tiers expand into their
  coarse scope set, e.g. `scope=admin` → `"scope": "management-api:admin identity-api:admin issuer-admin-api:admin"`

### ⚠️ The two audiences (common pitfall)

There are two distinct audiences and both must line up:

1. The **subject token** (the SA JWT) must be projected with the audience jwtlet expects for incoming tokens — jwtlet's
   `[token].client_audience`, i.e. the Kubernetes cluster issuer
   `https://kubernetes.default.svc.cluster.local`.
2. The **requested** `audience` parameter must equal jwtlet's `[token].audience` (`edcv`), which is the audience the EDC
   services accept. Currently, this is the same value (`"edcv"`) for all administrative APIs.

Each application or service that has a workload ID, and is thus able to exchange their workload token for a narrowly
scoped participant-bound token, must have a Kubernetes ServiceAccount and reads the service account token from a mapped
volume. The projected-token volume in the seed jobs shows how to request the right subject-token audience:

```yaml
volumes:
  - name: jwtlet-subject-token
    projected:
      sources:
        - serviceAccountToken:
            path: token
            audience: https://kubernetes.default.svc.cluster.local   # == jwtlet client_audience
            expirationSeconds: 3600
```

---

## 3. The jwtlet application and its APIs

`jwtlet` is deployed by `templates/security/jwtlet.yaml` and exposes **two ports**:

| Port   | Name             | Purpose                                               |
|--------|------------------|-------------------------------------------------------|
| `8080` | `token-exchange` | the public token endpoint + discovery + health        |
| `8081` | `management`     | the administrative API used to manage exchange policy |

It uses a PostgreSQL backend for its state and a **Vault** agent sidecar that supplies the signing key (the
`/vault/secrets/.vault-token` is produced by the agent and consumed by the jwtlet container). It validates incoming
subject tokens through the Kubernetes **TokenReview** API; the required RBAC is granted by the
`jwtlet-token-reviewer-binding` ClusterRoleBinding in
`templates/security/serviceaccounts.yaml`.

### 3.1 Token-exchange API (port 8080)

| Method & path                | Purpose                                                                 |
|------------------------------|-------------------------------------------------------------------------|
| `POST /token`                | RFC 8693 token exchange (see §2)                                        |
| `GET /.well-known/jwks.json` | published public keys; used by the EDC services to verify issued tokens |
| `GET /health`                | liveness / readiness probe                                              |

### 3.2 Management API (port 8081, base path `/api/v1`)

The management API administers the exchange policy. This defines which client app can exchange their subject token for a
participant-scoped token, and which claims are mapped into that token. Callers authenticate with their **Kubernetes
ServiceAccount token** (projected with the cluster-issuer audience) and are authorized via jwtlet's own management
scopes (see §4.1).

| Method & path           | Purpose                                                                                                                  |
|-------------------------|--------------------------------------------------------------------------------------------------------------------------|
| `POST /api/v1/mappings` | bind a client (a K8s ServiceAccount) to a participant context, the scope tiers it may request, and the allowed audiences |
| `POST /api/v1/scopes`   | define how an abstract scope tier expands into a concrete `scope` claim                                                  |

> Read operations are gated by the `jwtlet:management:read` scope. The seed job
> `templates/hooks/jwtlet-seed-job.yaml` is the canonical example of
> driving this API.

### 3.3 Configuration (`jwtlet.toml`)

Provided by the `jwtlet-config` ConfigMap in
`templates/security/jwtlet-config.yaml`:

```toml
# this defines the value of the `iss` claim in issued/exchanged tokens
issuer = "http://jwtlet.edc-v.svc.cluster.local:8080"

# ...
# static grants for the MANAGEMENT API (see §4.1)
"system:serviceaccount:edc-v:cfm-agents" = ["jwtlet:management:mappings:write", "jwtlet:management:scope:write", "jwtlet:management:read"]
"system:serviceaccount:edc-v:seed-jobs" = ["jwtlet:management:mappings:write", "jwtlet:management:scope:write", "jwtlet:management:read"]
```

---

## 4. Scope mechanisms

There are **two independent scope systems**. Don't confuse them.

### 4.1 jwtlet management scopes — *who may manage/manipulate jwtlet*

These gate jwtlet's own management API (§3.2). They follow the grammar `jwtlet:management:[resource:]action` and are
granted **statically** in the `[service_accounts]` table of `jwtlet.toml`, which maps a Kubernetes ServiceAccount to a
set of management scopes.

| Scope                              | Allows                                                              |
|------------------------------------|---------------------------------------------------------------------|
| `jwtlet:management:mappings:write` | create/update client→context **mappings** (`POST /api/v1/mappings`) |
| `jwtlet:management:scope:write`    | create/update **scope mappings** (`POST /api/v1/scopes`)            |
| `jwtlet:management:read`           | read management resources                                           |

Notably, the scopes here are **not** hierarchical, i.e., having the `jwtlet:management:mappings:write` scope does
**not** imply the `"..:read"` scope.

### 4.2 EDC API scopes — *what an exchanged token may do*

These are the scopes carried in the **issued/exchanged** token and enforced by the EDC services. They are produced by a
**two-level** model:

**(a) A mapping** (`POST /api/v1/mappings`) binds a client to a participant context and lists the scope
**identifiers** it may request. This defines *who* may exchange the token. For example:

```json
{
  "clientIdentifier": "system:serviceaccount:edc-v:seed-jobs",
  "participantContext": "your-new-participant-context-id",
  "scopes": [
    "tenant-manager-api:read",
    "management-api:assets:write"
  ],
  "audiences": [
    "edcv"
  ]
}
```

| Field                | Meaning                                                                                                 |
|----------------------|----------------------------------------------------------------------------------------------------------|
| `clientIdentifier`   | the calling Kubernetes ServiceAccount (`system:serviceaccount:<namespace>:<name>`)                      |
| `participantContext` | the identity placed in the token's `sub` claim                                                          |
| `scopes`             | the scope identifiers this client may request via the `scope=` exchange parameter (narrow or tier)     |
| `audiences`          | the audiences this client may request                                                                   |

**(b) A scope mapping** (`POST /api/v1/scopes`) defines how each requested scope identifier **expands** into the
concrete `scope` claim. For example:

```json
{
  "scope": "read",
  "claims": {
    "scope": "management-api:read identity-api:read issuer-admin-api:read"
  }
}
```

This means that the abstract `read` scope in the resource mapping is expanded into a
`"scope": "management-api:read identity-api:read issuer-admin-api:read"` claim in the resulting exchanged token.

#### Narrow scopes (preferred)

With the migration to narrow API scopes, `jwtlet-seed-job.yaml` seeds a **1:1 scope mapping for every concrete scope**
the platform APIs check — the identifier a client requests via `scope=` *is* the claim minted into the token. Clients
should request exactly the scopes the endpoints they call require, instead of a coarse tier. The seeded narrow scopes
are:

| API namespace           | Service            | Seeded scopes                                                                                                                                                                                                          |
|-------------------------|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `management-api`        | Control plane      | `:admin`, `:read`, `:write`, `:*:read`, `:*:write`, and per-resource: `agreements:read`, `assets:read/write`, `catalog:read`, `contractdefinitions:read/write`, `dataplanes:write`, `discovery:read`, `negotiations:read/write`, `policies:read/write`, `profiles:read/write`, `transfers:read/write` |
| `identity-api`          | IdentityHub        | `:admin`, `:read`, `:write`, `:*:read`, `:*:write`, and per-resource: `participants:read/write`, `dids:read/write`, `keypairs:read/write`, `credentials:read/write`                                                    |
| `issuer-admin-api`      | IssuerService      | `:admin`, `:read`, `:write`, `:*:read`, `:*:write`, and per-resource: `attestations:read/write`, `credentialdefinitions:read/write`, `credentials:read/write`, `holders:read/write`, `issuanceprocesses:read`          |
| `provision-manager-api` | Provision Manager  | `:read`, `:write` (api-level only)                                                                                                                                                                                     |
| `tenant-manager-api`    | Tenant Manager     | `:read`, `:write` (api-level only)                                                                                                                                                                                     |
| `siglet-mgmt-api`       | Siglet             | `:read`, `:write` (api-level only)                                                                                                                                                                                     |

(Read the table as `<namespace>:<suffix>`, e.g. `management-api:assets:write`, `identity-api:*:read`,
`tenant-manager-api:read`.)

#### Legacy coarse tiers (transition)

The abstract tiers predate the narrow scopes and remain seeded for transition and e2e tooling. Each expands into a
coarse multi-API claim:

| Tier           | Concrete `scope` claim minted into the token                                   |
|----------------|--------------------------------------------------------------------------------|
| `read`         | `identity-api:read management-api:read issuer-admin-api:read siglet-api:read` |
| `write`        | `identity-api:write management-api:write issuer-admin-api:write`              |
| `admin`        | `management-api:admin identity-api:admin issuer-admin-api:admin`              |
| `cfm-read`     | `provision-manager-api:read tenant-manager-api:read`                           |
| `cfm-write`    | `provision-manager-api:write tenant-manager-api:write`                        |
| `siglet-read`  | `siglet-mgmt-api:read`                                                         |
| `siglet-write` | `siglet-mgmt-api:write`                                                        |

New clients should not rely on the tiers — prefer the narrow scopes above.

#### Scope grammar and implication

The concrete scopes follow the EDC scope grammar `<api>:[resource:]action`. A held scope satisfies a required scope
when it **implies** it:

- **actions** form a hierarchy: `admin ⊇ write ⊇ read` (a `write` grant also satisfies `read`; `admin` satisfies
  everything and additionally bypasses the per-tenant ownership check).
- **api-level** (`identity-api:read`) and **wildcard** (`identity-api:*:read`) scopes cover any resource-level
  requirement (`identity-api:participants:read`); the reverse does not hold — a resource-level scope never satisfies
  an api-level requirement.
- the CFM (`provision-manager-api`, `tenant-manager-api`) and Siglet scopes have no resource segment and are matched
  by the same api-level rules (`tenant-manager-api:write` satisfies `tenant-manager-api:read`).

> ⚠️ **Cross-context caveat:** for non-`admin` scopes the EDC services resolve the token's `sub` against an existing
> participant context and enforce per-tenant ownership. Calls that touch **another** context's resources — e.g. a
> tenant-bound token driving the issuer-owned `issuer-admin-api` — fail with *"No participant for sub found"* unless
> the token carries the elevated `<api>:admin` scope (this is why the CFM agents request `issuer-admin-api:admin`).

#### Enforcement at the gateway

Scopes are enforced twice — once at the gateway, once inside the services:

1. **Gateway (clearglass):** `clearglass` evaluates every request against a **route→scope map** (a ConfigMap-mounted
   `routes.yaml`, part of the Core Platform Distribution chart). Rules are method- and path-aware, evaluated
   first-match-wins against the *rewritten* backend path, with **default deny**; each rule names the narrowest
   sufficient scope and the implication rules above apply. A token that lacks the required scope is rejected with
   `403` and an *"insufficient scope"* error before it ever reaches the service. §4.3 lists the resulting
   per-endpoint scope requirements. The older per-API Traefik `jwt-auth-*` middlewares (see
   [README → Auth middleware scopes](../README.md#auth-middleware-scopes)) still perform the coarse per-API
   `read`/`write` check in addition.
2. **Services:** the EDC services verify the token themselves and enforce the same scope requirements (plus per-tenant
   ownership) internally, so in-cluster calls that bypass the gateway are still authorized.

### 4.3 Endpoint → scope reference (external access)

The tables below list, per API, which endpoint requires which **narrowest** scope when accessed **from outside the
cluster** through the `jad.localhost` gateway. They are derived from the clearglass route map shipped in the Core
Platform Distribution chart (`security.clearglass.routeMap.rules` in its `values.yaml`); that file is the
authoritative source. Rules are evaluated **first-match-wins** with **default deny** — a request matching no rule is
rejected with `403`, as is a token whose scopes don't satisfy the matched rule (*"insufficient scope"*).

Reading the tables:

- Paths are the **external** gateway paths. (Internally, clearglass matches the *rewritten* backend paths — e.g.
  `/api/management/…` → `/api/mgmt/…` — but the translation is already applied here.)
- `*` matches exactly one path segment, `**` matches any remainder; `<pid>` is a participant-context id.
- Per the EDC query convention, `POST …/request` and `POST …/query` endpoints are **reads** and require the `:read`
  scope. They are folded into the GET rows below.

**Wider scopes.** Every rule names the narrowest sufficient scope; any scope that *implies* it (§4.2) is accepted as
well. That expands mechanically:

| Narrowest required scope    | Wider narrow scopes that also work                                                          |
|-----------------------------|----------------------------------------------------------------------------------------------|
| `<api>:<resource>:read`     | `<api>:<resource>:write`, `<api>:*:read`, `<api>:*:write`, `<api>:read`, `<api>:write`, `<api>:admin` |
| `<api>:<resource>:write`    | `<api>:*:write`, `<api>:write`, `<api>:admin`                                              |
| `<api>:read` (api-level)    | `<api>:write`, `<api>:admin`                                                                |
| `<api>:write` (api-level)   | `<api>:admin`                                                                               |
| `<api>:admin`               | — (nothing wider)                                                                           |

The legacy coarse tiers satisfy requirements per their expansion (§4.2):

| API namespace                                        | `…:read` satisfied by tier               | `…:write` satisfied by tier | `…:admin` satisfied by tier |
|------------------------------------------------------|-------------------------------------------|------------------------------|------------------------------|
| `management-api`, `identity-api`, `issuer-admin-api` | `read`, `write`, `admin`                  | `write`, `admin`             | `admin`                      |
| `tenant-manager-api`, `provision-manager-api`        | `cfm-read`, `cfm-write`                   | `cfm-write`                  | —                            |
| `siglet-mgmt-api`                                    | `siglet-read`, `siglet-write`, `read`     | `siglet-write`               | —                            |

#### Control plane Management API — external base `/api/management/v5beta`

| Method(s)             | Endpoint (below base)                       | Narrowest required scope               |
|-----------------------|----------------------------------------------|-----------------------------------------|
| POST                  | `/participants`                              | `management-api:admin`                  |
| DELETE                | `/participants/<pid>`                        | `management-api:admin`                  |
| PATCH, PUT            | `/participants/<pid>/config`                 | `management-api:admin`                  |
| GET, POST             | `/participants/<pid>/catalog/**`             | `management-api:catalog:read`           |
| GET, POST `…/request` | `/participants/<pid>/assets/**`              | `management-api:assets:read`            |
| POST, PUT, DELETE     | `/participants/<pid>/assets/**`              | `management-api:assets:write`           |
| GET, POST `…/request` | `/participants/<pid>/policydefinitions/**`   | `management-api:policies:read`          |
| POST, PUT, DELETE     | `/participants/<pid>/policydefinitions/**`   | `management-api:policies:write`         |
| GET, POST `…/request` | `/participants/<pid>/contractdefinitions/**` | `management-api:contractdefinitions:read` |
| POST, PUT, DELETE     | `/participants/<pid>/contractdefinitions/**` | `management-api:contractdefinitions:write` |
| GET, POST `…/request` | `/participants/<pid>/contractnegotiations/**` | `management-api:negotiations:read`     |
| POST                  | `/participants/<pid>/contractnegotiations/**` | `management-api:negotiations:write`    |
| GET, POST             | `/participants/<pid>/contractagreements/**`  | `management-api:agreements:read`        |
| GET, POST `…/request` | `/participants/<pid>/transferprocesses/**`   | `management-api:transfers:read`         |
| POST                  | `/participants/<pid>/transferprocesses/**`   | `management-api:transfers:write`        |
| GET                   | `/participants/<pid>/dataplanes/**`          | `management-api:read` (no `dataplanes:read` scope exists) |
| POST, PUT, DELETE     | `/participants/<pid>/dataplanes/**`          | `management-api:dataplanes:write`       |
| GET                   | anything else (fallback)                     | `management-api:read`                   |
| any other method      | anything else (fallback)                     | `management-api:write`                  |

#### IdentityHub Identity API — external base `/api/identity`

| Method(s)         | Endpoint (below base)                   | Narrowest required scope        |
|-------------------|------------------------------------------|----------------------------------|
| GET               | `/participants/<pid>/dids/**`            | `identity-api:dids:read`        |
| POST, PUT, DELETE | `/participants/<pid>/dids/**`            | `identity-api:dids:write`       |
| GET               | `/participants/<pid>/keypairs/**`        | `identity-api:keypairs:read`    |
| POST, PUT, DELETE | `/participants/<pid>/keypairs/**`        | `identity-api:keypairs:write`   |
| GET               | `/participants/<pid>/credentials/**`     | `identity-api:credentials:read` |
| POST, PUT, DELETE | `/participants/<pid>/credentials/**`     | `identity-api:credentials:write` |
| GET               | `/participants/**` (other)               | `identity-api:participants:read` |
| POST, PUT, DELETE | `/participants/**` (other)               | `identity-api:participants:write` |
| GET, POST         | `/dids/**` (DID resolution; POST = query) | `identity-api:dids:read`        |
| GET               | anything else (fallback)                 | `identity-api:read`             |
| any other method  | anything else (fallback)                 | `identity-api:write`            |

#### IssuerService Issuer Admin API — external base `/api/issuer/admin`

| Method(s)             | Endpoint (below base)                            | Narrowest required scope                     |
|-----------------------|---------------------------------------------------|-----------------------------------------------|
| GET                   | `/participants/<pid>/holders/**`                  | `issuer-admin-api:holders:read`              |
| POST, PUT, DELETE     | `/participants/<pid>/holders/**`                  | `issuer-admin-api:holders:write`             |
| GET, POST `…/query`   | `/participants/<pid>/credentials/**`              | `issuer-admin-api:credentials:read`          |
| POST, PUT, DELETE     | `/participants/<pid>/credentials/**`              | `issuer-admin-api:credentials:write`         |
| GET                   | `/participants/<pid>/attestations/**`             | `issuer-admin-api:attestations:read`         |
| POST, PUT, DELETE     | `/participants/<pid>/attestations/**`             | `issuer-admin-api:attestations:write`        |
| GET                   | `/participants/<pid>/credentialdefinitions/**`    | `issuer-admin-api:credentialdefinitions:read` |
| POST, PUT, DELETE     | `/participants/<pid>/credentialdefinitions/**`    | `issuer-admin-api:credentialdefinitions:write` |
| GET, POST             | `/participants/<pid>/issuanceprocesses/**`        | `issuer-admin-api:issuanceprocesses:read`    |
| GET                   | anything else (fallback)                          | `issuer-admin-api:read`                      |
| any other method      | anything else (fallback)                          | `issuer-admin-api:write`                     |

> Remember the cross-context caveat from §4.2: driving the issuer-owned Issuer Admin API with a token bound to a
> *tenant* participant context requires `issuer-admin-api:admin`, even where the table lists a narrower scope.

#### CFM Tenant Manager — external base `/api/tm`

All Tenant Manager resources (`tenants`, `tenants/*/participant-profiles`, `cells`, `dataspace-profiles`,
`participant-profiles`) follow the same pattern:

| Method(s)            | Endpoint (below base)                          | Narrowest required scope   |
|----------------------|-------------------------------------------------|-----------------------------|
| GET, POST `…/query`  | any Tenant Manager resource                     | `tenant-manager-api:read`  |
| POST, PATCH, DELETE  | any Tenant Manager resource                     | `tenant-manager-api:write` |

#### CFM Provision Manager — external base `/api/pm`

Likewise for the Provision Manager resources (`orchestrations`, `activity-definitions`, `orchestration-definitions`):

| Method(s)           | Endpoint (below base)          | Narrowest required scope      |
|---------------------|---------------------------------|--------------------------------|
| GET, POST `…/query` | any Provision Manager resource  | `provision-manager-api:read`  |
| POST, DELETE        | any Provision Manager resource  | `provision-manager-api:write` |

#### Siglet — external base `/api/siglet`

| Method(s)         | Endpoint (below base) | Narrowest required scope |
|-------------------|------------------------|---------------------------|
| GET               | `/tokens/**`           | `siglet-mgmt-api:read`   |
| GET               | `/key-mappings/**`     | `siglet-mgmt-api:read`   |
| POST, PUT, DELETE | `/key-mappings/**`     | `siglet-mgmt-api:write`  |

> The Siglet rules also accept the historical `siglet-api:read` / `siglet-api:write` spellings until the naming is
> unified — which is why the legacy `read` tier (whose expansion contains `siglet-api:read`) passes Siglet read routes.

---

## 5. Onboard a new client application

Goal: a new in-cluster workload `my-app` obtains scoped EDC tokens using its ServiceAccount. This `my-app` then reads
the SA token, exchanges it for a participant-scoped token, and executes requests against EDC's administrative APIs. The
steps below adapt the patterns already used by the CFM agents and seed jobs.

### Step 1 — Create a ServiceAccount

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app
  namespace: edc-v
```

No extra RBAC is needed for the *subject* identity: jwtlet validates **any** SA token via TokenReview using its own
permissions, so you do not grant `my-app` anything cluster-wide.

### Step 2 — Project a subject token into the pod

Mount a projected ServiceAccount token whose audience matches jwtlet's `client_audience`:

```yaml
volumes:
  - name: jwtlet-subject-token
    projected:
      sources:
        - serviceAccountToken:
            path: token
            audience: https://kubernetes.default.svc.cluster.local
            expirationSeconds: 3600
# ...and mount it, e.g. at /var/run/secrets/jwtlet (readOnly)
```

### Step 3 — Register a mapping in `jwtlet`

Call the jwtlet management API **as a caller that holds `jwtlet:management:mappings:write`** (e.g. from a seed Job that
runs as the `seed-jobs` ServiceAccount or as the `cfm-agents` ServiceAccount — see `jwtlet-seed-job.yaml`):

List the **narrowest scopes** the app actually needs — one entry per narrow scope it may request:

```shell
curl -X POST "http://jwtlet.edc-v.svc.cluster.local:8081/api/v1/mappings" \
  -H "Authorization: Bearer $(cat /var/run/secrets/jwtlet/token)" \
  -H "Content-Type: application/json" \
  -d '{
    "clientIdentifier": "system:serviceaccount:edc-v:my-app",
    "participantContext": "<participant-context-id>",
    "scopes": ["management-api:assets:write", "management-api:contractdefinitions:read"],
    "audiences": ["edcv"]
  }'
```

> **`participantContext`** becomes the token's `sub`. For non-`admin` scopes the EDC services require `sub` to be an
> **existing participant context**; `admin`-scoped tokens (`<api>:admin` or the `admin` tier) are elevated and may use
> any subject (e.g. a service account that is not itself a participant). Choose the participant context the workload
> should act as.

### Step 4 — Make sure the scope mappings exist

All narrow scopes from §4.2 (and the legacy tiers) are seeded cluster-wide as 1:1 mappings by `jwtlet-seed-job.yaml`.
**You do not need to re-create these!** Only if your app needs a custom *alias* — one identifier that expands into a
set of concrete scopes — add a scope mapping (requires `jwtlet:management:scope:write`):

```shell
curl -X POST "http://jwtlet.edc-v.svc.cluster.local:8081/api/v1/scopes" \
  -H "Authorization: Bearer $(cat /var/run/secrets/jwtlet/token)" \
  -H "Content-Type: application/json" \
  -d '{ "scope": "my-app-scopes", "claims": { "scope": "management-api:assets:write management-api:contractdefinitions:read" } }'
```

### Step 5 — Configure the app to exchange tokens

You `my-app` needs to know _where_ to get the SA token, and _where_ to exchange it for an access token.

```yaml
tokenexchange.url: http://jwtlet.edc-v.svc.cluster.local:8080
tokenexchange.tokenFilePath: /var/run/secrets/jwtlet/token
```

A custom application performs the exchange itself (§2): `POST /token` with `grant_type=token-exchange`,
`subject_token=<SA token>`, `resource=<participantContext>`, `scope=<narrow scope(s)>`, `audience=edcv`, then calls the
target API with `Authorization: Bearer <access_token>`.


### End-to-end example

```shell
# from within my-app's pod
SA_TOKEN=$(cat /var/run/secrets/jwtlet/token)

# 1) exchange the SA token for a narrowly-scoped token (the scope must be
#    listed in my-app's mapping, see Step 3)
ACCESS_TOKEN=$(curl -s -X POST "http://jwtlet.edc-v.svc.cluster.local:8080/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
  --data-urlencode "subject_token=${SA_TOKEN}" \
  --data-urlencode "resource=<participant-context-id>" \
  --data-urlencode "scope=management-api:contractdefinitions:read" \
  --data-urlencode "audience=edcv" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

# 2) call an EDC API with the scoped token
curl -X GET "http://issuerservice.edc-v.svc.cluster.local:10013/api/management/v5beta/participants/<participant-context-id>/contractdefinitions" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}"
```

---

## 6. Vault authentication via token exchange

EDC APIs are not the only consumer of jwtlet-issued tokens: the **control plane also authenticates to HashiCorp Vault**
via the same token-exchange mechanism, replacing the previous Keycloak (OAuth2 client-credentials) flow. This removes the
need for a per-participant Keycloak "vault client".

### How the control plane authenticates to Vault

The control plane serves multiple participant contexts, each mapped to its own **vault partition**. For a participant's
partition it:

1. reads its projected ServiceAccount token from `/var/run/secrets/jwtlet/token`;
2. exchanges it at jwtlet for a participant-scoped token (`resource = <participantContextId>` → `sub`, `audience = edcv`);
3. presents that token to Vault's JWT auth method (`POST v1/auth/jwt/login`, role `participant`) to obtain a Vault token.

Vault is configured to trust jwtlet in `templates/hooks/vault-bootstrap-job.yaml`:

```shell
vault write auth/jwt/config \
  jwks_url="http://jwtlet.edc-v.svc.cluster.local:8080/.well-known/jwks.json" \
  default_role="participant"

vault write auth/jwt/role/participant - <<EOF
{ "role_type": "jwt", "user_claim": "sub",
  "bound_issuer": "http://jwtlet.edc-v.svc.cluster.local:8080",
  "bound_audiences": ["edcv"],
  "token_policies": ["participants-restricted", "participant-transit-policy"] }
EOF
```

Because `user_claim = sub` and `sub = participantContextId`, the templated policies
(`participants/data/{{identity.entity.aliases.<accessor>.name}}/*`, `transit/.../participant_<…>*`) automatically scope
each participant to its own secrets and transit keys. Vault ignores the token's `scope` claim — authorization is by role
— so the connector can request the lowest tier (`read`).

> The control plane's **default** partition (its own secrets + transit signing key) still uses the static `root` token
> (`edc.vault.hashicorp.token`). Token exchange is used only for **named** per-participant partitions. The relevant
> control-plane settings live in `templates/edc/controlplane-config.yaml`
> (`edc.vault.hashicorp.auth.tokenexchange.*`, `edc.vault.hashicorp.auth.jwt.role`), and the projected subject-token
> volume is mounted in `templates/edc/controlplane.yaml`.

### What onboarding (CFM) must do per participant

The control plane reaching a participant's vault partition requires two things, which the onboarding flow must provision
when it creates a participant:

1. **A participant context with a credential-free vault config.** The `vaultConfig.credentials` block (Keycloak
   clientId/secret/tokenUrl) is no longer used and must be omitted — only `vaultConfig.config` (vault URL, secret path,
   folder path) is needed. See [`create_participant_controlplane.json`](../tests/end2end/src/test/resources/create_participant_controlplane.json).
2. **A jwtlet mapping** binding the control plane's ServiceAccount to that participant context, so it may exchange for a
   token whose `sub` is the participant:

   ```shell
   curl -X POST "http://jwtlet.edc-v.svc.cluster.local:8081/api/v1/mappings" \
     -H "Authorization: Bearer $(cat /var/run/secrets/jwtlet/token)" \
     -H "Content-Type: application/json" \
     -d '{
       "clientIdentifier": "system:serviceaccount:edc-v:controlplane",
       "participantContext": "<participant-context-id>",
       "scopes": ["read"],
       "audiences": ["edcv"]
     }'
   ```

Onboarding should therefore **stop creating the per-participant Keycloak vault client** and instead create the mapping
above. No per-participant Vault role or policy is needed — the single templated `participant` role covers every
participant.

