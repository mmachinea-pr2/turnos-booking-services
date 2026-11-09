package ar.edu.programacion2.booking.phone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class BookingEventTests {
    static final String JSON = """
        {"eventId":"event-1","eventType":"AdditionalInformationRequested",
        "reservationProcessId":"process-1","holdId":"hold-1","requiredField":"PHONE_NUMBER",
        "message":"Ingrese un telefono","expiresAt":"2030-01-01T11:00:00Z",
        "occurredAt":"2030-01-01T10:00:00Z","schemaVersion":1}
        """;
    @Test void readsContractWithoutRequiringGroupId() {
        var event=BookingEvent.parse("process-1",JSON);
        assertThat(event.eventId()).isEqualTo("event-1");
        assertThat(event.toString()).doesNotContain("Ingrese","process-1");
        assertThat(PhoneRequest.from(event).requestEventId()).isEqualTo("event-1");
    }
    @ParameterizedTest
    @NullSource
    @ValueSource(strings={"", "null", "{}", "[]", "bad-json"})
    void rejectsMalformedJson(String value) {
        assertThatThrownBy(() -> BookingEvent.parse("process-1",value)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid booking event envelope").hasNoCause();
    }
    @ParameterizedTest
    @ValueSource(strings={"eventId","eventType","reservationProcessId","holdId","requiredField","message","expiresAt","occurredAt","schemaVersion"})
    void rejectsMissingRequiredFields(String field) {
        var node=tools.jackson.databind.json.JsonMapper.builder().build().readTree(JSON).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)node).remove(field);
        assertThatThrownBy(() -> BookingEvent.parse("process-1",node.toString())).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"2", "1.0", "\"1\"", "null", "4294967297"})
    void rejectsUnsupportedSchema(String schema) {
        assertThatThrownBy(() -> BookingEvent.parse("process-1",JSON.replace("\"schemaVersion\":1","\"schemaVersion\":"+schema)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMismatchedKeyAndInvalidPhoneFields() {
        assertThatThrownBy(() -> BookingEvent.parse("wrong",JSON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BookingEvent.parse(null,JSON)).isInstanceOf(IllegalArgumentException.class);
        for(String value : java.util.List.of(JSON.replace("PHONE_NUMBER","EMAIL"),JSON.replace("11:00:00Z","09:00:00Z"),
                JSON.replace("event-1"," "),JSON.replace("event-1","x".repeat(101)),JSON.replace("2030-01-01T10:00:00Z","invalid-date"),"x".repeat(65537))) {
            assertThatThrownBy(() -> BookingEvent.parse("process-1",value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
