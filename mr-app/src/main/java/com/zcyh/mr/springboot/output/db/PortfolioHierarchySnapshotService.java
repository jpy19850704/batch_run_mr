package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组合层级快照写入服务。
 */
@Service
public class PortfolioHierarchySnapshotService {
    private final JdbcTemplate engineDbJdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public PortfolioHierarchySnapshotService(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    public void writeSnapshot(String batchId, LocalDate dataDate) {
        String safeBatchId = requireText(batchId, "batchId 不能为空");
        if (dataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
        String normalizedDataDate = dataDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        List<Map<String, Object>> hierarchyRows = engineDbJdbcTemplate.queryForList(
                "SELECT PORTFOLIO_CODE, PORTFOLIO_NAME, UPPER_LEVEL_PORTFOLIO, LEVEL_CODE FROM V_PORTFOLIO_HIERARCHY");
        if (hierarchyRows.isEmpty()) {
            return;
        }

        String now = ResultPersistTime.nowText();
        Set<String> uniqueKeys = new LinkedHashSet<>();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                "TB_OUT_PORTFOLIO_HIERARCHY",
                "BATCH_ID,DATA_DATE,PORTFOLIO_CODE,PORTFOLIO_NAME,UPPER_LEVEL_PORTFOLIO,LEVEL_CODE,CREATED_AT,UPDATED_AT",
                "portfolio_hierarchy_" + safeBatchId,
                5000);
        for (Map<String, Object> row : hierarchyRows) {
            String portfolioCode = trimToNull(stringValue(row.get("PORTFOLIO_CODE")));
            String levelCode = trimToNull(stringValue(row.get("LEVEL_CODE")));
            String upperLevelPortfolio = trimToNull(stringValue(row.get("UPPER_LEVEL_PORTFOLIO")));
            String uniqueKey = String.join("|",
                    valueOrEmpty(portfolioCode),
                    valueOrEmpty(levelCode),
                    valueOrEmpty(upperLevelPortfolio));
            if (!uniqueKeys.add(uniqueKey)) {
                continue;
            }
            buffer.appendRow(
                    safeBatchId,
                    normalizedDataDate,
                    portfolioCode,
                    trimToNull(stringValue(row.get("PORTFOLIO_NAME"))),
                    upperLevelPortfolio,
                    levelCode,
                    now,
                    now);
        }
        buffer.flush();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String requireText(String value, String message) {
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            throw new IllegalArgumentException(message);
        }
        return safeValue;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
