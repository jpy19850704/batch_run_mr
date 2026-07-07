package com.zcyh.mr.springboot.out.db;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 曲线生成结果写入有效市场数据表。
 */
@Service
public class GeneratedMarketDataPersistService {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String INSERT_SQL = ""
            + "INSERT INTO MR_MARKET_CURVE_INPUT "
            + "(data_date, market_data_type, curve_id, curve_content_text, content_format, version_no, source_system, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'JSON', 1, 'CURVE_GENERATION', ?, ?) "
            + "ON DUPLICATE KEY UPDATE "
            + "curve_content_text = VALUES(curve_content_text), "
            + "source_system = VALUES(source_system), "
            + "updated_at = VALUES(updated_at)";

    private final JdbcTemplate jdbcTemplate;

    public GeneratedMarketDataPersistService(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int persist(JSONArray generatedMarketData) {
        if (generatedMarketData == null || generatedMarketData.isEmpty()) {
            return 0;
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int count = 0;
        for (int i = 0; i < generatedMarketData.size(); i++) {
            JSONObject curve = generatedMarketData.getJSONObject(i);
            if (curve == null) {
                throw new IllegalArgumentException("generated_market_data[" + i + "]不能为空");
            }
            LocalDate dataDate = parseDataDate(requiredText(curve, "DATA_DATE", i));
            String curveType = requiredText(curve, "CURVE_TYPE", i);
            String curveId = requiredText(curve, "CURVE_ID", i);
            jdbcTemplate.update(INSERT_SQL,
                    Date.valueOf(dataDate),
                    curveType,
                    curveId,
                    JSON.toJSONString(curve, JSONWriter.Feature.WriteBigDecimalAsPlain),
                    now,
                    now);
            count++;
        }
        return count;
    }

    private static LocalDate parseDataDate(String value) {
        try {
            return LocalDate.parse(value, BASIC_DATE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("generated_market_data.DATA_DATE格式必须为yyyyMMdd: " + value, ex);
        }
    }

    private static String requiredText(JSONObject curve, String fieldName, int index) {
        String value = curve.getString(fieldName);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("generated_market_data[" + index + "]." + fieldName + "不能为空");
        }
        return value.trim();
    }
}
