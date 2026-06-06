package com.unfurl.fabric.studio;

import java.nio.file.Path;

public record StudioMicroserviceConfig(
        String bindAddress,
        int port,
        Path statePath,
        Path assetRoot,
        String eventBus,
        String redisUrl,
        String kafkaBootstrapServers,
        String kafkaTopic
) {
    public StudioMicroserviceConfig {
        bindAddress = firstNonBlank(bindAddress, StudioServer.DEFAULT_BIND_ADDRESS);
        port = port <= 0 ? StudioServer.DEFAULT_PORT : port;
        statePath = statePath == null ? StudioStateStore.defaultPath() : statePath;
        eventBus = firstNonBlank(eventBus, env("UNFURL_STUDIO_EVENT_BUS", "in-memory"));
        redisUrl = firstNonBlank(redisUrl, env("UNFURL_STUDIO_REDIS_URL", "redis://localhost:6379"));
        kafkaBootstrapServers = firstNonBlank(
                kafkaBootstrapServers,
                env("UNFURL_STUDIO_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        kafkaTopic = firstNonBlank(kafkaTopic, env("UNFURL_STUDIO_KAFKA_TOPIC", "unfurl.fabric.studio.sessions"));
    }

    public static StudioMicroserviceConfig defaults() {
        return new StudioMicroserviceConfig(
                StudioServer.DEFAULT_BIND_ADDRESS,
                StudioServer.DEFAULT_PORT,
                StudioStateStore.defaultPath(),
                null,
                null,
                null,
                null,
                null);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return firstNonBlank(value, fallback);
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
