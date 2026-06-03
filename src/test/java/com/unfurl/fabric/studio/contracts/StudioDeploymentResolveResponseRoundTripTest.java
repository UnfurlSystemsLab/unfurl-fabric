package com.unfurl.fabric.studio.contracts;

import com.unfurl.fabric.studio.StudioDeploymentResolveResponse;
import com.unfurl.fabric.studio.StudioJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StudioDeploymentResolveResponseRoundTripTest {

    @Test
    void roundTripsSharedFixture() throws Exception {
        var mapper = StudioJson.mapper();
        byte[] fixture = Files.readAllBytes(Path.of(
                "src/test/resources/studio-contracts/resolve-deployment.json"));

        StudioDeploymentResolveResponse response = mapper.readValue(fixture, StudioDeploymentResolveResponse.class);
        StudioDeploymentResolveResponse reparsed = mapper.readValue(
                mapper.writeValueAsBytes(response),
                StudioDeploymentResolveResponse.class);

        assertThat(reparsed).isEqualTo(response);
        assertThat(reparsed.status()).isEqualTo("RESOLVED");
        assertThat(reparsed.selections()).singleElement()
                .satisfies(selection -> assertThat(selection.deploymentShape().name())
                        .isEqualTo("CONTAINERIZED_SERVICE"));
    }
}
