package org.imixs.ai.workflow.builder;

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.imixs.ai.workflow.LLMPromptEvent;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;

import jakarta.enterprise.event.Observes;

/**
 * The LLMFileContextBuilder adds a file content stored in a workitem into the
 * prompt template.
 * The template must provide a <<context>> place holder
 * 
 * @author rsoika
 *
 */
public class LLMFileContextBuilder {

    public static final String API_ERROR = "API_ERROR";
    public static final String FILE_CONTENT_REGEX = "(?i)<filecontext>(.*?)</filecontext>";

    private static Logger logger = Logger.getLogger(LLMFileContextBuilder.class.getName());

    public void onEvent(@Observes LLMPromptEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }

        // test if we have a <filecontent> tags and insert the matching file data...
        Pattern pattern = Pattern.compile(FILE_CONTENT_REGEX);
        Matcher matcher = pattern.matcher(event.getPromptTemplate());
        StringBuffer prompt = new StringBuffer();
        while (matcher.find()) {
            String fileContext = "";

            // Extract the file pattern inside <filecontent> tag
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
            matcher.appendReplacement(prompt, fileContext);
        }

        // finally update the prompt template
        matcher.appendTail(prompt); // append prompt
        event.setPromptTemplate(prompt.toString());

    }

}
