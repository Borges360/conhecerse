package com.psicocare.shared;

import java.util.*;

public record Actor(UUID tenant, UUID user, Set<String> roles, UUID requestId) {
    public boolean has(String... allowed) {
        return Arrays.stream(allowed).anyMatch(roles::contains);
    }

    public void require(String... allowed) {
        Problem.require(has(allowed), 403, "PERMISSION_DENIED");
    }
}
