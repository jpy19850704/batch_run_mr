import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TmpRewriteTradesToCurrentMarket {

    private static final String DB_BASE = "E:/zcyh_mr/H2db/mr_input_store";
    private static final String DB_URL = "jdbc:h2:file:" + DB_BASE + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    private static final String TARGET_DATE_JSON = "20251231";
    private static final String TARGET_DATE_DB = "2025-12-31";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Map<String, String>> FIELD_MAPPING = new LinkedHashMap<>();

    static {
        put("DISCOUNT_CURVE", "RF_CNY_REPO_SHIBOR_ZERO", "IR_CURVE_CNY");
        put("DISCOUNT_CURVE", "RF_CNY_SHIBOR_ZERO", "IR_CURVE_CNY");
        put("DISCOUNT_CURVE", "RF_USD_LIBOR_ZERO", "IR_CURVE_USD");
        put("DISCOUNT_CURVE", "RISK_FREE_CNY", "IR_CURVE_CNY");
        put("DISCOUNT_CURVE", "YC_CNY_GOV_ZERO", "IR_CURVE_CNY");

        put("BASE_DISCOUNT_CURVE", "FX_IMPLIED_RFR_CNY", "IR_CURVE_CNY");
        put("BASE_DISCOUNT_CURVE", "RF_CNY_REPO_SHIBOR_ZERO", "IR_CURVE_CNY");
        put("BASE_DISCOUNT_CURVE", "USD_IMPLIED_ZERO", "IR_CURVE_USD");
        put("SETTLE_DISCOUNT_CURVE", "RISK_FREE_CNY", "IR_CURVE_CNY");
        put("SETTLE_DISCOUNT_CURVE", "RF_CNY_REPO_SHIBOR_ZERO", "IR_CURVE_CNY");
        put("SETTLE_DISCOUNT_CURVE", "YC_CNY_GOV_ZERO", "IR_CURVE_CNY");

        put("UNDERLYING_DISCOUNT_CURVE", "EUR_IMPLIED_ZERO", "IR_CURVE_EUR");
        put("UNDERLYING_DISCOUNT_CURVE", "FX_IMPLIED_EUR_ZERO", "IR_CURVE_EUR");
        put("UNDERLYING_DISCOUNT_CURVE", "FX_IMPLIED_RFR_USD", "IR_CURVE_USD");
        put("UNDERLYING_DISCOUNT_CURVE", "RF_USD_SOFR_OIS_ZERO", "IR_CURVE_USD");

        put("PAY_DISCOUNT_CURVE", "FX_IMPLIED_RFR_CNY", "IR_CURVE_CNY");
        put("PAY_DISCOUNT_CURVE", "RF_CNY_REPO_SHIBOR_ZERO", "IR_CURVE_CNY");
        put("PAY_DISCOUNT_CURVE", "FX_IMPLIED_RFR_USD", "IR_CURVE_USD");
        put("REC_DISCOUNT_CURVE", "FX_IMPLIED_RFR_CNY", "IR_CURVE_CNY");
        put("REC_DISCOUNT_CURVE", "FX_IMPLIED_RFR_USD", "IR_CURVE_USD");
        put("REC_DISCOUNT_CURVE", "RF_USD_SOFR_OIS_ZERO", "IR_CURVE_USD");

        put("REFERENCE_CURVE", "COMM_SGE_AUX", "COMM_SPOT_A99");
        put("REFERENCE_CURVE", "COMM_SGE_AUY", "COMM_SPOT_A99");

        put("VOLATILITY_SURFACE", "COMM_SPREAD_VOL_A99", "COMM_VOL_A99");
        put("VOLATILITY_SURFACE", "EQ_SPREAD_VOL_CN_A", "EQ_VOL_CN_A");
        put("VOLATILITY_SURFACE", "IR_VOL_CNY_SHARK", "IR_VOL_CNY");
        put("VOLATILITY_SURFACE", "SWPOPT_CNY_VOLSURF", "IR_VOL_CNY");
        put("VOLATILITY_SURFACE", "VOL_AUXCNY", "COMM_VOL_A99");
        put("VOLATILITY_SURFACE", "VOL_USDCNY", "FX_VOL_USD_CNY");

        put("FIXING_ID", "COMM_FIXING_A99", "FIXING_COMM_SPOT_A99");
        put("FIXING_ID", "EQ_FIXING_CSI300", "FIXING_EQ_SPOT_CN_A");
        put("FIXING_ID", "IR_FIXING_CNY_3M", "FIXING_IR_CNY");
        put("FIXING_ID", "LPR5Y", "FIXING_IR_CNY");
        put("FIXING_ID", "SHIBOR_3M", "FIXING_IR_CNY");
        put("PAY_FIXING_ID", "IR_FIXING_CNY_3M", "FIXING_IR_CNY");
        put("PAY_FIXING_ID", "SHIBOR_3M", "FIXING_IR_CNY");
        put("REC_FIXING_ID", "IR_FIXING_CNY_3M", "FIXING_IR_CNY");
        put("REC_FIXING_ID", "SHIBOR_3M", "FIXING_IR_CNY");

        put("REFERENCE_CURVE", "REF_CNY_LPR_5Y", "IR_CURVE_CNY");
        put("REFERENCE_CURVE", "RF_CNY_REPO_SHIBOR_ZERO", "IR_CURVE_CNY");
        put("REFERENCE_CURVE", "RF_CNY_SHIBOR3M_ZERO", "IR_CURVE_CNY");
        put("REFERENCE_CURVE", "YC_CNY_NCD_AAA", "IR_CURVE_CNY");
        put("PAY_FIXING_CURVE", "FX_IMPLIED_RFR_CNY", "IR_CURVE_CNY");
        put("PAY_FIXING_CURVE", "RF_CNY_REPO_SHIBOR_ZERO", "IR_CURVE_CNY");
        put("REC_FIXING_CURVE", "FX_IMPLIED_RFR_CNY", "IR_CURVE_CNY");
        put("REC_FIXING_CURVE", "FX_IMPLIED_RFR_USD", "IR_CURVE_USD");

        put("HISTORICAL_CURVE", "CNY_FIXING_SHARK", "FIXING_IR_CNY");
        put("HISTORICAL_CURVE", "COMM_FIXING_A99", "FIXING_COMM_SPOT_A99");
        put("HISTORICAL_CURVE", "EQ_FIXING_CSI300", "FIXING_EQ_SPOT_CN_A");
        put("HISTORICAL_CURVE", "IR_FIXING_CNY_3M", "FIXING_IR_CNY");

        put("CREDIT_SPREAD_CURVE", "CS_YC_CNY_4192_ZERO_NCTP", "CS_CURVE_CNY");
        put("CREDIT_SPREAD_CURVE", "NOT_EXIST_CURVE", "CS_CURVE_CNY");
        put("CREDIT_SPREAD_CURVE", "YC_CNY_GOV_ZERO", "CS_CURVE_CNY");
        put("CREDIT_SPREAD_CURVE", "YC_USD_CREDIT_AA", "CS_CURVE_CNY");
    }

    public static void main(String[] args) throws Exception {
        Path backup = backupDatabase();
        try (Connection connection = DriverManager.getConnection(DB_URL, "sa", "")) {
            connection.setAutoCommit(false);
            int updated = rewriteTrades(connection);
            connection.commit();
            System.out.println("BACKUP|" + backup);
            System.out.println("UPDATED|" + updated);
        }
    }

    private static Path backupDatabase() throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path source = Path.of(DB_BASE + ".mv.db");
        Path target = Path.of("E:/zcyh_mr/H2db/backup/mr_input_store_trade_rewrite_" + ts + ".mv.db");
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static int rewriteTrades(Connection connection) throws Exception {
        String querySql = "select id, trade_id, data_date, trade_content_text from MR_TRADE_INPUT order by id";
        String updateSql = "update MR_TRADE_INPUT set trade_id=?, data_date=?, trade_content_text=?, updated_at=? where id=?";
        int count = 0;
        List<TradeRow> rows = new ArrayList<>();
        Set<String> duplicatedTradeIds = new HashSet<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(querySql)) {
            while (rs.next()) {
                TradeRow row = new TradeRow();
                row.id = rs.getLong(1);
                row.tradeId = rs.getString(2);
                row.dataDate = rs.getDate(3).toString();
                row.tradeContentText = rs.getString(4);
                rows.add(row);
            }
        }
        Map<String, Integer> tradeIdCount = new HashMap<>();
        for (TradeRow row : rows) {
            tradeIdCount.put(row.tradeId, tradeIdCount.getOrDefault(row.tradeId, 0) + 1);
        }
        for (Map.Entry<String, Integer> entry : tradeIdCount.entrySet()) {
            if (entry.getValue() > 1) {
                duplicatedTradeIds.add(entry.getKey());
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            for (TradeRow row : rows) {
                JsonNode root = MAPPER.readTree(row.tradeContentText);
                String newTradeId = duplicatedTradeIds.contains(row.tradeId)
                        ? row.tradeId + "_" + row.dataDate.replace("-", "")
                        : row.tradeId;
                rewriteNode(root, row.tradeId, newTradeId);
                ps.setString(1, newTradeId);
                ps.setDate(2, java.sql.Date.valueOf(TARGET_DATE_DB));
                ps.setString(3, MAPPER.writeValueAsString(root));
                ps.setLong(4, System.currentTimeMillis());
                ps.setLong(5, row.id);
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    private static void rewriteNode(JsonNode node, String oldTradeId, String newTradeId) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node instanceof ObjectNode obj) {
            obj.fieldNames().forEachRemaining(field -> {
                JsonNode child = obj.get(field);
                if (child == null || child.isNull()) {
                    return;
                }
                if (child.isValueNode()) {
                    rewriteField(obj, field, child.asText(), oldTradeId, newTradeId);
                } else {
                    rewriteNode(child, oldTradeId, newTradeId);
                }
            });
            return;
        }
        if (node instanceof ArrayNode arr) {
            for (JsonNode item : arr) {
                rewriteNode(item, oldTradeId, newTradeId);
            }
        }
    }

    private static void rewriteField(ObjectNode obj, String field, String value, String oldTradeId, String newTradeId) {
        if ("INSTRUMENT_ID".equals(field) && oldTradeId.equals(value)) {
            obj.put(field, newTradeId);
            return;
        }
        if ("DATA_DATE".equals(field) || "TRADE_DATE".equals(field)) {
            obj.put(field, TARGET_DATE_JSON);
            return;
        }

        if (("UNDERLYING_CODE".equals(field) || "UNDERLYING_ID".equals(field))
                && ("AUX".equalsIgnoreCase(value) || "AUY".equalsIgnoreCase(value))) {
            obj.put(field, "A99");
            return;
        }

        Map<String, String> mapping = FIELD_MAPPING.get(field);
        if (mapping == null) {
            return;
        }
        String target = mapping.get(value);
        if (target != null) {
            obj.put(field, target);
        }
    }

    private static class TradeRow {
        long id;
        String tradeId;
        String dataDate;
        String tradeContentText;
    }

    private static void put(String field, String oldValue, String newValue) {
        FIELD_MAPPING.computeIfAbsent(field, k -> new HashMap<>()).put(oldValue, newValue);
    }
}
