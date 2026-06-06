package com.unfurl.fabric.studio;

public interface StudioSessionEventBus extends AutoCloseable {
    String name();

    StudioSessionEventSubscription subscribe(String key, StudioSessionEvent initialEvent);

    void publish(String key, StudioSessionEvent event);

    default StudioEventBusHealth health() {
        return new StudioEventBusHealth(name(), "UP", "");
    }

    @Override
    default void close() {
    }
}
