param([switch]$BackendOnly)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
Push-Location $ProjectRoot
try {
    uv python install 3.12
    if ($LASTEXITCODE -ne 0) { throw 'uv Python installation failed' }
    uv sync --all-groups
    if ($LASTEXITCODE -ne 0) { throw 'uv dependency sync failed' }
    if (-not $BackendOnly -and (Test-Path 'frontend\package.json')) {
        npm --prefix frontend ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency sync failed' }
    }
    Write-Output "Project environment ready: $ProjectRoot\.venv"
} finally {
    Pop-Location
}
