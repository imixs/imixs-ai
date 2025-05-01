package org.imixs.ai.workflow;

import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Test class to test the LLMAdapter configuration
 * 
 * 
 * @author rsoika
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.WARN)
public class TestLLMAdapter {

    private static Logger logger = Logger.getLogger(TestLLMAdapter.class.getName());

    @InjectMocks
    protected OpenAIAPIAdapter adapter;

    ItemCollection workitem;

    /**
     * The setup method loads t
     * 
     * @throws AdapterException
     * 
     */

}
