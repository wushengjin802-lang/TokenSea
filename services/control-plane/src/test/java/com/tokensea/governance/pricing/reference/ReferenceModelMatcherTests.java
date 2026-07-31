package com.tokensea.governance.pricing.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceModelMatcherTests {
    @Test
    void preservesProviderSemanticsForAggregatedModels() {
        String openRouter = ReferenceModelMatcher.canonical("openrouter", "openai/gpt-4o");
        String directOpenAi = ReferenceModelMatcher.canonical("openai", "gpt-4o");

        assertEquals("openrouter/openai/gpt-4o", openRouter);
        assertEquals("openai/gpt-4o", directOpenAi);
        assertNotEquals(openRouter, directOpenAi);
    }

    @Test
    void avoidsDuplicatingExistingProviderPrefixAndNormalizesCase() {
        assertEquals("openai/gpt-4o", ReferenceModelMatcher.canonical(" OpenAI ", "OPENAI/GPT-4O"));
        assertEquals("qwen/qwen_plus", ReferenceModelMatcher.canonical("Qwen", "qwen_plus"));
    }

    @Test
    void rejectsMissingIdentityParts() {
        assertThrows(IllegalArgumentException.class, () -> ReferenceModelMatcher.canonical("", "gpt-4o"));
        assertThrows(IllegalArgumentException.class, () -> ReferenceModelMatcher.canonical("openai", " "));
    }
}
