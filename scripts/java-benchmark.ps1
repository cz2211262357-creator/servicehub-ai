[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$WorkspaceRoot,[Parameter(Mandatory=$true)][string]$ReportPath)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'java-acceptance-common.ps1')
Invoke-JavaVerificationMain -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -MainClass 'cn.servicehub.support.BenchmarkMain'
