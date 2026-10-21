param([switch]$Online, [switch]$Faults, [switch]$Load)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
Push-Location $ProjectRoot
function Check-Exit([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}
try {
    uv run ruff check backend scripts
    Check-Exit 'Python lint'
    uv run ruff format --check backend scripts
    Check-Exit 'Python formatting'
    uv run pytest -q
    Check-Exit 'Backend tests'
    uv run python scripts/export_openapi.py
    Check-Exit 'OpenAPI export'
    npm --prefix frontend run generate:api
    Check-Exit 'API types'
    npm --prefix frontend run typecheck
    Check-Exit 'Frontend typecheck'
    npm --prefix frontend test -- --run
    Check-Exit 'Frontend tests'
    npm --prefix frontend run build
    Check-Exit 'Production web build'
    if ($Faults) {
        uv run python scripts/fault_test.py --scenario all --output .cache/fault-report.json
        Check-Exit 'Fault experiments'
    }
    if ($Online) {
        uv run pytest backend/tests/online -m online -q
        Check-Exit 'Real online model tests'
    }
    if ($Load) {
        uv run python scripts/load_test.py --users 8 --duration 5 --scenario business --output .cache/load-business.json
        Check-Exit 'Business load'
        uv run python scripts/load_test.py --users 4 --duration 5 --scenario live --output .cache/load-live.json
        Check-Exit 'Live load'
    }
    Write-Output 'All selected verification steps passed.'
} finally { Pop-Location }
