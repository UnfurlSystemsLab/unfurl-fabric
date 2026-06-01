package com.unfurl.fabric.cli;

import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.substrate.api.SubstrateMetadata;
import com.unfurl.substrate.api.SubstratePortRequirement;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExplainSubstrateCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        SubstrateProfile profile = readProfile(args.requiredPath("profile"));
        SubstrateProfileCodec codec = new SubstrateProfileCodec();
        String computedHash = codec.computeProfileHash(profile);
        boolean profileHashValid = profile.profileHash() == null || computedHash.equals(profile.profileHash());

        out.println("Substrate profile explanation");
        out.println("componentId=" + profile.componentId());
        out.println("componentVersion=" + profile.componentVersion());
        out.println("profileHash=" + value(profile.profileHash()));
        out.println("computedProfileHash=" + computedHash);
        out.println("profileHashValid=" + profileHashValid);

        Path contractPath = args.optionalPath("contract");
        if (contractPath != null) {
            SignedFabricContract signed = readSigned(contractPath);
            String contractHash = signed.contract().substrateProfileHash();
            boolean matches = computedHash.equals(contractHash);
            out.println("contractProfileHash=" + value(contractHash));
            out.println("profileHashMatchesContract=" + matches);
            if (!matches) {
                throw FabricCliException.runtime("substrate profile hash does not match contract");
            }
        }

        SubstrateMetadata metadata = profile.metadata();
        out.println("schemaVersion=" + metadata.schemaVersion());
        out.println("sourceArtifact=" + value(metadata.sourceArtifact()));
        out.println("supportedBindingModes=" + metadata.supportedBindingModes());
        out.println("closedWorldPorts=" + profile.portRequirements().size());
        out.println();

        out.println("Required ports");
        if (profile.portRequirements().isEmpty()) {
            out.println("- none");
        }
        for (SubstratePortRequirement port : profile.portRequirements()) {
            out.println("- " + port.port());
            out.println("  capability=" + port.capability());
            out.println("  versionRange=" + port.versionRange());
            out.println("  required=" + port.required());
            out.println("  bindingMode=" + value(port.bindingMode() == null ? null : port.bindingMode().name()));
            out.println("  providerPreference=" + value(provider(port)));
        }
        out.println();
        out.println("Closed-world note: flow STRICT mode initializes only the required ports above.");

        if (!profileHashValid) {
            throw FabricCliException.runtime("substrate profile hash does not match profile content");
        }
        return 0;
    }

    private SubstrateProfile readProfile(Path path) {
        try {
            return new SubstrateProfileCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read substrate profile " + path + ": " + ex.getMessage());
        }
    }

    private SignedFabricContract readSigned(Path path) {
        try {
            return new SignedFabricContractCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read signed contract " + path + ": " + ex.getMessage());
        }
    }

    private String provider(SubstratePortRequirement port) {
        Object provider = port.constraints().get("provider");
        return provider == null ? null : String.valueOf(provider);
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
