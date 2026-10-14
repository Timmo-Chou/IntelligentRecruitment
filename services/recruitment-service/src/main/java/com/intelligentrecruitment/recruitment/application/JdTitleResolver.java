package com.intelligentrecruitment.recruitment.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JdTitleResolver {

    private static final Pattern LABELED_TITLE = Pattern.compile(
            "(?m)^\\s*(?:\\d+[.．、)]\\s*)?(?:岗位名称|职位名称|招聘岗位|岗位|职位)\\s*[:：]\\s*(\\S.*?)\\s*$");

    private JdTitleResolver() { }

    static String resolve(String explicitTitle, String requirement) {
        if (explicitTitle != null && !explicitTitle.isBlank()) return explicitTitle.trim();
        if (requirement == null || requirement.isBlank()) return "";

        Matcher matcher = LABELED_TITLE.matcher(requirement);
        if (!matcher.find()) return "";

        String title = matcher.group(1).trim().replaceAll("[。；;，,]+$", "");
        if (title.matches("(?i)^(待确认|待补充|未确认|未提供|n/?a)$")) return "";
        return title;
    }
}
