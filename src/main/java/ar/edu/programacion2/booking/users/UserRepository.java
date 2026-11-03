package ar.edu.programacion2.booking.users;

import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;
    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final RowMapper<StoredUser> MAPPER = (rs, row) -> new StoredUser(
        new UserAccount(rs.getString("id"), rs.getString("login"), rs.getString("first_name"),
            rs.getString("last_name"), rs.getString("email"), rs.getString("image_url"), rs.getString("lang_key"),
            rs.getBoolean("activated"), Set.of(rs.getString("authority"))), rs.getString("password_hash"));

    Optional<StoredUser> byLogin(String login) {
        return jdbc.query("SELECT * FROM application_user WHERE login = ?", MAPPER, login).stream().findFirst();
    }
    Optional<StoredUser> byId(String id) {
        return jdbc.query("SELECT * FROM application_user WHERE id = ?", MAPPER, id).stream().findFirst();
    }
    void insert(UserAccount user, String hash) {
        jdbc.update("""
            INSERT INTO application_user
            (id, login, email, password_hash, first_name, last_name, image_url, lang_key, activated, authority)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 'ROLE_USER')
            """, user.id(), user.login(), user.email(), hash, user.firstName(), user.lastName(), user.imageUrl(), user.langKey());
    }
}
