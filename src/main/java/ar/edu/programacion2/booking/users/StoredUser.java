package ar.edu.programacion2.booking.users;

record StoredUser(UserAccount account, String passwordHash) {
    @Override public String toString() { return "StoredUser[redacted]"; }
}
