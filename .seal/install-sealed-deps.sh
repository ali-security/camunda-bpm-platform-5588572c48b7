#!/usr/bin/env bash
# Seal: builds the Jackson 2.15.2 modules that camunda-spin-dataformat-all shades with their security fixes and installs
# them into the local Maven repository under the public coordinates, so the Spin build resolves them and shades them as
# spinjar.com.fasterxml.jackson.*:
#
# - jackson-core 2.15.2 with the fix for CVE-2026-18401 / CVE-2026-68494 (maxNumberLength enforced in the
#   non-blocking parser, upstream FasterXML/jackson-core b0c428e6f993e1b5ece5c1c3cb2523e887cd52cf (#1555) and
#   4cdd529749da396cc7edf6d4a2aad41d47902641 (#1611)).
#   The patch is the one sealed com.fasterxml.jackson.core:jackson-core 2.15.2+sp3 ships
#   (patches/CVE-2026-18401+CVE-2026-68494.llm.patch).
# - jackson-databind 2.15.2 with the fix for CVE-2026-54514 (InetSocketAddress deserialization creates unresolved
#   addresses instead of resolving the host name (SSRF), upstream FasterXML/jackson-databind
#   1f5a1037b1e9e05920e755cb35f198bcd46667e4 (#5951), plus the unresolved IPv6 bracketing in InetSocketAddressSerializer
#   from f3c95dcf728b6b726a2c0375f07b28c6442967ea (#6185)).
#   The #5951 part is the one sealed com.fasterxml.jackson.core:jackson-databind 2.15.2+sp5p5 ships
#   (patches/CVE-2026-54514.patch).
#
# jackson-core is installed first: jackson-databind 2.15.2 builds against the jackson-core 2.15.2 in ${HOME}/.m2.
set -euo pipefail

JACKSON_CORE_COMMIT=1fe0290afdafcce003123f3caea31beb5f13c539  # tag jackson-core-2.15.2
JACKSON_DATABIND_COMMIT=74a57b2f6cd3b6e0fe2a61ed16734f15ba2abc95  # tag jackson-databind-2.15.2

# The sealed jackson-core 2.15.2+sp3 and jackson-databind 2.15.2+sp5p5 were built and tested on JDK 8.
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

git init --quiet "${work}/jackson-databind"
git -C "${work}/jackson-databind" fetch --quiet --depth 1 https://github.com/FasterXML/jackson-databind.git \
  "${JACKSON_DATABIND_COMMIT}"
git -C "${work}/jackson-databind" checkout --quiet FETCH_HEAD
git -C "${work}/jackson-databind" apply "${here}/jackson-databind/CVE-2026-54514.patch"
# Runs the patch's own tests, so the install fails unless InetSocketAddress values are deserialized unresolved and
# unresolved IPv6 hosts are serialized bracketed.
mvn -B -f "${work}/jackson-databind/pom.xml" install \
  -Dtest=JDKStringLikeTypeDeserTest,JDKTypeSerializationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.javadoc.skip=true -Dgpg.skip=true
echo "installed jackson-databind 2.15.2 with the unresolved InetSocketAddress deserialization into ${HOME}/.m2"
