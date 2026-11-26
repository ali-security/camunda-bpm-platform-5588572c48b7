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
#   instead of only the raw container class (DatabindContext._resolveAndValidateGeneric calls _validateContainedTypes,
#   which replaced _validateTypeParameter with the CVE-2026-110879 fix);
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
#   calls _isSchemeAllowed before Paths.get(URI), and DEFAULT_ALLOWED_SCHEMES is the singleton list of "file");
# - the shaded jackson-databind DefaultBaseTypeLimitingValidator blocks java.lang.Comparable as an unsafe polymorphic base
#   type (the DefaultBaseTypeLimitingValidator$UnsafeBaseTypes constructor adds Comparable to UNSAFE);
# - the shaded jackson-databind bounds the type id to deserializer cache of its TypeDeserializers
#   (TypeDeserializerBase._findDeserializer clears the cache once it holds 1000 entries, skips type ids longer than 256
#   characters, and returns the "nullifying" fallback without caching it);
# - the shaded jackson-databind validates the type parameters of generic type ids and the element type of array type
#   ids like direct type ids, also once the base type is approved (StdTypeResolverBuilder.verifyBaseTypeValidity
#   keeps the configured validator in the new ApprovedBaseTypeValidator instead of swapping in
#   LaissezFaireSubTypeValidator.instance; DatabindContext.resolveAndValidateSubType asks
#   PolymorphicTypeValidator.isArrayTypeAllowedExplicitly and calls _validateArrayElementType;
#   DatabindContext._validateContainedType calls _configuredValidator and SubTypeValidator.isIllegalType), and
#   BasicPolymorphicTypeValidator.validateSubType no longer accepts every abstract / interface array element type (no
#   Class.isInterface / Modifier.isAbstract call).
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
# _validateContainedTypes is also called by _validateContainedType, so check the bytecode of _resolveAndValidateGeneric
# itself. (The CVE-2026-110879 fix replaced the CVE-2026-54512 walk _validateTypeParameter with _validateContainedTypes.)
javap -c -p "${tmp}/DatabindContext.class" > "${tmp}/DatabindContext.javap"
if ! awk '/ _resolveAndValidateGeneric\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
    "${tmp}/DatabindContext.javap" | grep 'Method _validateContainedTypes:' > /dev/null; then
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

unzip -p "${jar}" 'spinjar/com/fasterxml/jackson/databind/jsontype/DefaultBaseTypeLimitingValidator$UnsafeBaseTypes.class' \
  > "${tmp}/DefaultBaseTypeLimitingValidator\$UnsafeBaseTypes.class"
# UNSAFE is filled by the instance initializer, i.e. in the constructor of UnsafeBaseTypes: its body must load the
# java.lang.Comparable class constant whose name it adds. Unpatched, the class does not reference Comparable at all.
javap -c -p "${tmp}/DefaultBaseTypeLimitingValidator\$UnsafeBaseTypes.class" \
  > "${tmp}/DefaultBaseTypeLimitingValidator\$UnsafeBaseTypes.javap"
if ! awk '/[$]UnsafeBaseTypes\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
    "${tmp}/DefaultBaseTypeLimitingValidator\$UnsafeBaseTypes.javap" \
    | grep -E 'ldc(_w)? .*// class java/lang/Comparable$' > /dev/null; then
  echo "the shaded jackson-databind DefaultBaseTypeLimitingValidator in ${jar} does not block java.lang.Comparable as" \
    "an unsafe polymorphic base type" >&2
  exit 1
fi
echo "the shaded jackson-databind DefaultBaseTypeLimitingValidator blocks java.lang.Comparable as an unsafe polymorphic" \
  "base type"

unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/jsontype/impl/TypeDeserializerBase.class \
  > "${tmp}/TypeDeserializerBase.class"
# _findDeserializer must bound the type id cache before it adds an entry: compare the type id length with 256 and the
# cache size with 1000, and clear the cache once it is full. It must also compare the deserializer it found with
# NullifyingDeserializer.instance (so it can return it uncached), besides returning that instance for unknown ids.
# Unpatched, it puts every type id into the cache unconditionally and reads NullifyingDeserializer.instance only once.
javap -c -p "${tmp}/TypeDeserializerBase.class" > "${tmp}/TypeDeserializerBase.javap"
awk '/ _findDeserializer\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
  "${tmp}/TypeDeserializerBase.javap" > "${tmp}/_findDeserializer.javap"
