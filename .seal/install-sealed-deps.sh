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
# - jackson-databind 2.15.2 with the fix for CVE-2026-59888 (@JsonIgnore on a Record component bypassed when a
#   PropertyNamingStrategy renames it, upstream FasterXML/jackson-databind c7c678360624da5bc7eed2152789fa522880db9d
#   (#5974)).
#   The patch is the one sealed com.fasterxml.jackson.core:jackson-databind 2.15.2+sp5p5 ships
#   (patches/CVE-2026-59888.patch).
# - jackson-databind 2.15.2 with the fix for CVE-2026-54513 (BasicPolymorphicTypeValidator.allowIfSubTypeIsArray()
#   approved every array, so a denied type wrapped as an array, e.g. Evil[] or Evil[][], bypassed the sub-type
#   allow-list; the validator now unwraps arrays and validates the innermost element type, upstream
#   FasterXML/jackson-databind 01d1692c8d0ed03e51a0e3c4f8a9e6908e4931e5 (#5981)).
#   The patch is the one sealed com.fasterxml.jackson.core:jackson-databind 2.15.2+sp5p5 ships
#   (patches/CVE-2026-54513.llm.patch).
# - jackson-databind 2.15.2 with the fix for CVE-2026-54512 (the PolymorphicTypeValidator only saw the raw container
#   class of a generic type id, so a name-based allow-list for a safe container was bypassed by smuggling a denied
#   type as a type parameter, e.g. "java.util.ArrayList<EvilGadget>"; DatabindContext now validates every type
#   parameter, nested parameters and array components included, and the array element type check of
#   BasicPolymorphicTypeValidator also consults the name-based matchers, upstream FasterXML/jackson-databind
#   434d6c511de7fdd9872f29157aafb6162d12d8d5 and 26a8c70bd42949ad72fbd9608e9f060ce661cb11 (#5988)).
#   The patch is the one sealed com.fasterxml.jackson.core:jackson-databind 2.15.2+sp5p5 ships
#   (patches/CVE-2026-54512.patch).
# - jackson-databind 2.15.2 with the fix for CVE-2026-54518 (@JsonView bypassed while the parameters of a
#   property-based creator are collected: a view-restricted creator parameter of a POJO with a @JsonUnwrapped property,
#   and a view-restricted setter / builder method whose value is buffered before the creator is complete, were set from
#   untrusted JSON even when the active view hides them; BeanDeserializer and BuilderBasedDeserializer now skip such
#   properties in _deserializeUsingPropertyBased and deserializeUsingPropertyBasedWithUnwrapped, upstream
#   FasterXML/jackson-databind c76ae1a121859cb983b28829566887d3a1b89faf (#5969, #5971)).
# - jackson-databind 2.15.2 with the fix for CVE-2026-54515 (a per-property @JsonFormat that changes
#   ACCEPT_CASE_INSENSITIVE_PROPERTIES made BeanDeserializerBase.createContextual rebuild the property map from the
#   unfiltered one, undoing the per-property @JsonIgnoreProperties / @JsonIncludeProperties of the same property, so
#   untrusted JSON could set the ignored properties again; the rebuild now starts from the filtered contextual map,
#   upstream FasterXML/jackson-databind bc1613c765704703ec7385e314fa8b19448e1ddd (#5962)).
#   The patch is the one sealed com.fasterxml.jackson.core:jackson-databind 2.15.2+sp5p5 ships
#   (patches/CVE-2026-54515.patch).
# - jackson-databind 2.15.2 with the fix for CVE-2026-59889 (@JsonView bypassed for @JsonUnwrapped field / setter
#   properties: UnwrappedPropertyHandler.processUnwrapped set every unwrapped property from the buffered JSON, so
#   untrusted JSON could populate an unwrapped block that the active view hides; it now skips the unwrapped properties
#   that are not visible in the active view, upstream FasterXML/jackson-databind
#   d627a8a86fcb062429282f79f3f256f181ed2c7b (#6060)).
#   The patch is the one sealed com.fasterxml.jackson.core:jackson-databind 2.15.2+sp5p5 ships
#   (patches/CVE-2026-59889.patch).
# - jackson-databind 2.15.2 with the fix for GHSA-mhm7-754m-9p8w (sonatype-2026-004744, @JsonView bypassed for
#   properties with an external type id (@JsonTypeInfo(include = EXTERNAL_PROPERTY)): ExternalTypeHandler bound the
#   buffered value of such a property in complete(...) and _deserializeAndSet even when the active view hides it, and
#   the creator-property branch of BeanDeserializer.deserializeUsingPropertyBasedWithExternalTypeId set view-restricted
#   creator parameters from untrusted JSON; both now skip the properties that are not visible in the active view,
#   upstream FasterXML/jackson-databind c628b357ed143d8492756d5c1458cfb9fbeb29ed (#6054), plus the creator-property
#   view check of the external-type-id path from 7dc7a17459e70b7db071cfdf06d2d96d70d4069d (#6004)).
#   The patch is ported from the one sealed com.fasterxml.jackson.core:jackson-databind 2.15.4+sp1p1 ships
#   (patches/GHSA-mhm7-754m-9p8w.llm.patch).
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
# The CVE-2026-59888 tests deserialize Records, which jackson-databind 2.15.2 compiles only on JDK 17 (java17 profile).
if [ -z "${JAVA_HOME_17_X64:-}" ]; then
  echo "JAVA_HOME_17_X64 is not set: set up JDK 17 before running this script" >&2
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
git -C "${work}/jackson-databind" apply "${here}/jackson-databind/CVE-2026-59888.patch"
git -C "${work}/jackson-databind" apply "${here}/jackson-databind/CVE-2026-54513.patch"
git -C "${work}/jackson-databind" apply "${here}/jackson-databind/CVE-2026-54512.patch"
git -C "${work}/jackson-databind" apply "${here}/jackson-databind/CVE-2026-54518.patch"
git -C "${work}/jackson-databind" apply "${here}/jackson-databind/CVE-2026-54515.patch"
git -C "${work}/jackson-databind" apply "${here}/jackson-databind/CVE-2026-59889.patch"
git -C "${work}/jackson-databind" apply "${here}/jackson-databind/GHSA-mhm7-754m-9p8w.patch"
# Runs the patches' own tests, so the install fails unless InetSocketAddress values are deserialized unresolved and
# unresolved IPv6 hosts are serialized bracketed, plus the property ignoral and naming strategy tests covering
# POJOPropertiesCollector, plus the array element type validation tests and the other tests covering
# BasicPolymorphicTypeValidator.allowIfSubTypeIsArray(), plus the generic type parameter validation tests and the
# other tests covering PolymorphicTypeValidator use in DatabindContext.resolveAndValidateSubType(), plus the
# @JsonView creator bypass tests and the other view, builder and unwrapped tests covering the property-based creator
# paths of BeanDeserializer and BuilderBasedDeserializer, plus the case-insensitive per-property ignoral tests and the
# other case-insensitivity and per-property ignoral / inclusion tests covering BeanDeserializerBase.createContextual,
# plus the @JsonView unwrapped field / setter bypass tests and the other unwrapped tests covering
# UnwrappedPropertyHandler.processUnwrapped, plus the @JsonView external type id bypass tests and the other external
# type id tests covering ExternalTypeHandler and BeanDeserializer.deserializeUsingPropertyBasedWithExternalTypeId.
mvn -B -f "${work}/jackson-databind/pom.xml" install \
  -Dtest=JDKStringLikeTypeDeserTest,JDKTypeSerializationTest,POJOPropertiesCollectorTest,IgnoreWithDeserTest,TestNamingStrategyStd,TestNamingStrategyCustom,NamingStrategyViaCreator556Test,BasicPTVArrayComponentBypassTest,BasicPTVWithArraysTest,BasicPTVTest,TestDefaultForArrays,ExceptionDeserializationTest,BasicPTVGenericParameterBypassTest,ValidatePolymSubTypeTest,ValidatePolymBaseTypeTest,CustomPTVMatchersTest,AnnotatedPolymorphicValidationTest,GenericTypeId1735Test,JsonTypeInfoIgnored2968Test,DefaultTypeResolver3505Test,DeserDefaultTypedConcrete2968Test,BuilderViewBypass5969And5971Test,UnwrappedCreatorViewBypass5971Test,TestViewDeserialization,DefaultViewTest,BuilderWithViewTest,UnwrappedWithView1559Test,UnwrappedCreatorParam265Test,BuilderWithUnwrappedTest,TestUnwrapped,TestUpdateViaObjectReader,IgnorePropertiesCaseInsensitive5962Test,CaseInsensitiveDeserTest,CaseInsensitiveDeser953Test,IgnorePropertyOnDeserTest,IncludeWithDeserTest,RecursiveIgnorePropertiesTest,UnwrappedViewBypass6060Test,TestUnwrappedWithPrefix,TestUnwrappedWithSameName647,TestUnwrappedWithTypeInfo,TestUnwrappedRecursive383,JsonViewExternalTypeIdBypassTest,CreatorViewBypassExtTypeIdTest,ExternalTypeIdTest,ExternalTypeIdWithCreatorTest,ExternalTypeIdWithCreator3045Test,ExternalTypeId2588Test,ExternalTypeId96Test,ExternalTypeIdWithEnum1328Test,ExternalTypeIdWithIgnoreUnknownTest,ExternalTypeCustomResolverTest,JsonValueExtTypeIdTest,MultipleExternalIds291Test,TestPropertyCreatorSubtypesExternalPropertyMissingProperty,TestSubtypesExternalPropertyMissingProperty,DelegatingExternalProperty1003Test,TestVisibleTypeId,BuilderAdvancedTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.javadoc.skip=true -Dgpg.skip=true
