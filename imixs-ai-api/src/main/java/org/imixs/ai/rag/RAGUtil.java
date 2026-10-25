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
import java.util.regex.Pattern;

/**
 * The RAGUtil provides methods to chunk markdown text
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
    public static final String ITEM_RAG_ENDPOINT_TOKEN = "rag.endpoint.token";
    public static final String ITEM_RAG_EMBEDDINGS = "rag.embeddings";
    public static final String ITEM_RAG_EMBEDDINGS_OPTIONS = "rag.embeddings.options";
    public static final String ITEM_RAG_CATEGORY = "rag.category";
    public static final String ITEM_RAG_TEXT = "rag.text";
    public static final String ITEM_DEBUG = "debug";

    // Optional prefixes for embedding models that require task prefixes
    // (e.g. nomic-embed: 'search_document:' and 'search_query:')
    public static final String ENV_EMBEDDINGS_PREFIX_DOCUMENT = "EMBEDDINGS_PREFIX_DOCUMENT";
    public static final String ENV_EMBEDDINGS_PREFIX_QUERY = "EMBEDDINGS_PREFIX_QUERY";

    // A heading is either a markdown heading (# .. ######) or a line that is
    // completely bold. Lines like "- **Price:** 50" or "**Artikel** text..." are
    // NOT headings.
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6} .+|\\*\\*[^*]+\\*\\*)$");

    private static Logger logger = Logger.getLogger(RAGUtil.class.getName());

    /**
     * Creates smaller text chunks from a markdown text. The text is split into
     * sections at headings. A heading always stays together with its content.
     * Small sections are merged into one chunk up to maxChunkSize. A section that
     * is larger than maxChunkSize is split at line breaks (then spaces, then hard
     * cut) and the last heading is repeated at the start of each part.
     *
     * @param markdownText the markdown text to chunk
     * @param maxChunkSize the maximum size of a single chunk in characters
     */
    public static List<String> chunkMarkdownDocument(String markdownText, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (Section section : parseSections(markdownText)) {
            String text = section.text();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() <= maxChunkSize) {
                // Section fits: merge into the current chunk if there is room
                if (currentChunk.length() > 0 && currentChunk.length() + 1 + text.length() > maxChunkSize) {
                    flush(chunks, currentChunk);
                }
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n");
                }
                currentChunk.append(text);
            } else {
                // Section is too large: close the current chunk and split the section
                flush(chunks, currentChunk);
                splitSection(chunks, section, maxChunkSize);
            }
        }
        flush(chunks, currentChunk);
        return chunks;
    }

    /**
     * Splits the text into sections. A heading without any content (e.g. "#
     * Summary"
     * directly followed by another heading) is stored as 'lead' of the next
     * section, so it never ends up alone at the end of a chunk.
     */
    private static List<Section> parseSections(String text) {
        List<Section> sections = new ArrayList<>();
        String lead = "";
        String header = "";
        StringBuilder body = new StringBuilder();

        for (String rawLine : text.split("\n")) {
            String line = rawLine.stripTrailing(); // also removes \r
            if (HEADER_PATTERN.matcher(line.trim()).matches()) {
                String content = body.toString().trim();
                if (content.isEmpty() && !header.isEmpty()) {
                    // orphan heading: carry it over to the next section
                    lead = join(lead, header);
                } else {
                    sections.add(new Section(lead, header, content));
                    lead = "";
                }
                header = line.trim();
                body.setLength(0);
            } else {
                body.append(line).append("\n");
            }
        }
        sections.add(new Section(lead, header, body.toString().trim()));
        return sections;
    }

    /**
     * Splits an oversized section into several chunks. Each part (except the
     * first) starts with the last heading of the section as context.
     */
    private static void splitSection(List<String> chunks, Section section, int maxChunkSize) {
        // Repeat the heading only if it does not consume too much of the budget
        String repeat = section.header.length() <= maxChunkSize / 2 ? section.header : "";
        String start = join(section.lead, section.header);
        if (start.length() > maxChunkSize / 2) {
            start = repeat;
        }

        int budget = maxChunkSize - Math.max(start.length(), repeat.length()) - 1;
        StringBuilder piece = new StringBuilder(start);
        boolean hasBody = false;

        for (String line : section.body.split("\n")) {
            for (String part : cutLine(line.stripTrailing(), budget)) {
                if (hasBody && piece.length() + 1 + part.length() > maxChunkSize) {
                    chunks.add(piece.toString().trim());
                    piece = new StringBuilder(repeat);
                    hasBody = false;
                }
                if (piece.length() > 0) {
                    piece.append("\n");
                }
                piece.append(part);
                hasBody = true;
            }
        }
        if (hasBody) {
            chunks.add(piece.toString().trim());
        }
    }

    /**
     * Cuts a single line into parts of at most 'budget' characters. Prefers the
     * last space, falls back to a hard cut.
     */
    private static List<String> cutLine(String line, int budget) {
        List<String> parts = new ArrayList<>();
        String rest = line;
        while (rest.length() > budget) {
            int pos = rest.lastIndexOf(' ', budget);
            if (pos <= 0) {
                pos = budget;
            }
            parts.add(rest.substring(0, pos).trim());
            rest = rest.substring(pos).trim();
        }
        parts.add(rest);
        return parts;
    }

    private static void flush(List<String> chunks, StringBuilder currentChunk) {
        String content = currentChunk.toString().trim();
        currentChunk.setLength(0);
        if (!content.isEmpty()) {
            chunks.add(content);
        }
    }

    /** Joins all non-empty parts with a line break. */
    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /**
     * A heading with its content. 'lead' holds preceding headings without content.
     */
    private static class Section {
        final String lead;
        final String header;
        final String body;

        Section(String lead, String header, String body) {
            this.lead = lead;
            this.header = header;
            this.body = body;
        }

        String text() {
            return join(lead, header, body);
        }
    }

    // /**
    // * Appends content to the current chunk. If the combined size exceeds
    // * maxChunkSize, the current chunk is flushed first. If the content itself
    // * exceeds maxChunkSize, it is split into smaller pieces.
    // */
    // private static void appendContent(List<String> chunks, StringBuilder
    // currentChunk,
    // String content, int maxChunkSize) {
    // // If content fits into the current chunk, just append
    // if (currentChunk.length() + content.length() <= maxChunkSize) {
    // currentChunk.append(content).append("\n");
    // return;
    // }

    // // Flush current chunk first
    // if (currentChunk.length() > 0) {
    // chunks.add(currentChunk.toString().trim());
    // currentChunk.setLength(0);
    // }

    // // If content fits into an empty chunk, just append
    // if (content.length() <= maxChunkSize) {
    // currentChunk.append(content).append("\n");
    // return;
    // }

    // // Content is too large — split it into smaller pieces
    // splitOversizedBlock(chunks, currentChunk, content, maxChunkSize);
    // }

    // /**
    // * Splits an oversized text block into chunks using the following priority: 1.
    // * Line break (\n) 2. Last space 3. Hard cut at maxChunkSize
    // */
    // private static void splitOversizedBlock(List<String> chunks, StringBuilder
    // currentChunk,
    // String text, int maxChunkSize) {
    // String remaining = text;

    // while (remaining.length() > maxChunkSize) {
    // String window = remaining.substring(0, maxChunkSize);
    // int splitPos = -1;

    // // Priority 1: find the last line break within the window
    // splitPos = window.lastIndexOf('\n');

    // // Priority 2: find the last space
    // if (splitPos <= 0) {
    // splitPos = window.lastIndexOf(' ');
    // }

    // // Priority 3: hard cut
    // if (splitPos <= 0) {
    // splitPos = maxChunkSize;
    // }

    // chunks.add(remaining.substring(0, splitPos).trim());
    // remaining = remaining.substring(splitPos).trim();
    // }

    // // Put the last piece into currentChunk for potential merging with next
    // content
    // if (!remaining.isEmpty()) {
    // currentChunk.append(remaining).append("\n");
    // }
    // }

    // /**
    // * Flushes the current chunk into the chunks list. If the chunk exceeds
    // * maxChunkSize, it is split further.
    // */
    // private static void flushChunk(List<String> chunks, StringBuilder
    // currentChunk, int maxChunkSize) {
    // if (currentChunk.length() == 0) {
    // return;
    // }
    // String content = currentChunk.toString().trim();
    // currentChunk.setLength(0);

    // if (content.length() <= maxChunkSize) {
    // if (!content.isEmpty()) {
    // chunks.add(content);
    // }
    // } else {
    // // Even the flushed chunk is too large — split it
    // splitOversizedBlock(chunks, currentChunk, content, maxChunkSize);
    // // Flush any remainder left in currentChunk
    // if (currentChunk.length() > 0) {
    // chunks.add(currentChunk.toString().trim());
    // currentChunk.setLength(0);
    // }
    // }
    // }

    /**
     * Prepends an optional prefix to the given text. The prefix is separated by a
     * single space. If the prefix is null or blank, the text is returned unchanged.
     * <p>
     * The separating space is added here, because trailing spaces in environment
     * variables are easily lost (e.g. in Kubernetes manifests).
     *
     * @param prefix the optional prefix, e.g. 'search_document:'
     * @param text   the text to be embedded
     * @return the text with the prefix
     */
    public static String applyPrefix(String prefix, String text) {
        if (prefix == null || prefix.isBlank()) {
            return text;
        }
        return prefix.trim() + " " + text;
    }
}