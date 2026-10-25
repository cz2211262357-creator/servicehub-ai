#!/usr/bin/env bash
set -euo pipefail
probe_workspace="$1"
probe_source="$2"
probe_report="$3"
source "$probe_source/scripts/java-env.sh" "$probe_workspace"
probe_repo="$(bash "$probe_source/backend-java/mvnw" -q -f "$probe_source/backend-java/pom.xml" org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate -Dexpression=settings.localRepository -DforceStdout)"
probe_version="$(bash "$probe_source/backend-java/mvnw" -v)"
probe_maven="$(printf '%s\n' "$probe_version" | sed -n 's/^Maven home: //p')"
export JAVA_PROBE_REPO="$probe_repo" JAVA_PROBE_MAVEN="$probe_maven" JAVA_PROBE_REPORT="$probe_report"
"$probe_workspace/.venv/bin/python" -B -c 'import json,os;from pathlib import Path;Path(os.environ["JAVA_PROBE_REPORT"]).write_text(json.dumps(dict(maven_user_home=os.environ["MAVEN_USER_HOME"],repository=os.environ["JAVA_PROBE_REPO"],temporary=os.environ["TMPDIR"],java_home=os.environ["JAVA_HOME"],maven_home=os.environ["JAVA_PROBE_MAVEN"])),encoding="utf-8")'
