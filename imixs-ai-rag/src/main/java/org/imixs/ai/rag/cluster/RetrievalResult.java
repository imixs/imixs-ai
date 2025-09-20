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

package org.imixs.ai.rag.cluster;

/**
 * Represents a single result from a semantic search operation in the RAG
 * (Retrieval-Augmented Generation) system.
 * 
 * This immutable data class encapsulates the information about a document that
 * was retrieved based on semantic similarity to a query. Each result contains
 * the document's unique identifier, the content of the best matching chunk, and
 * the similarity score that indicates how well the document matches the search
 * query.
 * 
 * <p>
 * The similarity score is typically a cosine similarity value between the query
 * embedding and the document's content embedding, where higher values indicate
 * better matches.
 * </p>
 * 
 * <p>
 * <strong>Usage Example:</strong>
 * </p>
 * 
 * <pre>{@code
 * List<RetrievalResult> results = searchEmbeddings(queryVector);
 * for (RetrievalResult result : results) {
 *     System.out.println("Document: " + result.getUniqueId() +
 *             " (Score: " + result.getScore() + ")");
 *     System.out.println("Content: " + result.getContent());
 * }
 * }</pre>
 * 
 * @author Your Name
 * @version 1.0
 * @since 1.0
 * @see #getUniqueId()
 * @see #getContent()
 * @see #getScore()
 */
public class RetrievalResult {
    private final String uniqueId;
    private final String content;
    private final Float score;

    public RetrievalResult(String uniqueID, String content, Float score) {
        this.uniqueId = uniqueID;
        this.content = content;
        this.score = score;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public String getContent() {
        return content;
    }

    public Float getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "RetrievalResult{" +
                "$uniqueId='" + uniqueId + '\'' +
                ", score=" + score +
                ", content='" + content.substring(0, Math.min(50, content.length())) + "...'" +
                '}';
    }
}
