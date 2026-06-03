package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StudioServerBindAddressTest {

    @Test
    void defaultsToLoopbackBindAddress() throws Exception {
        try (StudioServer server = new StudioServer()) {
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
        StudioServerLauncher.Options options = StudioServerLauncher.Options.parse(
                new String[]{"--bind", "0.0.0.0", "--port", "9000"});

        assertThat(options.bindAddress()).isEqualTo("0.0.0.0");
        assertThat(options.port()).isEqualTo(9000);
    }
}
