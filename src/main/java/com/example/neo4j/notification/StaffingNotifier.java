package com.example.neo4j.notification;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.neo4j.configuration.NotificationConfig;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.StaffingQueries;

/**
 * Emails people about the staffing workflow:
 *
 * <pre>
 *   REQUESTED -> HR mailbox        (a new request to review)
 *   APPROVED  -> requesting lead   (approved)
 *                affected employee (you were added to / removed from a project)
 *   REJECTED  -> requesting lead   (rejected, with HR's reason)
 *   CANCELLED -> HR mailbox        (withdrawn, drop it from the queue)
 * </pre>
 *
 * Runs only after the transaction commits, so nobody is told about a change that rolled back,
 * and on a background thread. Failures are logged and never affect the workflow itself.
 * Emails are plain text: names and job titles are user-entered, so no HTML is ever built from them.
 * Without a configured mail server (spring.mail.host) emails are only logged.
 */
@Component
public class StaffingNotifier {

    private static final Logger log = LoggerFactory.getLogger(StaffingNotifier.class);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** One email to send. */
    record Email(String to, String subject, String body) {
    }

    private final ObjectProvider<JavaMailSender> mailSender;
    private final StaffingQueries staffingQueries;
    private final AppUserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final String from;
    private final String hrEmail;
    private final String baseUrl;

