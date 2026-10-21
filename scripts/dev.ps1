param([Parameter(Mandatory=$true)][ValidateSet('api','worker','frontend')][string]$Service)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
Push-Location $ProjectRoot
try {
    if (-not (Test-Path -LiteralPath '.env')) { throw 'Create the local .env using .env.example first' }
    switch ($Service) {
        'api' { uv run uvicorn servicehub.main:app --host 127.0.0.1 --port 8000 --reload }
        'worker' { uv run python -m servicehub.jobs.worker }
        'frontend' { npm --prefix frontend run dev }
    }
    if ($LASTEXITCODE -ne 0) { throw "$Service exited with code $LASTEXITCODE" }
} finally { Pop-Location }
