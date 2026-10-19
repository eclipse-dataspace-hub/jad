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

package org.eclipse.edc.virtualized.dataplane.cert.api;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.edc.virtualized.dataplane.cert.model.CertMetadata;
import org.eclipse.edc.virtualized.dataplane.cert.store.CertStore;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.jetbrains.annotations.NotNull;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("certs")
public class CertInternalExchangeController {

    private final CertStore certStore;
    private final TransactionContext transactionContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CertInternalExchangeController(CertStore certStore, TransactionContext transactionContext) {
        this.certStore = certStore;
        this.transactionContext = transactionContext;
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response certificateUpload(
            @FormDataParam("metadata") String metadata,
            @FormDataParam("file") InputStream stream,
            @FormDataParam("file") FormDataBodyPart bodyPart
    ) {
        var mediaType = bodyPart.getMediaType();
        var contentType = mediaType != null ? mediaType.toString() : "unknown";
        try {
            Map<String, Object> certMetadataInput = objectMapper.convertValue(objectMapper.readTree(metadata), getTypeRef());
            byte[] bytes = stream.readAllBytes();
            var certMetadata = new CertMetadata(
                    java.util.UUID.randomUUID().toString(),
                    contentType,
                    certMetadataInput
            );
            transactionContext.execute(() -> certStore.store(certMetadata.id(), certMetadata, bytes));
            return Response.ok().entity(certMetadata).build();
        } catch (Exception e) {
            throw new BadRequestException(e);
        }

    }

    @POST
    @Path("/request")
    public List<CertMetadata> queryCertificates(QuerySpec querySpec) {

        var query = Optional.ofNullable(querySpec)
                .orElseGet(() -> QuerySpec.Builder.newInstance().build());

        return transactionContext.execute(() -> certStore.queryMetadata(query));
    }

    @DELETE
    @Path("/{id}")
    public Response certificateDelete(@PathParam("id") String id) {
        return transactionContext.execute(() -> {
            certStore.delete(id);
            return Response.ok().build();
        });
    }

    @GET
    @Path("/{id}")
    public Response certificateDownload(@PathParam("id") String id) {
        return transactionContext.execute(() -> {
            var metadata = certStore.getMetadata(id);
            if (metadata == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            StreamingOutput stream = output -> {
                try (InputStream is = certStore.retrieve(id)) {
                    is.transferTo(output);
                }
            };

            return Response.ok(stream)
                    .header("Content-Type", metadata.contentType())
                    .build();
        });
    }

    @NotNull
    protected <T> TypeReference<T> getTypeRef() {
        return new TypeReference<>() {
        };
    }
}
