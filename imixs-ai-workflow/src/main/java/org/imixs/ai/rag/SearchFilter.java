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

package org.imixs.ai.rag;

import java.io.Serializable;

/**
 * Describes the optional filters of a RAG index search. The class replaces a
 * list of String parameters that are easy to mix up.
 * <p>
 * Semantic of the values:
 * <ul>
 * <li>category: <code>null</code> = all categories, empty string = primary data
 * only, otherwise the name of the category</li>
 * <li>modelGroups, tasks: <code>null</code> or blank = no filter</li>
 * </ul>
 * 
 * <pre>
 * {@code
 * SearchFilter filter = SearchFilter.create()
 *         .primaryDataOnly()
 *         .modelGroups("Auftrag")
 *         .tasks("1000:1300");
 * }
 * </pre>
 */
public class SearchFilter implements Serializable {

    private static final long serialVersionUID = 1L;

    // null = all categories, "" = primary data only
    private String category = null;
    private String modelGroups = null;
    private String tasks = null;

    public static SearchFilter create() {
        return new SearchFilter();
    }

    /**
     * Sets the category filter. Note: an empty string means 'primary data only'.
     */
    public SearchFilter category(String category) {
        this.category = category;
        return this;
    }

    /** Search in all categories (default) */
    public SearchFilter allCategories() {
        this.category = null;
        return this;
    }

    /** Search in the primary data only (category is empty) */
    public SearchFilter primaryDataOnly() {
        this.category = "";
        return this;
    }

    /**
     * Comma separated model group patterns, wildcards are supported (e.g.
     * "customer*, invoice-1.0"). A blank value means 'no filter'.
     */
    public SearchFilter modelGroups(String modelGroups) {
        this.modelGroups = blankToNull(modelGroups);
        return this;
    }

    /**
     * Comma separated task IDs and/or ranges (e.g. "1400, 1410, 1000:1300"). A
     * blank value means 'no filter'.
     */
    public SearchFilter tasks(String tasks) {
        this.tasks = blankToNull(tasks);
        return this;
    }

    public String getCategory() {
        return category;
    }

    public String getModelGroups() {
        return modelGroups;
    }

    public String getTasks() {
        return tasks;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    @Override
    public String toString() {
        String categoryText = category == null ? "all" : (category.isEmpty() ? "primary" : category);
        return "category=" + categoryText + ", modelGroups=" + (modelGroups == null ? "-" : modelGroups)
                + ", tasks=" + (tasks == null ? "-" : tasks);
    }
}