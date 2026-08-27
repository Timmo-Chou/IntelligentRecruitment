package com.intelligentrecruitment.platform.ticket.application;

import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

/**
 * 工单管理服务。
 * 负责工单的创建、查询、回复、分配、状态变更等操作。
 */
@Service
public class TicketService {

    private final JdbcTemplate jdbc;

    public TicketService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询工单列表，支持多条件筛选和分页。
     */
    public PagedResult<TicketRow> listTickets(String status, String category, String priority,
                                               UUID assignedTo, String q, int page, int size) {
        return listTickets(status, category, priority, assignedTo, null, q, page, size);
    }

    /**
     * 查询工单列表（含创建者过滤），支持多条件筛选和分页。
     */
    public PagedResult<TicketRow> listTickets(String status, String category, String priority,
                                               UUID assignedTo, UUID creatorUserId, String q, int page, int size) {
        int offset = (page - 1) * size;

        // 动态构建查询条件
        StringBuilder whereClause = new StringBuilder("WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            whereClause.append(" AND st.status = ?");
            params.add(status);
        }
        if (category != null && !category.isBlank()) {
            whereClause.append(" AND st.category = ?");
            params.add(category);
        }
        if (priority != null && !priority.isBlank()) {
            whereClause.append(" AND st.priority = ?");
            params.add(priority);
        }
        if (assignedTo != null) {
            whereClause.append(" AND st.assigned_to_id = ?");
            params.add(assignedTo);
        }
        if (creatorUserId != null) {
            whereClause.append(" AND st.creator_user_id = ?");
            params.add(creatorUserId);
        }
        if (q != null && !q.isBlank()) {
            whereClause.append(" AND (st.title ILIKE ? OR st.ticket_number ILIKE ?)");
            String likeQ = "%" + q.trim() + "%";
            params.add(likeQ);
            params.add(likeQ);
        }

        String countSql = "SELECT COUNT(*) FROM support_tickets st " + whereClause;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());

        // 查询列表
        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(size);
        queryParams.add(offset);
        String listSql = """
                SELECT st.id, st.ticket_number, st.creator_user_id, st.creator_name, st.title,
                       st.category, st.priority, st.status, st.assigned_to_id, st.closed_at,
                       st.created_at, st.updated_at
                FROM support_tickets st
                """ + whereClause + """
                 ORDER BY st.created_at DESC
                 LIMIT ? OFFSET ?
                """;

        List<TicketRow> rows = jdbc.query(listSql, (rs, n) -> new TicketRow(
                rs.getObject("id", UUID.class),
                rs.getString("ticket_number"),
                rs.getObject("creator_user_id", UUID.class),
                rs.getString("creator_name"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getObject("assigned_to_id", UUID.class),
                rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null,
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ), queryParams.toArray());

        return new PagedResult<>(rows, total != null ? total : 0, page, size);
    }

