package ar.edu.programacion2.booking.cancellation;

import java.time.Instant;

public interface CancellationGateway {
    Outcome cancel(Cancellation cancellation);
    record Outcome(String status, Instant cancelledAt, String errorCode) {
        public static Outcome uncertain() { return new Outcome("UNCERTAIN",null,"CANCELLATION_RESULT_UNKNOWN"); }
    }
}
