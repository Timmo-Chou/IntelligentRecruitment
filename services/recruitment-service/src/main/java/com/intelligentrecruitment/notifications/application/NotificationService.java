package com.intelligentrecruitment.notifications.application;

import com.intelligentrecruitment.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(UUID userId, int limit) {
        int size = Math.min(100, Math.max(1, limit));
        List<Item> items = jdbc.query(
                "SELECT id,type,title,content,link,read_at,created_at FROM notifications "
                        + "WHERE user_id=? ORDER BY created_at DESC LIMIT ?",
                (r, n) -> new Item(
                        r.getObject("id", UUID.class),
                        r.getString("type"),
                        r.getString("title"),
                        r.getString("content"),
                        r.getString("link"),
                        r.getTimestamp("read_at") != null ? r.getTimestamp("read_at").toInstant() : null,
                        r.getTimestamp("created_at").toInstant()),
                userId, size);
        Integer unread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL", Integer.class, userId);
        return new Page(items, unread == null ? 0 : unread);
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        int changed = jdbc.update(
                "UPDATE notifications SET read_at=? WHERE id=? AND user_id=?",
                timestamp(Instant.now()), id, userId);
        if (changed == 0) {
            throw new ApiException("NOTIFICATION_NOT_FOUND", "通知不存在", HttpStatus.NOT_FOUND);
        }
    }

    @Transactional
    public void markAllRead(UUID userId) {
        jdbc.update("UPDATE notifications SET read_at=? WHERE user_id=? AND read_at IS NULL",
                timestamp(Instant.now()), userId);
    }

    public void create(UUID userId, String type, String title, String content, String link) {
        jdbc.update(
                "INSERT INTO notifications(id,user_id,type,title,content,link,created_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID(), userId, type, title, content, link, timestamp(Instant.now()));
    }

    @Transactional
    public void createEnterpriseRegistrationRejected(
            UUID userId, UUID registrationId, String legalName, String reason) {
        String safeName = truncate(legalName, 180);
        String safeReason = truncate(reason, 700);
        String content = "您的企业“" + safeName + "”注册申请未通过审核，原因：" + safeReason;
        jdbc.update("""
                INSERT INTO notifications(id,user_id,type,title,content,link,dedupe_key,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT (user_id, dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
                """, UUID.randomUUID(), userId, "ENTERPRISE_REGISTRATION_REJECTED",
                "企业注册申请未通过", content, "/enterprise-management",
                "ENTERPRISE_REGISTRATION_REJECTED:" + registrationId, timestamp(Instant.now()));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record Item(UUID id, String type, String title, String content, String link,
                       Instant readAt, Instant createdAt) { }

    public record Page(List<Item> items, int unreadCount) { }
}
