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
#   instead of only the raw container class (DatabindContext._resolveAndValidateGeneric calls _validateTypeParameter);
# - the shaded jackson-databind keeps the per-property @JsonIgnoreProperties / @JsonIncludeProperties when a per-property
#   @JsonFormat changes the case-insensitivity (BeanDeserializerBase.createContextual rebuilds the case-insensitive
#   property map from the filtered contextual deserializer, not from this);
# - the shaded jackson-databind does not set @JsonUnwrapped properties that the active @JsonView hides
#   (UnwrappedPropertyHandler.processUnwrapped calls SettableBeanProperty.visibleInView);
# - the shaded jackson-databind does not set external type id (EXTERNAL_PROPERTY) properties, nor the creator
#   parameters of a bean that has one, that the active @JsonView hides (both ExternalTypeHandler.complete overloads and
#   ExternalTypeHandler._deserializeAndSet call SettableBeanProperty.visibleInView, and
#   BeanDeserializer.deserializeUsingPropertyBasedWithExternalTypeId calls it for creator properties too);
# - the shaded jackson-databind deserializes InetAddress values from IP address literals only, without DNS lookups
#   (FromStringDeserializer$Std._deserialize calls InetAddressValidator.isInetAddress before InetAddress.getByName,
#   and InetAddressValidator checks every character through its ASCII-only _digit helper, never Character.digit);
# - the shaded jackson-databind deserializes java.nio.file.Path values only from "file" URIs (or values with no scheme)
#   by default and rejects every other URI scheme before any FileSystemProvider lookup (NioPathDeserializer.deserialize
#   calls _isSchemeAllowed before Paths.get(URI), and DEFAULT_ALLOWED_SCHEMES is the singleton list of "file").
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

# _deserialize must validate the InetAddress string as an IP address literal before it calls InetAddress.getByName,
# which resolves anything else as a host name. Unpatched, it calls getByName on the raw value.
javap -c -p "${tmp}/FromStringDeserializer\$Std.class" > "${tmp}/FromStringDeserializer\$Std.javap"
if ! awk '/ _deserialize\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' "${tmp}/FromStringDeserializer\$Std.javap" \
    | grep 'InetAddressValidator.isInetAddress:' > /dev/null; then
  echo "the shaded jackson-databind FromStringDeserializer\$Std in ${jar} resolves InetAddress host names" >&2
  exit 1
fi
validator='spinjar/com/fasterxml/jackson/databind/deser/std/InetAddressValidator.class'
if ! unzip -p "${jar}" "${validator}" > "${tmp}/InetAddressValidator.class" \
    || [ ! -s "${tmp}/InetAddressValidator.class" ]; then
  echo "the shaded jackson-databind in ${jar} has no ${validator}" >&2
  exit 1
