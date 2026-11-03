package ar.edu.programacion2.booking.users;

import java.util.Set;

public record UserAccount(String id, String login, String firstName, String lastName, String email,
        String imageUrl, String langKey, boolean activated, Set<String> authorities) {}
