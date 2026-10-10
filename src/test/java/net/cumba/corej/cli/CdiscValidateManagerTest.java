package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

/**
 * PLAN-dictionary-backed-formats owner ruling OQ3 (a), 2026-10-10: a validation run loads its data
 * through a local manager with dictionary formats switched off — the engine reads the dictionaries
 * itself, so the manager parses none of them at load.
 */
class CdiscValidateManagerTest
{

    @Test
    void theRunsManagerResolvesNoDictionaryFormats()
    {
        assertFalse(CdiscValidate.validationManager().isResolvingDictionaryFormats());
        assertNotSame(CdiscValidate.validationManager(), CdiscValidate.validationManager(),
                "a new manager per run");
    }
}
