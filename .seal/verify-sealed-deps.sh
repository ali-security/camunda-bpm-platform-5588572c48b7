#!/usr/bin/env bash
# Seal: fails unless the camunda-spin-dataformat-all jar built from this tree carries the sealed Jackson modules, i.e.
# - the shaded jackson-core non-blocking parser validates number lengths (NonBlockingUtf8JsonParserBase._setIntLength);
# - the shaded jackson-databind deserializes InetSocketAddress values unresolved
#   (FromStringDeserializer$Std calls InetSocketAddress.createUnresolved) and brackets unresolved IPv6 hosts when
#   serializing them (InetSocketAddressSerializer checks str.startsWith("["));
# - the shaded jackson-databind keeps a @JsonIgnore'd Record component ignored when a PropertyNamingStrategy renames it
#   (POJOPropertiesCollector._renameUsing calls _collectIgnorals for the renamed name).
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

unzip -p "${jar}" 'spinjar/com/fasterxml/jackson/databind/deser/std/FromStringDeserializer$Std.class' \
  > "${tmp}/FromStringDeserializer\$Std.class"
if ! grep -aq createUnresolved "${tmp}/FromStringDeserializer\$Std.class"; then
  echo "the shaded jackson-databind FromStringDeserializer\$Std in ${jar} resolves InetSocketAddress host names" >&2
  exit 1
fi
echo "the shaded jackson-databind FromStringDeserializer\$Std creates unresolved InetSocketAddress values"

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/ser/std/InetSocketAddressSerializer.class \
  > "${tmp}/InetSocketAddressSerializer.class"
if ! grep -aq startsWith "${tmp}/InetSocketAddressSerializer.class"; then
  echo "the shaded jackson-databind InetSocketAddressSerializer in ${jar} does not bracket unresolved IPv6 hosts" >&2
  exit 1
fi
echo "the shaded jackson-databind InetSocketAddressSerializer brackets unresolved IPv6 hosts"

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/introspect/POJOPropertiesCollector.class \
  > "${tmp}/POJOPropertiesCollector.class"
# _collectIgnorals is called from other methods of the class as well, so check the bytecode of _renameUsing itself.
javap -c -p "${tmp}/POJOPropertiesCollector.class" > "${tmp}/POJOPropertiesCollector.javap"
if ! awk '/ _renameUsing\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' "${tmp}/POJOPropertiesCollector.javap" \
    | grep 'Method _collectIgnorals:' > /dev/null; then
  echo "the shaded jackson-databind POJOPropertiesCollector in ${jar} drops the ignoral of renamed properties" >&2
  exit 1
fi
echo "the shaded jackson-databind POJOPropertiesCollector keeps renamed @JsonIgnore'd properties ignored"