    public StaffingNotifier(ObjectProvider<JavaMailSender> mailSender, StaffingQueries staffingQueries,
                            AppUserRepository userRepository, EmployeeRepository employeeRepository,
                            @Value("${app.notifications.from:no-reply@worksphere.local}") String from,
                            @Value("${app.notifications.hr-email:}") String hrEmail,
                            @Value("${app.notifications.base-url:}") String baseUrl) {
        this.mailSender = mailSender;
        this.staffingQueries = staffingQueries;
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.from = from;
        this.hrEmail = hrEmail;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // After commit, the finished transaction can still be bound to the thread; lookups must run in
    // a fresh one, or they fail with "Cannot run more queries in this transaction".
    @Async(NotificationConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onStaffingRequestEvent(StaffingRequestEvent event) {
        try {
            emailsFor(event).forEach(this::send);
        } catch (Exception e) {
            // Never let a notification problem surface to the user; the change itself is already saved.
            log.warn("Could not prepare staffing notification for request {}", event.requestId(), e);
        }
    }

    // ---- Building emails ----------------------------------------------------------

    List<Email> emailsFor(StaffingRequestEvent event) {

        Optional<StaffingRequestResponse> found = staffingQueries.findById(event.requestId());
        if (found.isEmpty()) {
            log.warn("Staffing request {} not found; no notification sent", event.requestId());
            return List.of();
        }
        StaffingRequestResponse request = found.get();

        Person requester = person(request.requestedBy());
        String employeeName = request.employee() == null ? "an employee" : request.employee().name();
        String projectName = request.project() == null ? "a project"
                : request.project().name() + " (" + request.project().code() + ")";
        String change = describeChange(request, employeeName);

        List<Email> emails = new ArrayList<>();

        switch (event.type()) {
            case REQUESTED -> hrMailbox().ifPresent(to -> emails.add(new Email(to,
                    "Staffing request: " + shortChange(request, employeeName) + " on " + projectName,
                    """
                    %s has requested a staffing change that needs HR approval.

                    Project:   %s
                    Change:    %s
                    Requested: %s

                    %s
                    """.formatted(requester.displayName(), projectName, change,
                            TIMESTAMP.format(request.requestedAt()), link(request)))));

            case CANCELLED -> hrMailbox().ifPresent(to -> emails.add(new Email(to,
                    "Staffing request withdrawn: " + shortChange(request, employeeName) + " on " + projectName,
                    """
                    %s has withdrawn a staffing request. No action is needed.

                    Project: %s
                    Change:  %s
                    """.formatted(requester.displayName(), projectName, change))));

            case APPROVED -> {
                requester.email().ifPresent(to -> emails.add(new Email(to,
                        "Approved: " + shortChange(request, employeeName) + " on " + projectName,
                        """
                        Your staffing request was approved by %s and has been applied.

                        Project: %s
                        Change:  %s

                        %s
                        """.formatted(request.decidedBy(), projectName, change, link(request)))));

                employeeEmail(request).ifPresent(to -> emails.add(new Email(to,
                        (request.action() == StaffingAction.REMOVE ? "You have been removed from " : "Project update: ")
                                + projectName,
                        request.action() == StaffingAction.REMOVE
                                ? """
                                  You are no longer on %s.

                                  Contact the project lead, %s, if you have questions.
                                  """.formatted(projectName, requester.displayName())
                                : """
                                  You have been staffed on %s.

                                  Role:       %s
                                  Allocation: %d%% of your time

                                  The project lead is %s.
                                  """.formatted(projectName, orDash(request.role()), request.allocationPercent(),
                                        requester.displayName()))));
            }

            case REJECTED -> requester.email().ifPresent(to -> emails.add(new Email(to,
                    "Rejected: " + shortChange(request, employeeName) + " on " + projectName,
                    """
                    Your staffing request was rejected by %s.

                    Project: %s
                    Change:  %s
                    Reason:  %s

                    %s
                    """.formatted(request.decidedBy(), projectName, change, request.reason(), link(request)))));
        }

        if (emails.isEmpty()) {
            log.info("No recipients with an email address for {} of staffing request {}", event.type(),
                    event.requestId());
        }
        return emails;
    }

    // ---- Sending ------------------------------------------------------------------

    private void send(Email email) {

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.info("Email not sent (no mail server configured) to {}: {}", email.to(), email.subject());
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.to());
        message.setSubject("[WorkSphere] " + singleLine(email.subject()));
        message.setText(email.body());

        try {
            sender.send(message);
            log.info("Sent staffing notification to {}: {}", email.to(), email.subject());
        } catch (Exception e) {
            // One failed recipient doesn't stop the others.
            log.warn("Could not send staffing notification to {}: {}", email.to(), email.subject(), e);
        }
    }

    // ---- Helpers ------------------------------------------------------------------

    private record Person(String displayName, Optional<String> email) {
    }

    // A login's display name and email come from its linked employee, if any.
    private Person person(String username) {
        Optional<Employee> employee = userRepository.findByUsername(username)
                .map(user -> user.getEmployeeId())
                .flatMap(id -> id == null ? Optional.empty() : employeeRepository.findById(id));

        return new Person(
                employee.map(Employee::getName).orElse(username),
                employee.map(Employee::getEmail).filter(StaffingNotifier::hasText));
    }

    private Optional<String> employeeEmail(StaffingRequestResponse request) {
        return request.employee() == null ? Optional.empty()
                : employeeRepository.findById(request.employee().id())
                        .map(Employee::getEmail)
                        .filter(StaffingNotifier::hasText);
    }

    private Optional<String> hrMailbox() {
        if (!hasText(hrEmail)) {
            log.warn("app.notifications.hr-email is not set; HR is not notified of staffing requests");
            return Optional.empty();
        }
        return Optional.of(hrEmail);
    }

    private static String describeChange(StaffingRequestResponse request, String employeeName) {
        return request.action() == StaffingAction.REMOVE
                ? "Remove " + employeeName + " from the project"
                : "Add or update " + employeeName + ": " + orDash(request.role()) + ", "
                        + request.allocationPercent() + "% allocation";
    }

    private static String shortChange(StaffingRequestResponse request, String employeeName) {
        return (request.action() == StaffingAction.REMOVE ? "remove " : "add ") + employeeName;
    }

    private String link(StaffingRequestResponse request) {
        return hasText(baseUrl)
                ? "Open the request: " + baseUrl + "/staffing-requests/" + request.id()
                : "Request id: " + request.id();
    }

    // Subjects are built from user-entered names; line breaks must never reach the mail header.
    static String singleLine(String text) {
        return text.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String orDash(String value) {
        return hasText(value) ? value : "-";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
