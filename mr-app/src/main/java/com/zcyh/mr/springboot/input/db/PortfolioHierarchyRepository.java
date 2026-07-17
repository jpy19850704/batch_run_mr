package com.zcyh.mr.springboot.input.db;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 组合层级平铺查询仓储。
 */
@Repository
public class PortfolioHierarchyRepository {
    private final JdbcTemplate jdbcTemplate;

    public PortfolioHierarchyRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, PortfolioHierarchyRow> findByPortfolioCodes(List<String> portfolioCodes) {
        List<String> normalizedCodes = normalizePortfolioCodes(portfolioCodes);
        if (normalizedCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("SELECT PORTFOLIO_CODE, ");
        sql.append("PORTFOLIO_CODE_1, PORTFOLIO_CODE_2, PORTFOLIO_CODE_3, PORTFOLIO_CODE_4, PORTFOLIO_CODE_5, PORTFOLIO_CODE_6, PORTFOLIO_CODE_7, ");
        sql.append("PORTFOLIO_NAME_1, PORTFOLIO_NAME_2, PORTFOLIO_NAME_3, PORTFOLIO_NAME_4, PORTFOLIO_NAME_5, PORTFOLIO_NAME_6, PORTFOLIO_NAME_7 ");
        sql.append("FROM V_PORTFOLIO_HIERARCHY_FLAT WHERE PORTFOLIO_CODE IN (");
        for (int i = 0; i < normalizedCodes.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(normalizedCodes.get(i));
        }
        sql.append(")");

        List<PortfolioHierarchyRow> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            PortfolioHierarchyRow row = new PortfolioHierarchyRow();
            row.portfolioCode = rs.getString("PORTFOLIO_CODE");
            row.portfolioCode1 = rs.getString("PORTFOLIO_CODE_1");
            row.portfolioCode2 = rs.getString("PORTFOLIO_CODE_2");
            row.portfolioCode3 = rs.getString("PORTFOLIO_CODE_3");
            row.portfolioCode4 = rs.getString("PORTFOLIO_CODE_4");
            row.portfolioCode5 = rs.getString("PORTFOLIO_CODE_5");
            row.portfolioCode6 = rs.getString("PORTFOLIO_CODE_6");
            row.portfolioCode7 = rs.getString("PORTFOLIO_CODE_7");
            row.portfolioName1 = rs.getString("PORTFOLIO_NAME_1");
            row.portfolioName2 = rs.getString("PORTFOLIO_NAME_2");
            row.portfolioName3 = rs.getString("PORTFOLIO_NAME_3");
            row.portfolioName4 = rs.getString("PORTFOLIO_NAME_4");
            row.portfolioName5 = rs.getString("PORTFOLIO_NAME_5");
            row.portfolioName6 = rs.getString("PORTFOLIO_NAME_6");
            row.portfolioName7 = rs.getString("PORTFOLIO_NAME_7");
            return row;
        }, params.toArray());

        Map<String, PortfolioHierarchyRow> result = new LinkedHashMap<String, PortfolioHierarchyRow>();
        for (PortfolioHierarchyRow row : rows) {
            String key = trimToNull(row.portfolioCode);
            if (key != null) {
                result.put(key, row);
            }
        }
        return result;
    }

    private static List<String> normalizePortfolioCodes(List<String> portfolioCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        if (portfolioCodes != null) {
            for (String portfolioCode : portfolioCodes) {
                String safeCode = trimToNull(portfolioCode);
                if (safeCode != null) {
                    normalized.add(safeCode);
                }
            }
        }
        return new ArrayList<String>(normalized);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
