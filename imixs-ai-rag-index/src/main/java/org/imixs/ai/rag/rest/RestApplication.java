package org.imixs.ai.rag.rest;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Activates Jakarta REST. All resources of the index service are published
 * below the context path '/api'.
 */
@ApplicationPath("/api")
public class RestApplication extends Application {
    // no further configuration needed - resources are discovered automatically
}
