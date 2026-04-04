@echo off
REM ============================================================================
REM Setup script for AI Response Schema Validation Tests (Windows)
REM ============================================================================
REM
REM Purpose: Configure environment to run AiQuizResponseSchemaValidationTest
REM
REM Usage:
REM   1. Customize: Replace OPENAI_API_KEY value below
REM   2. Run: setup-ai-test.bat
REM   3. Close and reopen IDE for changes to take effect
REM
REM ============================================================================

cls
echo.
echo ============================================================================
echo AI Response Schema Validation Test - Setup (Windows)
echo ============================================================================
echo.

REM Get OpenAI API key from user or command line argument
if "%1"=="" (
    echo Enter your OpenAI API key (from https://platform.openai.com/api-keys)
    echo Or press Ctrl+C to cancel
    echo.
    set /p OPENAI_API_KEY="API Key: "
) else (
    set OPENAI_API_KEY=%1
)

if "%OPENAI_API_KEY%"=="" (
    echo ERROR: API key not provided
    exit /b 1
)

if "%OPENAI_API_KEY:~0,3%"=="sk-" (
    echo.
    echo ✓ Valid OpenAI API key format detected (sk-...)
) else (
    echo.
    echo ⚠ WARNING: API key might be invalid (should start with sk-)
    pause
)

REM Set environment variable permanently (requires admin)
echo.
echo Setting OPENAI_API_KEY environment variable...
setx OPENAI_API_KEY "%OPENAI_API_KEY%"

if errorlevel 1 (
    echo.
    echo ERROR: Failed to set environment variable (admin required?)
    echo.
    echo Alternative: Set manually in PowerShell:
    echo $env:OPENAI_API_KEY = "%OPENAI_API_KEY%"
    pause
    exit /b 1
)

echo ✓ Environment variable set successfully
echo.
echo Next steps:
echo   1. Close all IDE windows completely
echo   2. Reopen IDE
echo   3. Run test: AiQuizResponseSchemaValidationTest
echo.
echo For detailed instructions, see: docs/AI_RESPONSE_SCHEMA_VALIDATION_TEST.md
echo.
pause

