function Invoke-JavaVerificationMain {
 param([string]$WorkspaceRoot,[string]$ReportPath,[string]$MainClass)
 . (Join-Path $PSScriptRoot 'java-env.ps1') -WorkspaceRoot $WorkspaceRoot
 $verificationRoot=[IO.Path]::GetFullPath((Join-Path $WorkspaceRoot '.cache/java-verification'))
 $report=[IO.Path]::GetFullPath($ReportPath)
 if(-not $report.StartsWith($verificationRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'ReportPath must remain inside workspace/.cache/java-verification.'}
 [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($report))|Out-Null
 $sourceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
 $classpathFile=Join-Path $verificationRoot 'test-classpath.txt'
 $compileLog=[IO.Path]::ChangeExtension($report,'.compile.log')
 & (Join-Path $sourceRoot 'backend-java/mvnw.cmd') -f (Join-Path $sourceRoot 'backend-java/pom.xml') '-DskipTests' package dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-DincludeScope=test' *> $compileLog
 if($LASTEXITCODE -ne 0){throw 'Verification harness compilation failed; see private compile log.'}
 $classPath=(Join-Path $sourceRoot 'backend-java/target/test-classes')+[IO.Path]::PathSeparator+(Join-Path $sourceRoot 'backend-java/target/classes')+[IO.Path]::PathSeparator+[IO.File]::ReadAllText($classpathFile).Trim()
 $log=[IO.Path]::ChangeExtension($report,'.log')
 # A manifest classpath avoids both Windows command length and native argument-file codepage limits.
 $manifestJar=[IO.Path]::ChangeExtension($report,'.classpath.jar')
 $uris=@()
 foreach($classpathEntry in $classPath.Split([IO.Path]::PathSeparator)){
  $entryPath=$classpathEntry.Replace('\','/')
  if([IO.Directory]::Exists($classpathEntry)){$entryPath=$entryPath.TrimEnd('/')+'/'}
  $entryUri=[Uri]::new($entryPath)
  $uris+=$entryUri.GetComponents([UriComponents]::AbsoluteUri,[UriFormat]::UriEscaped)
 }
 $remaining='Class-Path: '+($uris -join ' ')
 $manifestNewline=[string][char]13+[char]10
 $manifest='Manifest-Version: 1.0'+$manifestNewline
 while($remaining.Length -gt 70){$manifest+=$remaining.Substring(0,70)+$manifestNewline+' ';$remaining=$remaining.Substring(70)}
 $manifest+=$remaining+$manifestNewline+$manifestNewline
 $manifestFile=[IO.File]::Open($manifestJar,[IO.FileMode]::Create,[IO.FileAccess]::ReadWrite)
 try{
  $archive=[IO.Compression.ZipArchive]::new($manifestFile,[IO.Compression.ZipArchiveMode]::Create,$true)
  try{
   $entry=$archive.CreateEntry('META-INF/MANIFEST.MF')
   $entryStream=$entry.Open()
   try{$manifestBytes=[Text.UTF8Encoding]::new($false).GetBytes($manifest);$entryStream.Write($manifestBytes,0,$manifestBytes.Length)}finally{$entryStream.Dispose()}
  }finally{$archive.Dispose()}
 }finally{$manifestFile.Dispose()}
 & (Join-Path $env:JAVA_HOME 'bin/java.exe') "-Djava.io.tmpdir=$env:TMP" '-Dfile.encoding=UTF-8' -cp $manifestJar $MainClass $report *> $log
 if($LASTEXITCODE -ne 0){throw 'Verification failed; see the private JSON report and diagnostic log.'}
 Write-Output ('Verification passed; report: '+$report)
}
