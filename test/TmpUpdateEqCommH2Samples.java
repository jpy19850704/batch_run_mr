import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TmpUpdateEqCommH2Samples {
    private static final String URL = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
    private static final String DATA_DATE = "2025-12-31";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            c.setAutoCommit(false);
            try {
                updateCommFwd(c);
                updateCommOpt(c);
                updateCommSwap(c);
                updateCommRangeAccure(c);
                updateCommStepUp(c);
                upsertEqRangeAccure(c);
                upsertEqStepUp(c);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
        System.out.println("DONE");
    }

    private static void updateCommFwd(Connection c) throws Exception {
        JSONObject json = loadTradeJson(c, "COMM_SPOT_AUX");
        json.put("MATURITY_DATE", "20280630");
        json.put("SETTLE_DATE", "20280630");
        updateTradeJson(c, "COMM_SPOT_AUX", json);
    }

    private static void updateCommOpt(Connection c) throws Exception {
        JSONObject json = loadTradeJson(c, "COMMOPT_TEST");
        json.put("MATURITY_DATE", "20280630");
        json.put("SETTLE_DATE", "20280702");
        updateTradeJson(c, "COMMOPT_TEST", json);
    }

    private static void updateCommSwap(Connection c) throws Exception {
        JSONObject json = loadTradeJson(c, "GJS20240103030300000091");
        json.put("SPOT_SETTLE_DATE", "20260331");
        json.put("FWD_SETTLE_DATE", "20280630");
        updateTradeJson(c, "GJS20240103030300000091", json);
    }

    private static void updateCommRangeAccure(Connection c) throws Exception {
        JSONObject json = loadTradeJson(c, "COMM_RA_20241231_001");
        json.put("MATURITY_DATE", "20280630");
        json.put("SETTLE_DATE", "20280702");
        json.put("OBS_DATE", buildMonthEndSeries(LocalDate.of(2026, 1, 31), LocalDate.of(2028, 6, 30)));
        updateTradeJson(c, "COMM_RA_20241231_001", json);
    }

    private static void updateCommStepUp(Connection c) throws Exception {
        JSONObject json = loadTradeJson(c, "COMM_STEPUP_20241231_001");
        json.put("MATURITY_DATE", "20280630");
        json.put("SETTLE_DATE", "20280702");
        json.put("FIXING_DATE", "20280629");
        updateTradeJson(c, "COMM_STEPUP_20241231_001", json);
    }

    private static void upsertEqRangeAccure(Connection c) throws Exception {
        JSONObject json = loadTradeJson(c, "COMM_RA_20241231_001");
        json.put("INSTRUMENT_ID", "EQ_RA_20251231_001");
        json.put("PRODUCT_CODE", "EQ_RANGE_ACCURE");
        json.put("CURRENCY_CODE", "CNY");
        json.put("SETTLE_CURRENCY_CODE", "CNY");
        json.put("REFERENCE_CURVE", "EQ_SPOT_CN_A");
        json.put("VOLATILITY_SURFACE", "EQ_VOL_CN_A");
        json.put("FIXING_ID", "FIXING_EQ_SPOT_CN_A");
        json.remove("FRTB_COMM_BUCKET");
        json.remove("FRTB_COMM_ASSET");
        json.remove("FRTB_COMM_LOCATION");
        upsertTradeFromTemplate(c, "COMM_RA_20241231_001", "EQ_RA_20251231_001", "EQ_RANGE_ACCURE", json);
    }

    private static void upsertEqStepUp(Connection c) throws Exception {
        JSONObject json = loadTradeJson(c, "COMM_STEPUP_20241231_001");
        json.put("INSTRUMENT_ID", "EQ_STEPUP_20251231_001");
        json.put("PRODUCT_CODE", "EQ_STEP_UP");
        json.put("CURRENCY_CODE", "CNY");
        json.put("SETTLE_CURRENCY_CODE", "CNY");
        json.put("REFERENCE_CURVE", "EQ_SPOT_CN_A");
        json.put("VOLATILITY_SURFACE", "EQ_VOL_CN_A");
        json.put("FIXING_ID", "FIXING_EQ_SPOT_CN_A");
        json.put("UNDERLYING_TYPE", "EQ");
        json.put("UNDERLYING_ID", "EQ_CN_A");
        json.put("UNDERLYING_CODE", "EQ_CN_A");
        json.remove("FRTB_COMM_BUCKET");
        json.remove("FRTB_COMM_ASSET");
        json.remove("FRTB_COMM_LOCATION");
        upsertTradeFromTemplate(c, "COMM_STEPUP_20241231_001", "EQ_STEPUP_20251231_001", "EQ_STEP_UP", json);
    }

    private static JSONObject loadTradeJson(Connection c, String tradeId) throws Exception {
        String sql = "select trade_content_text from MR_TRADE_INPUT where data_date=? and trade_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, DATA_DATE);
            ps.setString(2, tradeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("未找到交易: " + tradeId);
                }
                return JSON.parseObject(rs.getString(1));
            }
        }
    }

    private static void updateTradeJson(Connection c, String tradeId, JSONObject json) throws Exception {
        String sql = "update MR_TRADE_INPUT set trade_content_text=?, updated_at=? where data_date=? and trade_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, JSON.toJSONString(json));
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, DATA_DATE);
            ps.setString(4, tradeId);
            ps.executeUpdate();
        }
    }

    private static void upsertTradeFromTemplate(
            Connection c,
            String templateTradeId,
            String newTradeId,
            String productType,
            JSONObject json) throws Exception {
        try (PreparedStatement del = c.prepareStatement(
                "delete from MR_TRADE_INPUT where data_date=? and trade_id=?")) {
            del.setString(1, DATA_DATE);
            del.setString(2, newTradeId);
            del.executeUpdate();
        }
        String selectSql = "select content_format, version_no, source_system, trader, portfolio, desk "
                + "from MR_TRADE_INPUT where data_date=? and trade_id=?";
        String insertSql = "insert into MR_TRADE_INPUT "
                + "(data_date, trade_id, product_type, trade_content_text, content_format, version_no, source_system, created_at, updated_at, trader, portfolio, desk) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement select = c.prepareStatement(selectSql)) {
            select.setString(1, DATA_DATE);
            select.setString(2, templateTradeId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("未找到模板交易: " + templateTradeId);
                }
                try (PreparedStatement insert = c.prepareStatement(insertSql)) {
                    insert.setString(1, DATA_DATE);
                    insert.setString(2, newTradeId);
                    insert.setString(3, productType);
                    insert.setString(4, JSON.toJSONString(json));
                    insert.setString(5, rs.getString(1));
                    insert.setInt(6, rs.getInt(2));
                    insert.setString(7, rs.getString(3));
                    long now = System.currentTimeMillis();
                    insert.setLong(8, now);
                    insert.setLong(9, now);
                    insert.setString(10, rs.getString(4));
                    insert.setString(11, rs.getString(5));
                    insert.setString(12, rs.getString(6));
                    insert.executeUpdate();
                }
            }
        }
    }

    private static String buildMonthEndSeries(LocalDate start, LocalDate end) {
        List<String> dates = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            dates.add(cursor.format(FMT));
            cursor = cursor.plusMonths(1).withDayOfMonth(cursor.plusMonths(1).lengthOfMonth());
        }
        return String.join(",", dates);
    }
}
