package com.unfurl.fabric.studio;

public final class StudioEventBusFactory {
    public static StudioSessionEventBus create(StudioMicroserviceConfig config) {
        StudioMicroserviceConfig safe = config == null ? StudioMicroserviceConfig.defaults() : config;
        return switch (safe.eventBus().trim().toLowerCase()) {
            case "in-memory", "memory", "local" -> new InMemoryStudioSessionEventBus();
            case "redis" -> new RedisStudioSessionEventBus(safe.redisUrl());
            case "kafka" -> new KafkaStudioSessionEventBus(safe.kafkaBootstrapServers(), safe.kafkaTopic());
            default -> throw new IllegalArgumentException("unknown Studio event bus provider: " + safe.eventBus());
        };
    }

    private StudioEventBusFactory() {
    }
}
