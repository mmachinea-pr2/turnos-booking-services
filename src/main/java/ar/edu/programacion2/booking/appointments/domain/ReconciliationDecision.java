package ar.edu.programacion2.booking.appointments.domain;

public record ReconciliationDecision(String outcome, String nextStatus) {
    public static ReconciliationDecision unchanged(String outcome) {
        return new ReconciliationDecision(outcome, null);
    }
    public boolean changesState() { return nextStatus != null; }
    public boolean cancelsReservation() { return "CANCELLED".equals(nextStatus); }
    public boolean stopsPhoneDelivery() { return !"WAITING_FOR_PHONE".equals(nextStatus); }
    public String errorCode() { return "INVALID".equals(nextStatus) ? "REMOTE_RESERVATION_FAILED" : null; }
}
