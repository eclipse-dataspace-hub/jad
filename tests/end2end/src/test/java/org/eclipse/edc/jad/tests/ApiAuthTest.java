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

import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@EndToEndTest
public class ApiAuthTest {
    @Test
    void verifyAuthenticatedRequest_shouldSucceed() {
        var token = TokenExchange.getParticipantToken("redline", "tenant-manager-api:read");

        given()
                .header("Authorization", "Bearer " + token)
                .baseUri(Constants.TM_BASE_URL)
                .get("/cells")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void verifyAuthenticatedRequest_missingScope_shouldFailWith403() {
        var token = TokenExchange.getParticipantToken("redline", "provision-manager-api:read"); // should be tenant-manager-api:read

        given()
                .header("Authorization", "Bearer " + token)
                .baseUri(Constants.TM_BASE_URL)
                .get("/cells")
                .then()
                .statusCode(403)
                .body(containsString("insufficient scope"));
    }

    @Test
    void verifyMissingAuthHeader_shouldReturn401() {
        given()
                // missing: auth header
                .baseUri(Constants.TM_BASE_URL)
                .get("/cells")
                .then()
                .statusCode(401);
    }

    @Test
    void verifyInvalidToken_shouldReturn401() {
        given()
                .header("Authorization", "Bearer invalid-token")
                .baseUri(Constants.TM_BASE_URL)
                .get("/cells")
                .then()
                .statusCode(401);
    }
}
