param()

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$docs = Join-Path $repo 'docs'
$errors = New-Object System.Collections.Generic.List[string]

function Record-Error([string]$m) { $errors.Add($m) }

Write-Host "=== verify-contract (GOV-DATA-001 / GOV-API-001 / GOV-DB-001) ==="

# 1. GOV-DATA-001: Check table & API ownership registration
Write-Host "[GOV-DATA-001] Checking ownership catalog completeness..."
$ownershipPath = Join-Path $docs 'architecture\ownership-catalog.md'
if (-not (Test-Path -LiteralPath $ownershipPath)) {
    Record-Error "GOV-DATA-001 missing ownership-catalog.md"
} else {
    $ownershipText = Get-Content $ownershipPath -Raw
    Get-ChildItem (Join-Path $repo 'gate-adapters\src\main\resources\db\migration') -Filter 'V*.sql' | ForEach-Object {
        $sql = Get-Content $_.FullName -Raw
        $regex = [regex]::new('CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-zA-Z0-9_]+)', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        $matches = $regex.Matches($sql)
        foreach ($match in $matches) {
            $table = $match.Groups[1].Value
            if (-not $ownershipText.Contains($table)) {
                Record-Error ("GOV-DATA-001 unowned table in migration " + $_.Name + ": " + $table)
            }
        }
    }
}

# 2. GOV-DB-001: Flyway migration version continuity & Expand/Contract audit
Write-Host "[GOV-DB-001] Checking migration scripts naming, ordering and Expand/Contract safety..."
$migrationFiles = @(Get-ChildItem (Join-Path $repo 'gate-adapters\src\main\resources\db\migration') -Filter 'V*__*.sql')
$migrationObjs = @()
foreach ($m in $migrationFiles) {
    if ($m.Name -match '^V(\d+)__([a-zA-Z0-9_]+)\.sql$') {
        $migrationObjs += [PSCustomObject]@{
            Version = [int]$Matches[1]
            Name = $m.Name
            FullName = $m.FullName
        }
    } else {
        Record-Error ("GOV-DB-001 invalid migration naming format: " + $m.Name)
    }
}
$sortedMigrations = @($migrationObjs | Sort-Object Version)
for ($i = 0; $i -lt $sortedMigrations.Count - 1; $i++) {
    if ($sortedMigrations[$i + 1].Version -le $sortedMigrations[$i].Version) {
        Record-Error ("GOV-DB-001 duplicate or disordered migration version: " + $sortedMigrations[$i].Version + " in " + $sortedMigrations[$i+1].Name)
    }
}

# Expand/Contract Safety Audit:
# Destructive statements (DROP TABLE, DROP COLUMN, ALTER TABLE ... RENAME) are forbidden unless in explicitly marked contract migrations.
# In expand phase, adding non-null columns without defaults on existing tables is flagged.
foreach ($m in $sortedMigrations) {
    $content = Get-Content $m.FullName -Raw
    # Audit for destructive table drops
    if ($content -match '(?i)\bDROP\s+TABLE\b') {
        if (-not ($m.Name -match '(?i)contract')) {
            Record-Error ("GOV-DB-001 destructive DROP TABLE without contract phase naming in " + $m.Name)
        }
    }
    # Audit for destructive column drops
    if ($content -match '(?i)\bDROP\s+COLUMN\b') {
        if (-not ($m.Name -match '(?i)contract')) {
            Record-Error ("GOV-DB-001 destructive DROP COLUMN without contract phase naming in " + $m.Name)
        }
    }
    # Audit for ADD COLUMN with NOT NULL but no DEFAULT
    if ($content -match '(?i)\bALTER\s+TABLE\s+\w+\s+ADD\s+COLUMN\s+[^;]+NOT\s+NULL(?!\s+DEFAULT)\b') {
        Record-Error ("GOV-DB-001 destructive non-null column add without default in " + $m.Name)
    }
}

# 3. GOV-API-001: Ensure known endpoints in ApiRoutes are declared in ownership catalog
Write-Host "[GOV-API-001] Checking API route ownership declarations..."
$apiRoutesPath = Join-Path $repo 'gate-web\src\main\java\gate\web\ApiRoutes.java'
if (Test-Path -LiteralPath $apiRoutesPath) {
    $apiText = Get-Content $apiRoutesPath -Raw
    $segRegex = [regex]::new('seg\[1\]\.equals\("([^"]+)"\)')
    $matches = $segRegex.Matches($apiText)
    foreach ($match in $matches) {
        $segment = $match.Groups[1].Value
        $expectedRoute = "/api/" + $segment
        if (-not $ownershipText.Contains($expectedRoute)) {
            Record-Error ("GOV-API-001 undeclared API route in ownership catalog: " + $expectedRoute)
        }
    }
}

$result = [ordered]@{
    profile = 'verify-contract'
    rules = @('GOV-DATA-001', 'GOV-API-001', 'GOV-DB-001')
    commit = (git -C $repo rev-parse HEAD).Trim()
    checked_at = (Get-Date).ToString('o')
    tables_scanned = $sortedMigrations.Count
    errors = @($errors)
    passed = ($errors.Count -eq 0)
}

Write-Host ($result | ConvertTo-Json -Depth 5)
if (-not $result.passed) { exit 1 }
