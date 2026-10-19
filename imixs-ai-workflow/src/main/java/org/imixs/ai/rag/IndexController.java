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

import java.util.logging.Logger;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * CDI Controller Bean to provide access to the RAG ClusterService.
 * <p>
 * This controller delegates calls to the ClusterService and can be used by the
 * workflow UI to retrieve indexed cognito content for a given document and
 * category.
 * 
 * @author rsoika
 * @version 1.0
 */
@Named
@RequestScoped
public class IndexController {

    private static final Logger logger = Logger.getLogger(IndexController.class.getName());

    /**
     * Returns the full content text for a given uniqueId and category.
     * <p>
     * The category is optional. If no category is provided, the primary data
     * content is returned.
     * 
     * @param uniqueId the document ID
     * @param category the content category (null or "" for primary data)
     * @return the full content text, or null if no content was found
     */
    public String getContent(String uniqueId, String category) {
        logger.info("getContent NOT Implemented");
        return null;
    }

    /**
     * Returns the full primary data content for a given uniqueId. Convenience
     * method for retrieving content without a category.
     * 
     * @param uniqueId the document ID
     * @return the full content text, or null if no content was found
     */
    public String getContent(String uniqueId) {
        return getContent(uniqueId, null);
    }

    /**
     * Returns true if content exists for a given uniqueId and category.
     * 
     * @param uniqueId the document ID
     * @param category the content category (null or "" for primary data)
     * @return true if content exists
     */
    public boolean hasContent(String uniqueId, String category) {
        logger.info("hasContent NOT Implemented");
        return false;
    }
}
