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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link RAGUtil#chunkMarkupDocument(String)}.
 *
 * 
 * @author rsoika
 */
public class RAGUtilTest {

    // Same heading definition as used in RAGUtil (used to verify the chunk results)
    private static final Pattern HEADING = Pattern.compile("^(#{1,6} .+|\\*\\*[^*]+\\*\\*)$");

    private static Logger logger = Logger.getLogger(RAGUtilTest.class.getName());

    @Test
    public void testChunkWithMarkdownHeaders() {
        String markup = "# Header 1\nSome content under header 1.\n# Header 2\nContent under header 2.";

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 512);

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

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 512);

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

        List<String> chunks = RAGUtil.chunkMarkdownDocument(sb.toString(), 512);

        assertTrue(chunks.size() >= 2, "Large content should produce multiple chunks");
    }

    @Test
    public void testEmptyInput() {
        List<String> chunks = RAGUtil.chunkMarkdownDocument("", 512);
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty(), "Empty input should produce no chunks");
    }

    @Test
    public void testPlainTextWithoutHeaders() {
        String markup = "Just some plain text without any headers.";

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 512);

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

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 40);

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
        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, maxChunkSize);

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

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 20);

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

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 10);

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

        List<String> chunks = RAGUtil.chunkMarkdownDocument(sb.toString(), maxChunkSize);

        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length() <= maxChunkSize,
                    "Chunk " + i + " exceeds max size: " + chunks.get(i).length() + " chars");
        }
    }

    private static String lastLine(String chunk) {
        String[] lines = chunk.split("\n");
        return lines[lines.length - 1].trim();
    }

    private static int countOccurrences(String text, String part) {
        int count = 0;
        int pos = 0;
        while ((pos = text.indexOf(part, pos)) >= 0) {
            count++;
            pos += part.length();
        }
        return count;
    }

    /**
     * Regression test based on a real document: a heading must never end up alone
     * at the end of a chunk, but must stay together with its content.
     */
    @Test
    public void testHeadingStaysWithContent() {
        String markup = "Angebot: Koordination\n"
                + "23.10.2026 Ross & Reiter GmbH & Co. KG (Standardartikel)\n\n"
                + "# Summary\n"
                + "**Klassifizierung:**\n"
                + "- **Kundenstatus:** Bestandskunde (–)\r\n"
                + "- **Dringlichkeit/Priorität:** Normal\n\n"
                + "**Firmeninformationen:**\n"
                + "- **Firma:** Ross & Reiter GmbH & Co. KG\r\n"
                + "- **Adresse:** Am Studio 3, 12000 Berlin, Germany\r\n"
                + "- **USt-ID:** DE 222 000 000\r\n"
                + "- **Kundennummer:** –\n\n"
                + "**Ansprechpartner:**\n"
                + "- **Name:** Konstonio Primultus\r\n"
                + "- **Position:** Operations (ursprünglich Procurement Engineer)\r\n"
                + "- **E-Mail:** xxxxxxx.bbbbbb@ross-reiter.com\r\n"
                + "- **Telefon:** +49 10 12391164\r\n"
                + "- **Mobil:** +49 333 4446139\n\n"
                + "**Details der Anfrage:**\n"
                + "- **Produkt:** Thermigrease® TG XXX61\r\n"
                + "- **Artikelnummer:** –\r\n"
                + "- **Menge:**\r\n"
                + "  - **Jahresbedarf:** ~10 kg\r\n"
                + "  - **Abrufmenge pro Bestellung:** 800–900 g\r\n"
                + "  - **Musteranfrage:** 1–2 Kartuschen (unentgeltlich nicht möglich)\r\n"
                + "- **Zweck:** Testphase für Messgerät\r\n"
                + "- **Offene Frage:** UL-Zulassung/Rating für TG 20061\n\n"
                + "**Lieferbedingungen:**\n"
                + "- **Versandart:** –\r\n"
                + "- **Incoterms:** –\r\n"
                + "- **Besondere Konditionen:** Muster nur gegen Bezahlung, Jahresmenge für Angebot nötig\n\n"
                + "**Allgemeine Hinweise:**\n"
                + "- **Sonderwünsche:** Automatisierte Applikation (Dosiersysteme), Schichtdickenempfehlung "
                + "(0,5–2,0 mm), Formstabilität, Wärmeleitfähigkeit (3,0 W/m·K), "
                + "Temperaturbereich (-30 °C bis +150 °C).\r\n"
                + "- **Referenzen:** Keine genannten.\r\n"
                + "- **Besondere Anforderungen:** UL-Zertifizierung prüfen.\n\n\n";

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 512);

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            assertTrue(chunk.length() <= 512, "Chunk " + i + " exceeds max size: " + chunk.length());
            assertFalse(HEADING.matcher(lastLine(chunk)).matches(),
                    "Chunk " + i + " ends with a heading: " + lastLine(chunk));
            assertFalse(chunk.contains("\r"), "Chunk " + i + " should not contain carriage returns");
        }

        // Each heading must be in the same chunk as its first content line
        assertChunkContainsBoth(chunks, "**Ansprechpartner:**", "Konstonio Primultus");
        assertChunkContainsBoth(chunks, "**Details der Anfrage:**", "Thermigrease");
        assertChunkContainsBoth(chunks, "**Lieferbedingungen:**", "Versandart");
        assertChunkContainsBoth(chunks, "**Allgemeine Hinweise:**", "Sonderwünsche");

        // Nothing got lost
        String joined = String.join("\n", chunks);
        assertTrue(joined.contains("UL-Zertifizierung prüfen."));
    }

    private void assertChunkContainsBoth(List<String> chunks, String heading, String content) {
        for (String chunk : chunks) {
            if (chunk.contains(heading)) {
                assertTrue(chunk.contains(content),
                        "Heading '" + heading + "' is separated from its content '" + content + "'");
                return;
            }
        }
        fail("Heading not found in any chunk: " + heading);
    }

    /**
     * A heading without own content (e.g. '# Summary' directly followed by another
     * heading) must be kept together with the next section.
     */
    @Test
    public void testOrphanHeadingIsCarriedToNextSection() {
        String markup = "A".repeat(50) + "\n"
                + "# Summary\n"
                + "**Klassifizierung:**\n"
                + "- **Status:** Bestandskunde";

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 80);

        assertEquals(2, chunks.size());
        assertEquals("A".repeat(50), chunks.get(0));
        assertTrue(chunks.get(1).startsWith("# Summary"));
        assertTrue(chunks.get(1).contains("**Klassifizierung:**"));
        assertTrue(chunks.get(1).contains("Bestandskunde"));
    }

    /**
     * An oversized section is split at line breaks and the last heading is
     * repeated at the start of each part (instead of using overlapping).
     */
    @Test
    public void testOversizedSectionRepeatsHeading() {
        StringBuilder sb = new StringBuilder("**Details:**\n");
        for (int i = 1; i <= 10; i++) {
            sb.append(String.format("- Line number %02d with some text\n", i));
        }

        List<String> chunks = RAGUtil.chunkMarkdownDocument(sb.toString(), 100);

        assertTrue(chunks.size() >= 2, "Section should be split into several chunks");
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length() <= 100, "Chunk " + i + " exceeds max size");
            assertTrue(chunks.get(i).startsWith("**Details:**"),
                    "Chunk " + i + " should start with the repeated heading");
        }
        // Every line must appear exactly once (no overlapping, nothing lost)
        String joined = String.join("\n", chunks);
        for (int i = 1; i <= 10; i++) {
            assertEquals(1, countOccurrences(joined, String.format("Line number %02d ", i)));
        }
    }

    /**
     * Lines that only start with bold text or are list items with bold labels are
     * NOT headings and must not be repeated as prefix.
     */
    @Test
    public void testBoldTextInLineIsNotAHeading() {
        StringBuilder sb = new StringBuilder("**Details:**\n");
        sb.append("**Artikel** sind hier gesondert zu bestellen.\n");
        sb.append("- **Price :** 50,50\n");
        for (int i = 1; i <= 6; i++) {
            sb.append(String.format("- Line number %02d with some text\n", i));
        }

        List<String> chunks = RAGUtil.chunkMarkdownDocument(sb.toString(), 100);

        assertTrue(chunks.size() >= 2);
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).startsWith("**Details:**"),
                    "Chunk " + i + " should start with the real heading: " + chunks.get(i));
        }
    }

    /**
     * A very long line without spaces below a heading is hard-cut, and the heading
     * is still repeated while the chunk size is respected.
     */
    @Test
    public void testHardCutKeepsHeadingAndSize() {
        String markup = "**H**\n" + "X".repeat(300);

        List<String> chunks = RAGUtil.chunkMarkdownDocument(markup, 100);

        long xCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length() <= 100, "Chunk " + i + " exceeds max size");
            assertTrue(chunks.get(i).startsWith("**H**"));
            xCount += chunks.get(i).chars().filter(c -> c == 'X').count();
        }
        assertEquals(300, xCount, "No content must be lost by the hard cut");
    }
}
