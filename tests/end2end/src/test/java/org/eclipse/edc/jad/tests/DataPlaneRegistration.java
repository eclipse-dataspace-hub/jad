/*
 *  Copyright (c) 2026 Metaform Systems, Inc.
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

import org.eclipse.edc.connector.controlplane.test.system.utils.client.ManagementApiClientV5;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.http.ContentType.JSON;

/**
 * Registers the (Siglet) data plane for a participant context with the control plane.
 */
public final class DataPlaneRegistration {

    private static final Set<String> PROFILES = Set.of("https://w3id.org/dspace-sig/profile/http-pull");

    private DataPlaneRegistration() {
    }

    /**
     * Registers a data plane for a participant context.
     *
     * @param client               the Management API client
     * @param participantContextId participant context for which the data plane should be registered
     * @param authorization        the authorization profile of the data plane, may be null
     */
    public static void register(ManagementApiClientV5 client, String participantContextId, Map<String, Object> authorization) {
        var registration = new HashMap<String, Object>();
        registration.put("dataplaneId", "dataplane-%s".formatted(participantContextId));
        registration.put("endpoint", "http://siglet.edc-v.svc.cluster.local:8081/api/v1/%s/dataflows".formatted(participantContextId));
        // EDC renamed "transferTypes" to "profiles". The control plane image pinned by the platform chart and the
        // EDC snapshot this repo builds against can be on different sides of that rename, and a control plane
        // silently drops the name it does not know, so both are sent.
        registration.put("transferTypes", PROFILES);
        registration.put("profiles", PROFILES);
        registration.put("labels", Set.of());
        registration.put("authorization", authorization);

        client.baseManagementRequest(participantContextId)
                .contentType(JSON)
                .body(registration)
                .when()
                .put("/v5/participants/%s/dataplanes".formatted(participantContextId))
                .then()
                .log().ifValidationFails()
                .statusCode(200);
    }
}
