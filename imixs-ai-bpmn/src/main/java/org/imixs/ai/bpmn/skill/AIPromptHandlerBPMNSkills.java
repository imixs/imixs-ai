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

package org.imixs.ai.bpmn.skill;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.imixs.ai.workflow.ImixsAIPromptEvent;
import org.imixs.workflow.exceptions.AdapterException;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The AIPromptHandlerBPMNSkills adds a BPMN Skill template in a workitem into
 * the prompt template. The template must provide a <BPMNSKILLs/> place holder.
 * <p>
 * The method uses the BPMNSkillService to build/load a bpmn skill tree form the
 * current workitem
 * <p>
 * Filtering rules applied while building the skill tree:
 * <ul>
 * <li><b>Model level</b> - excluded (cascading) if its documentation is blank
 * or contains the ignore tag.</li>
 * <li><b>Process/Group level</b> - excluded (cascading) if its documentation
 * is blank or contains the ignore tag.</li>
 * <li><b>Task level</b> - blank documentation is allowed; only excluded if it
 * contains the ignore tag.</li>
 * <li><b>Event level</b> - blank documentation is allowed; only excluded if
 * it contains the ignore tag.</li>
 * </ul>
 * A cascading exclusion on Model or Group level always removes the entire
 * branch beneath it, ensuring the resulting tree is always complete and
 * consistent for the LLM.
 *
 * @author rsoika
 *
 */
public class AIPromptHandlerBPMNSkills {

    public static final String ITEM_AGENT_SKILLS = "ai.skill.bpmn";

    public static final String PROMPT_ERROR = "PROMPT_ERROR";

    @Inject
    BPMNSkillCache bpmnSkillCache;

    /**
     * Matches: <skill.bpmn/> <SKILL.BPMN/> <Skill.Bpmn /> etc.
     */
    private static final Pattern BPMN_SKILLS_PATTERN = Pattern.compile("<\\s*skill\\.bpmn\\s*/>",
            Pattern.CASE_INSENSITIVE);

    private static final Logger logger = Logger.getLogger(AIPromptHandlerBPMNSkills.class.getName());

    public void onEvent(@Observes ImixsAIPromptEvent event) throws AdapterException {
        if (event == null || event.getWorkitem() == null) {
            return;
        }

        String prompt = event.getPromptTemplate();

        if (prompt == null || prompt.isBlank()) {
            return;
        }

        Matcher matcher = BPMN_SKILLS_PATTERN.matcher(prompt);
        // Update Prompt Template?
        if (matcher.find()) {
            String skillSnapshot = bpmnSkillCache.getBPMNSkillTree();
            if (skillSnapshot != null && !skillSnapshot.isBlank()) {
                logger.info("├── AIPromptHandlerBPMNSkills: resolving bpmn skills...");
                prompt = matcher.replaceAll(Matcher.quoteReplacement(skillSnapshot));
                logger.info("└── ✅ BPMN skill context injected into prompt.");
                event.setPromptTemplate(prompt);
            }
        }

    }

}