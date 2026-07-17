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

import java.util.List;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;

/**
 * Shared search logic for generic workitem lookups by a set of index
 * field/value criteria. Used by both ToolCallHandlerFindWorkitem (read-only
 * lookup) and ToolCallHandlerLinkWorkitem (lookup + link), so the query
 * construction and execution logic only exists in one place.
 */
@Stateless
@LocalBean
public class WorkitemSearchService {

    @Inject
    DocumentService documentService;

    /**
     * Builds a Lucene query from the given criteria map - always scoped to
     * type:workitem - and returns the matching workitems, limited to maxResult
     * entries.
     *
     * @param criteria  map of index field name to search value (combined with AND)
     * @param maxResult maximum number of results to return
     * @throws QueryException if the query is malformed or execution fails
     */
    public List<ItemCollection> findWorkitems(JsonObject criteria, int maxResult) throws QueryException {
        StringBuilder queryBuilder = new StringBuilder("(type:workitem)");
        for (String field : criteria.keySet()) {
            String value = criteria.getString(field);
            // Quote the value to handle spaces and Lucene special characters
            // like the hyphen in a license plate (e.g. "M-AH-4524")
            queryBuilder.append(" AND (").append(field).append(":\"").append(value).append("\")");
        }
        String query = queryBuilder.toString();

        return documentService.find(query, maxResult, 0);
    }
}