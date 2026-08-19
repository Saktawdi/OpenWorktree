param(
    [switch]$StrictAdmission
)

# verify-fast: CI fast path for GOV-DEP-001 (ArchUnit) + GOV-DOC-001/GOV-CPLX-001 (governance.ps1)
# Implements verification-contract.md for verify-fast profile.
# L3 implementation for Phase 0 next-actions P1: "将已实现的文档/复杂度最小检查接入 verify-fast CI，并补过期豁免 negative fixture"

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$errors = New-Object System.Collections.Generic.List[string]

function Add-Error([string]$m) { $errors.Add($m) }

Write-Host "=== verify-fast (GOV-DEP-001 / GOV-DOC-001 / GOV-CPLX-001) ==="

# 1. GOV-DEP-001: ArchUnit via mvn
Write-Host "[GOV-DEP-001] Running ArchUnit: gate.arch.ArchitectureTest"
$mvnArgs = @("--%", "-Dtest=gate.arch.ArchitectureTest", "-Dsurefire.failIfNoSpecifiedTests=false", "-pl", "gate-cli", "-am", "test")
# Use --% to avoid PowerShell parsing of -D
$archResult = & mvn --% -Dtest=gate.arch.ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false -pl gate-cli -am test 2>&1
$archExit = $LASTEXITCODE
$archResult | ForEach-Object { Write-Host $_ }
if ($archExit -ne 0) {
    Add-Error "GOV-DEP-001 ArchUnit failed (exit $archExit)"
} else {
    Write-Host "[GOV-DEP-001] passed"
}

# 2. GOV-DOC-001 / GOV-CPLX-001 via verify-governance.ps1
Write-Host "[GOV-DOC-001/GOV-CPLX-001] Running verify-governance.ps1"
$govArgs = @()
if ($StrictAdmission) { $govArgs += "-StrictAdmission" }
& (Join-Path $PSScriptRoot 'verify-governance.ps1') @govArgs 2>&1 | ForEach-Object { Write-Host $_ }
$govExit = $LASTEXITCODE
if ($govExit -ne 0) {
    # Default mode means document inconsistency; strict mode may instead mean real production blockers.
    # Both must propagate a non-zero result so callers cannot mistake a blocked admission for success.
    Add-Error "GOV-DOC-001/GOV-CPLX-001 governance check failed (exit $govExit, strict=$([bool]$StrictAdmission))"
}

# 3. Summary with rule contract
$result = [ordered]@{
    profile = 'verify-fast'
    rules = @('GOV-DEP-001', 'GOV-DOC-001', 'GOV-CPLX-001', 'GOV-BOOT-001')
    commit = (git -C $repo rev-parse HEAD).Trim()
    checked_at = (Get-Date).ToString('o')
    gov_exit = $govExit
    arch_exit = $archExit
    strict = [bool]$StrictAdmission
    errors = @($errors)
    passed = ($errors.Count -eq 0)
}
Write-Host ($result | ConvertTo-Json -Depth 5)
if (-not $result.passed) { exit 1 }
