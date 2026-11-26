#!/usr/bin/env bash
# Seal: fails unless the camunda-spin-dataformat-all jar built from this tree carries the sealed Jackson modules, i.e.
# - the shaded jackson-core non-blocking parser validates number lengths (NonBlockingUtf8JsonParserBase._setIntLength);
# - the shaded jackson-databind deserializes InetSocketAddress values unresolved
#   (FromStringDeserializer$Std calls InetSocketAddress.createUnresolved) and brackets unresolved IPv6 hosts when
#   serializing them (InetSocketAddressSerializer checks str.startsWith("["));
# - the shaded jackson-databind keeps a @JsonIgnore'd Record component ignored when a PropertyNamingStrategy renames it
#   (POJOPropertiesCollector._renameUsing calls _collectIgnorals for the renamed name);
# - the shaded jackson-databind BasicPolymorphicTypeValidator validates the element type of array sub-types instead of
#   approving every array (validateSubType reads _acceptArrayTypes and unwraps Class.getComponentType), and consults
#   the name-based matchers for the element type too (validateSubType calls validateSubClassName);
# - the shaded jackson-databind validates the type parameters of generic type ids against the PolymorphicTypeValidator
#   instead of only the raw container class (DatabindContext._resolveAndValidateGeneric calls _validateTypeParameter).
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

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/jsontype/BasicPolymorphicTypeValidator.class \
  > "${tmp}/BasicPolymorphicTypeValidator.class"
# Check the bytecode of validateSubType itself: it must read _acceptArrayTypes and unwrap the array component type.
javap -c -p "${tmp}/BasicPolymorphicTypeValidator.class" > "${tmp}/BasicPolymorphicTypeValidator.javap"
awk '/ validateSubType\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
  "${tmp}/BasicPolymorphicTypeValidator.javap" > "${tmp}/validateSubType.javap"
if ! grep 'Field _acceptArrayTypes:Z' "${tmp}/validateSubType.javap" > /dev/null \
    || ! grep 'Method java/lang/Class.getComponentType:' "${tmp}/validateSubType.javap" > /dev/null; then
  echo "the shaded jackson-databind BasicPolymorphicTypeValidator in ${jar} approves arrays of any element type" >&2
  exit 1
fi
echo "the shaded jackson-databind BasicPolymorphicTypeValidator validates the element type of array sub-types"
if ! grep 'Method validateSubClassName:' "${tmp}/validateSubType.javap" > /dev/null; then
  echo "the shaded jackson-databind BasicPolymorphicTypeValidator in ${jar} ignores the name-based matchers for" \
    "array element types" >&2
  exit 1
fi
echo "the shaded jackson-databind BasicPolymorphicTypeValidator consults the name-based matchers for array element types"

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/DatabindContext.class > "${tmp}/DatabindContext.class"
# _validateTypeParameter also calls itself, so check the bytecode of _resolveAndValidateGeneric itself.
javap -c -p "${tmp}/DatabindContext.class" > "${tmp}/DatabindContext.javap"
if ! awk '/ _resolveAndValidateGeneric\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
    "${tmp}/DatabindContext.javap" | grep 'Method _validateTypeParameter:' > /dev/null; then
  echo "the shaded jackson-databind DatabindContext in ${jar} does not validate the type parameters of generic type ids" >&2
  exit 1
fi
echo "the shaded jackson-databind DatabindContext validates the type parameters of generic type ids"
