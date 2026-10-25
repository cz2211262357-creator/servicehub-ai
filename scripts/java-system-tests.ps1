[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$WorkspaceRoot,[Parameter(Mandatory=$true)][string]$ReportPath)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'java-env.ps1') -WorkspaceRoot $WorkspaceRoot
$verificationRoot=[IO.Path]::GetFullPath((Join-Path $WorkspaceRoot '.cache/java-verification'))
$report=[IO.Path]::GetFullPath($ReportPath)
if(-not $report.StartsWith($verificationRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'ReportPath must remain inside workspace/.cache/java-verification.'}
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($report))|Out-Null
$verificationStarted=[DateTime]::UtcNow
$sourceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$log=[IO.Path]::ChangeExtension($report,'.log')
& (Join-Path $sourceRoot 'backend-java/mvnw.cmd') -f (Join-Path $sourceRoot 'backend-java/pom.xml') '-Dit.test=FullHttpContractIT,ConcurrencySystemIT,FailureRecoverySystemIT,BuildBoundaryIT' verify *> $log
$verificationExit=$LASTEXITCODE
$rows=@()
foreach($suite in Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'backend-java/target/failsafe-reports') -Filter 'TEST-*.xml'){
 if($suite.LastWriteTimeUtc -lt $verificationStarted){continue}
 [xml]$data=[IO.File]::ReadAllText($suite.FullName)
 $rows+=@{suite=$data.testsuite.name;tests=[int]$data.testsuite.tests;failures=[int]$data.testsuite.failures;errors=[int]$data.testsuite.errors;skips=[int]$data.testsuite.skipped}
}
$sha=(& git -C $sourceRoot rev-parse HEAD | Out-String).Trim()
$result=@{passed=($verificationExit -eq 0);source_commit=$sha;started_at=$verificationStarted.ToString('o');finished_at=[DateTime]::UtcNow.ToString('o');exit_code=$verificationExit;suites=$rows;diagnostic_log=$log;offline_provider='controlled HTTP transport; online checks are separate'}
[IO.File]::WriteAllText($report,($result|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
if($verificationExit -ne 0){throw 'System acceptance failed. Inspect the private report and log.'}
Write-Output ('System acceptance passed; report: '+$report)
