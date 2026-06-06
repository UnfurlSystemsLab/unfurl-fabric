package com.unfurl.fabric.studio;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class KafkaStudioSessionEventBus implements StudioSessionEventBus {
    private final String bootstrapServers;
    private final String topic;
    private final KafkaProducer<String, String> producer;

    public KafkaStudioSessionEventBus(String bootstrapServers, String topic) {
        this.bootstrapServers = bootstrapServers == null || bootstrapServers.isBlank()
                ? "localhost:9092"
                : bootstrapServers;
        this.topic = topic == null || topic.isBlank() ? "unfurl.fabric.studio.sessions" : topic;
        this.producer = new KafkaProducer<>(producerProperties(this.bootstrapServers));
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public StudioSessionEventSubscription subscribe(String key, StudioSessionEvent initialEvent) {
        BlockingQueue<StudioSessionEvent> events = new LinkedBlockingQueue<>();
        events.offer(initialEvent);
        AtomicBoolean running = new AtomicBoolean(true);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers));
        consumer.subscribe(List.of(topic));
        Thread thread = Thread.ofVirtual()
                .name("studio-kafka-events-" + UUID.randomUUID())
                .start(() -> {
                    try {
                        while (running.get()) {
                            for (var record : consumer.poll(Duration.ofSeconds(1))) {
                                if (key.equals(record.key())) {
                                    parseEvent(record.value()).ifPresent(events::offer);
                                }
                            }
                        }
                    } catch (WakeupException ex) {
                        if (running.get()) {
                            throw ex;
                        }
                    }
                });
        return new StudioSessionEventSubscription(key, events, () -> {
            running.set(false);
            consumer.wakeup();
            thread.interrupt();
            consumer.close();
        });
    }

    @Override
    public void publish(String key, StudioSessionEvent event) {
        try {
            producer.send(new ProducerRecord<>(topic, key, StudioJson.mapper().writeValueAsString(event)));
        } catch (Exception ex) {
            throw new IllegalStateException("unable to publish Studio session event to Kafka", ex);
        }
    }

    @Override
    public StudioEventBusHealth health() {
        try {
            producer.partitionsFor(topic);
            return new StudioEventBusHealth(name(), "UP", bootstrapServers + "/" + topic);
        } catch (Exception ex) {
            return new StudioEventBusHealth(name(), "DOWN", ex.getMessage());
        }
    }

    @Override
    public void close() {
        producer.close();
    }

    private static Properties producerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return properties;
    }

    private static Properties consumerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "unfurl-fabric-studio-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return properties;
    }

    private static java.util.Optional<StudioSessionEvent> parseEvent(String message) {
        try {
            return java.util.Optional.of(StudioJson.mapper().readValue(message, StudioSessionEvent.class));
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }
}
