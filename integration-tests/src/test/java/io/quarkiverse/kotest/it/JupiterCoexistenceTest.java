package io.quarkiverse.kotest.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit Jupiter test (no @QuarkusTest) that runs alongside Kotest specs
 * in the same Surefire execution. Validates that Jupiter and Kotest engines
 * coexist without interfering with each other.
 *
 * Note: Mixed @QuarkusTest across Jupiter and Kotest in the same Surefire fork
 * is not supported in v1.0 — Jupiter shuts down the Quarkus app between engines,
 * leaving a polluted classloader for Kotest.
 */
public class JupiterCoexistenceTest {

    @Test
    void jupiterEngineRunsAlongsideKotest() {
        Assertions.assertTrue(true, "Jupiter engine executes without interfering with Kotest");
    }
}