param(
    [switch]$StrictAdmission
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$docs = Join-Path $repo 'docs'
$errors = New-Object System.Collections.Generic.List[string]
$blockers = New-Object System.Collections.Generic.List[string]

function Add-Error([string]$message) { $errors.Add($message) }

# GOV-DOC-001: local Markdown links.
Get-ChildItem $docs -Recurse -Filter '*.md' | ForEach-Object {
    $source = $_
    $text = Get-Content -LiteralPath $source.FullName -Raw
    [regex]::Matches($text, '\]\(([^)#]+)(?:#[^)]+)?\)') | ForEach-Object {
        $link = $_.Groups[1].Value
        if ($link -notmatch '^(https?://|mailto:|#)') {
            $target = Join-Path $source.DirectoryName $link
            if (-not (Test-Path -LiteralPath $target)) {
                Add-Error "GOV-DOC-001 broken link: $($source.FullName) -> $link"
            }
        }
    }
}

# Machine-readable baselines must parse.
$complexityPath = Join-Path $docs 'architecture\complexity-baseline.json'
$statusPath = Join-Path $docs 'architecture\governance-status.json'
try { $complexity = Get-Content $complexityPath -Raw | ConvertFrom-Json } catch { Add-Error "GOV-CPLX-001 invalid JSON: $complexityPath" }
try { $status = Get-Content $statusPath -Raw | ConvertFrom-Json } catch { Add-Error "GOV-DOC-001 invalid JSON: $statusPath" }

# GOV-CPLX-001: reproduce physical-line baselines.
if ($null -ne $complexity) {
    foreach ($hotspot in $complexity.hotspots) {
        $file = Join-Path $repo $hotspot.path
        if (-not (Test-Path -LiteralPath $file)) {
            Add-Error "GOV-CPLX-001 missing hotspot: $($hotspot.path)"
            continue
        }
        $actual = @(Get-Content -LiteralPath $file).Count
        if ($actual -gt [int]$hotspot.physical_lines) {
            Add-Error "GOV-CPLX-001 baseline growth: $($hotspot.path) maximum=$($hotspot.physical_lines) actual=$actual"
        }
    }
}

# ADR and capability entity counts/statuses.
$adrFiles = @(Get-ChildItem (Join-Path $docs 'adr') -Filter '00*.md')
if ($adrFiles.Count -ne 8) { Add-Error "GOV-DOC-001 ADR entity count expected=8 actual=$($adrFiles.Count)" }
$allowedAdrStatuses = @('Draft', 'Proposed', 'Accepted', 'Implemented', 'Verified', 'Rejected', 'Superseded')
$adrCounts = @{ draft = 0; proposed = 0; accepted = 0; implemented = 0; verified = 0 }
foreach ($adr in $adrFiles) {
    $body = Get-Content $adr.FullName -Raw
    if ($body -notmatch '(?m)^状态：\s*([A-Za-z]+)') {
        Add-Error "GOV-DOC-001 missing ADR status: $($adr.Name)"
    } else {
        $adrStatus = $Matches[1]
        if ($allowedAdrStatuses -notcontains $adrStatus) {
            Add-Error "GOV-DOC-001 invalid ADR status: $($adr.Name) status=$adrStatus"
        } elseif ($adrCounts.ContainsKey($adrStatus.ToLowerInvariant())) {
            $adrCounts[$adrStatus.ToLowerInvariant()]++
        }
    }
    if ($body -match '(?m)^状态：\s*(Accepted|Implemented|Verified)' -and $body -notmatch '(?m)^批准人：\s*\S+') {
        Add-Error "GOV-DOC-001 approved ADR missing approver: $($adr.Name)"
    }
    if ($body -notmatch '复审日期：\d{4}-\d{2}-\d{2}') { Add-Error "GOV-DOC-001 missing ADR review date: $($adr.Name)" }
}

$capabilityFiles = @(Get-ChildItem (Join-Path $docs 'capabilities') -Filter '*.md' | Where-Object Name -ne 'README.md')
if ($capabilityFiles.Count -ne 12) { Add-Error "GOV-DOC-001 capability instance count expected=12 actual=$($capabilityFiles.Count)" }
$capabilityCounts = @{ baseline = 0; implementing = 0; planned = 0; accepted = 0 }
foreach ($capability in $capabilityFiles) {
    $body = Get-Content $capability.FullName -Raw
    if ($body -notmatch '(?m)^状态：\s*(Baseline|Implementing|Planned|Accepted)') {
        Add-Error "GOV-DOC-001 missing/invalid capability status: $($capability.Name)"
    } else {
        $capabilityCounts[$Matches[1].ToLowerInvariant()]++
    }
}

# Verification contract is the source for governance-rule implementation maturity.
$verificationPath = Join-Path $docs 'architecture\verification-contract.md'
$governanceRuleRows = @(Get-Content $verificationPath | Where-Object { $_ -match '^\| `GOV-[A-Z]+-\d+` \|' })
$governanceRuleCounts = @{ implemented = 0; partial = 0; planned = 0 }
foreach ($row in $governanceRuleRows) {
    $columns = $row.Split('|')
    if ($columns.Count -lt 7) { Add-Error "GOV-DOC-001 malformed verification rule row: $row"; continue }
    $ruleState = $columns[5].Trim()
    if ($ruleState.StartsWith('已实现')) { $governanceRuleCounts.implemented++ }
    elseif ($ruleState.StartsWith('部分实现')) { $governanceRuleCounts.partial++ }
    elseif ($ruleState.StartsWith('待实现')) { $governanceRuleCounts.planned++ }
    else { Add-Error "GOV-DOC-001 unknown verification rule state: $ruleState" }
}

# P1 debt SLA columns must contain explicit dates.
$debtPath = Join-Path $docs 'debt-register.md'
Get-Content $debtPath | Where-Object { $_ -match '^\| DEBT-\d+ \| P1 \|' } | ForEach-Object {
    $columns = $_.Split('|')
    if ($columns.Count -lt 15) { Add-Error "GOV-DOC-001 malformed debt row: $_"; return }
    foreach ($index in 8..10) {
        if ($columns[$index] -notmatch '\d{4}-\d{2}-\d{2}') {
            Add-Error "GOV-DOC-001 non-calendar debt SLA: $($columns[1].Trim()) column=$index"
        }
    }
}

# Known current API routes must have semantic owners.
$ownership = Get-Content (Join-Path $docs 'architecture\ownership-catalog.md') -Raw
foreach ($route in @('/api/agent-runtimes', '/api/reconcile')) {
    if (-not $ownership.Contains($route)) { Add-Error "GOV-DATA-001 missing API owner: $route" }
}

# GOV-CPLX-001 / GOV-DOC-001: exemption expiry check - expired exemptions are CI failures.
# Each EX-NNN row is parsed for expiry date; if expired and not marked Expired, report error.
$exemptionPath = Join-Path $docs 'architecture\exemption-register.md'
$exemptionCounts = @{ proposed = 0; active = 0; expired = 0 }
if (Test-Path -LiteralPath $exemptionPath) {
    Get-Content $exemptionPath | Where-Object { $_ -match '^\| EX-\d+' } | ForEach-Object {
        $cols = $_.Split('|')
        # Columns: 0 empty, 1 ID, 2 rule, 3 object, 4 type, 5 owner, 6 approver, 7 created, 8 review, 9 expiry, 10 exit, 11 status
        if ($cols.Count -ge 11) {
            $id = $cols[1].Trim()
            $expiryRaw = $cols[9].Trim()
            $statusRaw = $cols[11].Trim()
            $statusKey = $statusRaw.ToLowerInvariant()
            if ($exemptionCounts.ContainsKey($statusKey)) { $exemptionCounts[$statusKey]++ }
            if ($expiryRaw -match '(\d{4}-\d{2}-\d{2})') {
                $expiry = [DateTime]::Parse($Matches[1])
                $today = [DateTime]::Today
                if ($expiry -lt $today -and $statusRaw -notmatch 'Expired') {
                    Add-Error "GOV-CPLX-001 exemption expired but not marked Expired: $id expiry=$($Matches[1]) status=$statusRaw"
                    if ($StrictAdmission) {
                        $blockers.Add("exemption $id expired on $($Matches[1])")
                    }
                }
            }
        }
    }
}

# GOV-DOC-001: exemption count must match governance-status.json
if ($null -ne $status -and (Test-Path -LiteralPath $exemptionPath)) {
    foreach ($key in @('proposed', 'active', 'expired')) {
        if ([int]$status.exemptions.$key -ne [int]$exemptionCounts[$key]) {
            Add-Error "GOV-DOC-001 exemption $key count differs: status=$($status.exemptions.$key) actual=$($exemptionCounts[$key])"
        }
    }
}

# Debt rows are the source for current P0/P1 status counts.
$debtRows = @(Get-Content $debtPath | Where-Object { $_ -match '^\| DEBT-\d+ \| P[01] \|' })
$debtCounts = @{ open = 0; in_progress = 0; resolved = 0 }
$unresolvedDebtIds = New-Object System.Collections.Generic.List[string]
foreach ($row in $debtRows) {
    $columns = $row.Split('|')
    if ($columns.Count -lt 15) { continue }
    $id = $columns[1].Trim()
    $debtStatus = $columns[13].Trim()
    switch ($debtStatus) {
        'Open' { $debtCounts.open++; $unresolvedDebtIds.Add($id) }
        'In Progress' { $debtCounts.in_progress++; $unresolvedDebtIds.Add($id) }
        'Resolved' { $debtCounts.resolved++ }
        'Accepted Risk' { }
        default { Add-Error "GOV-DOC-001 invalid debt status: $id status=$debtStatus" }
    }
}

# Admission blockers are derived from entity/status facts. The descriptive hard_blockers
# array is a human snapshot and is deliberately not copied into the result.
if ($null -ne $status) {
    if (@('Reviewing', 'Accepted') -notcontains [string]$status.architecture.status) {
        Add-Error "GOV-DOC-001 invalid architecture status: $($status.architecture.status)"
    }
    if ([int]$status.adr.required -ne $adrFiles.Count) { Add-Error 'GOV-DOC-001 ADR required count differs from entities' }
    foreach ($key in @('draft', 'proposed', 'accepted', 'implemented', 'verified')) {
        if ([int]$status.adr.$key -ne [int]$adrCounts[$key]) {
            Add-Error "GOV-DOC-001 ADR $key count differs: status=$($status.adr.$key) actual=$($adrCounts[$key])"
        }
    }
    if ([int]$status.capabilities.instances -ne $capabilityFiles.Count) { Add-Error 'GOV-DOC-001 capability count differs from governance-status.json' }
    foreach ($key in @('baseline', 'implementing', 'planned', 'accepted')) {
        if ([int]$status.capabilities.$key -ne [int]$capabilityCounts[$key]) {
            Add-Error "GOV-DOC-001 capability $key count differs: status=$($status.capabilities.$key) actual=$($capabilityCounts[$key])"
        }
    }
    foreach ($key in @('open', 'in_progress', 'resolved')) {
        if ([int]$status.debt.$key -ne [int]$debtCounts[$key]) {
            Add-Error "GOV-DOC-001 debt $key count differs: status=$($status.debt.$key) actual=$($debtCounts[$key])"
        }
    }
    if ([int]$status.debt.p1_total -ne $debtRows.Count) {
        Add-Error "GOV-DOC-001 P0/P1 debt total differs: status=$($status.debt.p1_total) actual=$($debtRows.Count)"
    }
    if ([int]$status.governance_rules.defined -ne $governanceRuleRows.Count) {
        Add-Error "GOV-DOC-001 governance rule total differs: status=$($status.governance_rules.defined) actual=$($governanceRuleRows.Count)"
    }
    foreach ($key in @('implemented', 'partial', 'planned')) {
        if ([int]$status.governance_rules.$key -ne [int]$governanceRuleCounts[$key]) {
            Add-Error "GOV-DOC-001 governance rule $key count differs: status=$($status.governance_rules.$key) actual=$($governanceRuleCounts[$key])"
        }
    }

    if ([string]$status.architecture.status -ne 'Accepted') { $blockers.Add('architecture baseline is not Accepted') }
    if (($adrCounts.accepted + $adrCounts.implemented + $adrCounts.verified) -lt [int]$status.adr.required) {
        $blockers.Add('one or more required ADRs are below Accepted')
    }
    if ($exemptionCounts.proposed -gt 0) { $blockers.Add("proposed exemptions remain: $($exemptionCounts.proposed)") }
    if ($exemptionCounts.active -gt 0) { $blockers.Add("active development-only exemptions must be retired before production: $($exemptionCounts.active)") }
    if ([int]$status.governance_rules.partial -gt 0 -or [int]$status.governance_rules.planned -gt 0) {
        $blockers.Add("governance rules are not fully implemented: partial=$($status.governance_rules.partial) planned=$($status.governance_rules.planned)")
    }
    if ($capabilityCounts.accepted -lt $capabilityFiles.Count) {
        $blockers.Add("capabilities below Accepted: $($capabilityFiles.Count - $capabilityCounts.accepted)")
    }
    if ($unresolvedDebtIds.Count -gt 0) {
        $blockers.Add("unresolved P0/P1 debt: $($unresolvedDebtIds -join ',')")
    }
    foreach ($profileName in @('team', 'enterprise')) {
        if ([string]$status.profiles.$profileName -ne 'Verified') {
            $blockers.Add("profile $profileName is not Verified: $($status.profiles.$profileName)")
        }
    }
    foreach ($phaseProperty in $status.phases.PSObject.Properties) {
        if ([string]$phaseProperty.Value -ne 'Verified') {
            $blockers.Add("$($phaseProperty.Name) is not Verified: $($phaseProperty.Value)")
        }
    }
}

$result = [ordered]@{
    rule = 'GOV-DOC-001/GOV-CPLX-001'
    commit = (git -C $repo rev-parse HEAD)
    checked_at = (Get-Date).ToString('o')
    errors = @($errors)
    admission_blockers = @($blockers)
    strict_admission = [bool]$StrictAdmission
    passed = ($errors.Count -eq 0 -and (-not $StrictAdmission -or $blockers.Count -eq 0))
}

$result | ConvertTo-Json -Depth 5
if (-not $result.passed) { exit 1 }
