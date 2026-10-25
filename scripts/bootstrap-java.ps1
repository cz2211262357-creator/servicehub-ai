[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$WorkspaceRoot)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = [IO.Path]::GetFullPath($WorkspaceRoot)
if (-not [IO.Path]::IsPathRooted($WorkspaceRoot) -or -not (Test-Path -LiteralPath $projectRoot -PathType Container)) { throw 'Use an existing absolute workspace root.' }
$toolLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'java-toolchain.lock.json') -Raw | ConvertFrom-Json
$toolRoot = Join-Path $projectRoot '.tools'
$downloadRoot = Join-Path $toolRoot 'downloads'
New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
function Install-PinnedTool($tool, [string]$executable) {
    $archive = Join-Path $downloadRoot $tool.archive
    $destination = Join-Path $toolRoot $tool.extractDirectory
    $toolHome = Join-Path $toolRoot $tool.home
    foreach ($candidate in @($archive,$destination,$toolHome)) {
        if (-not [IO.Path]::GetFullPath($candidate).StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Tool path escaped workspace.' }
    }
    if (Test-Path -LiteralPath $destination) {
        if (-not (Test-Path -LiteralPath (Join-Path $toolHome $executable))) { throw 'Existing tool directory differs from lock; inspect it without overwriting.' }
        return $toolHome
    }
    if (-not (Test-Path -LiteralPath $archive)) {
        $partial = $archive+'.partial'
        Invoke-WebRequest -Uri $tool.url -OutFile $partial -TimeoutSec 600
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $tool.sha256.ToLowerInvariant()) { throw 'Downloaded tool SHA256 mismatch.' }
        Move-Item -LiteralPath $partial -Destination $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $tool.sha256.ToLowerInvariant()) { throw 'Cached tool SHA256 mismatch.' }
    if ($tool.PSObject.Properties.Name -contains 'sha512') {
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash.ToLowerInvariant() -ne $tool.sha512.ToLowerInvariant()) { throw 'Official SHA512 mismatch.' }
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $destination
    if (-not (Test-Path -LiteralPath (Join-Path $toolHome $executable))) { throw 'Pinned executable missing after extraction.' }
    return $toolHome
}
$javaHome = Install-PinnedTool $toolLock.jdk 'bin/java.exe'
$env:JAVA_HOME = $javaHome
$mavenHome = Install-PinnedTool $toolLock.maven 'bin/mvn.cmd'
$javaData = Join-Path $projectRoot '.data/java'
$javaEnvironment = Join-Path $projectRoot '.env.java'
if (-not (Test-Path -LiteralPath $javaEnvironment)) {
    if ((Test-Path -LiteralPath $javaData) -and @(Get-ChildItem -LiteralPath $javaData -Force).Count -gt 0) { throw 'Existing Java data without .env.java: recover credentials before continuing.' }
    function New-LocalPassword {
        $bytes = New-Object byte[] 24
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
        return [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
    }
    $lines = @('# Local Java credentials. Ignored by Git. Keep this file with your private backups.',
        ('SERVICEHUB_JAVA_MYSQL_ROOT_PASSWORD='+(New-LocalPassword)),
        'SERVICEHUB_JAVA_DB_USER=servicehub_java',
        ('SERVICEHUB_JAVA_DB_PASSWORD='+(New-LocalPassword)),
        ('SERVICEHUB_JAVA_REDIS_PASSWORD='+(New-LocalPassword)),
        'SERVICEHUB_JAVA_RABBIT_USER=servicehub_java',
        ('SERVICEHUB_JAVA_RABBIT_PASSWORD='+(New-LocalPassword)),
        ('SERVICEHUB_JAVA_RABBIT_COOKIE='+(New-LocalPassword)))
    [IO.File]::WriteAllLines($javaEnvironment,$lines,(New-Object Text.UTF8Encoding($false)))
}
. (Join-Path $PSScriptRoot 'java-env.ps1') -WorkspaceRoot $projectRoot
& (Join-Path $javaHome 'bin/java.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'Project JDK is not executable.' }
Write-Output 'Pinned Java toolchain ready; no global installation was changed.'