fi
# Character.digit accepts non-ASCII digits (full-width, Arabic-Indic, ...) that InetAddress.getByName resolves as host
# names, so the methods that check the characters of the literal must call the ASCII-only _digit helper instead.
javap -c -p "${tmp}/InetAddressValidator.class" > "${tmp}/InetAddressValidator.javap"
for method in _ipStringToBytes _parseOctet _parseHextet; do
  body="$(awk -v m=" ${method}\\\\(" '$0 ~ m { inside = 1 } inside && /^$/ { inside = 0 } inside' \
    "${tmp}/InetAddressValidator.javap")"
  if ! echo "${body}" | grep 'Method _digit:' > /dev/null \
      || echo "${body}" | grep 'Method java/lang/Character.digit:' > /dev/null; then
    echo "the shaded jackson-databind InetAddressValidator.${method} in ${jar} accepts non-ASCII digits" >&2
    exit 1
  fi
done
echo "the shaded jackson-databind FromStringDeserializer\$Std accepts InetAddress values only as ASCII IP address" \
  "literals, without DNS lookups"

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

# The property-based creator paths must check the active view both for creator properties and for the regular
# properties they buffer, i.e. call SettableBeanProperty.visibleInView twice. Unpatched, _deserializeUsingPropertyBased
# checks only creator properties (one call) and deserializeUsingPropertyBasedWithUnwrapped checks neither (no call).
for cls in BeanDeserializer BuilderBasedDeserializer; do
  unzip -p "${jar}" "spinjar/com/fasterxml/jackson/databind/deser/${cls}.class" > "${tmp}/${cls}.class"
  javap -c -p "${tmp}/${cls}.class" > "${tmp}/${cls}.javap"
  for method in _deserializeUsingPropertyBased deserializeUsingPropertyBasedWithUnwrapped; do
    calls="$(awk -v m=" ${method}\\\\(" '$0 ~ m { inside = 1 } inside && /^$/ { inside = 0 } inside' "${tmp}/${cls}.javap" \
      | grep -c 'SettableBeanProperty.visibleInView:' || true)"
    if [ "${calls}" -lt 2 ]; then
      echo "the shaded jackson-databind ${cls}.${method} in ${jar} lets @JsonView-restricted properties be set" >&2
      exit 1
    fi
  done
done
echo "the shaded jackson-databind BeanDeserializer and BuilderBasedDeserializer honor @JsonView in the property-based" \
  "creator paths"

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/deser/BeanDeserializerBase.class \
  > "${tmp}/BeanDeserializerBase.class"
# createContextual must read the BeanPropertyMap it hands to withCaseInsensitivity from the contextual deserializer that
# _handleByNameInclusion filtered (a local variable), not from this (aload_0), which still holds the ignored properties.
# Print the instruction that loads the object whose _beanProperties is read right before withCaseInsensitivity.
javap -c -p "${tmp}/BeanDeserializerBase.class" > "${tmp}/BeanDeserializerBase.javap"
loader="$(awk '/ createContextual\(/ { inside = 1 } inside && /^$/ { inside = 0 }
    inside && /Field ([^ ]*[.])?_beanProperties:/ { owner = prev }
    inside && /BeanPropertyMap.withCaseInsensitivity:/ { print owner }
    inside { prev = $0 }' "${tmp}/BeanDeserializerBase.javap")"
if ! echo "${loader}" | grep ': aload' > /dev/null || echo "${loader}" | grep ': aload_0 *$' > /dev/null; then
  echo "the shaded jackson-databind BeanDeserializerBase.createContextual in ${jar} rebuilds the case-insensitive" \
    "property map from the unfiltered properties, undoing per-property @JsonIgnoreProperties" >&2
  exit 1
fi
echo "the shaded jackson-databind BeanDeserializerBase keeps per-property @JsonIgnoreProperties for case-insensitive" \
  "properties"

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/deser/impl/UnwrappedPropertyHandler.class \
  > "${tmp}/UnwrappedPropertyHandler.class"
# processUnwrapped must check the active view before it sets each unwrapped property from the buffered JSON.
# Unpatched, it never calls SettableBeanProperty.visibleInView.
javap -c -p "${tmp}/UnwrappedPropertyHandler.class" > "${tmp}/UnwrappedPropertyHandler.javap"
if ! awk '/ processUnwrapped\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
    "${tmp}/UnwrappedPropertyHandler.javap" | grep 'SettableBeanProperty.visibleInView:' > /dev/null; then
  echo "the shaded jackson-databind UnwrappedPropertyHandler.processUnwrapped in ${jar} lets @JsonView-restricted" \
    "@JsonUnwrapped properties be set" >&2
  exit 1
fi
echo "the shaded jackson-databind UnwrappedPropertyHandler honors @JsonView for @JsonUnwrapped properties"

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/deser/impl/ExternalTypeHandler.class \
  > "${tmp}/ExternalTypeHandler.class"
# Both complete overloads (default-constructor and property-based creator) and _deserializeAndSet must check the active
# view before they bind the buffered value of an external type id property. Unpatched, none of them calls
# SettableBeanProperty.visibleInView. Print the number of calls in each method body, one line per overload.
javap -c -p "${tmp}/ExternalTypeHandler.class" > "${tmp}/ExternalTypeHandler.javap"
for method in complete _deserializeAndSet; do
  counts="$(awk -v m=" ${method}\\\\(" '$0 ~ m { inside = 1; n = 0 } inside && /SettableBeanProperty.visibleInView:/ { n++ }
      inside && /^$/ { inside = 0; print n } END { if (inside) print n }' "${tmp}/ExternalTypeHandler.javap")"
  expected=1
  if [ "${method}" = complete ]; then
    expected=2
  fi
  checked="$(echo "${counts}" | grep -c '^[1-9]' || true)"
  if [ "${checked}" -ne "${expected}" ]; then
    echo "the shaded jackson-databind ExternalTypeHandler.${method} in ${jar} lets @JsonView-restricted external type id" \
      "properties be set" >&2
    exit 1
  fi
done
# The external-type-id property-based creator path must check the active view for creator properties as well as for
# the regular properties it buffers, i.e. call SettableBeanProperty.visibleInView twice. Unpatched, it checks only the
# regular properties (one call).
calls="$(awk '/ deserializeUsingPropertyBasedWithExternalTypeId\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
  "${tmp}/BeanDeserializer.javap" | grep -c 'SettableBeanProperty.visibleInView:' || true)"
if [ "${calls}" -lt 2 ]; then
  echo "the shaded jackson-databind BeanDeserializer.deserializeUsingPropertyBasedWithExternalTypeId in ${jar} lets" \
    "@JsonView-restricted creator properties be set" >&2
  exit 1
fi
echo "the shaded jackson-databind ExternalTypeHandler and BeanDeserializer honor @JsonView for external type id" \
  "properties and the creator properties of their beans"

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/ext/NioPathDeserializer.class \
  > "${tmp}/NioPathDeserializer.class"
# deserialize must check the URI scheme against the allow-list before it hands the URI to Paths.get(URI), whose
# FileSystemNotFoundException fallback looks the scheme's FileSystemProvider up through the thread context class
# loader. Unpatched, deserialize passes every scheme to Paths.get(URI). Print the javap line numbers of the first
# _isSchemeAllowed call and of the first Paths.get(URI) call in its body.
javap -c -p "${tmp}/NioPathDeserializer.class" > "${tmp}/NioPathDeserializer.javap"
order="$(awk '/ deserialize\(/ { inside = 1 } inside && /^$/ { inside = 0 }
    inside && /Method _isSchemeAllowed:/ && !allowed { allowed = NR }
    inside && /Method java\/nio\/file\/Paths.get:\(Ljava\/net\/URI;\)/ && !get { get = NR }
    END { print allowed + 0, get + 0 }' "${tmp}/NioPathDeserializer.javap")"
read -r allowed_line get_line <<< "${order}"
if [ "${allowed_line}" -eq 0 ] || [ "${get_line}" -eq 0 ] || [ "${allowed_line}" -gt "${get_line}" ]; then
  echo "the shaded jackson-databind NioPathDeserializer.deserialize in ${jar} resolves Path values of any URI scheme" >&2
  exit 1
fi
# The default allow-list (used by the no-argument constructor that Jackson registers) must hold only "file".
if ! awk '/^  static \{\};/ { inside = 1 } inside && /^$/ { inside = 0 } inside' "${tmp}/NioPathDeserializer.javap" \
    | grep -A 1 '// String file$' | grep 'Method java/util/Collections.singletonList:' > /dev/null; then
  echo "the shaded jackson-databind NioPathDeserializer in ${jar} does not default to the \"file\" URI scheme only" >&2
  exit 1
fi
echo "the shaded jackson-databind NioPathDeserializer accepts only \"file\" URIs (or no scheme) for Path values and" \
  "rejects other URI schemes before any FileSystemProvider lookup"
