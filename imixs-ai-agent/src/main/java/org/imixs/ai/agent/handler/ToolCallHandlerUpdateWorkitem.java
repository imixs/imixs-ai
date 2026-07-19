/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/
package org.imixs.ai.agent.handler;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Logger;

import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.tools.ImixsAIToolCallEvent;
import org.imixs.ai.tools.ImixsAIToolRegistrationEvent;
import org.imixs.ai.tools.ToolCallHandler;
import org.imixs.workflow.ItemCollection;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Named;
import jakarta.interceptor.Interceptor;
import jakarta.json.JsonObject;

/**
 * Handles the "update_workitem" tool call.
 * 
 * Writes a set of field/value/type entries directly into the current workitem.
 * This is the tool-call equivalent of a user filling out form fields manually -
 * the agent performs the same action programmatically, based on data it
 * extracted from the current task.
 * <p>
 * Which fields are available and what they mean is described in the BPMN prompt
 * definition of the current task - this handler itself has no knowledge of any
 * specific business field.
 */
@Named
public class ToolCallHandlerUpdateWorkitem implements ToolCallHandler, Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TOOL_UPDATE_WORKITEM = "update_workitem";

    // Workitem fields with this prefix are reserved for workflow control and
    // must not be overwritten via this generic tool call
    private static final String PROTECTED_FIELD_PREFIX = "$";

    private static final Logger logger = Logger.getLogger(ToolCallHandlerUpdateWorkitem.class.getName());

    @Override
    public String getToolName() {
        return TOOL_UPDATE_WORKITEM;
    }

    /**
     * Registers the update_workitem function definition during the agent tool
     * registration phase.
     */
    public void onToolRegistration(
            @Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) ImixsAIToolRegistrationEvent event) {
        event.addFunction(
                TOOL_UPDATE_WORKITEM,
                "Writes a set of field/value/type entries directly into the current workitem. "
                        + "Which field names are available and what they mean is described in the current "
                        + "task instructions. Fields starting with '$' are reserved and will be ignored. "
                        + "Date values must be formatted as ISO 8601 (YYYY-MM-DD). Amount/number values "
                        + "must be formatted as plain decimal numbers (e.g. '46.77'), without currency "
                        + "symbols or thousands separators.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "values": {
                                    "type": "object",
                                    "description": "Map of workitem field name to value and type. Example: {\\"payment.iban\\": {\\"value\\": \\"DE98500109000022175010\\", \\"type\\": \\"string\\"}, \\"payment.amount\\": {\\"value\\": \\"46.77\\", \\"type\\": \\"double\\"}, \\"payment.duedate\\": {\\"value\\": \\"2026-06-27\\", \\"type\\": \\"date\\"}}",
                                    "additionalProperties": {
                                        "type": "object",
                                        "properties": {
                                            "value": {
                                                "type": "string",
                                                "description": "The value as a string. For type 'date' use ISO 8601 (YYYY-MM-DD). For type 'double' use a plain decimal number."
                                            },
                                            "type": {
                                                "type": "string",
                                                "enum": ["string", "double", "date"],
                                                "description": "Target data type of the field"
                                            }
                                        },
                                        "required": ["value", "type"]
                                    }
                                }
                            },
                            "required": ["values"]
                        }
                        """);
    }

    /**
     * Handles the "update_workitem" tool call. Writes each entry of the given
     * values map into the current workitem, converting the value according to the
     * given type. Fields with a reserved '$' prefix are skipped with a warning.
     * Entries that fail type conversion are skipped with a warning as well - the
     * remaining entries are still applied.
     */
    @Override
    public void handle(@Observes ImixsAIToolCallEvent event) {
        if (!TOOL_UPDATE_WORKITEM.equals(event.getToolName())) {
            return;
        }

        JsonObject values = event.getArguments().getJsonObject("values");
        if (values == null || values.isEmpty()) {
            event.setError("Missing or empty 'values' argument!");
            return;
        }

        ImixsAIContextHandler contextHandler = event.getContextHandler();
        ItemCollection workitem = contextHandler.getWorkItem();

        int updatedCount = 0;
        int skippedCount = 0;

        for (String field : values.keySet()) {
            // Protect workflow control fields from being overwritten
            if (field.startsWith(PROTECTED_FIELD_PREFIX)) {
                logger.warning("│   ├── ⚠️ update_workitem: field '" + field
                        + "' is protected and will be ignored");
                skippedCount++;
                continue;
            }

            JsonObject entry = values.getJsonObject(field);
            String rawValue = entry.getString("value", null);
            String type = entry.getString("type", "string");

            if (rawValue == null) {
                logger.warning("│   ├── ⚠️ update_workitem: missing value for field '" + field + "', skipping");
                skippedCount++;
                continue;
            }

            try {
                switch (type) {
                case "double":
                    workitem.setItemValue(field, Double.parseDouble(rawValue));
                    break;
                case "date":
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                    sdf.setLenient(false);
                    Date date = sdf.parse(rawValue);
                    workitem.setItemValue(field, date);
                    break;
                case "string":
                default:
                    workitem.setItemValue(field, rawValue);
                    break;
                }
                updatedCount++;
            } catch (NumberFormatException | ParseException e) {
                logger.warning("│   ├── ⚠️ update_workitem: invalid " + type + " value '" + rawValue
                        + "' for field '" + field + "', skipping: " + e.getMessage());
                skippedCount++;
            }
        }

        logger.info("│   └── ✅ update_workitem: " + updatedCount + " field(s) updated, "
                + skippedCount + " field(s) skipped");

        event.setToolMessage("{\"updated\": " + updatedCount + ", \"skipped\": " + skippedCount + "}");
    }
}
