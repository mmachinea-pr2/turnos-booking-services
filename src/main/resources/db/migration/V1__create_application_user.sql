CREATE TABLE application_user (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    login VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    email VARCHAR(254) COLLATE utf8mb4_bin NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    image_url VARCHAR(256),
    lang_key VARCHAR(10) NOT NULL,
    activated BOOLEAN NOT NULL,
    authority VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_application_user_login UNIQUE (login),
    CONSTRAINT uk_application_user_email UNIQUE (email)
);
