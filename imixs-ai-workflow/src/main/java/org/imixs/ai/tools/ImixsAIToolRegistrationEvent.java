package org.imixs.ai.tools;

import java.util.ArrayList;
import java.util.List;

import org.imixs.ai.ImixsAIContextHandler;

/**
 * CDI event can be fired before the first LLM call in an agent loop to collect
 * all available tool/function definitions from registered handlers. Each
 * observer adds its own function definitions via addFunction(). The
 * AIAgentOperator then registers all collected definitions with the
 * contextHandler.
 */
public class ImixsAIToolRegistrationEvent {
    private final ImixsAIContextHandler contextHandler;
    private final List<FunctionDefinition> functions = new ArrayList<>();

    /**
     * Holds a single function definition to be registered with the LLM context.
     */
    public static class FunctionDefinition {
        private final String name;
        private final String description;
        private final String schema;

        public FunctionDefinition(String name, String description, String schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getSchema() {
            return schema;
        }
    }

    public ImixsAIToolRegistrationEvent(ImixsAIContextHandler contextHandler) {
        this.contextHandler = contextHandler;
    }

    public ImixsAIContextHandler getContextHandler() {
        return contextHandler;
    }

    /**
     * Called by each handler to register its function definition.
     */
    public void addFunction(String name, String description, String schema) {
        functions.add(new FunctionDefinition(name, description, schema));
    }

    public List<FunctionDefinition> getFunctions() {
        return functions;
    }
}