import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class TmpInsertAutoCallSamples {
    private static final String URL = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
    private static final String SQL_DATA_DATE = "2025-12-31";

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            c.setAutoCommit(false);
            deleteTrade(c, "EQ_AUTOCALL_20251231_001");
            deleteTrade(c, "COMM_AUTOCALL_20251231_001");
            enrichExistingCommAutoCall(c, "COMM_AC_20241231_001");
            upsertTrade(c, "EQ_AUTO_CALL_20251231_001", "EQ_AUTO_CALL", buildEqTrade());
            upsertTrade(c, "COMM_AUTO_CALL_20251231_001", "COMM_AUTO_CALL", buildCommTrade());
            c.commit();
        }
        System.out.println("UPSERT_DONE|2");
    }

    private static void deleteTrade(Connection c, String tradeId) throws Exception {
        try (PreparedStatement deletePs = c.prepareStatement("delete from MR_TRADE_INPUT where trade_id=?")) {
            deletePs.setString(1, tradeId);
            deletePs.executeUpdate();
        }
    }

    private static void enrichExistingCommAutoCall(Connection c, String tradeId) throws Exception {
        try (PreparedStatement selectPs = c.prepareStatement(
                "select trade_content_text from MR_TRADE_INPUT where data_date=? and trade_id=?")) {
            selectPs.setString(1, SQL_DATA_DATE);
            selectPs.setString(2, tradeId);
            try (ResultSet rs = selectPs.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                JSONObject trade = JSON.parseObject(rs.getString(1));
                trade.put("FRTB_COMM_ASSET", "GOLD");
                trade.put("FRTB_COMM_BUCKET", "Bucket 7");
                trade.put("FRTB_COMM_LOCATION", "shanghai");
                trade.put("UNDERLYING_CODE", "A99");
                trade.put("UNDERLYING_ID", "COMM_SPOT_A99");
                try (PreparedStatement updatePs = c.prepareStatement(
                        "update MR_TRADE_INPUT set trade_content_text=?, updated_at=? where data_date=? and trade_id=?")) {
                    updatePs.setString(1, JSON.toJSONString(trade));
                    updatePs.setLong(2, System.currentTimeMillis());
                    updatePs.setString(3, SQL_DATA_DATE);
                    updatePs.setString(4, tradeId);
                    updatePs.executeUpdate();
                }
            }
        }
    }

    private static void upsertTrade(Connection c, String tradeId, String productType, JSONObject trade) throws Exception {
        try (PreparedStatement deletePs = c.prepareStatement("delete from MR_TRADE_INPUT where data_date=? and trade_id=?")) {
            deletePs.setString(1, SQL_DATA_DATE);
            deletePs.setString(2, tradeId);
            deletePs.executeUpdate();
        }

        String insertSql = "insert into MR_TRADE_INPUT "
                + "(data_date, trade_id, product_type, trade_content_text, content_format, version_no, "
                + "source_system, created_at, updated_at, trader, portfolio, desk) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        long now = System.currentTimeMillis();
        try (PreparedStatement insertPs = c.prepareStatement(insertSql)) {
            insertPs.setString(1, SQL_DATA_DATE);
            insertPs.setString(2, tradeId);
            insertPs.setString(3, productType);
            insertPs.setString(4, JSON.toJSONString(trade));
            insertPs.setString(5, "JSON");
            insertPs.setInt(6, 1);
            insertPs.setString(7, "TMP");
            insertPs.setLong(8, now);
            insertPs.setLong(9, now);
            insertPs.setString(10, "TMP");
            insertPs.setString(11, "TMP");
            insertPs.setString(12, "TMP");
            insertPs.executeUpdate();
        }
    }

    private static JSONObject buildEqTrade() {
        JSONObject o = new JSONObject();
        o.put("INSTRUMENT_ID", "EQ_AUTO_CALL_20251231_001");
        o.put("PRODUCT_CODE", "EQ_AUTO_CALL");
        o.put("BUY_OR_SELL", "B");
        o.put("CURRENCY_CODE", "CNY");
        o.put("NOTIONAL", 5_000_000.0);
        o.put("BARRIER", 4200.0);
        o.put("BARRIER_DIRECTION", "UP");
        o.put("START_DATE", "20260102");
        o.put("SETTLE_DATE", "20280702");
        o.put("OBS_DATE", "20260331,20260630,20260930,20261231,20270331,20270630,20270930,20271231,20280331,20280630");
        o.put("PAYOFF_RATE", 0.018);
        o.put("PREMIUM_RATE", 0.002);
        o.put("DISCOUNT_CURVE", "IR_CURVE_CNY");
        o.put("VOLATILITY_SURFACE", "EQ_VOL_CN_A");
        o.put("FIXING_ID", "FIXING_EQ_SPOT_CN_A");
        o.put("REFERENCE_CURVE", "EQ_SPOT_CN_A");
        o.put("PATH_NB", 2000);
        o.put("PATH_FLAG", false);
        o.put("SETTLE_CALENDAR", "SH");
        o.put("SETTLE_RULE", "P");
        o.put("SETTLE_DAYOFF", 2);
        return o;
    }

    private static JSONObject buildCommTrade() {
        JSONObject o = new JSONObject();
        o.put("INSTRUMENT_ID", "COMM_AUTO_CALL_20251231_001");
        o.put("PRODUCT_CODE", "COMM_AUTO_CALL");
        o.put("BUY_OR_SELL", "B");
        o.put("CURRENCY_CODE", "CNY");
        o.put("NOTIONAL", 3_000_000.0);
        o.put("BARRIER", 560.0);
        o.put("BARRIER_DIRECTION", "UP");
        o.put("START_DATE", "20260102");
        o.put("SETTLE_DATE", "20280702");
        o.put("OBS_DATE", "20260331,20260630,20260930,20261231,20270331,20270630,20270930,20271231,20280331,20280630");
        o.put("PAYOFF_RATE", 0.02);
        o.put("PREMIUM_RATE", 0.003);
        o.put("DISCOUNT_CURVE", "IR_CURVE_CNY");
        o.put("VOLATILITY_SURFACE", "COMM_VOL_A99");
        o.put("FIXING_ID", "FIXING_COMM_SPOT_A99");
        o.put("REFERENCE_CURVE", "COMM_SPOT_A99");
        o.put("FRTB_COMM_ASSET", "GOLD");
        o.put("FRTB_COMM_BUCKET", "Bucket 7");
        o.put("FRTB_COMM_LOCATION", "shanghai");
        o.put("UNDERLYING_CODE", "A99");
        o.put("UNDERLYING_ID", "COMM_SPOT_A99");
        o.put("PATH_NB", 2000);
        o.put("PATH_FLAG", false);
        o.put("SETTLE_CALENDAR", "SH");
        o.put("SETTLE_RULE", "P");
        o.put("SETTLE_DAYOFF", 2);
        return o;
    }
}