echo "installed jackson-databind 2.15.2 with the unresolved InetSocketAddress deserialization, the renamed" \
  "@JsonIgnore Record component ignoral, the array element type validation, the generic type parameter" \
  "validation, the @JsonView checks of the property-based creator paths, the per-property ignoral kept by" \
  "case-insensitive deserialization, the @JsonView checks of unwrapped properties and the @JsonView checks of" \
  "external type id properties into ${HOME}/.m2"

# The CVE-2026-59888 test (src/test-jdk17/.../records/RecordIgnoreNamingStrategy5974Test) uses Records, so the JDK 8
# build above does not compile it. Run it on JDK 17, together with the Record ignoral and naming strategy tests, against
# the same patched checkout, so the install fails unless a @JsonIgnore'd Record component cannot be set through its
# PropertyNamingStrategy-renamed name. The jar installed above stays the JDK 8 build.
JAVA_HOME="${JAVA_HOME_17_X64}" PATH="${JAVA_HOME_17_X64}/bin:${PATH}" mvn -B -f "${work}/jackson-databind/pom.xml" test \
  -Dtest=RecordIgnoreNamingStrategy5974Test,RecordWithJsonIgnoreTest,RecordWithJsonNaming3102Test,RecordNamingStrategy2992Test \
  -Dmaven.javadoc.skip=true -Dgpg.skip=true
echo "jackson-databind 2.15.2 ignores renamed @JsonIgnore Record components on JDK 17"
