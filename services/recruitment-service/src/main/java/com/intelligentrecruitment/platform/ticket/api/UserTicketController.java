package com.intelligentrecruitment.platform.ticket.api;

import com.intelligentrecruitment.platform.ticket.application.TicketService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 用户端工单接口。
 * 用户对自己的工单进行查询、创建、回复操作。
 */
@RestController
@RequestMapping("/api/v1")
public class UserTicketController {

    private final TicketService ticketService;

    public UserTicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * 查询当前用户的工单列表。
     */
    @GetMapping("/me/tickets")
    TicketService.PagedResult<TicketService.TicketRow> listMyTickets(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = CurrentUser.id(authentication);
        // 查询当前用户的工单
        return ticketService.listTickets(null, null, null, null, userId, null, page, size);
    }

    /**
     * 获取当前用户工单详情（需校验所有权）。
     */
    @GetMapping("/me/tickets/{ticketId}")
    TicketService.TicketDetail getMyTicket(@PathVariable UUID ticketId,
                                            Authentication authentication) {
        UUID userId = CurrentUser.id(authentication);
        ticketService.verifyTicketOwner(ticketId, userId);
        return ticketService.getTicket(ticketId);
    }

    /**
     * 创建工单。
     */
    @PostMapping("/me/tickets")
    TicketService.TicketRow createTicket(Authentication authentication,
                                          @Valid @RequestBody CreateTicketRequest request) {
        UUID userId = CurrentUser.id(authentication);
        // 从认证信息中获取用户显示名称
        String userName = authentication.getName();
        return ticketService.createTicket(userId, userName, request.title(),
                request.category(), "MEDIUM", request.body());
    }

    /**
     * 用户回复工单。
     */
    @PostMapping("/me/tickets/{ticketId}/messages")
    TicketService.MessageRow addMessage(@PathVariable UUID ticketId,
                                         Authentication authentication,
                                         @Valid @RequestBody AddMessageRequest request) {
        UUID userId = CurrentUser.id(authentication);
        ticketService.verifyTicketOwner(ticketId, userId);
        String userName = authentication.getName();
        return ticketService.addMessage(ticketId, "USER", userId, userName, request.body());
    }

    // ---- 请求体记录 ----

    public record CreateTicketRequest(
            @NotBlank String title,
            @NotBlank String category,
            @NotBlank String body
    ) {}

    public record AddMessageRequest(
            @NotBlank String body
    ) {}
}