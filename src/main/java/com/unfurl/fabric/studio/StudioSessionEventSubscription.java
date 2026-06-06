package com.unfurl.fabric.studio;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class StudioSessionEventSubscription implements AutoCloseable {
    private final String key;
    private final BlockingQueue<StudioSessionEvent> events;
    private final Runnable closeAction;

    StudioSessionEventSubscription(
            String key,
            BlockingQueue<StudioSessionEvent> events,
            Runnable closeAction
    ) {
        this.key = key;
        this.events = events;
        this.closeAction = closeAction;
    }

    public String key() {
        return key;
    }

    public StudioSessionEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        return events.poll(timeout, unit);
    }

    @Override
    public void close() {
        closeAction.run();
    }
}
