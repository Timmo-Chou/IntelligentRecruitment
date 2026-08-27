package com.intelligentrecruitment.platform.ticket.api;

import com.intelligentrecruitment.platform.ticket.application.TicketService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 平台管理端工单接口。
 * 提供工单的查询、创建、回复、分配、状态变更等操作。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformTicketController {

    private final TicketService ticketService;
    private final PlatformAdminGuard guard;

    public PlatformTicketController(TicketService ticketService, PlatformAdminGuard guard) {
        this.ticketService = ticketService;
        this.guard = guard;
    }

    /**
     * 查询工单列表（支持多条件筛选和分页）。
     */
    @GetMapping("/tickets")
    TicketService.PagedResult<TicketService.TicketRow> listTickets(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) UUID assigned_to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "ticket:read");
        return ticketService.listTickets(status, category, priority, assigned_to, q, page, size);
    }

    /**
     * 获取工单详情（包含所有消息）。
     */
    @GetMapping("/tickets/{ticketId}")
    TicketService.TicketDetail getTicket(@PathVariable UUID ticketId,
                                          @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "ticket:read");
        return ticketService.getTicket(ticketId);
    }

    /**
     * 平台管理员代用户创建工单。
     */
    @PostMapping("/tickets")
    TicketService.TicketRow createTicket(@RequestHeader("X-Platform-Admin-Key") String key,
                                          @Valid @RequestBody CreateTicketRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "ticket:write");
        return ticketService.createTicketByAdmin(
                request.creatorName(), request.title(), request.category(),
                request.priority(), request.body()
        );
    }

    /**
     * 平台管理员回复工单。
     */
    @PostMapping("/tickets/{ticketId}/messages")
    TicketService.MessageRow addMessage(@PathVariable UUID ticketId,
                                         @RequestHeader("X-Platform-Admin-Key") String key,
                                         @Valid @RequestBody AddMessageRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "ticket:write");
        return ticketService.addAdminMessage(ticketId, admin, request.body());
    }

    /**
     * 分配工单给指定管理员。
     */
    @PostMapping("/tickets/{ticketId}/assign")
    void assignTicket(@PathVariable UUID ticketId,
                      @RequestHeader("X-Platform-Admin-Key") String key,
                      @Valid @RequestBody AssignTicketRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "ticket:write");
        ticketService.assignTicket(ticketId, request.adminId());
    }

    /**
     * 更新工单状态。
     */
    @PostMapping("/tickets/{ticketId}/status")
    void updateStatus(@PathVariable UUID ticketId,
                      @RequestHeader("X-Platform-Admin-Key") String key,
                      @Valid @RequestBody UpdateStatusRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "ticket:write");
        ticketService.updateStatus(ticketId, request.status());
    }

    /**
     * 关闭工单。
     */
    @PostMapping("/tickets/{ticketId}/close")
    void closeTicket(@PathVariable UUID ticketId,
                     @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "ticket:write");
        ticketService.closeTicket(ticketId);
    }

    // ---- 请求体记录 ----

    public record CreateTicketRequest(
            @NotBlank String creatorName,
            @NotBlank String title,
            @NotBlank String category,
            @NotBlank String priority,
            @NotBlank String body
    ) {}

    public record AddMessageRequest(
            @NotBlank String body
    ) {}

    public record AssignTicketRequest(
            UUID adminId
    ) {}

    public record UpdateStatusRequest(
            @NotBlank String status
    ) {}
}