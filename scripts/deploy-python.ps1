param(
    [string]$StateRoot = "",
    [switch]$Build,
    [switch]$Migrate
)
$ErrorActionPreference = 'Stop'
$sourceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $StateRoot) { $StateRoot = $sourceRoot }
$stateDirectory = (Resolve-Path -LiteralPath $StateRoot).Path
$privateEnv = Join-Path $stateDirectory '.env'
if (-not (Test-Path -LiteralPath $privateEnv -PathType Leaf)) {
    throw "Missing private .env in the selected state directory"
}
# Source may be an isolated worktree; existing state always resolves under StateRoot.
$env:SERVICEHUB_SOURCE_ROOT = $sourceRoot.Replace('\', '/')
$composeArgs = @('compose', '--project-directory', $stateDirectory,
    '--env-file', $privateEnv, '-f', (Join-Path $sourceRoot 'compose.yaml'), '--profile', 'app')
function Invoke-Compose([string[]]$TaskArguments) {
    & docker @composeArgs @TaskArguments
    if ($LASTEXITCODE -ne 0) { throw "Python deployment step failed ($LASTEXITCODE)" }
}
Invoke-Compose @('up', '-d', '--wait', 'mysql', 'redis')
if ($Build) { Invoke-Compose @('build', 'api', 'worker', 'web') }
if ($Migrate) {
    # Caller must back up the original database and uploads before choosing -Migrate.
    Invoke-Compose @('stop', 'worker')
    Invoke-Compose @('run', '--rm', '--no-deps', 'api', 'alembic', '-c', 'backend/alembic.ini', 'upgrade', 'head')
}
Invoke-Compose @('up', '-d', '--wait', 'api', 'worker', 'web')
Write-Output 'Python platform is ready at http://localhost:8080'
