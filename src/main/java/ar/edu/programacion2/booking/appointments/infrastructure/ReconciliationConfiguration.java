package ar.edu.programacion2.booking.appointments.infrastructure;

import ar.edu.programacion2.booking.appointments.domain.ReconciliationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReconciliationConfiguration {
    @Bean
    ReconciliationPolicy reconciliationPolicy() { return new ReconciliationPolicy(); }
}
