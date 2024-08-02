package org.imixs.ai.workflow.builder;

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.imixs.ai.workflow.ImixsAIPromptEvent;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;

import jakarta.enterprise.event.Observes;

/**
 * The LLMFileContextBuilder adds a file content stored in a workitem into the
 * prompt template.
 * The template must provide a <<context>> place holder
 * 
 * @author rsoika
 *
 */
public class ImixsAIFileContextBuilder {

    public static final String PROMPT_ERROR = "PROMPT_ERROR";
    public static final String FILE_CONTENT_REGEX = "(?i)<filecontext>(.*?)</filecontext>";

    private static Logger logger = Logger.getLogger(ImixsAIFileContextBuilder.class.getName());

    public void onEvent(@Observes ImixsAIPromptEvent event) throws AdapterException {
        if (event.getWorkitem() == null) {
            return;
        }

        String prompt = event.getPromptTemplate();
        // test if we have a <filecontent> tags and insert the matching file data...
        Pattern pattern = Pattern.compile(FILE_CONTENT_REGEX);
        Matcher matcher = pattern.matcher(prompt);

        while (matcher.find()) {
            String fileContext = "";

            // Extract the file pattern inside <filecontent> tag
            String fullTag = matcher.group(0);
            String fileNameRegex = matcher.group(1);
            Pattern filenamePattern = Pattern.compile(fileNameRegex);

            // test for all files attached to this workitem....
            List<FileData> files = event.getWorkitem().getFileData();
            if (files != null && files.size() > 0) {
                // aggregate all text attributes form attached files
                // apply only files matching the filename pattern
                for (FileData file : files) {
                    if (filenamePattern == null || filenamePattern.matcher(file.getName()).find()) {
                        logger.finest("...adding content of '" + file.getName() + "'.....");
                        ItemCollection metadata = new ItemCollection(file.getAttributes());
                        String _text = metadata.getItemValueString("text");
                        if (!_text.isEmpty()) {
                            fileContext = fileContext + _text + " \n\n";
                        }
                    }
                }
            }
            // replace the regex with the fileContext String...
            if (fileContext == null || fileContext.isEmpty()) {
                throw new AdapterException(ImixsAIFileContextBuilder.class.getSimpleName(), PROMPT_ERROR,
                        "No File Context found in current workitem");
            }

            fileContext = cleanupFileContext(fileContext);

            prompt = prompt.replace(fullTag, fileContext);
            matcher = pattern.matcher(prompt);

        }

        // finally update the prompt template
        // matcher.appendTail(prompt); // append prompt
        event.setPromptTemplate(prompt.toString());

    }

    /**
     * This method removes multiple newlines in the file context.
     * The occurrence of multiple \n may cause infinite loops with complex prompt
     * e.g. in Mistral 7b
     * 
     * 
     * @See Issue #22
     * @See https://github.com/ggerganov/llama.cpp/issues/3969
     *
     * @param fileContext
     * @return
     */
    private String cleanupFileContext(String fileContext) {

        // First iterate over all lines and trim the content of each line
        StringBuilder trimmedContentBuffer = new StringBuilder();
        String[] lines = fileContext.split("\n");

        for (String line : lines) {
            String trimmedLine = trimRight(line);
            if (!trimmedLine.isEmpty()) {
                trimmedContentBuffer.append(trimmedLine).append("\n");
            }
        }
        // Remove the last newline character if the result is not empty
        if (trimmedContentBuffer.length() > 0) {
            trimmedContentBuffer.setLength(trimmedContentBuffer.length() - 1);
        }

        fileContext = trimmedContentBuffer.toString();

        // finally remove repeating new lines
        fileContext = fileContext.replace("\n\n\n", "\n\n");

        return fileContext;
    }

    /**
     * Trim only the right part of a line
     * 
     * @param input
     * @return
     */
    public static String trimRight(String input) {
        int end = input.length();
        while (end > 0 && Character.isWhitespace(input.charAt(end - 1))) {
            end--;
        }
        return input.substring(0, end);
    }
}
