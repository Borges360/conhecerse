package com.psicocare.shared;

import java.time.Instant;
import java.util.*;

public record Actor(UUID tenant, UUID user, Set<String> roles, Instant mfaAt, UUID requestId) {
    public boolean has(String... allowed) {
        return Arrays.stream(allowed).anyMatch(roles::contains);
    }

    public void require(String... allowed) {
        Problem.require(has(allowed), 403, "PERMISSION_DENIED");
    }

    public void recentMfa() {
        Problem.require(
                mfaAt != null && mfaAt.isAfter(Instant.now().minusSeconds(900)),
                403,
                "RECENT_MFA_REQUIRED");
    }
}
