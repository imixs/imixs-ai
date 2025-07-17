/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * This Source Code may also be made available under the terms of the
 * GNU General Public License, version 2 or later (GPL-2.0-or-later),
 * which is available at https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/

package org.imixs.ai.rest;

import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.model.BPMNTemplateBuilder;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.engine.ModelService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.ModelException;
import org.openbpmn.bpmn.BPMNModel;
import org.openbpmn.bpmn.exceptions.BPMNValidationException;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The MCPRestService provides Model Context Protocol (MCP) endpoints for
 * accessing workflow resources and tools.
 * 
 * @author rsoika
 */
@Named
@RequestScoped
@Path("/ai/bpmn/template/")
@Produces({ MediaType.APPLICATION_JSON })
public class BPMNTemplateRestService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(BPMNTemplateRestService.class.getSimpleName());

    @Context
    UriInfo uriInfo;

    @Inject
    WorkflowService workflowService;

    @Inject
    ModelService modelService;

    /**
     * MCP JSON-RPC 2.0 Endpoint All MCP protocol methods are handled here URL:
     * /mcp/v1
     */
    @GET
    @Path("/model/version/{version}")
    @Produces({ MediaType.TEXT_PLAIN })
    public Response buildPromptTemplate(@PathParam("version") String version) {

        // Eingabe-Validierung
        if (version == null || version.trim().isEmpty()) {
            logger.warning("Version parameter is null or empty");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Version parameter is required")
                    .build();
        }
        BPMNModel model;
        try {
            model = workflowService.fetchModel(version);

            ModelManager modelManager = new ModelManager(workflowService);
            String template = BPMNTemplateBuilder.buildPromptTemplate(model, modelManager);
            // verify template result
            if (template == null || template.trim().isEmpty()) {
                logger.warning("Template generation failed for version: " + version);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("Template generation failed")
                        .build();
            }

            logger.fine("Successfully built template for version: " + version);
            return Response.ok(template).build();
        } catch (ModelException | BPMNValidationException e) {
            logger.log(Level.SEVERE, "ModelException for version: " + version, e);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Model error: " + e.getMessage())
                    .build();
        }

    }

}