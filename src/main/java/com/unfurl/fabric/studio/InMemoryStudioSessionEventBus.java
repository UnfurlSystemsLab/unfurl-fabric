package com.unfurl.fabric.studio;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public final class InMemoryStudioSessionEventBus implements StudioSessionEventBus {
    private final Map<String, CopyOnWriteArrayList<BlockingQueue<StudioSessionEvent>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public StudioSessionEventSubscription subscribe(String key, StudioSessionEvent initialEvent) {
        BlockingQueue<StudioSessionEvent> events = new LinkedBlockingQueue<>();
        events.offer(initialEvent);
        CopyOnWriteArrayList<BlockingQueue<StudioSessionEvent>> listeners =
                subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(events);
        return new StudioSessionEventSubscription(key, events, () -> listeners.remove(events));
    }

    @Override
    public void publish(String key, StudioSessionEvent event) {
        List<BlockingQueue<StudioSessionEvent>> listeners = subscribers.get(key);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (BlockingQueue<StudioSessionEvent> listener : listeners) {
            listener.offer(event);
        }
    }
}
