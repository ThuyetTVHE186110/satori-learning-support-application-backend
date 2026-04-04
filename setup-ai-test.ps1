# ============================================================================
# Setup script for AI Response Schema Validation Tests (PowerShell)
# ============================================================================
#
# Purpose: Configure environment to run AiQuizResponseSchemaValidationTest
#
# Usage (method 1 - interactive):
#   PS> .\setup-ai-test.ps1
#
# Usage (method 2 - with API key as argument):
#   PS> .\setup-ai-test.ps1 -ApiKey "sk-your-key-here"
#
# ============================================================================

param(
    [string]$ApiKey = ""
)

# Colors for output
$SuccessColor = 'Green'
$ErrorColor = 'Red'
$WarningColor = 'Yellow'
$InfoColor = 'Cyan'

Write-Host "`n" -NoNewline
Write-Host "============================================================================" -ForegroundColor $InfoColor
Write-Host "AI Response Schema Validation Test - Setup (PowerShell)" -ForegroundColor $InfoColor
Write-Host "============================================================================" -ForegroundColor $InfoColor
Write-Host ""

# Get API key if not provided
if ([string]::IsNullOrEmpty($ApiKey)) {
    Write-Host "Enter your OpenAI API key (from https://platform.openai.com/api-keys)" -ForegroundColor White
    Write-Host "Or press Ctrl+C to cancel`n" -ForegroundColor White
    $ApiKey = Read-Host "API Key"
}

# Validate API key
if ([string]::IsNullOrEmpty($ApiKey)) {
    Write-Host "ERROR: API key not provided" -ForegroundColor $ErrorColor
    exit 1
}

if ($ApiKey.StartsWith("sk-")) {
    Write-Host "`n✓ Valid OpenAI API key format detected (sk-...)" -ForegroundColor $SuccessColor
} else {
    Write-Host "`n⚠ WARNING: API key might be invalid (should start with sk-...)" -ForegroundColor $WarningColor
    $confirm = Read-Host "Continue anyway? (y/n)"
    if ($confirm -ne 'y' -and $confirm -ne 'Y') {
        exit 1
    }
}

# Set environment variable for current session (immediate)
Write-Host "`nSetting OPENAI_API_KEY for current session..." -ForegroundColor White
$env:OPENAI_API_KEY = $ApiKey
Write-Host "✓ Current session: OPENAI_API_KEY is now set" -ForegroundColor $SuccessColor

# Option to set permanently
Write-Host ""
Write-Host "Do you want to set OPENAI_API_KEY permanently in system environment?" -ForegroundColor White
Write-Host "(This requires Administrator privileges)" -ForegroundColor $WarningColor
$setPermanent = Read-Host "Set permanently? (y/n)"

if ($setPermanent -eq 'y' -or $setPermanent -eq 'Y') {
    try {
        Write-Host "`nAttempting to set system environment variable..." -ForegroundColor White
        [Environment]::SetEnvironmentVariable('OPENAI_API_KEY', $ApiKey, 'User')
        Write-Host "✓ Permanent environment variable set (User scope)" -ForegroundColor $SuccessColor
        Write-Host "  (Applies to all new sessions)" -ForegroundColor $InfoColor
    } catch {
        Write-Host "ERROR: Failed to set environment variable" -ForegroundColor $ErrorColor
        Write-Host "  Message: $_" -ForegroundColor $ErrorColor
        Write-Host "`n  This is OK - you can still run tests using current session variable" -ForegroundColor $InfoColor
    }
}

# Verify it's set
Write-Host ""
Write-Host "Verifying OPENAI_API_KEY is set..." -ForegroundColor White
$currentKey = $env:OPENAI_API_KEY
if ($currentKey -eq $ApiKey) {
    Write-Host "✓ OPENAI_API_KEY is correctly set in current session" -ForegroundColor $SuccessColor
} else {
    Write-Host "ERROR: OPENAI_API_KEY not set correctly" -ForegroundColor $ErrorColor
    exit 1
}

# Next steps
Write-Host "`n" -NoNewline
Write-Host "Next steps:" -ForegroundColor $InfoColor
Write-Host "  1. Open IDE or run tests from this PowerShell session" -ForegroundColor White
Write-Host "  2. Run test: AiQuizResponseSchemaValidationTest" -ForegroundColor White
Write-Host "  3. If using IDE, close and reopen it for permanent changes" -ForegroundColor White
Write-Host ""
Write-Host "For detailed instructions: " -ForegroundColor White -NoNewline
Write-Host "docs/AI_RESPONSE_SCHEMA_VALIDATION_TEST.md" -ForegroundColor $InfoColor
Write-Host ""
Write-Host "Run tests:" -ForegroundColor White
Write-Host "  mvn -pl modules/assignment -am test -Dtest=AiQuizResponseSchemaValidationTest" -ForegroundColor $InfoColor
Write-Host ""

