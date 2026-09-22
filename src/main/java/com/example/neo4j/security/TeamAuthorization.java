package com.example.neo4j.security;

import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import com.example.neo4j.repository.EmployeeQueries;

/**
 * URL authorization rules for manager self-service. A manager is whoever the org chart says
 * it is: a login linked to an employee who has the target somewhere below them in REPORTS_TO.
 * There is no separate MANAGER role to keep in sync - moving someone in the org chart changes
 * who can edit them immediately.
 */
@Component
public class TeamAuthorization {

    private static final AuthorizationManager<RequestAuthorizationContext> HR_OR_ADMIN =
            AuthorityAuthorizationManager.hasAnyRole(Role.HR.name(), Role.ADMIN.name());

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
    private final EmployeeQueries employeeQueries;

    public TeamAuthorization(EmployeeQueries employeeQueries) {
        this.employeeQueries = employeeQueries;
    }

    /** HR/ADMIN, or a manager of the employee named by the path variable (any depth, not themselves). */
    public AuthorizationManager<RequestAuthorizationContext> hrOrManagerOf(String employeeVariable) {
        return (authentication, context) -> decide(authentication, context, username ->
                employeeQueries.isInTeamOf(username, variable(context, employeeVariable), false));
    }

    /**
     * HR/ADMIN, or a manager moving one of their reports to a new manager inside their own team
     * (themselves or someone below them), so nobody can be moved out of, or into, another team.
     */
    public AuthorizationManager<RequestAuthorizationContext> hrOrManagerMovingWithinTeam(
            String employeeVariable, String newManagerVariable) {

        return (authentication, context) -> decide(authentication, context, username ->
                employeeQueries.isInTeamOf(username, variable(context, employeeVariable), false)
                        && employeeQueries.isInTeamOf(username, variable(context, newManagerVariable), true));
    }

    private AuthorizationDecision decide(Supplier<Authentication> authentication,
                                         RequestAuthorizationContext context,
                                         Predicate<String> managerCheck) {

        if (HR_OR_ADMIN.authorize(authentication, context).isGranted()) {
            return new AuthorizationDecision(true);
        }

        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || trustResolver.isAnonymous(auth)) {
            return new AuthorizationDecision(false);
        }

        return new AuthorizationDecision(managerCheck.test(auth.getName()));
    }

    private static String variable(RequestAuthorizationContext context, String name) {
        Map<String, String> variables = context.getVariables();
        String value = variables.get(name);
        if (value == null) {
            throw new IllegalStateException("Security rule expects path variable {" + name + "}");
        }
        return value;
    }
}
