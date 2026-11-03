package ar.edu.programacion2.booking.users;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    public UserService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserAccount register(RegisterRequest request) {
        var user = new UserAccount(UUID.randomUUID().toString(), normalize(request.login()), request.firstName(),
            request.lastName(), normalize(request.email()), request.imageUrl(),
            request.langKey() == null ? "es" : request.langKey(), true, Set.of("ROLE_USER"));
        try {
            users.insert(user, passwords.encode(request.password()));
        } catch (DuplicateKeyException exception) {
            throw new DuplicateUserException();
        }
        return user;
    }

    public UserAccount authenticate(LoginRequest request) {
        String name = normalize(request.username());
        // Unknown and incorrect logins return the same response and both verify a password hash.
        var stored = users.byLogin(name);
        boolean matches = passwords.matches(request.password(), stored.map(StoredUser::passwordHash).orElse(dummyHash));
        if (!matches || stored.isEmpty() || !stored.get().account().activated()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return stored.get().account();
    }

    public UserAccount account(String id) {
        return users.byId(id).map(StoredUser::account).filter(UserAccount::activated)
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    }
    private static String normalize(String value) { return value.strip().toLowerCase(Locale.ROOT); }

    public static class DuplicateUserException extends RuntimeException {}
}
