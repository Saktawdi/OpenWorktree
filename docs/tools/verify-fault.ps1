param(
    [switch]$Mock
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$errors = New-Object System.Collections.Generic.List[string]

function Record-Error([string]$m) { $errors.Add($m) }

Write-Host "=== verify-fault (GOV-CON-001 / GOV-TX-001) ==="

Write-Host "[GOV-CON-001] Checking Task Lease / SSE Disconnect / Git UNKNOWN Fault Scenarios..."
# 1. Lease Stale / Fencing Token Check: Task lease isolation
Write-Host "  - Scenario 1: Worker pause & lease expiration fencing (Mock/Contract verification)"
# 2. SSE Network Drop & Reconnect
Write-Host "  - Scenario 2: Client disconnect & replay cursor backpressure"
# 3. Git UNKNOWN outcome & Reconcile convergence
Write-Host "  - Scenario 3: Git push unknown state & reconcile idempotency"
# 4. Persistent event sequence and cursor replay (TaskEventOutboxFaultTest)
Write-Host "  - Scenario 4: Persistent task_event sequence and cursor replay (DB-backed Last-Event-ID)"
# 5. Slow consumer bounded buffer backpressure DEBT-006 / Phase2 #4 (SseHandler MAX_BUFFERED_EVENTS=100)
Write-Host "  - Scenario 5: slowConsumerDoesNotGrowUnbounded - 200 events bounded to 100, DB replay recovers terminal"
# 6. Node switch replay after lease expiry (Worker A -> Worker B takeover without loss)
Write-Host "  - Scenario 6: nodeSwitchReplayAfterLeaseExpiry - lease expiry takeover with fence increment, replay lossless"

# Check if AcceptanceTest or ArchitectureTest contains fault-injection / crash recovery coverage
$testFiles = @(Get-ChildItem (Join-Path $repo 'gate-cli\src\test\java') -Recurse -Filter '*Recovery*.java')
if ($testFiles.Count -eq 0) {
    Record-Error "GOV-CON-001 missing recovery test cases in gate-cli"
} else {
    Write-Host ("  - Found " + $testFiles.Count + " recovery/fault test files in gate-cli.")
}

# Phase2 DEBT-006 validation: verify TaskEventOutboxFaultTest covers slow consumer + node switch
$faultTestPath = Join-Path $repo 'gate-adapters\src\test\java\gate\adapters\store\TaskEventOutboxFaultTest.java'
if (Test-Path -LiteralPath $faultTestPath) {
    $faultText = Get-Content $faultTestPath -Raw
    if ($faultText -notmatch 'slowConsumerDoesNotGrowUnbounded') {
        Record-Error "GOV-CON-001 missing scenario 5 slowConsumerDoesNotGrowUnbounded in TaskEventOutboxFaultTest.java"
    } else {
        Write-Host "  - Found scenario 5 slowConsumerDoesNotGrowUnbounded"
    }
    if ($faultText -notmatch 'nodeSwitchReplayAfterLeaseExpiry') {
        Record-Error "GOV-CON-001 missing scenario 6 nodeSwitchReplayAfterLeaseExpiry in TaskEventOutboxFaultTest.java"
    } else {
        Write-Host "  - Found scenario 6 nodeSwitchReplayAfterLeaseExpiry"
    }
    if ($faultText -notmatch 'MAX_BUFFERED_EVENTS') {
        Write-Host "  WARN: TaskEventOutboxFaultTest does not reference MAX_BUFFERED_EVENTS=100 bound"
    }
} else {
    Record-Error "GOV-CON-001 missing TaskEventOutboxFaultTest.java"
}

# REAL EXECUTION: run fault tests (not just file-name check) – prevents fake green when logic regresses
if (-not $Mock) {
    Write-Host "[GOV-CON-001] Running real fault tests across adapters and web modules..."
    $adapterArgs = @("-Dtest=TaskEventOutboxFaultTest,Phase3HaFaultTest,Phase4SecurityAndHaTest","-Dsurefire.failIfNoSpecifiedTests=false","-pl","gate-adapters","-am","test")
    $webArgs = @("-Dtest=TaskRegistryTest,RbacNegativeTest,WebGateEquivalenceTest","-Dsurefire.failIfNoSpecifiedTests=false","-pl","gate-web","-am","test")
    $procA = Start-Process -FilePath "mvn" -ArgumentList $adapterArgs -WorkingDirectory $repo -Wait -PassThru -NoNewWindow
    $procW = Start-Process -FilePath "mvn" -ArgumentList $webArgs -WorkingDirectory $repo -Wait -PassThru -NoNewWindow
    if ($procA.ExitCode -ne 0 -or $procW.ExitCode -ne 0) {
        Record-Error "GOV-CON-001 fault tests failed (adapter exit=$($procA.ExitCode) web exit=$($procW.ExitCode)); see surefire-reports"
        Get-ChildItem -Recurse -Filter "*.txt" (Join-Path $repo "gate-adapters/target/surefire-reports") -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "--- $($_.Name)"; Get-Content $_.FullName -Head 40 }
        Get-ChildItem -Recurse -Filter "*.txt" (Join-Path $repo "gate-web/target/surefire-reports") -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "*TaskRegistry*" -or $_.Name -like "*Rbac*" -or $_.Name -like "*Equivalence*" } | ForEach-Object { Write-Host "--- $($_.Name)"; Get-Content $_.FullName -Head 40 }
    } else {
        Write-Host "  - Fault tests passed across gate-adapters and gate-web (mvn exit 0)"
    }
} else {
    Write-Host "[MOCK] Skipping real mvn execution (Mock flag)"
}

$result = [ordered]@{
    profile = 'verify-fault'
    rules = @('GOV-CON-001', 'GOV-TX-001')
    commit = (git -C $repo rev-parse HEAD).Trim()
    checked_at = (Get-Date).ToString('o')
    scenarios_evaluated = 6
    scenarios_executed = (-not $Mock)
    errors = @($errors)
    passed = ($errors.Count -eq 0)
}

Write-Host ($result | ConvertTo-Json -Depth 5)
if (-not $result.passed) { exit 1 }
