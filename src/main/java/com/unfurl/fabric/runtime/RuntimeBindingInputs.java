package com.unfurl.fabric.runtime;

import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.fabric.signing.SignedFabricContract;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Value Object and Adapter: the operator's runtime-binding inputs, read from a map whose keys are the arguments of
 * {@code fabric.runtime-binding-generate} (the flags of {@code fabric runtime-bindings}). The binding tools and Studio's
 * deployment environments (LLD-fabric-studio-api, <i>Deployment Environments</i>) both read their inputs here, so an
 * environment's settings mean exactly what the same flags mean.
 *
 * <p>Keys: {@code tenant} (or {@code tenantId}), {@code environment}, {@code telemetryNamespace}, {@code bindingIdPrefix},
 * {@code serviceBaseUrls} ({@code {claimUri: url}}) and the {@code flowBaseUrl}/{@code foundryBaseUrl} aliases,
 * {@code timeoutMs}, {@code identity} (HLD-needs-driven-identity §6), {@code componentConfig}
 * ({@code <claimUri>#<key>=<value>} entries), {@code documentationVisibilityDefault}, {@code documentationVisibilityPath},
 * {@code catalogObservationIntervalMs}, {@code catalogObservationMaxBackoffMs} and {@code disableBindings}
 * ({@code <provider-claim-uri>#<capability>} entries). Nothing is guessed: an absent key keeps the generator's default,
 * and identity has no default at all.
 *
 * @param tenant                the binding tenant, or null for the generator's default.
 * @param environment           the target environment name, or null for the generator's default.
 * @param telemetryNamespace    the telemetry namespace, or null for the generator's default.
 * @param bindingIdPrefix       the binding id prefix, or null for the generator's default.
 * @param serviceBaseUrls       operator base URLs by provider claim URI.
 * @param timeoutMs             the bindings' runtime timeout; 0 keeps the generator's default.
 * @param identity              the identity-provider binding settings; {@link RuntimeBindingSetGenerator.IdentityOptions#none()}
 *                              when none was given.
 * @param componentConfig       hosted components' configuration entries, as given.
 * @param documentation         the API catalog's documentation settings.
 * @param disabledBindings      the bindings the operator switches off.
 */
public record RuntimeBindingInputs(
        String tenant,
        String environment,
        String telemetryNamespace,
        String bindingIdPrefix,
        Map<URI, String> serviceBaseUrls,
        int timeoutMs,
        RuntimeBindingSetGenerator.IdentityOptions identity,
        List<String> componentConfig,
        DocumentationOptions documentation,
        DisabledBindings disabledBindings
) {
    /** Canonical constructor: never-null collections and options. */
    public RuntimeBindingInputs {
        serviceBaseUrls = serviceBaseUrls == null ? Map.of() : Map.copyOf(serviceBaseUrls);
        identity = identity == null ? RuntimeBindingSetGenerator.IdentityOptions.none() : identity;
        componentConfig = componentConfig == null ? List.of() : List.copyOf(componentConfig);
        documentation = documentation == null ? DocumentationOptions.none() : documentation;
        disabledBindings = disabledBindings == null ? DisabledBindings.none() : disabledBindings;
    }

    /**
     * A malformed input, naming the argument it was read from so a caller can say which one to fix.
     */
    public static final class Invalid extends IllegalArgumentException {
        private final String argument;

        /**
         * @param argument the argument key, for example {@code disableBindings}.
         * @param message  what is wrong with it.
         */
        public Invalid(String argument, String message) {
            super(message);
            this.argument = argument;
        }

        /** @return the argument key the problem was read from. */
        public String argument() {
            return argument;
        }
    }

    /**
     * Reads the inputs from a map with the tool's argument names.
     *
     * @param arguments    the arguments; null reads as empty.
     * @param existingPath resolves a path argument to an existing file ({@code documentationVisibilityPath}); it may
     *                     throw for a path that does not exist.
     * @return the inputs.
     * @throws Invalid naming the argument for a malformed base URL, number, documentation setting or disabled binding.
     */
    public static RuntimeBindingInputs fromMap(Map<String, Object> arguments, Function<String, Path> existingPath) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Map<URI, String> bases;
        try {
            bases = ServiceBaseUrls.parse(baseUrlEntries(map(args.get("serviceBaseUrls"))),
                    text(args, "flowBaseUrl"), text(args, "foundryBaseUrl"));
        } catch (IllegalArgumentException invalid) {
            throw new Invalid("serviceBaseUrls", invalid.getMessage());
        }
        long timeout = wholeNumber(args, args.containsKey("timeoutMs") ? "timeoutMs" : "timeout-ms", 0L);
        if (timeout < 0 || timeout > Integer.MAX_VALUE) {
            throw new Invalid("timeoutMs", "timeoutMs must be a positive whole number of milliseconds");
        }
        DocumentationOptions documentation;
        try {
            String visibilityPath = text(args, "documentationVisibilityPath");
            documentation = DocumentationOptions.of(text(args, "documentationVisibilityDefault"),
                    visibilityPath == null ? null : existingPath.apply(visibilityPath),
                    optionalWholeNumber(args, "catalogObservationIntervalMs"),
                    optionalWholeNumber(args, "catalogObservationMaxBackoffMs"));
        } catch (Invalid invalid) {
            throw invalid;
        } catch (IllegalArgumentException invalid) {
            throw new Invalid("documentation", invalid.getMessage());
        }
        DisabledBindings disabled;
        try {
            disabled = DisabledBindings.parse(stringList(args.get("disableBindings")));
        } catch (IllegalArgumentException invalid) {
            throw new Invalid("disableBindings", invalid.getMessage());
        }
        return new RuntimeBindingInputs(
                firstText(args, "tenant", "tenantId"),
                text(args, "environment"),
                text(args, "telemetryNamespace"),
                text(args, "bindingIdPrefix"),
                bases,
                (int) timeout,
                identity(map(args.get("identity"))),
                stringList(args.get("componentConfig")),
                documentation,
                disabled);
    }

    /**
     * Builds the generator's options for one signed contract.
     *
     * @param sourceSignedContract   the path the bindings name as their source signed contract.
     * @param signedSha256           the signed envelope's SHA-256, as lower-case hex.
     * @param contract               the signed contract (its canonical hash and signer fingerprint are named).
     * @param componentConfiguration the hosted components' configuration, built from {@link #componentConfig()} by
     *                               {@link HostedComponentConfiguration}.
     * @return the options; blank values fall back to the generator's defaults when it normalizes them.
     */
    public RuntimeBindingSetGenerator.Options options(Path sourceSignedContract, String signedSha256,
                                                      SignedFabricContract contract,
                                                      Map<URI, Configuration> componentConfiguration) {
        RuntimeBindingSetGenerator.Options defaults = RuntimeBindingSetGenerator.Options.defaults();
        return new RuntimeBindingSetGenerator.Options(
                sourceSignedContract,
                "sha256:" + signedSha256,
                contract.canonicalHash(),
                contract.signerKeyFingerprint(),
                tenant == null ? defaults.tenant() : tenant,
                environment == null ? defaults.environment() : environment,
                telemetryNamespace == null ? defaults.telemetryNamespace() : telemetryNamespace,
                bindingIdPrefix == null ? defaults.bindingIdPrefix() : bindingIdPrefix,
                serviceBaseUrls,
                timeoutMs <= 0 ? defaults.timeoutMs() : timeoutMs,
                identity,
                componentConfiguration,
                documentation,
                disabledBindings);
    }

    /**
     * Maps the {@code identity} object to the generator's identity options field for field, with no defaults.
     * {@code audiences} maps service claim URI to audience, {@code serviceCredentials} service claim URI to the
     * {@code secret://} reference of its client credentials.
     */
    private static RuntimeBindingSetGenerator.IdentityOptions identity(Map<String, Object> identity) {
        Map<String, String> audiences = new LinkedHashMap<>();
        map(identity.get("audiences")).forEach((service, audience) ->
                audiences.put(service, audience == null ? null : String.valueOf(audience)));
        Map<String, String> serviceCredentials = new LinkedHashMap<>();
        map(identity.get("serviceCredentials")).forEach((service, ref) ->
                serviceCredentials.put(service, ref == null ? null : String.valueOf(ref)));
        return new RuntimeBindingSetGenerator.IdentityOptions(
                text(identity, "issuer"),
                audiences,
                text(identity, "tenantClaim"),
                text(identity, "rolesClaim"),
                text(identity, "permissionsClaim"),
                text(identity, "tokenEndpointRef"),
                text(identity, "verificationKeysRef"),
                text(identity, "clientCredentialsRef"),
                serviceCredentials);
    }

    /** The {@code serviceBaseUrls} object ({@code {claimUri: url}}) as {@code claimUri=url} entries for ServiceBaseUrls. */
    private static List<String> baseUrlEntries(Map<String, Object> bases) {
        List<String> entries = new ArrayList<>();
        bases.forEach((claim, url) -> entries.add(claim + "=" + (url == null ? "" : url)));
        return entries;
    }

    /** A whole-number argument, or the fallback when absent; refused when not a whole number. */
    private static long wholeNumber(Map<String, Object> args, String key, long fallback) {
        Long value = optionalWholeNumber(args, key);
        return value == null ? fallback : value;
    }

    /** An optional whole-number argument: null when absent, refused (naming the key) when not a whole number. */
    private static Long optionalWholeNumber(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || value.toString().isBlank()) return null;
        if (value instanceof Number number) {
            if (number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new Invalid(key, "'" + value + "' is not a whole number");
            }
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException notANumber) {
            throw new Invalid(key, "'" + value + "' is not a whole number");
        }
    }

    /** A text argument, trimmed; null when absent or blank. */
    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** The first non-blank of two text arguments. */
    private static String firstText(Map<String, Object> args, String first, String second) {
        String value = text(args, first);
        return value != null ? value : text(args, second);
    }

    /** A list argument given as a JSON array or one string; empty when absent. */
    private static List<String> stringList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> values) return values.stream().map(String::valueOf).toList();
        String text = String.valueOf(raw);
        return text.isBlank() ? List.of() : List.of(text);
    }

    /** An object argument as a string-keyed map; empty when absent or not an object. */
    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
