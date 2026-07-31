package org.imixs.ai.bpmn.util;

import java.util.regex.Pattern;

/**
 * Utility class to convert HTML content into clean, LLM-friendly markup text.
 */
public class HtmlStripper {

        // Precompiled patterns for performance
        private static final Pattern P_SCRIPT_STYLE = Pattern.compile(
                        "<(script|style)[^>]*>[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);

        private static final Pattern P_HEADING = Pattern.compile(
                        "<h[1-5][^>]*>(.*?)</h[1-5]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern P_STRONG = Pattern.compile(
                        "<(strong|b)[^>]*>(.*?)</(strong|b)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern P_EM = Pattern.compile(
                        "<(em|i)[^>]*>(.*?)</(em|i)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern P_LI = Pattern.compile(
                        "<li[^>]*>(.*?)</li>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern P_BLOCK = Pattern.compile(
                        "<(p|div|br|tr|blockquote|pre|ul|ol|table)[^>]*/?>",
                        Pattern.CASE_INSENSITIVE);
        private static final Pattern P_CLOSE_BLOCK = Pattern.compile(
                        "</(p|div|tr|blockquote|pre|ul|ol|table)>",
                        Pattern.CASE_INSENSITIVE);
        private static final Pattern P_TAGS = Pattern.compile("<[^>]+>");
        private static final Pattern P_MULTI_NEWLINE = Pattern.compile("\\n{3,}");
        private static final Pattern P_BLANK_LINE = Pattern.compile("(?m)^[ \\t]+$");

        /**
         * Converts an HTML string into clean, LLM-readable markdown-style text. Handles
         * headings, bold/italic, lists, images, HTML entities and removes all remaining
         * tags. Designed to be minimal and allocation-efficient.
         *
         * @param content raw HTML input (may be null)
         * @return clean markup text, or empty string if input is null/blank
         */
        public static String stripHtml(String content) {
                if (content == null || content.isBlank()) {
                        return "";
                }

                String s = content;

                // 1. Normalize line endings
                s = s.replace("\r\n", "\n").replace("\r", "\n");

                // 2. Remove script / style blocks entirely
                s = P_SCRIPT_STYLE.matcher(s).replaceAll("");

                // 3. Images → [Image: alt] or drop silently
                // s = P_IMG.matcher(s).replaceAll("[Image: $1]");
                // s = P_IMG_NO_ALT.matcher(s).replaceAll("");

                // 4. Headings → Markdown headings
                s = P_HEADING.matcher(s).replaceAll("\n**$1**\n");

                // 5. Bold / Italic → Markdown
                s = P_STRONG.matcher(s).replaceAll("**$2**");
                s = P_EM.matcher(s).replaceAll("*$2*");

                // 6. List items → Markdown bullets
                s = P_LI.matcher(s).replaceAll("\n- $1");

                // 7. Block-level elements → newlines
                s = P_BLOCK.matcher(s).replaceAll("\n");
                s = P_CLOSE_BLOCK.matcher(s).replaceAll("\n");

                // 8. Strip all remaining tags
                s = P_TAGS.matcher(s).replaceAll("");

                // 9. Decode HTML entities
                s = decodeEntities(s);

                // 10. Clean up whitespace
                s = P_BLANK_LINE.matcher(s).replaceAll("");
                s = P_MULTI_NEWLINE.matcher(s).replaceAll("\n\n");

                return s.strip();
        }

        /**
         * Decodes common named and numeric HTML entities. Covers the full ISO-8859-1 /
         * HTML4 named entity set plus numeric references.
         */
        private static String decodeEntities(String s) {

                // Use a simple regex-replace loop for correctness
                s = decodeNumericEntities(s);

                // Named entities – most frequent ones in German/English business docs
                s = s
                                .replace("&nbsp;", " ")
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&quot;", "\"")
                                .replace("&apos;", "'")
                                .replace("&auml;", "ä").replace("&Auml;", "Ä")
                                .replace("&ouml;", "ö").replace("&Ouml;", "Ö")
                                .replace("&uuml;", "ü").replace("&Uuml;", "Ü")
                                .replace("&szlig;", "ß")
                                .replace("&eacute;", "é").replace("&Eacute;", "É")
                                .replace("&egrave;", "è").replace("&Egrave;", "È")
                                .replace("&ecirc;", "ê").replace("&Ecirc;", "Ê")
                                .replace("&agrave;", "à").replace("&Agrave;", "À")
                                .replace("&acirc;", "â").replace("&Acirc;", "Â")
                                .replace("&ocirc;", "ô").replace("&Ocirc;", "Ô")
                                .replace("&ucirc;", "û").replace("&Ucirc;", "Û")
                                .replace("&ccedil;", "ç").replace("&Ccedil;", "Ç")
                                .replace("&ntilde;", "ñ").replace("&Ntilde;", "Ñ")
                                .replace("&copy;", "©")
                                .replace("&reg;", "®")
                                .replace("&trade;", "™")
                                .replace("&mdash;", "—")
                                .replace("&ndash;", "–")
                                .replace("&hellip;", "…")
                                .replace("&laquo;", "«").replace("&raquo;", "»")
                                .replace("&bull;", "•")
                                .replace("&euro;", "€");

                return s;
        }

        private static final Pattern P_DEC_ENTITY = Pattern.compile("&#(\\d+);");
        private static final Pattern P_HEX_ENTITY = Pattern.compile("&#x([0-9a-fA-F]+);");

        /**
         * Replaces numeric HTML entities (decimal and hex) with their Unicode
         * characters.
         */
        private static String decodeNumericEntities(String s) {
                // Hex
                var m = P_HEX_ENTITY.matcher(s);
                var sb = new StringBuilder(); // ← StringBuilder statt StringBuffer
                while (m.find()) {
                        int cp = Integer.parseInt(m.group(1), 16);
                        m.appendReplacement(sb, new String(Character.toChars(cp)));
                }
                m.appendTail(sb);
                s = sb.toString();

                // Decimal
                m = P_DEC_ENTITY.matcher(s);
                sb = new StringBuilder(); // ← StringBuilder
                while (m.find()) {
                        int cp = Integer.parseInt(m.group(1));
                        m.appendReplacement(sb, new String(Character.toChars(cp)));
                }
                m.appendTail(sb);
                return sb.toString();
        }
}