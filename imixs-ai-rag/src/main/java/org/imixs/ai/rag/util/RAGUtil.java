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

package org.imixs.ai.rag.util;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.imixs.ai.rag.workflow.RAGRetrievalAdapter;

/**
 * The RAGUtil provides methods to chunk markup text
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */
public class RAGUtil {

    private static Logger logger = Logger.getLogger(RAGRetrievalAdapter.class.getName());

    /**
     * This helper method creates smaller text chunks from a large markup text.
     */
    public static List<String> chunkMarkupDocument(String markupText) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        // Regex für Abschnitts-Header (# oder **)
        Pattern sectionPattern = Pattern.compile("^(# .+|\\*\\*.+\\*\\*)", Pattern.MULTILINE);
        Matcher matcher = sectionPattern.matcher(markupText);

        int lastEnd = 0;
        while (matcher.find()) {
            // Text zwischen den Headern
            String sectionContent = markupText.substring(lastEnd, matcher.start()).trim();

            if (!sectionContent.isEmpty()) {
                // Füge Inhalt zum aktuellen Chunk hinzu
                if (currentChunk.length() + sectionContent.length() > 500) {
                    flushChunk(chunks, currentChunk);
                }
                currentChunk.append(sectionContent).append("\n");
            }

            // Header selbst behandeln
            String header = matcher.group(1);
            if (currentChunk.length() + header.length() > 300) {
                flushChunk(chunks, currentChunk);
            }
            currentChunk.append(header).append("\n");

            lastEnd = matcher.end();
        }

        // Restlichen Text nach dem letzten Header
        String remainingContent = markupText.substring(lastEnd).trim();
        if (!remainingContent.isEmpty()) {
            currentChunk.append(remainingContent);
            flushChunk(chunks, currentChunk);
        } else if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private static void flushChunk(List<String> chunks, StringBuilder currentChunk) {
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
            currentChunk.setLength(0);
        }
    }

}
