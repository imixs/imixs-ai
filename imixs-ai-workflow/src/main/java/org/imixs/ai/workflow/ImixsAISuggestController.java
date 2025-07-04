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

package org.imixs.ai.workflow;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.imixs.workflow.faces.data.WorkflowController;

import jakarta.enterprise.context.ConversationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * The MLController CDI Bean provides Front-End methods for the ml integration.
 * The method getJSON returns a sringified JSON object providing the mls status
 * and ml items.
 * <p>
 * The method search provides a suggest list searching a phrase within the
 * document content of the current workitem.
 * 
 * @author rsoika
 *
 */
@Named("llmSuggestController")
@ConversationScoped
public class ImixsAISuggestController implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(ImixsAISuggestController.class.getName());

    public static List<String> STOP_PHRASE_LIST = Arrays.asList("\n", "  ", ", ", ". ", ".\n");

    @Inject
    protected WorkflowController workflowController;

    private List<String> searchResult = null;

    /**
     * Returns a JSON object containing the current ml result status and the item
     * names collected by the MLAdatper.
     * <p>
     * 
     * <pre>
     * {@code
     *  {"mode":"ON",
     *   "items": [
     *              "_invoicenumber",
     *              "_invoicetotal"
     *            ]
     *  }
     * }
     * </pre>
     * <p>
     * Only the modes ON|OFF are supported.
     * 
     * @return
     */
    @SuppressWarnings("unchecked")
    public String getSuggestResult() {
        String result = "{";

        if (workflowController.getWorkitem() != null) {
            List<String> suggestItems = new ArrayList<String>();

            // default mode is OFF
            String mode = workflowController.getWorkitem().getItemValueString("ai.suggest.mode");
            if (!mode.equalsIgnoreCase("ON")) {
                mode = "OFF";
            }
            suggestItems = workflowController.getWorkitem().getItemValue("ai.suggest.items");
            // set mode
            result = result + "\"mode\":\"" + mode + "\"";

            if (suggestItems != null && suggestItems.size() > 0) {
                result = result + ",\"items\":";
                // create a json value array out of the list of item names...
                result = result
                        + suggestItems.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(",", "[", "]"));
            }

        }

        result = result + "}";
        return result;
    }

    /**
     * This method searches a text phrase within the document content of attached
     * documents. The textphrase is extracted from the RequestParamterMap.
     * <p>
     * JSF Integration:
     * 
     * {@code 
     * 
     * <h:commandScript name="imixsOfficeWorkflow.mlSearch" action=
     * "#{mlController.search()}" rendered="#{mlController!=null}" render=
     * "ml-results" /> }
     * 
     * <p>
     * JavaScript Example:
     * 
     * <pre>
     * {@code
     *  imixsOfficeWorkflow.mlSearch({ item: '_invoicenumber' })
     *  }
     * </pre>
     * 
     */
    public void search() {

        // get the param from faces context....
        FacesContext fc = FacesContext.getCurrentInstance();
        String phrase = fc.getExternalContext().getRequestParameterMap().get("phrase");

        logger.finest("search prase '" + phrase + "'");

        // String input =workflowController.getWorkitem().getItemValueString(itemName);
        if (phrase == null || phrase.length() < 2) {
            return;
        }

        logger.fine("search for=" + phrase);
        searchResult = new ArrayList<String>();

        // the text to search for is stored by the LLMService in the item with the name
        // 'ai.result.item'
        String suggestItem = workflowController.getWorkitem().getItemValueString(OpenAIAPIService.ITEM_AI_RESULT_ITEM);
        String text = workflowController.getWorkitem().getItemValueString(suggestItem);
        if (text != null) {
            searchResult = findMatches(phrase, text);
        }

    }

    /**
     * Returns a matching text sequences form a search phrase
     * <p>
     * e.g. 'cat' is found in 'Catalog'
     * <p>
     * The method also searches for computed phrases based on the first hit with
     * spaces. The method combines words separated by spaces.
     * <p>
     * A so called stop-character-phrase indicates the end for a computed phrase.
     * For example more than one space, \n, '. ' or ', '
     * 
     * @param data
     * @return
     */
    public static List<String> findMatches(String phrase, final String _text) {

        List<String> result = new ArrayList<String>();

        String text = _text + " "; // we add a tailing space for later extracting..

        // search text is lower case!
        String searchText = text.toLowerCase();

        String searchPhrase = phrase.toLowerCase();
        String originSearchPhrase = searchPhrase;

        // find start pos...
        int index = 0;
        while (true) {
            int found = searchText.indexOf(searchPhrase, index);
            if (found > -1) {
                String hit = null;
                boolean tailingSpace = false;
                int endPos = -1;
                // test if the text ends with a space or a newline
                int nextSpacePos = searchText.indexOf(" ", found + searchPhrase.length() + 0);
                // int nextStopPhrase = searchText.indexOf("\n", found + searchPhrase.length() +
                // 1);
                int nextStopPhrase = indexOfStopPhrase(searchText, found + searchPhrase.length() + 0);

                if (nextStopPhrase > -1) {
                    // there was a stopPhrase, so this may be the best match...
                    endPos = nextStopPhrase;
                    if (nextSpacePos > -1 && nextSpacePos < nextStopPhrase) {
                        // there is a space before a possible stopPhrase...
                        endPos = nextSpacePos;
                        tailingSpace = true;
                    }
                } else {
                    if (nextSpacePos > -1) {
                        endPos = nextSpacePos;
                        tailingSpace = true;
                    }
                }

                if (endPos > -1) {
                    hit = text.substring(found, endPos).trim();
                }

                if (hit == null) {
                    break;
                }
                // if the hit is longer than 64 chars - we cut it....
                if (hit.length() > 64) {
                    hit = hit.substring(0, 64).trim();
                }

                if (!result.contains(hit)) {

                    // We are cleaning the hit from tailing special characters
                    // hits like "Software &" should not be returned
                    // hits like "Software & Hardware" are ok
                    // so we look for special characters to block bad looking results
                    if (!endsWithSpecialCharacter(hit)) {
                        result.add(hit);
                    }
                }
                // lets see if it makes sense to search for variant with spaces
                if (tailingSpace && !searchPhrase.equals(hit.toLowerCase() + " ")) {
                    searchPhrase = hit.toLowerCase() + " ";
                } else {
                    // reset to origin search phrase
                    searchPhrase = originSearchPhrase;
                    index = found + hit.length() + 1;
                }

            } else {
                // do we still work with the originSearchPhrase?
                if (searchPhrase.equals(originSearchPhrase)) {
                    // no more matches
                    break;
                } else {
                    // reset origin phrase
                    index = index + searchPhrase.length() + 1;
                    searchPhrase = originSearchPhrase;
                }

            }

            // if max count of 12 matches is reached we break;
            if (result.size() >= 12) {
                break;
            }

        }

        // Finally clean the result set entries with tailing special characters
        // hits like "Software &" should not be returned
        // hits like "Software & Hardware" are ok
        // so we look for special characters to filter bad looking results
        // List<String> cleanedResult = result.stream().filter(c ->
        // endsWithSpecialCharacter(c)==false).collect(Collectors.toList());

        // return cleanedResult;
        return result;
    }

    /**
     * This helper method tests if a given string ends with a special character like
     * '&', '.', ',', ...
     * 
     * @param s
     * @return true if a special character was found at the end of the string.
     */
    public static boolean endsWithSpecialCharacter(String s) {

        char c = s.charAt(s.length() - 1);

        if ((c >= 34 && c <= 47) || (c >= 58 && c <= 64) || (c == '\n') || (c == '\t')) {
            return true;
        }

        // U+0083 - U+00BF
        if ((c >= 161 && c <= 191)) {
            return true;
        }

        if ((c >= '\u2000')) {
            return true;
        }

        return false;
    }

    /**
     * This method finds the earliest stop phrase in a given string.
     * <p>
     * Stop phrases are more than one space, '. ', ', ', '\n'
     * 
     * @return
     */
    public static int indexOfStopPhrase(String searchText, int fromIndex) {
        int stopPhrasePos = -1;
        int bestMatch = 99999999;

        for (String stopPhrase : STOP_PHRASE_LIST) {
            int match = searchText.indexOf(stopPhrase, fromIndex);

            if (match > -1) {
                if (match < bestMatch) {
                    bestMatch = match;
                    stopPhrasePos = bestMatch;
                }
            }
        }
        return stopPhrasePos;
    }

    /**
     * Returns a matching text sequence form a search phrase
     * <p>
     * e.g. 'cat' is found in 'Catalog'
     * <p>
     * The method also searches for computed phrases based on the first hit.
     * <p>
     * It seems that this kind of problem can not be solved with regex.
     * 
     * @param data
     * @return
     */
    @Deprecated
    public static List<String> findMatchesOld(String phrase, final String _text) {

        List<String> result = new ArrayList<String>();

        String text = _text;
        // replace \n with space
        text = text.replace("\n", " ");

        // search text is lower case!
        String searchText = text.toLowerCase();

        String searchPhrase = phrase.toLowerCase();
        String originSearchPhrase = searchPhrase;

        // find start pos...
        int index = 0;
        while (true) {
            int found = searchText.indexOf(searchPhrase, index);
            if (found > -1) {
                String hit = null;

                // take the phrase up to the 1st space
                int endPos = searchText.indexOf(" ", found + searchPhrase.length() + 1);
                // if we do not found a space, we search for a .
                if (endPos == -1) {
                    endPos = searchText.indexOf(".", found + searchPhrase.length() + 1);
                }
                // if we do not found a ., we search for a \n
                if (endPos == -1) {
                    endPos = searchText.indexOf("\n", found + searchPhrase.length() + 1);
                }

                if (endPos > -1) {
                    hit = text.substring(found, endPos).trim();
                } else {
                    // take it as is
                    hit = text.substring(found).trim();

                }

                // if the hit is longer than 64 chars - we cut it....
                if (hit.length() > 64) {
                    hit = hit.substring(0, 64).trim();
                }

                if (!result.contains(hit)) {
                    result.add(hit);

                    // lets see if it makes sense to search for variant with spaces
                    if (!searchPhrase.endsWith(" ")) {
                        searchPhrase = hit.toLowerCase() + " ";
                    } else {
                        // reset to origin search phrase
                        searchPhrase = originSearchPhrase;
                        index = found + hit.length();
                    }
                } else {
                    index = found + hit.length();
                }

            } else {
                // no more matches
                break;
            }

            // if max count of 12 matches is reached we break;
            if (result.size() >= 12) {
                break;
            }

        }
        return result;
    }

    public List<String> getSearchResult() {
        return searchResult;
    }

    /**
     * This method reset the search and input state.
     */
    public void reset() {
        searchResult = new ArrayList<String>();
        logger.fine("reset");
    }

}
