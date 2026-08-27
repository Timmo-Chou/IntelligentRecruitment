package com.intelligentrecruitment.screening.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ScreeningMatcher {

    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

    MatchResult match(FrozenJob job, FrozenCandidate candidate,
                      List<ScreeningService.DimensionInput> dimensions) {
        Set<String> jobSkills = tokens(job.skills());
        Set<String> candidateSkills = new LinkedHashSet<>(candidate.skills());
        List<String> matchedSkills = jobSkills.stream().filter(skill -> candidateSkills.stream()
                .anyMatch(value -> value.equalsIgnoreCase(skill))).toList();
        List<String> unmatchedSkills = jobSkills.stream().filter(skill -> matchedSkills.stream()
                .noneMatch(value -> value.equalsIgnoreCase(skill))).toList();

        List<String> matched = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        List<String> negotiable = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        double weighted = 0;
        boolean requiredNotMet = false;

        for (ScreeningService.DimensionInput dimension : dimensions) {
            DimensionScore score = dimensionScore(dimension, job, candidate, jobSkills, matchedSkills);
            int value = score.value();
            if (score.missing()) {
                String missingPolicy = dimension.missingPolicy() == null ? "REVIEW" : dimension.missingPolicy();
                switch (missingPolicy) {
                    case "IGNORE" -> value = 70;
                    case "NEGOTIABLE" -> {
                        value = 60;
                        negotiable.add(dimension.name() + "信息缺失，建议后续沟通");
                    }
                    default -> {
                        value = 50;
                        missing.add(dimension.name() + "信息不足，需人工复核");
                    }
                }
            }
            weighted += value * dimension.weight() / 100.0;
            if (value >= 70) matched.add(dimension.name() + "：" + score.reason());
            else unmatched.add(dimension.name() + "：" + score.reason());
            evidence.add(dimension.name() + "子分 " + value + "/100（权重 " + dimension.weight() + "%）");
            if (dimension.required() && (score.missing() || value < 60)) {
                requiredNotMet = true;
                risks.add("必须项未满足：" + dimension.name() + "；总分已限制在59分以内，需人工判断");
            }
            appendExclusionRisk(dimension.exclusionRule(), candidate.searchableText(), risks);
        }

        if (!matchedSkills.isEmpty()) evidence.add("已识别匹配技能：" + String.join("、", matchedSkills));
        if (!unmatchedSkills.isEmpty()) unmatched.add("待核实技能：" + String.join("、", unmatchedSkills));
        int total = Math.max(0, Math.min(100, (int) Math.round(weighted)));
        if (requiredNotMet) total = Math.min(total, 59);
        if (total < 60) risks.add("综合匹配较弱，建议人工复核，不得据此自动淘汰");
        else risks.add("AI 结果仅供招聘人员辅助判断，不得自动淘汰候选人");
        String level = total >= 85 ? "STRONG_MATCH" : total >= 70 ? "MATCH"
                : total >= 60 ? "GENERAL_MATCH" : "WEAK_MATCH";
        return new MatchResult(total, level, matched, unmatched, negotiable, missing, risks, evidence);
    }

    private DimensionScore dimensionScore(ScreeningService.DimensionInput dimension, FrozenJob job,
                                           FrozenCandidate candidate, Set<String> jobSkills,
                                           List<String> matchedSkills) {
        String name = dimension.name();
        if (name.contains("技能")) {
            if (candidate.skills().isEmpty()) return missing("简历未解析出技能");
            if (jobSkills.isEmpty()) return score(70, "职位未设置明确技能，按中性分处理");
            double ratio = (double) matchedSkills.size() / jobSkills.size();
            return score((int) Math.round(35 + ratio * 65), "技能覆盖率 " + Math.round(ratio * 100) + "%");
        }
        if (name.contains("教育") || name.contains("学历")) {
            if (blank(candidate.education())) return missing("简历未解析出学历");
            if (blank(job.education())) return score(75, "候选人学历为" + candidate.education());
            int candidateRank = educationRank(candidate.education());
            int jobRank = educationRank(job.education());
            return score(candidateRank >= jobRank ? 90 : 55,
                    "候选人学历" + candidate.education() + "，职位要求" + job.education());
        }
        if (name.contains("履历") || name.contains("经验")) {
            if (candidate.yearsExperience() <= 0) return missing("简历未明确工作年限");
            int requiredYears = firstNumber(job.experienceLevel());
            int value = requiredYears <= 0 ? Math.min(90, 65 + candidate.yearsExperience() * 3)
                    : candidate.yearsExperience() >= requiredYears ? 90
                    : Math.max(40, 90 - (requiredYears - candidate.yearsExperience()) * 12);
            return score(value, "工作年限" + candidate.yearsExperience() + "年"
                    + (requiredYears > 0 ? "，职位参考" + requiredYears + "年" : ""));
        }
        if (name.contains("项目") || name.contains("成果")) {
            if (blank(candidate.summary()) && blank(candidate.workExperience())) return missing("简历未提供可核验项目成果");
            return score(candidate.summary().length() >= 30 || candidate.workExperience().length() >= 40 ? 80 : 68,
                    "已识别项目或履历描述，仍需面试核验成果");
        }
        if (name.contains("动机") || name.contains("意愿")) {
            return missing("求职动机无法仅由简历可靠判断");
        }
        if (name.contains("基本")) {
            if (blank(candidate.headline())) return missing("简历基础摘要不足");
            return score(72, "已解析候选人基础摘要");
        }
        if (candidate.searchableText().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
            return score(75, "简历中存在与该维度相关的文本证据");
        }
        return missing("暂未识别到该自定义维度的可靠证据");
    }

    private static void appendExclusionRisk(String rule, String searchableText, List<String> risks) {
        if (blank(rule)) return;
        List<String> terms = tokens(rule).stream().filter(value -> value.length() >= 2).toList();
        boolean hit = terms.stream().anyMatch(term -> searchableText.toLowerCase(Locale.ROOT)
                .contains(term.toLowerCase(Locale.ROOT)));
        if (hit) risks.add("命中排除项“" + rule + "”，仅标记人工复核，不自动淘汰");
        else risks.add("排除项“" + rule + "”未发现明确命中，仍建议人工确认");
    }

    static Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) return result;
        for (String token : value.split("[、,，/;；\\n\\r]+")) {
            if (!token.isBlank()) result.add(token.trim());
        }
        return result;
    }

    private static int firstNumber(String value) {
        Matcher matcher = FIRST_NUMBER.matcher(value == null ? "" : value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static int educationRank(String value) {
        if (value == null) return 0;
        if (value.contains("博士")) return 5;
        if (value.contains("硕士") || value.contains("研究生")) return 4;
        if (value.contains("本科")) return 3;
        if (value.contains("大专") || value.contains("专科")) return 2;
        if (value.contains("高中") || value.contains("中专")) return 1;
        return 0;
    }

    private static DimensionScore score(int value, String reason) { return new DimensionScore(value, false, reason); }
    private static DimensionScore missing(String reason) { return new DimensionScore(0, true, reason); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    record FrozenJob(String title, String skills, String experienceLevel, String education, String requirements) { }
    record FrozenCandidate(String headline, int yearsExperience, String education, List<String> skills,
                           String summary, String workExperience, String rawText) {
        String searchableText() {
            return String.join(" ", safe(headline), safe(education), String.join(" ", skills), safe(summary),
                    safe(workExperience), safe(rawText));
        }
        private static String safe(String value) { return value == null ? "" : value; }
    }
    record MatchResult(int score, String level, List<String> matched, List<String> unmatched,
                       List<String> negotiable, List<String> missing, List<String> risks,
                       List<String> evidence) { }
    private record DimensionScore(int value, boolean missing, String reason) { }
}
