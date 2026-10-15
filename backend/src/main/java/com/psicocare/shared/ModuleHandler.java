package com.psicocare.shared;

import tools.jackson.databind.JsonNode;

public interface ModuleHandler {
    boolean supports(Command command);

    JsonNode execute(Command command);
}
