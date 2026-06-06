package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StudioServerBindAddressTest {

    @Test
    void defaultsToLoopbackBindAddress() throws Exception {
        // The no-arg constructor binds to DEFAULT_PORT, which races against any
        // real Studio server running on the developer's machine. Verify the
        // loopback default through the constant + an ephemeral-port server.
        assertThat(StudioServer.DEFAULT_BIND_ADDRESS).isEqualTo("127.0.0.1");
        try (StudioServer server = new StudioServer(StudioServer.DEFAULT_BIND_ADDRESS, 0)) {
            assertThat(server.bindAddress()).isEqualTo("127.0.0.1");
            assertThat(server.nonLoopbackBindWarningRequired()).isFalse();
        }
    }

    @Test
    void nonLoopbackBindRequiresWarning() throws Exception {
        try (StudioServer server = new StudioServer("0.0.0.0", 0)) {
            assertThat(server.bindAddress()).isEqualTo("0.0.0.0");
            assertThat(server.nonLoopbackBindWarningRequired()).isTrue();
        }
    }

    @Test
    void launcherParsesBindAndPort() {
        StudioMicroserviceConfig options = StudioServerLauncher.Options.parse(
                new String[]{"--bind", "0.0.0.0", "--port", "9000"});

        assertThat(options.bindAddress()).isEqualTo("0.0.0.0");
        assertThat(options.port()).isEqualTo(9000);
    }

    @Test
    void launcherParsesEventBusOptions() {
        StudioMicroserviceConfig options = StudioServerLauncher.Options.parse(new String[]{
                "--event-bus", "kafka",
                "--kafka-bootstrap-servers", "broker-a:9092",
                "--kafka-topic", "studio.events"
        });

        assertThat(options.eventBus()).isEqualTo("kafka");
        assertThat(options.kafkaBootstrapServers()).isEqualTo("broker-a:9092");
        assertThat(options.kafkaTopic()).isEqualTo("studio.events");
    }
}
