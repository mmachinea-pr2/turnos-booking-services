package ar.edu.programacion2.booking.phone;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class BookingKafkaIntegrationTests {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @Test
    void commitsSuccessAndDuplicatesButRedeliversFailureAfterRestart() throws Exception {
        String topic = "booking-" + UUID.randomUUID();
        String group = "group-" + UUID.randomUUID();
        var fail = new AtomicBoolean(true);
        var firstEventCalls = new AtomicInteger();
        var secondEventCalls = new AtomicInteger();
        var handler = org.mockito.Mockito.mock(BookingEventInbox.class);
        org.mockito.Mockito.doAnswer(call -> {
            var event = call.getArgument(0,BookingEvent.class);
            if (event.eventId().equals("event-2")) {
                secondEventCalls.incrementAndGet();
                if (fail.get()) throw new IllegalStateException("Database unavailable");
            } else firstEventCalls.incrementAndGet();
            return null;
        }).when(handler).receive(org.mockito.ArgumentMatchers.any());
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
                var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
            send(producer, topic, "process-1", BookingEventTests.JSON);
            send(producer, topic, "process-1", BookingEventTests.JSON.replace("event-1", "event-2"));
            context(topic, group, handler).run(context -> {
                assertThat(context).hasNotFailed();
                var container = context.getBean(KafkaMessageListenerContainer.class);
                await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
                    assertThat(secondEventCalls.get()).isEqualTo(1);
                    assertThat(container.isRunning()).isFalse();
                    assertThat(offset(admin, group, topic)).isEqualTo(1);
                });
                assertThat(context.getBean(HealthIndicator.class).health().getStatus().getCode()).isEqualTo("DOWN");
                fail.set(false);
                container.start();
                await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(offset(admin, group, topic)).isEqualTo(2));
                assertThat(secondEventCalls.get()).isEqualTo(2);
                assertThat(firstEventCalls.get()).isEqualTo(1);
            });
            // Nuevo contexto y mismo grupo: retoma el offset confirmado, no desde el inicio.
            send(producer, topic, "process-1", BookingEventTests.JSON);
            context(topic, group, handler).run(context -> {
                assertThat(context).hasNotFailed();
                await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(offset(admin, group, topic)).isEqualTo(3));
                assertThat(firstEventCalls.get()).isEqualTo(2);
                assertThat(secondEventCalls.get()).isEqualTo(2);
                assertThat(context.getBean(HealthIndicator.class).health().getStatus().getCode()).isEqualTo("UP");
            });
        }
    }

    @Test
    void malformedEventStopsWithoutCommittingOrCallingApplication() throws Exception {
        String topic = "booking-invalid-" + UUID.randomUUID();
        String group = "group-" + UUID.randomUUID();
        var handler = org.mockito.Mockito.mock(BookingEventInbox.class);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
                var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
            send(producer, topic, "process-1", "invalid-json");
            context(topic, group, handler).run(context -> {
                assertThat(context).hasNotFailed();
                var container = context.getBean(KafkaMessageListenerContainer.class);
                await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(container.isRunning()).isFalse());
                assertThat(offset(admin, group, topic)).isLessThanOrEqualTo(0);
                org.mockito.Mockito.verifyNoInteractions(handler);
                assertThat(context.getBean(HealthIndicator.class).health().getStatus().getCode()).isEqualTo("DOWN");
            });
        }
    }

    private ApplicationContextRunner context(String topic, String group, BookingEventInbox handler) {
        return new ApplicationContextRunner().withUserConfiguration(BookingKafkaConfiguration.class)
            .withBean(BookingEventInbox.class, () -> handler)
            .withPropertyValues("booking.catedra.kafka.enabled=true",
                "booking.catedra.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "booking.catedra.kafka.topic=" + topic, "booking.catedra.kafka.group-id=" + group);
    }

    private KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all"),
            new StringSerializer(), new StringSerializer());
    }

    private void send(KafkaProducer<String, String> producer, String topic, String key, String value) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, value)).get(20, TimeUnit.SECONDS);
    }

    private long offset(AdminClient admin, String group, String topic) {
        try {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(topic, 0));
            return offset == null ? -1 : offset.offset();
        } catch (Exception exception) {
            throw new AssertionError("Could not read test consumer offset", exception);
        }
    }

    @Test
    void publishesPhoneEnvelopeWithProcessKeyUsingConfiguredProducer() throws Exception {
        String group="test-"+UUID.randomUUID();
        String topic="catedra.turnos.telefono."+group;
        String payload="""
            {"eventId":"phone-event","eventType":"AdditionalInformationSubmitted",
            "groupId":"%s","reservationProcessId":"process-1","holdId":"hold-1",
            "requestEventId":"request-1","information":{"phoneNumber":"+5492615551234"},
            "occurredAt":"2030-01-01T10:00:00Z","schemaVersion":1}
            """.formatted(group);
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()));
                var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(
                    Map.of("bootstrap.servers",KAFKA.getBootstrapServers(),"group.id",group,"auto.offset.reset","earliest","enable.auto.commit",false),
                    new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.StringDeserializer())) {
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(20,TimeUnit.SECONDS);
            consumer.subscribe(List.of(topic));
            new ApplicationContextRunner().withUserConfiguration(PhoneProducerConfiguration.class)
                .withPropertyValues("booking.catedra.kafka.enabled=true","booking.catedra.kafka.bootstrap-servers="+KAFKA.getBootstrapServers())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(PhonePublisher.class).publish(new PhoneSubmissionOutbox.Delivery("phone-event","lease",topic,"process-1",payload));
                    var received=new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String,String>>();
                    await().atMost(Duration.ofSeconds(20)).until(() -> {
                        consumer.poll(Duration.ofMillis(250)).forEach(received::add);
                        return !received.isEmpty();
                    });
                    assertThat(received).hasSize(1);
                    assertThat(received.getFirst().key()).isEqualTo("process-1");
                    assertThat(received.getFirst().value()).isEqualTo(payload);
                });
        }
    }
}
