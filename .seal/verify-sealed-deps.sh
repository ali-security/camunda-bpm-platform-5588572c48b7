#!/usr/bin/env bash
# Seal: fails unless the camunda-spin-dataformat-all jar built from this tree carries the sealed jackson-core,
# i.e. the shaded non-blocking parser validates number lengths (NonBlockingUtf8JsonParserBase._setIntLength).
set -euo pipefail

jar="spin/dataformat-all/target/camunda-spin-dataformat-all-7.23.0.jar"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

unzip -p "${jar}" spinjar/com/fasterxml/jackson/core/json/async/NonBlockingUtf8JsonParserBase.class \
  > "${tmp}/NonBlockingUtf8JsonParserBase.class"
if ! grep -aq _setIntLength "${tmp}/NonBlockingUtf8JsonParserBase.class"; then
  echo "the shaded jackson-core NonBlockingUtf8JsonParserBase in ${jar} has no number-length checks" >&2
  exit 1
fi
echo "the shaded jackson-core NonBlockingUtf8JsonParserBase has the number-length checks"
