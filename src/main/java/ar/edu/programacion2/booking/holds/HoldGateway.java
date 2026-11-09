package ar.edu.programacion2.booking.holds;

import java.time.Instant;

public interface HoldGateway {
    Outcome create(CreateHoldRequest request, String userId);
    Outcome confirm(ReservationProcess process, ConfirmHoldRequest request, String userId);
    record Outcome(ReservationProcess.Status status, String holdId, String processId, Instant expiresAt, String errorCode) {
        public static Outcome uncertain() {
            return new Outcome(ReservationProcess.Status.UNCERTAIN, null, null, null, "REMOTE_RESULT_UNKNOWN");
        }
        public static Outcome confirmationUncertain() {
            return new Outcome(ReservationProcess.Status.CONFIRMATION_UNCERTAIN, null, null, null, "CONFIRMATION_RESULT_UNKNOWN");
        }
        public static Outcome rejected(String code) {
            return new Outcome(ReservationProcess.Status.REJECTED, null, null, null, code);
        }
    }
}
