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
#   (UnwrappedPropertyHandler.processUnwrapped calls SettableBeanProperty.visibleInView).
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
