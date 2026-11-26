#!/usr/bin/env bash
# Seal: builds jackson-core 2.15.2 with the fix for CVE-2026-18401 / CVE-2026-68494 (maxNumberLength enforced in the
# non-blocking parser, upstream FasterXML/jackson-core b0c428e6f993e1b5ece5c1c3cb2523e887cd52cf (#1555) and
# 4cdd529749da396cc7edf6d4a2aad41d47902641 (#1611)) and installs it into the local Maven repository under the public
# coordinates, so the Spin build resolves it and shades it as spinjar.com.fasterxml.jackson.core.
# The patch is the one sealed com.fasterxml.jackson.core:jackson-core 2.15.2+sp3 ships
# (patches/CVE-2026-18401+CVE-2026-68494.llm.patch).
set -euo pipefail

JACKSON_CORE_COMMIT=1fe0290afdafcce003123f3caea31beb5f13c539  # tag jackson-core-2.15.2

# The sealed jackson-core 2.15.2+sp3 was built and tested on JDK 8.
# The job sets JDK 8 up ahead of JDK 17, which leaves JAVA_HOME_8_X64 behind for this script.
if [ -z "${JAVA_HOME_8_X64:-}" ]; then
  echo "JAVA_HOME_8_X64 is not set: set up JDK 8 before running this script" >&2
  exit 1
fi
export JAVA_HOME="${JAVA_HOME_8_X64}"
export PATH="${JAVA_HOME}/bin:${PATH}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

git init --quiet "${work}/jackson-core"
git -C "${work}/jackson-core" fetch --quiet --depth 1 https://github.com/FasterXML/jackson-core.git "${JACKSON_CORE_COMMIT}"
git -C "${work}/jackson-core" checkout --quiet FETCH_HEAD
git -C "${work}/jackson-core" apply "${here}/jackson-core/CVE-2026-18401.patch"
# Runs the patch's own tests, so the install fails unless the async parser enforces maxNumberLength.
mvn -B -f "${work}/jackson-core/pom.xml" install \
  -Dtest=AsyncLargeNumberReadTest,AsyncNumberLengthConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.javadoc.skip=true -Dgpg.skip=true
echo "installed jackson-core 2.15.2 with the async parser number-length checks into ${HOME}/.m2"
