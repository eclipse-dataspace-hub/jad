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

plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

configurations.all {
    // jnats drags in the BouncyCastle LTS provider (bcprov-lts8on), which (a) ships
    // glibc-linked JNI natives that crash on the musl-based alpine images
    // (UnsatisfiedLinkError in org.bouncycastle.crypto.NativeLoader) and (b) duplicates
    // the org.bouncycastle.* classes already provided by bcprov-jdk18on in the shadow
    // jar. jnats' ed25519/NKey code works against plain bcprov, which stays on the
    // classpath via the EDC dependencies.
    exclude(group = "org.bouncycastle", module = "bcprov-lts8on")
}

dependencies {
    implementation(libs.edc.issuance.spi) // for seeding the attestations
    runtimeOnly(libs.edc.bom.issuerservice)
    runtimeOnly(libs.edc.ih.api.did)
    runtimeOnly(libs.edc.ih.api.participants)
    runtimeOnly(libs.edc.vault.hashicorp)
    runtimeOnly(libs.edc.bom.issuerservice.sql)
    runtimeOnly(libs.edc.core.participantcontext.config)
    runtimeOnly(libs.edc.store.participantcontext.config.sql)
    runtimeOnly(libs.edc.monitor.console)
    runtimeOnly(libs.edc.monitor.otel)
    runtimeOnly(libs.edc.events.nats)
    runtimeOnly(libs.edc.vault.transit)
    runtimeOnly(libs.edc.nats.auth.nkey)
    runtimeOnly(libs.opentelemetry.exporter.otlp)
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("issuerservice.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

edcBuild {
    publish.set(false)
}