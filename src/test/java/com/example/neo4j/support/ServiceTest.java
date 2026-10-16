package com.example.neo4j.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.repository.AnalyticsQueries;
import com.example.neo4j.repository.DepartmentQueries;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.LeaveQueries;
import com.example.neo4j.repository.OfficeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.repository.StaffingQueries;
import com.example.neo4j.security.AppUserDetailsService;
import com.example.neo4j.security.SessionService;
import com.example.neo4j.security.TokenService;
import com.example.neo4j.service.AnalyticsService;
import com.example.neo4j.service.DepartmentService;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.service.LeaveService;
import com.example.neo4j.service.OfficeService;
import com.example.neo4j.service.OrgHistoryService;
import com.example.neo4j.service.ProjectService;
import com.example.neo4j.service.StaffingService;
import com.example.neo4j.service.UserService;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Service tests against the shared embedded Neo4j with migrations applied. Each test runs in a
 * transaction that is rolled back.
 *
 * Every class using it gets exactly the same Spring context, so Spring builds it (and runs the
 * migrations) once and reuses it. Keep it that way: a test class that adds its own beans,
 * properties or {@code @DynamicPropertySource} gets a context of its own again.
 * Use it together with {@code extends SharedNeo4jTest}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataNeo4jTest
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({EmployeeService.class, DepartmentService.class, OfficeService.class, ProjectService.class,
        StaffingService.class, LeaveService.class, AnalyticsService.class, OrgHistoryService.class, UserService.class,
        EmployeeQueries.class, DepartmentQueries.class, OfficeQueries.class, ProjectQueries.class,
        StaffingQueries.class, LeaveQueries.class, AnalyticsQueries.class, AuditLog.class,
        SessionService.class, TokenService.class, AppUserDetailsService.class, ServiceTest.Security.class})
public @interface ServiceTest {

    @TestConfiguration
    class Security {
        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }

        @Bean
        JwtEncoder jwtEncoder() {
            byte[] key = "test-secret-that-is-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8);
            return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(new SecretKeySpec(key, "HmacSHA256")));
        }
    }
}
