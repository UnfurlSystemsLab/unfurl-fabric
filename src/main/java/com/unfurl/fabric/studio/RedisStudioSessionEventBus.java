package com.unfurl.fabric.studio;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class RedisStudioSessionEventBus implements StudioSessionEventBus {
    private final URI redisUri;
    private final JedisPooled publisher;

    public RedisStudioSessionEventBus(String redisUrl) {
        this.redisUri = URI.create(redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl);
        this.publisher = new JedisPooled(redisUri);
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public StudioSessionEventSubscription subscribe(String key, StudioSessionEvent initialEvent) {
        BlockingQueue<StudioSessionEvent> events = new LinkedBlockingQueue<>();
        events.offer(initialEvent);
        Jedis subscriber = new Jedis(redisUri);
        JedisPubSub pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                parseEvent(message).ifPresent(events::offer);
            }
        };
        Thread thread = Thread.ofVirtual()
                .name("studio-redis-events-" + UUID.randomUUID())
                .start(() -> subscriber.subscribe(pubSub, channel(key)));
        return new StudioSessionEventSubscription(key, events, () -> {
            pubSub.unsubscribe();
            thread.interrupt();
            subscriber.close();
        });
    }

    @Override
    public void publish(String key, StudioSessionEvent event) {
        try {
            publisher.publish(channel(key), StudioJson.mapper().writeValueAsString(event));
        } catch (Exception ex) {
            throw new IllegalStateException("unable to publish Studio session event to Redis", ex);
        }
    }

    @Override
    public StudioEventBusHealth health() {
        try {
            publisher.ping();
            return new StudioEventBusHealth(name(), "UP", redisUri.toString());
        } catch (Exception ex) {
            return new StudioEventBusHealth(name(), "DOWN", ex.getMessage());
        }
    }

    @Override
    public void close() {
        publisher.close();
    }

    private static String channel(String key) {
        return "unfurl:fabric:studio:sessions:" + key;
    }

    private static java.util.Optional<StudioSessionEvent> parseEvent(String message) {
        try {
            return java.util.Optional.of(StudioJson.mapper().readValue(
                    message.getBytes(StandardCharsets.UTF_8),
                    StudioSessionEvent.class));
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }
}
