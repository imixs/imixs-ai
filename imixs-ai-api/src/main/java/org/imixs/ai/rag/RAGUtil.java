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

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The RAGUtil provides methods to chunk markup text
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */
public class RAGUtil {

    public static final String RAG_INDEX = "INDEX";
    public static final String RAG_UPDATE = "UPDATE";
    public static final String RAG_DELETE = "DELETE";
    public static final String RAG_PROMPT = "PROMPT";
    public static final String RAG_RETRIEVAL = "RETRIEVAL";
    public static final String RAG_DISABLED = "DISABLED";

    // Event Log Entries
    public static final String EVENTLOG_TOPIC_RAG_EVENT_INDEX = "rag.event.index";
    public static final String EVENTLOG_TOPIC_RAG_EVENT_UPDATE = "rag.event.update";
    public static final String EVENTLOG_TOPIC_RAG_EVENT_PROMPT = "rag.event.prompt";
    public static final String EVENTLOG_TOPIC_RAG_EVENT_DELETE = "rag.event.delete";

    public static final String ITEM_RAG_ENDPOINT = "rag.endpoint";
    public static final String ITEM_RAG_EMBEDDINGS = "rag.embeddings";
    public static final String ITEM_RAG_EMBEDDINGS_OPTIONS = "rag.embeddings.options";
    public static final String ITEM_RAG_CATEGORY = "rag.category";
    public static final String ITEM_RAG_TEXT = "rag.text";
    public static final String ITEM_DEBUG = "debug";

    private static Logger logger = Logger.getLogger(RAGUtil.class.getName());

    /**
     * Creates smaller text chunks from a large markup text. Splits first by
     * headers, then by line breaks, spaces, or hard cut.
     *
     * @param markupText   the markup text to chunk
     * @param maxChunkSize the maximum size of a single chunk in characters
     */
    public static List<String> chunkMarkupDocument(String markupText, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        // int headerFlushThreshold = (int) (maxChunkSize * 0.85);

        Pattern sectionPattern = Pattern.compile("^(# .+|\\*\\*.+\\*\\*)", Pattern.MULTILINE);
        Matcher matcher = sectionPattern.matcher(markupText);

        int lastEnd = 0;

        while (matcher.find()) {
            String sectionContent = markupText.substring(lastEnd, matcher.start()).trim();
            if (!sectionContent.isEmpty()) {
                appendContent(chunks, currentChunk, sectionContent, maxChunkSize);
            }

            String header = matcher.group(1);
            if (currentChunk.length() + header.length() > maxChunkSize) {
                flushChunk(chunks, currentChunk, maxChunkSize);
            }
            currentChunk.append(header).append("\n");
            lastEnd = matcher.end();
        }

        // Remaining text after the last header
        String remainingContent = markupText.substring(lastEnd).trim();
        if (!remainingContent.isEmpty()) {
            appendContent(chunks, currentChunk, remainingContent, maxChunkSize);
        }
        // Flush any remaining content
        flushChunk(chunks, currentChunk, maxChunkSize);

        return chunks;
    }

    /**
     * Appends content to the current chunk. If the combined size exceeds
     * maxChunkSize, the current chunk is flushed first. If the content itself
     * exceeds maxChunkSize, it is split into smaller pieces.
     */
    private static void appendContent(List<String> chunks, StringBuilder currentChunk,
            String content, int maxChunkSize) {
        // If content fits into the current chunk, just append
        if (currentChunk.length() + content.length() <= maxChunkSize) {
            currentChunk.append(content).append("\n");
            return;
        }

        // Flush current chunk first
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
            currentChunk.setLength(0);
        }

        // If content fits into an empty chunk, just append
        if (content.length() <= maxChunkSize) {
            currentChunk.append(content).append("\n");
            return;
        }

        // Content is too large — split it into smaller pieces
        splitOversizedBlock(chunks, currentChunk, content, maxChunkSize);
    }

    /**
     * Splits an oversized text block into chunks using the following priority: 1.
     * Line break (\n) 2. Last space 3. Hard cut at maxChunkSize
     */
    private static void splitOversizedBlock(List<String> chunks, StringBuilder currentChunk,
            String text, int maxChunkSize) {
        String remaining = text;

        while (remaining.length() > maxChunkSize) {
            String window = remaining.substring(0, maxChunkSize);
            int splitPos = -1;

            // Priority 1: find the last line break within the window
            splitPos = window.lastIndexOf('\n');

            // Priority 2: find the last space
            if (splitPos <= 0) {
                splitPos = window.lastIndexOf(' ');
            }

            // Priority 3: hard cut
            if (splitPos <= 0) {
                splitPos = maxChunkSize;
            }

            chunks.add(remaining.substring(0, splitPos).trim());
            remaining = remaining.substring(splitPos).trim();
        }

        // Put the last piece into currentChunk for potential merging with next content
        if (!remaining.isEmpty()) {
            currentChunk.append(remaining).append("\n");
        }
    }

    /**
     * Flushes the current chunk into the chunks list. If the chunk exceeds
     * maxChunkSize, it is split further.
     */
    private static void flushChunk(List<String> chunks, StringBuilder currentChunk, int maxChunkSize) {
        if (currentChunk.length() == 0) {
            return;
        }
        String content = currentChunk.toString().trim();
        currentChunk.setLength(0);

        if (content.length() <= maxChunkSize) {
            if (!content.isEmpty()) {
                chunks.add(content);
            }
        } else {
            // Even the flushed chunk is too large — split it
            splitOversizedBlock(chunks, currentChunk, content, maxChunkSize);
            // Flush any remainder left in currentChunk
            if (currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
            }
        }
    }
}