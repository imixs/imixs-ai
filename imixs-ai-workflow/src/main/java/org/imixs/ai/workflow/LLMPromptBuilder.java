package org.imixs.ai.workflow;

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.ProcessingErrorException;

/**
 * This builder class builds a prompt from the data stored in a workitem.
 * 
 * The prompt is based on a promptTempalte and a text-context that is part of
 * the final prompt. The builder class generates the context based on a
 * combination of item values and file content.
 * 
 * The promptTemplate is always a XML text with the full data to be send to the
 * Imixs-AI endpoint. The template must provide a <<context>> place holder
 * 
 * @author rsoika
 *
 */
public class LLMPromptBuilder {

    public static final String API_ERROR = "API_ERROR";

    private static Logger logger = Logger.getLogger(LLMPromptBuilder.class.getName());

    private List<String> itemNames = null;
    private boolean ignoreFiles = false;
    private Pattern filenamePattern = null;
    private ItemCollection workitem = null;
    String promptTemplate = null;

    /**
     * Construct a new Builder instance to build a ML content
     * 
     * @param itemNames
     * @param ignoreFiles
     */
    public LLMPromptBuilder(String promptTemplate, ItemCollection workitem, List<String> itemNames,
            boolean ignoreFiles,
            Pattern mlFilenamePattern) {
        super();
        this.workitem = workitem;
        this.itemNames = itemNames;
        this.ignoreFiles = ignoreFiles;
        this.filenamePattern = mlFilenamePattern;
        this.promptTemplate = promptTemplate;
    }

    /**
     * This method builds a new text content based on a given workiem. The method
     * build the content form the ml-content items and the file attachments.
     * <p>
     * File attachments can be ignored setting the flag 'ignorefiles'.
     * 
     * @return - text content
     */
    public String build() {
        String promptContext = "";

        // first build content form provided items...
        if (itemNames != null && itemNames.size() > 0) {
            for (String itemName : itemNames) {
                promptContext = promptContext + itemName + ": \n";
                List<String> values = workitem.getItemValueList(itemName, String.class);
                for (String value : values) {
                    promptContext = promptContext + value + "\n";
                }
            }
            promptContext = promptContext + "\n\n";
        }

        // now we add the filedata ...
        if (!ignoreFiles) {
            List<FileData> files = workitem.getFileData();
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
        }

        // finally we put the context into the promptTemplate and return the result
        String prompt = promptTemplate;
        validatePromptTemplate(prompt);

        prompt = prompt.replace("<<context>>", promptContext);

        return promptContext;

    }

    /**
     * Validate the prompt template
     * 
     */
    private void validatePromptTemplate(String inputString) {
        String pattern = "<<CONTEXT>>";
        Pattern p = Pattern.compile(Pattern.quote(pattern));
        Matcher m = p.matcher(inputString);
        int count = 0;
        while (m.find()) {
            count++;
        }

        if (count == 0) {
            throw new ProcessingErrorException(LLMPromptBuilder.class.getSimpleName(), API_ERROR,
                    "invalid prompt-template - missing <<context>> placeholder!");
        }
        if (count > 1) {
            throw new ProcessingErrorException(LLMPromptBuilder.class.getSimpleName(), API_ERROR,
                    "invalid prompt-template - more than one <<context>> placeholder found!");
        }

    }
}