nullifying="$(grep -c 'Field [^ ]*NullifyingDeserializer.instance:' "${tmp}/_findDeserializer.javap" || true)"
if ! grep -E 'sipush +256$' "${tmp}/_findDeserializer.javap" > /dev/null \
    || ! grep 'Method java/lang/String.length:' "${tmp}/_findDeserializer.javap" > /dev/null \
    || ! grep -E 'sipush +1000$' "${tmp}/_findDeserializer.javap" > /dev/null \
    || ! grep 'InterfaceMethod java/util/Map.size:' "${tmp}/_findDeserializer.javap" > /dev/null \
    || ! grep 'InterfaceMethod java/util/Map.clear:' "${tmp}/_findDeserializer.javap" > /dev/null \
    || [ "${nullifying}" -lt 2 ]; then
  echo "the shaded jackson-databind TypeDeserializerBase._findDeserializer in ${jar} lets the type id cache grow" \
    "without bound" >&2
  exit 1
fi
echo "the shaded jackson-databind TypeDeserializerBase bounds the type id cache and does not cache the nullifying" \
  "fallback"

approved='spinjar/com/fasterxml/jackson/databind/jsontype/impl/ApprovedBaseTypeValidator.class'
if ! unzip -p "${jar}" "${approved}" > "${tmp}/ApprovedBaseTypeValidator.class" \
    || [ ! -s "${tmp}/ApprovedBaseTypeValidator.class" ]; then
  echo "the shaded jackson-databind in ${jar} has no ${approved}" >&2
  exit 1
fi
unzip -p "${jar}" spinjar/com/fasterxml/jackson/databind/jsontype/impl/StdTypeResolverBuilder.class \
  > "${tmp}/StdTypeResolverBuilder.class"
# verifyBaseTypeValidity must keep the configured validator for an approved base type (new ApprovedBaseTypeValidator),
# so the type parameters and array element types of type ids can still be validated against it. Unpatched, it returns
# LaissezFaireSubTypeValidator.instance, which approves every one of them.
javap -c -p "${tmp}/StdTypeResolverBuilder.class" > "${tmp}/StdTypeResolverBuilder.javap"
awk '/ verifyBaseTypeValidity\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
  "${tmp}/StdTypeResolverBuilder.javap" > "${tmp}/verifyBaseTypeValidity.javap"
if ! grep -E '// class [^ ]*/ApprovedBaseTypeValidator$' "${tmp}/verifyBaseTypeValidity.javap" > /dev/null \
    || grep 'Field [^ ]*LaissezFaireSubTypeValidator.instance:' "${tmp}/verifyBaseTypeValidity.javap" > /dev/null; then
  echo "the shaded jackson-databind StdTypeResolverBuilder.verifyBaseTypeValidity in ${jar} drops the configured" \
    "validator once the base type is approved" >&2
  exit 1
fi
# resolveAndValidateSubType must validate the element type of an array type id unless the validator allows the array
# type explicitly, and _validateContainedType must check type parameters and array element types with the configured
# validator (not the approved-base-type wrapper) and against the deny list. Unpatched, none of these methods exist.
awk '/ resolveAndValidateSubType\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
  "${tmp}/DatabindContext.javap" > "${tmp}/resolveAndValidateSubType.javap"
awk '/ _validateContainedType\(/ { inside = 1 } inside && /^$/ { inside = 0 } inside' \
  "${tmp}/DatabindContext.javap" > "${tmp}/_validateContainedType.javap"
if ! grep 'Method [^ ]*PolymorphicTypeValidator.isArrayTypeAllowedExplicitly:' \
      "${tmp}/resolveAndValidateSubType.javap" > /dev/null \
    || ! grep 'Method _validateArrayElementType:' "${tmp}/resolveAndValidateSubType.javap" > /dev/null \
    || ! grep 'Method _configuredValidator:' "${tmp}/_validateContainedType.javap" > /dev/null \
    || ! grep 'Method [^ ]*SubTypeValidator.isIllegalType:' "${tmp}/_validateContainedType.javap" > /dev/null; then
  echo "the shaded jackson-databind DatabindContext in ${jar} does not validate the type parameters and array element" \
    "types of type ids like direct type ids" >&2
  exit 1
fi
# validateSubType must no longer approve arrays of every abstract / interface element type: unpatched, it calls
# Class.isInterface and Modifier.isAbstract on the innermost element type.
if grep -E 'Method java/lang/Class.isInterface:|Method java/lang/reflect/Modifier.isAbstract:' \
    "${tmp}/validateSubType.javap" > /dev/null; then
  echo "the shaded jackson-databind BasicPolymorphicTypeValidator.validateSubType in ${jar} approves arrays of any" \
    "abstract or interface element type" >&2
  exit 1
fi
echo "the shaded jackson-databind validates the type parameters and array element types of type ids like direct type" \
  "ids, also for approved base types, and no longer approves arrays of every abstract or interface element type"
