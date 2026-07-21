package com.zcyh.mr.springboot.output.db;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.input.db.MarketCurveInputRow;
import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按批次写入市场数据快照。
 */
@Service
public class MarketDataResultWriter {
    private static final String TARGET_TABLE = "TB_OUT_MARKET_DATA_DETAIL";
    private static final List<String> COLUMN_LIST = Arrays.asList(
            "BATCH_ID",
            "DATA_DATE",
            "CURVE_TYPE",
            "CURVE_ID",
            "CURVE_DATA_JSON",
            "CREATED_AT",
            "UPDATED_AT"
    );
    private static final String COLUMNS = String.join(",", COLUMN_LIST);

    private final DorisStreamLoadService dorisStreamLoadService;
    private final int batchSize;

    public MarketDataResultWriter(
            DorisStreamLoadService dorisStreamLoadService,
            @Value("${mr.doris.result.batch-size:50000}") int batchSize) {
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.batchSize = Math.max(1000, batchSize);
    }

    String tableName() {
        return TARGET_TABLE;
    }

    List<String> writeColumns() {
        return COLUMN_LIST;
    }

    public void writeSnapshot(String batchId,
                              LocalDate dataDate,
                              List<MarketCurveInputRow> marketData) {
        if (marketData == null || marketData.isEmpty()) {
            return;
        }
        Map<String, MarketCurveInputRow> deduplicated = new LinkedHashMap<String, MarketCurveInputRow>();
        for (MarketCurveInputRow row : marketData) {
            if (row == null) {
                continue;
            }
            String curveType = requireText(row.marketDataType, "市场数据CURVE_TYPE不能为空");
            String curveId = requireText(row.curveId, "市场数据CURVE_ID不能为空");
            deduplicated.putIfAbsent(batchId + "|" + curveType + "|" + curveId, row);
        }
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                COLUMNS,
                "market_data_" + batchId,
                batchSize);
        for (MarketCurveInputRow row : deduplicated.values()) {
            buffer.appendRow(
                    batchId,
                    dataDate,
                    row.marketDataType,
                    row.curveId,
                    normalizeJson(row.curveContentText),
                    now,
                    now);
        }
        buffer.flush();
    }

    private static String normalizeJson(String raw) {
        String value = requireText(raw, "市场数据内容不能为空");
        try {
            return JSON.toJSONString(JSON.parse(value), JSONWriter.Feature.WriteBigDecimalAsPlain);
        } catch (Exception ex) {
            throw new IllegalArgumentException("市场数据内容不是合法JSON", ex);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
