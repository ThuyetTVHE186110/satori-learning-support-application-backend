package com.fu.assignment.ai;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Custom condition for AI response schema tests.
 * 
 * Tests with @EnabledIfOpenAiKeyExists will only run if:
 * 1. OPENAI_API_KEY environment variable is set, AND
 * 2. It's NOT the dummy test key
 * 
 * If skipped: logs helpful message with setup instructions.
 */
@Slf4j
public class OpenAiKeyExistsCondition implements ExecutionCondition {

    private static final String DISPLAY_NAME = "OpenAI API Key Exists";
    private static final String OPENAI_API_KEY_ENV = "OPENAI_API_KEY";
    private static final String DUMMY_KEY = "test-dummy-key";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        
        String apiKey = System.getenv(OPENAI_API_KEY_ENV);
        
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals(DUMMY_KEY)) {
            String message = String.format(
                    """
                    ⏭️  AI Response Schema Validation Test SKIPPED
                    
                    Reason: OPENAI_API_KEY environment variable not set or is dummy key.
                    
                    To run this test with REAL OpenAI API calls:
                    
                    1️⃣  Set environment variable (PowerShell):
                        $env:OPENAI_API_KEY = "sk-your-actual-key-here"
                    
                    2️⃣  Or (Command Prompt):
                        setx OPENAI_API_KEY sk-your-actual-key-here
                    
                    3️⃣  Or (Linux/Mac):
                        export OPENAI_API_KEY="sk-your-actual-key-here"
                    
                    4️⃣  Restart IDE and re-run test
                    
                    ℹ️  Get API key: https://platform.openai.com/api-keys
                    ℹ️  Cost: ~$0.002 per test run (gpt-4o-mini model)
                    ℹ️  Full setup: docs/AI_RESPONSE_SCHEMA_VALIDATION_TEST.md
                    """
            );
            
            log.info(message);
            
            return ConditionEvaluationResult.disabled(DISPLAY_NAME, 
                    "OPENAI_API_KEY not configured");
        }
        
        log.info("✅ OpenAI API Key found - Running AI response validation tests (will call real API)");
        return ConditionEvaluationResult.enabled(DISPLAY_NAME);
    }
}

