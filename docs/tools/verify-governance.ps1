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
        if ($actual -ne [int]$hotspot.physical_lines) {
            Add-Error "GOV-CPLX-001 baseline drift: $($hotspot.path) expected=$($hotspot.physical_lines) actual=$actual"
        }
    }
}

# ADR and capability entity counts/statuses.
$adrFiles = @(Get-ChildItem (Join-Path $docs 'adr') -Filter '00*.md')
if ($adrFiles.Count -ne 8) { Add-Error "GOV-DOC-001 ADR entity count expected=8 actual=$($adrFiles.Count)" }
foreach ($adr in $adrFiles) {
    $body = Get-Content $adr.FullName -Raw
    if ($body -notmatch '状态：Draft') { Add-Error "GOV-DOC-001 unexpected ADR status: $($adr.Name)" }
    if ($body -notmatch '复审日期：\d{4}-\d{2}-\d{2}') { Add-Error "GOV-DOC-001 missing ADR review date: $($adr.Name)" }
}

$capabilityFiles = @(Get-ChildItem (Join-Path $docs 'capabilities') -Filter '*.md' | Where-Object Name -ne 'README.md')
if ($capabilityFiles.Count -ne 12) { Add-Error "GOV-DOC-001 capability instance count expected=12 actual=$($capabilityFiles.Count)" }

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
if (Test-Path -LiteralPath $exemptionPath) {
    Get-Content $exemptionPath | Where-Object { $_ -match '^\| EX-\d+' } | ForEach-Object {
        $cols = $_.Split('|')
        # Columns: 0 empty, 1 ID, 2 rule, 3 object, 4 type, 5 owner, 6 approver, 7 created, 8 review, 9 expiry, 10 exit, 11 status
        if ($cols.Count -ge 11) {
            $id = $cols[1].Trim()
            $expiryRaw = $cols[9].Trim()
            $statusRaw = $cols[11].Trim()
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
    $exemptionRows = @(Get-Content $exemptionPath | Where-Object { $_ -match '^\| EX-\d+' })
    $proposed = @($exemptionRows | Where-Object { $_ -match 'Proposed' }).Count
    if ([int]$status.exemptions.proposed -ne $proposed) {
        Add-Error "GOV-DOC-001 exemption proposed count differs: status=$($status.exemptions.proposed) actual=$proposed"
    }
}

# Admission blockers are valid current facts, not document-consistency errors.
if ($null -ne $status) {
    foreach ($item in $status.hard_blockers) { $blockers.Add([string]$item) }
    if ($status.architecture.status -ne 'Reviewing') { Add-Error 'GOV-DOC-001 architecture status must remain Reviewing' }
    if ([int]$status.adr.draft -ne $adrFiles.Count) { Add-Error 'GOV-DOC-001 ADR count differs from governance-status.json' }
    if ([int]$status.capabilities.instances -ne $capabilityFiles.Count) { Add-Error 'GOV-DOC-001 capability count differs from governance-status.json' }
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
