package ar.edu.programacion2.booking.phone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import static org.assertj.core.api.Assertions.*;

class BookingKafkaConfigurationTests {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(BookingKafkaConfiguration.class)
            .withBean(BookingEventInbox.class, () -> org.mockito.Mockito.mock(BookingEventInbox.class));
    }
    @Test void disabledConsumerDoesNotRequireBroker() {
        context().run(context -> assertThat(context).doesNotHaveBean(KafkaMessageListenerContainer.class));
    }
    @ParameterizedTest
    @ValueSource(strings={"", ".", "..", "wrong topic", "bad/topic"})
    void refusesInvalidTopics(String topic) {
        context().withPropertyValues("booking.catedra.kafka.enabled=true","booking.catedra.kafka.bootstrap-servers=localhost:9092",
            "booking.catedra.kafka.group-id=booking","booking.catedra.kafka.topic="+topic)
            .run(context -> assertThat(context).hasFailed());
    }
    @Test void refusesMissingBrokerGroupAndOversizedTopic() {
        for(String property : java.util.List.of("booking.catedra.kafka.bootstrap-servers=", "booking.catedra.kafka.group-id=",
                "booking.catedra.kafka.topic="+"x".repeat(250))) {
            context().withPropertyValues("booking.catedra.kafka.enabled=true","booking.catedra.kafka.bootstrap-servers=localhost:9092",
                "booking.catedra.kafka.group-id=booking","booking.catedra.kafka.topic=actions",property)
                .run(context -> assertThat(context).hasFailed());
        }
    }
    @Test void startupCanBeDisabledForDiagnosticsAndReportsDown() {
        context().withPropertyValues("booking.catedra.kafka.enabled=true","booking.catedra.kafka.bootstrap-servers=localhost:9092",
            "booking.catedra.kafka.group-id=booking","booking.catedra.kafka.topic=actions","booking.catedra.kafka.auto-startup=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(KafkaMessageListenerContainer.class).isRunning()).isFalse();
                assertThat(context.getBean(org.springframework.boot.health.contributor.HealthIndicator.class).health().getStatus().getCode()).isEqualTo("DOWN");
            });
    }
}
