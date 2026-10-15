package com.psicocare;

import static org.junit.jupiter.api.Assertions.*;

import com.psicocare.shared.Problem;
import com.psicocare.worker.WorkerEngine;

import org.junit.jupiter.api.Test;

class EventVersionTest {
    @Test
    void acceptsCurrentMajor() {
        assertDoesNotThrow(() -> WorkerEngine.requireEventVersion(1));
    }

    @Test
    void refusesUnknownMajorWithoutDispatch() {
        for (int version : new int[] {0, 2, -1, Integer.MAX_VALUE}) {
            Problem problem =
                    assertThrows(Problem.class, () -> WorkerEngine.requireEventVersion(version));
            assertEquals("UNSUPPORTED_EVENT_VERSION", problem.code);
        }
    }
}
