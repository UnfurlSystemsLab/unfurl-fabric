package com.unfurl.fabric.needs;

import com.unfurl.substrate.api.BindingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NeedsCodecTest {
    private final NeedsCodec codec = new NeedsCodec();

    @Test
    void parsesProviderFriendlyNeedsYaml() {
        byte[] yaml = """
                requiredCapabilities:
                  - capability: storage.put
                    capabilityVersion: ^1
                optionalCapabilities:
                  - capability: audit.write
                    capabilityVersion: ^1
                artifactConstraints:
                  - group: com.unfurl
                    name: storage-s3
                    version: ">=1.0.0 <2.0.0"
                refusalExpectations: [identity-management]
                trustPolicyRef: ./trust-policy.yaml
                bindingPreferences:
                  storage.put: REMOTE_HTTP
                """.getBytes(StandardCharsets.UTF_8);

        Need need = codec.parse(yaml);

        assertThat(need.requiredCapabilities()).extracting(CapabilityRequirement::capability)
                .containsExactly("storage.put");
        assertThat(need.optionalCapabilities()).extracting(CapabilityRequirement::capability)
                .containsExactly("audit.write");
        assertThat(need.artifactConstraints()).hasSize(1);
        assertThat(need.refusalExpectations()).containsExactly("identity-management");
        assertThat(need.trustPolicyRef()).isEqualTo(Path.of("./trust-policy.yaml"));
        assertThat(need.bindingPreferences()).containsEntry("storage.put", BindingMode.REMOTE_HTTP);
    }

    @Test
    void writesAndReadsNeed(@TempDir Path dir) {
        Need original = new Need(
                java.util.List.of(CapabilityRequirement.requiredOf("storage.put", "^1")),
                java.util.List.of(CapabilityRequirement.optionalOf("audit.write", "*")),
                java.util.List.of(new ArtifactConstraint("com.unfurl", "storage-s3", new ArtifactVersionRange("^1"))),
                java.util.Set.of("identity-management"),
                Path.of("trust.yaml"),
                java.util.Map.of("storage.put", BindingMode.IN_PROCESS));

        Path target = dir.resolve("needs.yaml");
        codec.write(original, target);

        Need parsed = codec.read(target);

        assertThat(parsed.requiredCapabilities()).isEqualTo(original.requiredCapabilities());
        assertThat(parsed.optionalCapabilities()).isEqualTo(original.optionalCapabilities());
        assertThat(parsed.artifactConstraints()).isEqualTo(original.artifactConstraints());
        assertThat(parsed.bindingPreferences()).isEqualTo(original.bindingPreferences());
    }
}