    /**
     * 获取工单详情（包含所有消息）。
     */
    public TicketDetail getTicket(UUID ticketId) {
        // 查询工单基本信息
        List<TicketRow> ticketRows = jdbc.query("""
                SELECT st.id, st.ticket_number, st.creator_user_id, st.creator_name, st.title,
                       st.category, st.priority, st.status, st.assigned_to_id, st.closed_at,
                       st.created_at, st.updated_at
                FROM support_tickets st
                WHERE st.id = ?
                """, (rs, n) -> new TicketRow(
                rs.getObject("id", UUID.class),
                rs.getString("ticket_number"),
                rs.getObject("creator_user_id", UUID.class),
                rs.getString("creator_name"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getObject("assigned_to_id", UUID.class),
                rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null,
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ), ticketId);

        if (ticketRows.isEmpty()) {
            throw new ApiException("TICKET_NOT_FOUND", "工单不存在", HttpStatus.NOT_FOUND);
        }

        // 查询工单消息
        List<MessageRow> messages = jdbc.query("""
                SELECT id, ticket_id, sender_type, sender_id, sender_name, body, created_at
                FROM support_ticket_messages
                WHERE ticket_id = ?
                ORDER BY created_at ASC
                """, (rs, n) -> new MessageRow(
                rs.getObject("id", UUID.class),
                rs.getObject("ticket_id", UUID.class),
                rs.getString("sender_type"),
                rs.getObject("sender_id", UUID.class),
                rs.getString("sender_name"),
                rs.getString("body"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null
        ), ticketId);

        return new TicketDetail(ticketRows.getFirst(), messages);
    }

    /**
     * 用户创建工单。
     * 自动生成工单编号：TK-YYYYMMDD-XXXX（按天自增）。
     */
    @Transactional
    public TicketRow createTicket(UUID creatorUserId, String creatorName, String title,
                                   String category, String priority, String body) {
        String ticketNumber = generateTicketNumber();
        Instant now = Instant.now();
        UUID ticketId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO support_tickets (id, ticket_number, creator_user_id, creator_name, title, category, priority, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                """, ticketId, ticketNumber, creatorUserId, required(creatorName, "创建者名称不能为空"),
                required(title, "工单标题不能为空"), required(category, "工单分类不能为空"),
                required(priority, "优先级不能为空"), timestamp(now), timestamp(now));

        // 插入第一条消息
        addMessageInternal(ticketId, "USER", creatorUserId, creatorName, required(body, "工单内容不能为空"), now);

        return new TicketRow(ticketId, ticketNumber, creatorUserId, creatorName, title, category, priority,
                "OPEN", null, null, now, now);
    }

    /**
     * 平台管理员代用户创建工单。
     */
    @Transactional
    public TicketRow createTicketByAdmin(String creatorName, String title, String category,
                                          String priority, String body) {
        String ticketNumber = generateTicketNumber();
        Instant now = Instant.now();
        UUID ticketId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO support_tickets (id, ticket_number, creator_user_id, creator_name, title, category, priority, status, created_at, updated_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, 'OPEN', ?, ?)
                """, ticketId, ticketNumber, required(creatorName, "创建者名称不能为空"),
                required(title, "工单标题不能为空"), required(category, "工单分类不能为空"),
                required(priority, "优先级不能为空"), timestamp(now), timestamp(now));

        // 插入第一条消息
        addMessageInternal(ticketId, "PLATFORM_ADMIN", null, creatorName, required(body, "工单内容不能为空"), now);

        return new TicketRow(ticketId, ticketNumber, null, creatorName, title, category, priority,
                "OPEN", null, null, now, now);
    }

    /**
     * 添加消息到工单。
     */
    @Transactional
    public MessageRow addMessage(UUID ticketId, String senderType, UUID senderId, String senderName, String body) {
        ensureTicketExists(ticketId);
        Instant now = Instant.now();
        return addMessageInternal(ticketId, senderType, senderId, senderName, required(body, "消息内容不能为空"), now);
    }

    /**
     * 平台管理员回复工单。
     */
    @Transactional
    public MessageRow addAdminMessage(UUID ticketId, PlatformAdminInfo admin, String body) {
        ensureTicketExists(ticketId);
        Instant now = Instant.now();
        return addMessageInternal(ticketId, "PLATFORM_ADMIN", admin.id(), admin.displayName(),
                required(body, "消息内容不能为空"), now);
    }

    /**
     * 分配工单给指定管理员。
     */
    @Transactional
    public void assignTicket(UUID ticketId, UUID adminId) {
        ensureTicketExists(ticketId);
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE support_tickets SET assigned_to_id = ?, updated_at = ?
                WHERE id = ?
                """, adminId, timestamp(now), ticketId);
    }

    /**
     * 更新工单状态。
     */
    @Transactional
    public void updateStatus(UUID ticketId, String status) {
        ensureTicketExists(ticketId);
        if (!List.of("OPEN", "IN_PROGRESS", "WAITING", "RESOLVED", "CLOSED").contains(status)) {
            throw new ApiException("INVALID_STATUS", "工单状态不合法", HttpStatus.BAD_REQUEST);
        }
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE support_tickets SET status = ?, updated_at = ?
                WHERE id = ?
                """, status, timestamp(now), ticketId);
    }

    /**
     * 关闭工单。
     */
    @Transactional
    public void closeTicket(UUID ticketId) {
        ensureTicketExists(ticketId);
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE support_tickets SET status = 'CLOSED', closed_at = ?, updated_at = ?
                WHERE id = ?
                """, timestamp(now), timestamp(now), ticketId);
    }

    /**
     * 生成工单编号：TK-YYYYMMDD-XXXX（按天自增）。
     * XXXX 为当日已创建的工单数量 + 1，补齐4位。
     */
    private String generateTicketNumber() {
        String dateStr = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String likePattern = "TK-" + dateStr + "-%";

        Long todayCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_tickets WHERE ticket_number LIKE ?",
                Long.class, likePattern);

        long nextCount = (todayCount != null ? todayCount : 0) + 1;
        String paddedCount = String.format("%04d", nextCount);

        return "TK-" + dateStr + "-" + paddedCount;
    }

    /**
     * 内部添加消息方法（不校验工单是否存在）。
     */
    private MessageRow addMessageInternal(UUID ticketId, String senderType, UUID senderId,
                                           String senderName, String body, Instant now) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO support_ticket_messages (id, ticket_id, sender_type, sender_id, sender_name, body, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, messageId, ticketId, senderType, senderId, senderName, body, timestamp(now));

        // 更新工单的 updated_at
        jdbc.update("UPDATE support_tickets SET updated_at = ? WHERE id = ?", timestamp(now), ticketId);

        return new MessageRow(messageId, ticketId, senderType, senderId, senderName, body, now);
    }

    /**
     * 确保工单存在。
     */
    private void ensureTicketExists(UUID ticketId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM support_tickets WHERE id = ?)",
                Boolean.class, ticketId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException("TICKET_NOT_FOUND", "工单不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 校验用户是否为工单创建者。
     */
    public void verifyTicketOwner(UUID ticketId, UUID userId) {
        List<UUID> creators = jdbc.query("""
                SELECT creator_user_id FROM support_tickets WHERE id = ?
                """, (rs, n) -> rs.getObject("creator_user_id", UUID.class), ticketId);
        if (creators.isEmpty()) {
            throw new ApiException("TICKET_NOT_FOUND", "工单不存在", HttpStatus.NOT_FOUND);
        }
        UUID creatorUserId = creators.getFirst();
        if (creatorUserId == null || !creatorUserId.equals(userId)) {
            throw new ApiException("TICKET_ACCESS_DENIED", "无权访问此工单", HttpStatus.FORBIDDEN);
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    // ---- 数据记录 ----

    /**
     * 工单数据行。
     */
    public record TicketRow(
            UUID id,
            String ticketNumber,
            UUID creatorUserId,
            String creatorName,
            String title,
            String category,
            String priority,
            String status,
            UUID assignedToId,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * 工单消息数据行。
     */
    public record MessageRow(
            UUID id,
            UUID ticketId,
            String senderType,
            UUID senderId,
            String senderName,
            String body,
            Instant createdAt
    ) {}

    /**
     * 工单详情（包含消息列表）。
     */
    public record TicketDetail(
            TicketRow ticket,
            List<MessageRow> messages
    ) {}

    /**
     * 分页结果封装。
     */
    public record PagedResult<T>(
            List<T> items,
            long total,
            int page,
            int size
    ) {}
}