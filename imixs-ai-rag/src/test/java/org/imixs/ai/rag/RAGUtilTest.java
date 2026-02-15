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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.rag.util.RAGUtil;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link RAGUtil#chunkMarkupDocument(String)}.
 *
 * 
 * @author rsoika
 */
public class RAGUtilTest {

    private static Logger logger = Logger.getLogger(RAGUtilTest.class.getName());

    @Test
    public void testChunkWithMarkdownHeaders() {
        String markup = "# Header 1\nSome content under header 1.\n# Header 2\nContent under header 2.";

        List<String> chunks = RAGUtil.chunkMarkupDocument(markup, 512);

        assertEquals(1, chunks.size());
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty(), "Should produce at least one chunk");

        // Verify all original text is preserved across chunks
        String joined = String.join(" ", chunks);
        assertTrue(joined.contains("Header 1"));
        assertTrue(joined.contains("Some content under header 1."));
        assertTrue(joined.contains("Header 2"));
        assertTrue(joined.contains("Content under header 2."));
    }

    @Test
    public void testChunkWithBoldHeaders() {
        String markup = "**Bold Header**\nParagraph text here.\n**Another Bold Header**\nMore text.";

        List<String> chunks = RAGUtil.chunkMarkupDocument(markup, 512);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        String joined = String.join(" ", chunks);
        assertTrue(joined.contains("**Bold Header**"));
        assertTrue(joined.contains("Paragraph text here."));
    }

    @Test
    public void testChunkSplitsLargeContent() {
        // Build a markup text that exceeds the 500-char threshold
        StringBuilder sb = new StringBuilder();
        sb.append("# First Section\n");
        sb.append("A".repeat(600)).append("\n");
        sb.append("# Second Section\n");
        sb.append("Short content.");

        List<String> chunks = RAGUtil.chunkMarkupDocument(sb.toString(), 512);

        assertTrue(chunks.size() >= 2, "Large content should produce multiple chunks");
    }

    @Test
    public void testEmptyInput() {
        List<String> chunks = RAGUtil.chunkMarkupDocument("", 512);
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty(), "Empty input should produce no chunks");
    }

    @Test
    public void testPlainTextWithoutHeaders() {
        String markup = "Just some plain text without any headers.";

        List<String> chunks = RAGUtil.chunkMarkupDocument(markup, 512);

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("Just some plain text without any headers.", chunks.get(0));
    }

    /**
     * Test that a long section without sub-headers is split at line breaks.
     */
    @Test
    public void testChunkSplitsAtLineBreak() {
        // maxChunkSize=40 to keep the test simple
        String markup = "# Title\nLine one here.\nLine two here.\nLine three here.";

        List<String> chunks = RAGUtil.chunkMarkupDocument(markup, 40);

        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length() <= 40,
                    "Chunk " + i + " exceeds max size: " + chunks.get(i).length());
        }
        // Verify no chunk ends with a broken line
        for (String chunk : chunks) {
            assertFalse(chunk.endsWith(" "), "Chunk should not end with trailing space");
        }
    }

    /**
     * A more detailed test...
     */
    @Test
    public void testChunkSplitsAtLineBreakDetail() {
        int maxChunkSize = 15;// to keep the test simple

        String markup = "# Title\nLine one here.\nLine two here.\nLine three here.";
        System.out.println("Test chunkMarkupDocument:");
        System.out.println("'" + markup + "'");
        System.out.println("Result:");
        List<String> chunks = RAGUtil.chunkMarkupDocument(markup, maxChunkSize);

        for (int i = 0; i < chunks.size(); i++) {
            System.out.println("chunk-" + (i + 1) + ": '" + chunks.get(i) + "'");
            assertTrue(chunks.get(i).length() <= maxChunkSize,
                    "Chunk " + i + " exceeds max size: " + chunks.get(i).length());
        }
        // Verify no chunk ends with a broken line
        for (String chunk : chunks) {
            assertFalse(chunk.endsWith(" "), "Chunk should not end with trailing space");
        }
    }

    /**
     * Test that text without line breaks is split at the last space.
     */
    @Test
    public void testChunkSplitsAtSpace() {
        // No line breaks, just words separated by spaces
        String markup = "word1 word2 word3 word4 word5 word6 word7 word8 word9";

        List<String> chunks = RAGUtil.chunkMarkupDocument(markup, 20);

        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length() <= 20,
                    "Chunk " + i + " exceeds max size: " + chunks.get(i).length());
        }
        // Verify no word is split in the middle
        for (String chunk : chunks) {
            assertFalse(chunk.startsWith(" "), "Chunk should not start with space");
            assertFalse(chunk.contains("  "), "Chunk should not contain double spaces");
        }
    }

    /**
     * Test that text without any line breaks or spaces is hard-cut at maxChunkSize.
     */
    @Test
    public void testChunkHardCutWithoutDelimiters() {
        // One continuous string without spaces or line breaks
        String markup = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";

        List<String> chunks = RAGUtil.chunkMarkupDocument(markup, 10);

        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length() <= 10,
                    "Chunk " + i + " exceeds max size: " + chunks.get(i).length());
        }
        // Verify all content is preserved
        String joined = String.join("", chunks);
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890", joined);
    }

    /**
     * Simulate a markup document with a very long section between two headers
     */
    @Test
    public void testChunkSizeShouldNotExceedLimit() {
        int maxChunkSize = 500;
        StringBuilder sb = new StringBuilder();
        sb.append("# Introduction\n");
        // Long paragraph without any sub-headers (~2000 chars)
        for (int i = 0; i < 100; i++) {
            sb.append("This is sentence number ").append(i).append(" in a very long section. ");
        }
        sb.append("\n");
        sb.append("# Next Section\n");
        sb.append("Short content here.");

        List<String> chunks = RAGUtil.chunkMarkupDocument(sb.toString(), maxChunkSize);

        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length() <= maxChunkSize,
                    "Chunk " + i + " exceeds max size: " + chunks.get(i).length() + " chars");
        }
    }

}
