package com.example.neo4j.security;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.neo4j.dto.CreateUserRequest;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.service.UserService;

/**
 * Creates the first ADMIN account on startup when no users exist yet,
 * so someone can log in and create the rest through /users.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository userRepository;
    private final UserService userService;
    private final String username;
    private final String password;

    public AdminBootstrap(AppUserRepository userRepository,
                          UserService userService,
                          @Value("${app.bootstrap.admin-username:}") String username,
                          @Value("${app.bootstrap.admin-password:}") String password) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (userRepository.count() > 0) {
            return;
        }

        if (username.isBlank() || password.isBlank()) {
            log.warn("No users exist. Set ADMIN_USERNAME and ADMIN_PASSWORD to create the first admin.");
            return;
        }

        userService.create(new CreateUserRequest(username, password, Set.of(Role.ADMIN), null));
        log.info("Created initial admin user '{}'", username);
    }
}
