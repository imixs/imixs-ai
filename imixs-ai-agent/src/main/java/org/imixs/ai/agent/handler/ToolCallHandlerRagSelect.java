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
import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.api.ImixsAIContextHandler;
import org.imixs.ai.toolcall.ToolCallFunction;
import org.imixs.ai.toolcall.ToolCallHandler;
import org.imixs.workflow.ItemCollection;

import jakarta.inject.Named;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Handles the "rag_select" tool call.
 * <p>
 * The LLM selects one candidate from the last "rag_retrieval" result by its
 * number. The handler resolves the number into the $uniqueid (stored in the
 * workitem item 'rag.candidates') and writes the uniqueid into the given
 * reference field. The LLM never has to reproduce a uniqueid.
 * <p>
 * If no candidate fits, the LLM simply does not call this tool.
 *
 * @see ToolCallHandlerRagRetrieval
 */
@Named
public class ToolCallHandlerRagSelect implements ToolCallHandler, Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TOOL_RAG_SELECT = "rag_select";

    private static final Logger logger = Logger.getLogger(ToolCallHandlerRagSelect.class.getName());

    @Override
    public String getToolName() {
        return TOOL_RAG_SELECT;
    }

    @Override
    public void register(ImixsAIContextHandler contextHandler) {
        contextHandler.addFunction(
                TOOL_RAG_SELECT,
                "Selects ONE candidate from the result of the last rag_retrieval call and stores a link to it "
                        + "in the current workitem. Pass the candidate number ('nr') exactly as returned by "
                        + "rag_retrieval - never a uniqueid. Only call this tool if you have decided that the "
                        + "candidate really is the match you are looking for. If no candidate fits, do not "
                        + "call this tool. A second call replaces the previous selection.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "candidate": {
                                    "type": "integer",
                                    "description": "The number ('nr') of the selected candidate from the last rag_retrieval result."
                                },
                                "refField": {
                                    "type": "string",
                                    "description": "Name of the workitem field to store the link in, exactly as given in the task instructions."
                                }
                            },
                            "required": ["candidate", "refField"]
                        }
                        """);
    }

    @Override
    public void handle(ToolCallFunction _function) {
        JsonObject args = _function.getArguments();
        ImixsAIContextHandler contextHandler = _function.getContextHandler();
        ItemCollection workitem = contextHandler.getWorkItem();

        if (contextHandler.isDebug()) {
            logger.info("├── ToolCallHandlerRagSelect: rag_select arguments=" + args);
            logger.info("│   ├── workitem: " + workitem.getUniqueID());
        }

        String refField = args.containsKey("refField") ? args.getString("refField") : null;
        if (refField == null || refField.isBlank()) {
            if (contextHandler.isDebug()) {
                logger.info("│   └── ⚠️ rag_select rejected: missing 'refField' argument");
            }
            _function.setError("Missing 'refField' argument!");
            return;
        }
        refField = refField.trim();
        // System items ($uniqueid, $workflowstatus, ...) must never be overwritten by a
        // tool call
        if (refField.startsWith("$")) {
            if (contextHandler.isDebug()) {
                logger.info("│   └── ⚠️ rag_select rejected: system item '" + refField + "' is not allowed");
            }
            _function.setError("Invalid 'refField' - system items are not allowed!");
            return;
        }
        if (!args.containsKey("candidate")) {
            if (contextHandler.isDebug()) {
                logger.info("│   └── ⚠️ rag_select rejected: missing 'candidate' argument");
            }
            _function.setError("Missing 'candidate' argument!");
            return;
        }
        int number = args.getInt("candidate", 0);
        if (contextHandler.isDebug()) {
            logger.info("│   ├── candidate number: " + number);
            logger.info("│   ├── refField: " + refField);
        }

        List<Object> candidateIds = workitem.getItemValue(ToolCallHandlerRagRetrieval.ITEM_CANDIDATES);
        if (contextHandler.isDebug()) {
            logger.info("│   ├── candidates available: " + candidateIds.size() + " "
                    + candidateIds);
        }
        if (candidateIds.isEmpty()) {
            if (contextHandler.isDebug()) {
                logger.info("│   └── ⚠️ rag_select rejected: no candidates stored in '"
                        + ToolCallHandlerRagRetrieval.ITEM_CANDIDATES + "' (rag_retrieval not called?)");
            }
            _function.setError("No candidates available - call rag_retrieval first!");
            return;
        }
        if (number < 1 || number > candidateIds.size()) {
            if (contextHandler.isDebug()) {
                logger.info("│   └── ⚠️ rag_select rejected: candidate number " + number
                        + " out of range (1-" + candidateIds.size() + ")");
            }
            _function.setError("Invalid candidate number " + number + " - valid numbers are 1 to "
                    + candidateIds.size() + "!");
            return;
        }

        String uniqueId = String.valueOf(candidateIds.get(number - 1));
        if (contextHandler.isDebug()) {
            logger.info("│   ├── resolved candidate " + number + " -> uniqueid " + uniqueId);
            String previousValue = workitem.getItemValueString(refField);
            if (!previousValue.isBlank()) {
                logger.info("│   ├── replacing previous value of '" + refField + "': " + previousValue);
            }
        }

        // Only one selected match is stored, so a repeated call replaces the value
        workitem.replaceItemValue(refField, uniqueId);

        _function.setResultValue(Json.createObjectBuilder()
                .add("selected", number)
                .build().toString());

        String message = "rag_select: candidate " + number + " stored in '" + refField + "'";
        _function.setToolMessage(message);
        if (contextHandler.isDebug()) {
            logger.info("│   └── ✅ " + message);
        }
    }
}