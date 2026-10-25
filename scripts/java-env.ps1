[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$WorkspaceRoot)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath($WorkspaceRoot)
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not [IO.Path]::IsPathRooted($WorkspaceRoot)) { throw 'WorkspaceRoot must be absolute.' }
if ($sourceRoot -ne $projectRoot -and -not $sourceRoot.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Source checkout must remain inside workspace root.' }
$toolLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'java-toolchain.lock.json') -Raw | ConvertFrom-Json
$wrapperJar = Join-Path $sourceRoot 'backend-java/.mvn/wrapper/maven-wrapper.jar'
if (-not (Test-Path -LiteralPath $wrapperJar) -or (Get-FileHash -LiteralPath $wrapperJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $toolLock.wrapper.sha256.ToLowerInvariant()) { throw 'Pinned Maven wrapper launcher is missing or modified.' }
$javaHome = Join-Path (Join-Path $projectRoot '.tools') $toolLock.jdk.home
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin/java.exe'))) { throw 'Run bootstrap-java.ps1 first.' }
function Import-AllowedEnvironment([string]$path,[string]$allowed) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        if ($line -match '^\s*([A-Za-z][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $name = $Matches[1]; $value = $Matches[2]
            if ($name -notmatch $allowed) { continue }
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1,$value.Length-2) }
            [Environment]::SetEnvironmentVariable($name,$value,'Process')
        }
    }
}
# Read literal values only; never evaluate dotenv text or import the Python DB URL.
Import-AllowedEnvironment (Join-Path $projectRoot '.env') '^SERVICEHUB_(MODEL_|EMBEDDING_)'
Import-AllowedEnvironment (Join-Path $projectRoot '.env.java') '^SERVICEHUB_(JAVA_|MODEL_|EMBEDDING_)'
$env:SERVICEHUB_POWERSHELL = (Get-Process -Id $PID).MainModule.FileName
$env:SERVICEHUB_WORKSPACE_ROOT = $projectRoot
$env:SERVICEHUB_JAVA_SOURCE_ROOT = $sourceRoot
$env:JAVA_HOME = $javaHome
# The official bin wrapper searches from cwd unless its nested project base is explicit.
$env:MAVEN_BASEDIR = Join-Path $sourceRoot 'backend-java'
$env:MAVEN_USER_HOME = Join-Path $projectRoot '.cache/maven'
$cacheRoot = Join-Path $projectRoot '.cache/m2'
$tempRoot = Join-Path $projectRoot '.cache/java-tmp'
foreach ($dir in @($env:MAVEN_USER_HOME,$cacheRoot,$tempRoot,(Join-Path $sourceRoot '.cache'))) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$env:TEMP = $tempRoot; $env:TMP = $tempRoot; $env:TMPDIR = $tempRoot
$settingsPath = Join-Path $sourceRoot 'backend-java/.mvn/settings.xml'
$env:MAVEN_ARGS = '-Dmaven.repo.local="'+$cacheRoot+'" --settings "'+$settingsPath+'"'
$env:MAVEN_CONFIG = $env:MAVEN_ARGS
$env:MAVEN_OPTS = '-Dmaven.repo.local="'+$cacheRoot+'" -Djava.io.tmpdir="'+$tempRoot+'" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
$env:PATH = (Join-Path $javaHome 'bin')+';'+(Join-Path $projectRoot '.tools/docker/resources/bin')+';'+$env:PATH
