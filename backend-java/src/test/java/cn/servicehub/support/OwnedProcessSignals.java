package cn.servicehub.support;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Fault injection is limited to an owned direct child; never signals unrelated processes. */
final class OwnedProcessSignals {
 static void change(Process child,boolean pause,Path root)throws Exception{
  if(!child.isAlive()||child.toHandle().parent().map(ProcessHandle::pid).orElse(-1L)!=ProcessHandle.current().pid())throw new IllegalArgumentException("Only a live owned direct child may be suspended");
  List<String> command;
  if(System.getProperty("os.name").startsWith("Windows")){
   Path script=root.resolve("owned-process-signals.ps1");if(!Files.exists(script))Files.writeString(script,"""
param([long]$OwnedPid,[ValidateSet('suspend','resume')][string]$Mode)
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class OwnedSignal { [DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr handle); [DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr handle); }'
$owned=[Diagnostics.Process]::GetProcessById($OwnedPid)
$result=if($Mode -eq 'suspend'){[OwnedSignal]::NtSuspendProcess($owned.Handle)}else{[OwnedSignal]::NtResumeProcess($owned.Handle)}
if($result -ne 0){throw 'Owned process signal failed'}
""",StandardCharsets.UTF_8);
   command=List.of(System.getenv().getOrDefault("SERVICEHUB_POWERSHELL","pwsh.exe"),"-NoProfile","-ExecutionPolicy","Bypass","-File",script.toString(),"-OwnedPid",String.valueOf(child.pid()),"-Mode",pause?"suspend":"resume");
  }else command=List.of("kill",pause?"-STOP":"-CONT",String.valueOf(child.pid()));
  Path log=root.resolve("signal-"+UUID.randomUUID()+".log");var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  if(!process.waitFor(20,TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("Owned signal failed; diagnostic: "+log);
 }
}
