#!/usr/bin/env bash
# Source this file; callers supply an absolute workspace and a JDK copied inside it.
set -euo pipefail
java_source_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
java_workspace_root="$(cd "${1:?absolute workspace root required}" && pwd)"
case "$java_source_root/" in "$java_workspace_root/"*) ;; *) echo "Source must remain inside workspace" >&2; return 1;; esac
: "${JAVA_HOME:?JDK21 must be installed under workspace/.tools}"
case "$JAVA_HOME/" in "$java_workspace_root/.tools/"*) ;; *) echo "JAVA_HOME must remain under workspace/.tools" >&2; return 1;; esac
export SERVICEHUB_WORKSPACE_ROOT="$java_workspace_root" SERVICEHUB_JAVA_SOURCE_ROOT="$java_source_root"
export MAVEN_BASEDIR="$java_source_root/backend-java" MAVEN_USER_HOME="$java_workspace_root/.cache/maven"
export TMPDIR="$java_workspace_root/.cache/java-tmp"
mkdir -p "$MAVEN_USER_HOME" "$java_workspace_root/.cache/m2" "$TMPDIR" "$java_source_root/.cache"
export MAVEN_OPTS="-Dmaven.repo.local=$java_workspace_root/.cache/m2 -Djava.io.tmpdir=$TMPDIR -Dfile.encoding=UTF-8"
export MAVEN_ARGS="-Dmaven.repo.local=$java_workspace_root/.cache/m2 --settings $java_source_root/backend-java/.mvn/settings.xml"
export MAVEN_CONFIG="$MAVEN_ARGS"
export PATH="$JAVA_HOME/bin:$PATH"
