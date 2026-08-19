param()

# Negative fixture: inject an expired EX-999 and verify detection.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$exemptionPath = Join-Path $repo 'docs\architecture\exemption-register.md'
$backup = Get-Content $exemptionPath -Raw
$fixtureRow = Get-Content (Join-Path $PSScriptRoot 'expired-exemption-case.md') -Raw

try {
    # Inject fixture row after EX-002 line
    $injected = $backup -replace '(\| EX-002[^\r\n]+\r?\n)', "`$1$fixtureRow`n"
    Set-Content -LiteralPath $exemptionPath -Value $injected -NoNewline
    Write-Host "Injected expired fixture, running verify-governance.ps1..."
    $output = & (Join-Path $repo 'docs\tools\verify-governance.ps1') 2>&1 | Out-String
    $exit = $LASTEXITCODE
    Write-Host $output
    if ($exit -eq 0) {
        Write-Error "Negative fixture FAILED: expected governance check to fail on expired exemption, but exit 0"
        exit 1
    }
    if ($output -notmatch 'exemption expired') {
        Write-Error "Negative fixture FAILED: output missing 'exemption expired' marker"
        exit 1
    }
    Write-Host "Negative fixture PASSED: expired exemption correctly detected (exit $exit)"
} finally {
    Set-Content -LiteralPath $exemptionPath -Value $backup -NoNewline
    Write-Host "Restored exemption-register.md"
}
