package org.imixs.ai.workflow.builder;

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.imixs.ai.workflow.LLMPromptEvent;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.ProcessingErrorException;

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
    public static final String PROMPT_CONTEXT = "<<CONTEXT>>";

    private static Logger logger = Logger.getLogger(LLMFileContextBuilder.class.getName());

    private Pattern filenamePattern = null;

    public void onEvent(@Observes LLMPromptEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }

        String prompt = event.getPromptTemplate();
        validatePromptTemplate(prompt);

        // test if we have a <<context>> and insert the file data...
        if (prompt.contains(PROMPT_CONTEXT)) {
            String promptContext = "";
            List<FileData> files = event.getWorkitem().getFileData();
            if (files != null && files.size() > 0) {
                // aggregate all text attributes form attached files
                // apply an optional regex for filenames
                for (FileData file : files) {
                    // test if the filename matches the pattern or the pattern is null
                    if (filenamePattern == null || filenamePattern.matcher(file.getName()).find()) {
                        logger.info("...analyzing content of '" + file.getName() + "'.....");
                        ItemCollection metadata = new ItemCollection(file.getAttributes());
                        String _text = metadata.getItemValueString("text");
                        if (!_text.isEmpty()) {
                            promptContext = promptContext + _text + " \n\n";
                        }
                    }
                }
            }
            // finally put the context into the promptTemplate
            prompt = prompt.replace(PROMPT_CONTEXT, promptContext);
        }

        // update the prompt tempalte
        event.setPromptTemplate(prompt);

    }

    /**
     * Validate the prompt template
     * 
     */
    private void validatePromptTemplate(String inputString) {
        String pattern = PROMPT_CONTEXT;
        Pattern p = Pattern.compile(Pattern.quote(pattern));
        Matcher m = p.matcher(inputString);
        int count = 0;
        while (m.find()) {
            count++;
        }

        if (count > 1) {
            throw new ProcessingErrorException(LLMFileContextBuilder.class.getSimpleName(), API_ERROR,
                    "invalid prompt-template - more than one <<context>> placeholder found!");
        }

    }
}
