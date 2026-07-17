package com.zcyh.mr.springboot.output.db;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按规则集合批量删除结果的公共支持。
 */
public final class RuleScopedDeleteSupport {
    private RuleScopedDeleteSupport() {
    }

    public static int deleteByRuleIds(JdbcTemplate jdbcTemplate,
                                      String tableName,
                                      String batchId,
                                      String dataDate,
                                      List<String> ruleIds) {
        List<String> safeRuleIds = requireRuleIds(ruleIds);
        String sql = "DELETE FROM " + requireIdentifier(tableName)
                + " WHERE BATCH_ID=? AND DATA_DATE=? AND RULE_ID IN ("
                + placeholders(safeRuleIds.size()) + ")";
        List<Object> args = new ArrayList<Object>(2 + safeRuleIds.size());
        args.add(batchId);
        args.add(dataDate);
        args.addAll(safeRuleIds);
        return jdbcTemplate.update(sql, args.toArray());
    }

    public static int deleteMetaByRuleIds(JdbcTemplate jdbcTemplate,
                                          String tableName,
                                          String batchId,
                                          String dataDate,
                                          String calcType,
                                          List<String> ruleIds) {
        List<String> safeRuleIds = requireRuleIds(ruleIds);
        String sql = "DELETE FROM " + requireIdentifier(tableName)
                + " WHERE BATCH_ID=? AND DATA_DATE=? AND CALC_TYPE=? AND RULE_ID IN ("
                + placeholders(safeRuleIds.size()) + ")";
        List<Object> args = new ArrayList<Object>(3 + safeRuleIds.size());
        args.add(batchId);
        args.add(dataDate);
        args.add(calcType);
        args.addAll(safeRuleIds);
        return jdbcTemplate.update(sql, args.toArray());
    }

    private static List<String> requireRuleIds(List<String> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            throw new IllegalArgumentException("RULE 清理模式下 ruleIds 不能为空");
        }
        List<String> values = new ArrayList<String>(ruleIds.size());
        for (String ruleId : ruleIds) {
            if (ruleId == null || ruleId.trim().isEmpty()) {
                throw new IllegalArgumentException("RULE 清理模式下 ruleId 不能为空");
            }
            values.add(ruleId.trim());
        }
        return values;
    }

    private static String requireIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("非法结果表名: " + identifier);
        }
        return identifier;
    }

    private static String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }
}
