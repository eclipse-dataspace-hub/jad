/*
 *  Copyright (c) 2025 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

package org.eclipse.edc.jad.tests;

public interface Constants {

    // make sure that all the following URLs are valid. The Gateway API Controller (Traefik) is reachable on
    // localhost:80 via its hostPort binding plus the port mappings of the KinD cluster (see kind-config.yaml)

    String APPLICATION_JSON = "application/json";
    String TM_BASE_URL = "http://jad.localhost/api/tm";
    String PM_BASE_URL = "http://jad.localhost/api/pm";
    String VAULT_URL = "http://vault.localhost";
    String CONTROLPLANE_BASE_URL = "http://jad.localhost/api/management";
    String SIGLET_BASE_URL = "http://jad.localhost/api/siglet";
    String DATAPLANE_BASE_URL = "http://jad.localhost/";
    String IDENTITYHUB_BASE_URL = "http://jad.localhost/api/identity";
    // The advertised DSP endpoint (matches the platform's edc.dsp.callback.address). Resolvable
    // from inside the cluster through the coredns-rewrite seed job of the jad-dataspace-profile
    // chart, which points the gateway hostnames at the Traefik Service.
    String CONTROLPLANE_PROTOCOL_URL = "http://jad.localhost/api/dsp/%s/http-dsp-profile-2025-1";
    String TOKEN_EXCHANGE_URL = "http://jad.localhost/api/auth/token";
    // Prefix for participant DIDs (participant name appended). Must match the platform
    // IdentityHub's did:web hostname (identity.<host>): IdentityHub resolves a DID document by
    // the request URL, so a DID under any other authority will not resolve through the gateway.
    String PARTICIPANT_DID_PREFIX = "did:web:identity.jad.localhost:";
}
