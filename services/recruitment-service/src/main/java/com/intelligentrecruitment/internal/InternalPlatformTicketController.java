package com.intelligentrecruitment.internal;

import com.intelligentrecruitment.platform.ticket.application.TicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** BOSS-to-IR internal ticket endpoint. BOSS remains the public admin façade. */
@RestController
@RequestMapping("/internal/v1/platform/tickets")
public class InternalPlatformTicketController {

    private final TicketService ticketService;
    private final String clientId;
    private final String clientSecret;

    public InternalPlatformTicketController(
            TicketService ticketService,
            @Value("${app.boss.internal-client-id:}") String clientId,
            @Value("${app.boss.internal-client-secret:}") String clientSecret) {
        this.ticketService = ticketService;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @GetMapping
    TicketService.PagedResult<TicketService.TicketRow> listTickets(
            @RequestHeader("X-Internal-Client-Id") String requestClientId,
            @RequestHeader("X-Internal-Client-Secret") String requestClientSecret,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String priority,
            @RequestParam(name = "assigned_to", required = false) UUID assignedTo,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        authenticate(requestClientId, requestClientSecret);
        return ticketService.listTickets(status, category, priority, assignedTo, q, page, size);
    }

    @GetMapping("/{ticketId}")
    TicketService.TicketDetail getTicket(
            @RequestHeader("X-Internal-Client-Id") String requestClientId,
            @RequestHeader("X-Internal-Client-Secret") String requestClientSecret,
            @PathVariable UUID ticketId) {
        authenticate(requestClientId, requestClientSecret);
        return ticketService.getTicket(ticketId);
    }

    @PostMapping
    TicketService.TicketRow createTicket(
            @RequestHeader("X-Internal-Client-Id") String requestClientId,
            @RequestHeader("X-Internal-Client-Secret") String requestClientSecret,
            @Valid @RequestBody CreateTicketRequest request) {
        authenticate(requestClientId, requestClientSecret);
        return ticketService.createTicketByAdmin(request.creatorName(), request.title(), request.category(),
                request.priority(), request.body());
    }

    @PostMapping("/{ticketId}/messages")
    TicketService.MessageRow addMessage(
            @RequestHeader("X-Internal-Client-Id") String requestClientId,
            @RequestHeader("X-Internal-Client-Secret") String requestClientSecret,
            @RequestHeader("X-Platform-Admin-Id") UUID adminId,
            @RequestHeader("X-Platform-Admin-Display-Name") String encodedDisplayName,
            @PathVariable UUID ticketId,
            @Valid @RequestBody AddMessageRequest request) {
        authenticate(requestClientId, requestClientSecret);
        return ticketService.addAdminMessage(ticketId, adminId, decodeDisplayName(encodedDisplayName), request.body());
    }

    @PostMapping("/{ticketId}/assign")
    void assignTicket(
            @RequestHeader("X-Internal-Client-Id") String requestClientId,
            @RequestHeader("X-Internal-Client-Secret") String requestClientSecret,
            @PathVariable UUID ticketId,
            @Valid @RequestBody AssignTicketRequest request) {
        authenticate(requestClientId, requestClientSecret);
        ticketService.assignTicket(ticketId, request.adminId());
    }

    @PostMapping("/{ticketId}/status")
    void updateStatus(
            @RequestHeader("X-Internal-Client-Id") String requestClientId,
            @RequestHeader("X-Internal-Client-Secret") String requestClientSecret,
            @PathVariable UUID ticketId,
            @Valid @RequestBody UpdateStatusRequest request) {
        authenticate(requestClientId, requestClientSecret);
        ticketService.updateStatus(ticketId, request.status());
    }

    @PostMapping("/{ticketId}/close")
    void closeTicket(
            @RequestHeader("X-Internal-Client-Id") String requestClientId,
            @RequestHeader("X-Internal-Client-Secret") String requestClientSecret,
            @PathVariable UUID ticketId) {
        authenticate(requestClientId, requestClientSecret);
        ticketService.closeTicket(ticketId);
    }

    private void authenticate(String requestClientId, String requestClientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()
                || requestClientId == null || requestClientSecret == null
                || !constantTimeEquals(clientId, requestClientId)
                || !constantTimeEquals(clientSecret, requestClientSecret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部服务凭证无效");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeDisplayName(String encoded) {
        try {
            String displayName = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (displayName.isBlank()) throw new IllegalArgumentException();
            return displayName;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理员名称编码无效");
        }
    }

    public record CreateTicketRequest(
            @NotBlank String creatorName,
            @NotBlank String title,
            @NotBlank String category,
            @NotBlank String priority,
            @NotBlank String body) {}

    public record AddMessageRequest(@NotBlank String body) {}

    public record AssignTicketRequest(UUID adminId) {}

    public record UpdateStatusRequest(@NotBlank String status) {}
}
