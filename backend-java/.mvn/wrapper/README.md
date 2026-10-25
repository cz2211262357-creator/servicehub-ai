# Maven Wrapper

Apache Maven Wrapper 3.3.4, official bin distribution. The launcher JAR is included so Windows does not need the legacy PowerShell WebClient download path. Apache license notices are included in the standard scripts and JAR.

Maven distribution is pinned to 3.9.11 with distributionSha256Sum. The official repository provides SHA1 for this launcher version; that checksum was compared at bootstrap, and the project pins its SHA256 in scripts/java-toolchain.lock.json. java-env.ps1 verifies that SHA256 before use. No global TLS, proxy or execution policy is changed.

From Windows, source scripts/java-env.ps1 with the physical project root. MAVEN_BASEDIR points at this checkout's backend-java directory; MAVEN_USER_HOME points at the physical project's .cache/maven; MAVEN_CONFIG/MAVEN_ARGS and a JVM repository property keep the dependency cache at .cache/m2 regardless of current working directory.
