package ar.edu.programacion2.booking.phone;

import java.util.HashMap;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "booking.catedra.kafka.enabled", havingValue = "true")
public class PhoneProducerConfiguration {
    @Bean
    DefaultKafkaProducerFactory<String,String> phoneProducerFactory(
            @Value("${booking.catedra.kafka.bootstrap-servers}") String brokers) {
        if (brokers.isBlank()) throw new IllegalArgumentException("Kafka brokers are required");
        var settings = new HashMap<String,Object>();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,brokers);
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        settings.put(ProducerConfig.ACKS_CONFIG,"all");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        settings.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);
        settings.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,10000);
        settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,5000);
        return new DefaultKafkaProducerFactory<>(settings);
    }
    @Bean
    KafkaTemplate<String,String> phoneKafkaTemplate(DefaultKafkaProducerFactory<String,String> phoneProducerFactory) {
        var template = new KafkaTemplate<>(phoneProducerFactory);
        // The default listener logs record values on failure; phone numbers must stay out of logs.
        template.setProducerListener(new org.springframework.kafka.support.ProducerListener<>() {
            @Override public void onError(org.apache.kafka.clients.producer.ProducerRecord<String,String> record,
                    org.apache.kafka.clients.producer.RecordMetadata metadata, Exception exception) {
                org.slf4j.LoggerFactory.getLogger(PhoneProducerConfiguration.class)
                    .warn("Phone event publication failed; delivery remains recoverable from the outbox");
            }
        });
        return template;
    }
    @Bean
    PhonePublisher phonePublisher(KafkaTemplate<String,String> phoneKafkaTemplate) {
        return new KafkaPhonePublisher(phoneKafkaTemplate);
    }
}
