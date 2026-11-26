package com.unfurl.fabric.studio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.unfurl.deployment.policy.DeploymentTarget;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A tenant's deployment environment (LLD-fabric-studio-api, <i>Deployment Environments</i>): what an emit needs besides
 * the contract, namely where it runs ({@link Target}, the CLI's {@code --target} file) and the operator's runtime-binding
 * inputs ({@link Bindings}, the flags of {@code fabric runtime-bindings}).
 *
 * <p>Pattern: immutable <b>DTO</b>, persisted as Studio tenant state. It holds references only, never secret values:
 * {@link StudioDeploymentEnvironments} checks every save. Rules that depend on a contract (identity inputs exactly when
 * identity is in the contract, hosted configuration schemas, documentation gaps) are not save-time rules; the emit-plan
 * preview runs them.
 *
 * @param name          the environment's name, unique per tenant ({@code [a-z0-9][a-z0-9-]{0,62}}).
 * @param revision      starts at 1 and grows by one with each save; for concurrent editing only, since an environment
 *                      deleted and created again starts at 1 again.
 * @param updatedAt     when it was last saved.
 * @param contentSha256 the SHA-256 of the canonical JSON of {@code {target, bindings}}: what an emit reads from the
 *                      environment. Always computed here, never taken from the caller or a stored file.
 * @param target        where it runs.
 * @param bindings      the runtime-binding inputs.
 */
public record StudioDeploymentEnvironment(String name, long revision, Instant updatedAt, String contentSha256, Target target,
                                          Bindings bindings) {
    /** Canonical JSON: sorted object keys and map entries, absent values left out, so equal content hashes equally. */
    private static final ObjectMapper CANONICAL = StudioJson.mapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    /** Canonical constructor: never-null parts, and the content hash computed from them, whatever was passed. */
    public StudioDeploymentEnvironment {
        target = target == null ? Target.EMPTY : target;
        bindings = bindings == null ? Bindings.EMPTY : bindings;
        contentSha256 = contentHash(target, bindings);
    }

    /** An environment as saved, its content hash computed. */
    public StudioDeploymentEnvironment(String name, long revision, Instant updatedAt, Target target, Bindings bindings) {
        this(name, revision, updatedAt, null, target, bindings);
    }

    /** The SHA-256, as lower-case hex, of the canonical JSON of {@code {target, bindings}}. */
    static String contentHash(Target target, Bindings bindings) {
        try {
            Map<String, Object> content = new TreeMap<>();
            content.put("bindings", bindings);
            content.put("target", target);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CANONICAL.writeValueAsBytes(content)));
        } catch (JsonProcessingException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("unable to hash a deployment environment: " + impossible.getMessage(), impossible);
        }
    }

    /**
     * The deployment target, as the CLI's {@code --target} file holds it.
     *
     * @param kind           the emitter that renders it, for example {@code local-compose} or {@code k8s}.
     * @param environment    the target environment name the target file names.
     * @param region         the region, or null.
     * @param endpoint       the endpoint, or null.
     * @param options        the target's own options, used unchanged.
     * @param credentialRefs credential names or labels, never values.
     */
    public record Target(String kind, String environment, String region, String endpoint, Map<String, String> options,
                         List<String> credentialRefs) {
        static final Target EMPTY = new Target(null, null, null, null, Map.of(), List.of());

        /** Canonical constructor: trimmed text, blank as null, and sorted, never-null collections. */
        public Target {
            kind = blankToNull(kind);
            environment = blankToNull(environment);
            region = blankToNull(region);
            endpoint = blankToNull(endpoint);
            options = options == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(options));
            credentialRefs = credentialRefs == null ? List.of() : List.copyOf(credentialRefs);
        }

        /**
         * The target as the deployment model holds it.
         *
         * @return the {@link DeploymentTarget}.
         * @throws IllegalArgumentException when the target is not a valid deployment target (a blank kind, or a
         *                                  credential ref that looks like a secret value).
         * @throws NullPointerException     when the kind is absent.
         */
        public DeploymentTarget deploymentTarget() {
            return new DeploymentTarget(kind, environment, Optional.ofNullable(region), Optional.ofNullable(endpoint),
                    options, credentialRefs);
        }
    }

    /**
     * The operator's runtime-binding inputs, with the meaning the same {@code fabric runtime-bindings} flags have.
     *
     * @param serviceBaseUrls    operator base URLs by provider claim URI.
     * @param timeoutMs          the bindings' runtime timeout, or null for the generator's default.
     * @param telemetryNamespace the telemetry namespace, or null for the generator's default.
     * @param identity           the identity-provider binding settings, or null when none are given.
     * @param componentConfig    hosted components' configuration entries, {@code <claimUri>#<key>=<value>}.
     * @param documentation      the API catalog's documentation settings, or null when none are given.
     * @param disabledBindings   bindings the operator switches off, {@code <provider-claim-uri>#<capability>}.
     */
    public record Bindings(Map<String, String> serviceBaseUrls, Integer timeoutMs, String telemetryNamespace,
                           Identity identity, List<String> componentConfig, Documentation documentation,
                           List<String> disabledBindings) {
        static final Bindings EMPTY = new Bindings(Map.of(), null, null, null, List.of(), null, List.of());

        /** Canonical constructor: sorted, never-null collections. */
        public Bindings {
            serviceBaseUrls = serviceBaseUrls == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(serviceBaseUrls));
            telemetryNamespace = blankToNull(telemetryNamespace);
            componentConfig = componentConfig == null ? List.of() : List.copyOf(componentConfig);
            disabledBindings = disabledBindings == null ? List.of() : List.copyOf(disabledBindings);
        }

        /**
         * Adapter: the inputs as the binding tools' arguments, which {@link com.unfurl.fabric.runtime.RuntimeBindingInputs}
         * reads, so an environment's settings mean what the same tool arguments mean.
         *
         * @param tenant         the binding tenant (the Studio tenant).
         * @param environment    the target environment name the bindings name.
         * @param visibilityPath the file the documentation visibility text was written to, or null when there is none.
         * @return the arguments map.
         */
        Map<String, Object> toolArguments(String tenant, String environment, Path visibilityPath) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("tenant", tenant);
            if (environment != null) arguments.put("environment", environment);
            if (telemetryNamespace != null) arguments.put("telemetryNamespace", telemetryNamespace);
            arguments.put("serviceBaseUrls", new LinkedHashMap<>(serviceBaseUrls));
            if (timeoutMs != null) arguments.put("timeoutMs", timeoutMs);
            if (identity != null) arguments.put("identity", identity.toolArguments());
            arguments.put("componentConfig", componentConfig);
            if (documentation != null) {
                if (documentation.visibilityDefault() != null) {
                    arguments.put("documentationVisibilityDefault", documentation.visibilityDefault());
                }
                if (visibilityPath != null) arguments.put("documentationVisibilityPath", visibilityPath.toString());
                if (documentation.observationIntervalMs() != null) {
                    arguments.put("catalogObservationIntervalMs", documentation.observationIntervalMs());
                }
                if (documentation.observationMaxBackoffMs() != null) {
                    arguments.put("catalogObservationMaxBackoffMs", documentation.observationMaxBackoffMs());
                }
            }
            arguments.put("disableBindings", disabledBindings);
            return arguments;
        }
    }

    /**
     * The identity-provider binding settings (HLD-needs-driven-identity §6). Every reference is a {@code secret://} or
     * {@code config://} URI, never a value.
     *
     * @param issuer               the token issuer.
     * @param audiences            token audience by protected service claim URI.
     * @param tenantClaim          the token claim naming the tenant.
     * @param rolesClaim           the token claim naming the roles.
     * @param permissionsClaim     the token claim naming the permissions.
     * @param tokenEndpointRef     the reference to the token endpoint.
     * @param verificationKeysRef  the reference to the verification keys.
     * @param clientCredentialsRef the reference to the client credentials.
     * @param serviceCredentials   the reference to each calling service's client credentials, by its claim URI.
     */
    public record Identity(String issuer, Map<String, String> audiences, String tenantClaim, String rolesClaim,
                           String permissionsClaim, String tokenEndpointRef, String verificationKeysRef,
                           String clientCredentialsRef, Map<String, String> serviceCredentials) {
        /** Canonical constructor: trimmed text, blank as null, sorted maps. */
        public Identity {
            issuer = blankToNull(issuer);
            audiences = audiences == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(audiences));
            tenantClaim = blankToNull(tenantClaim);
            rolesClaim = blankToNull(rolesClaim);
            permissionsClaim = blankToNull(permissionsClaim);
            tokenEndpointRef = blankToNull(tokenEndpointRef);
            verificationKeysRef = blankToNull(verificationKeysRef);
            clientCredentialsRef = blankToNull(clientCredentialsRef);
            serviceCredentials = serviceCredentials == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new TreeMap<>(serviceCredentials));
        }

        /** The settings as the binding tools' {@code identity} argument. */
        Map<String, Object> toolArguments() {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("issuer", issuer);
            identity.put("audiences", new LinkedHashMap<>(audiences));
            identity.put("tenantClaim", tenantClaim);
            identity.put("rolesClaim", rolesClaim);
            identity.put("permissionsClaim", permissionsClaim);
            identity.put("tokenEndpointRef", tokenEndpointRef);
            identity.put("verificationKeysRef", verificationKeysRef);
            identity.put("clientCredentialsRef", clientCredentialsRef);
            identity.put("serviceCredentials", new LinkedHashMap<>(serviceCredentials));
            return identity;
        }

        /** The references, by field path, each of which must be a {@code secret://} or {@code config://} URI. */
        Map<String, String> references() {
            Map<String, String> references = new LinkedHashMap<>();
            if (tokenEndpointRef != null) references.put("bindings.identity.tokenEndpointRef", tokenEndpointRef);
            if (verificationKeysRef != null) references.put("bindings.identity.verificationKeysRef", verificationKeysRef);
            if (clientCredentialsRef != null) references.put("bindings.identity.clientCredentialsRef", clientCredentialsRef);
            serviceCredentials.forEach((service, ref) ->
                    references.put("bindings.identity.serviceCredentials." + service, ref));
            return references;
        }
    }

    /**
     * The API catalog's documentation settings (HLD-api-documentation-artifacts §4.1, §4.2).
     *
     * @param visibilityDefault       the default visibility ({@code public}, {@code internal}, {@code private}), or null.
     * @param visibility              the text of a documentation-visibility file, or null.
     * @param observationIntervalMs   the catalog's observation interval, or null.
     * @param observationMaxBackoffMs the catalog's maximum observation backoff, or null.
     */
    public record Documentation(String visibilityDefault, String visibility, Long observationIntervalMs,
                                Long observationMaxBackoffMs) {
        /** Canonical constructor: blank text as null. */
        public Documentation {
            visibilityDefault = blankToNull(visibilityDefault);
            visibility = visibility == null || visibility.isBlank() ? null : visibility;
        }
    }

    /** Text helper: trims, mapping blank to null. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
