import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

/**
 * 向 H2 补充缺失的利率结构产品样例，并顺延已到期样例日期。
 */
public class TmpInsertIrMissingSamples {
    private static final String URL = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
    private static final String DATA_DATE = "2025-12-31";

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            c.setAutoCommit(false);
            long nextId = nextId(c);
            long now = Instant.now().toEpochMilli();

            nextId = upsertTrade(c, nextId, now, buildIrAutoCall());
            nextId = upsertTrade(c, nextId, now, buildIrSharkFin());
            nextId = upsertTrade(c, nextId, now, buildIrSpreadOpt());
            updateIrWedding(c, now);
            updateFxForwardAndSwap(c, now);

            c.commit();
        }
        System.out.println("DONE");
    }

    private static long nextId(Connection c) throws Exception {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select coalesce(max(id), 0) + 1 from MR_TRADE_INPUT")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long upsertTrade(Connection c, long nextId, long now, JSONObject trade) throws Exception {
        String tradeId = trade.getString("INSTRUMENT_ID");
        String productType = trade.getString("PRODUCT_CODE");
        String content = JSON.toJSONString(trade);

        try (PreparedStatement update = c.prepareStatement(
                "update MR_TRADE_INPUT set PRODUCT_TYPE=?, TRADE_CONTENT_TEXT=?, UPDATED_AT=? where DATA_DATE=? and TRADE_ID=?")) {
            update.setString(1, productType);
            update.setString(2, content);
            update.setLong(3, now);
            update.setString(4, DATA_DATE);
            update.setString(5, tradeId);
            int count = update.executeUpdate();
            if (count > 0) {
                System.out.println("UPDATED|" + productType + "|" + tradeId);
                return nextId;
            }
        }

        try (PreparedStatement insert = c.prepareStatement(
                "insert into MR_TRADE_INPUT (ID, DATA_DATE, TRADE_ID, PRODUCT_TYPE, TRADE_CONTENT_TEXT, CONTENT_FORMAT, VERSION_NO, SOURCE_SYSTEM, CREATED_AT, UPDATED_AT, TRADER, PORTFOLIO, DESK) "
                        + "values (?, ?, ?, ?, ?, 'JSON', 1, 'LOCAL_TMP', ?, ?, null, null, null)")) {
            insert.setLong(1, nextId);
            insert.setString(2, DATA_DATE);
            insert.setString(3, tradeId);
            insert.setString(4, productType);
            insert.setString(5, content);
            insert.setLong(6, now);
            insert.setLong(7, now);
            insert.executeUpdate();
        }
        System.out.println("INSERTED|" + productType + "|" + tradeId);
        return nextId + 1;
    }

    private static void updateIrWedding(Connection c, long now) throws Exception {
        String sql = "select TRADE_CONTENT_TEXT from MR_TRADE_INPUT where DATA_DATE=? and TRADE_ID='IR_WEDDING_001'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, DATA_DATE);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                JSONObject trade = JSON.parseObject(rs.getString(1));
                trade.put("MATURITY_DATE", "20280701");
                trade.put("SETTLE_DATE", "20280703");
                try (PreparedStatement update = c.prepareStatement(
                        "update MR_TRADE_INPUT set TRADE_CONTENT_TEXT=?, UPDATED_AT=? where DATA_DATE=? and TRADE_ID='IR_WEDDING_001'")) {
                    update.setString(1, JSON.toJSONString(trade));
                    update.setLong(2, now);
                    update.setString(3, DATA_DATE);
                    update.executeUpdate();
                }
                System.out.println("UPDATED|IR_WEDDING_CAKE|IR_WEDDING_001");
            }
        }
    }

    private static void updateFxForwardAndSwap(Connection c, long now) throws Exception {
        updateTradeDates(c, now, "FXFWD_1_20211231",
                new String[][] { { "SETTLE_DATE", "20270102" } }, "FXFWD");
        updateTradeDates(c, now, "FXFWD_0000001",
                new String[][] { { "SETTLE_DATE", "20270430" } }, "FXFWD");
        updateTradeDates(c, now, "FXFWD_0000002",
                new String[][] { { "SETTLE_DATE", "20270515" } }, "FXFWD");
        updateTradeDates(c, now, "FXFWD_0000003",
                new String[][] { { "SETTLE_DATE", "20270630" } }, "FXFWD");
        updateTradeDates(c, now, "FXFWD_1_20220413",
                new String[][] {
                        { "SPOT_SETTLE_DATE", "20260402" },
                        { "FWD_SETTLE_DATE", "20270102" }
                }, "FXSWAP");
    }

    private static void updateTradeDates(Connection c, long now, String tradeId, String[][] fields, String productType)
            throws Exception {
        String sql = "select TRADE_CONTENT_TEXT from MR_TRADE_INPUT where DATA_DATE=? and TRADE_ID=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, DATA_DATE);
            ps.setString(2, tradeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                JSONObject trade = JSON.parseObject(rs.getString(1));
                for (String[] field : fields) {
                    trade.put(field[0], field[1]);
                }
                try (PreparedStatement update = c.prepareStatement(
                        "update MR_TRADE_INPUT set TRADE_CONTENT_TEXT=?, UPDATED_AT=? where DATA_DATE=? and TRADE_ID=?")) {
                    update.setString(1, JSON.toJSONString(trade));
                    update.setLong(2, now);
                    update.setString(3, DATA_DATE);
                    update.setString(4, tradeId);
                    update.executeUpdate();
                }
                System.out.println("UPDATED|" + productType + "|" + tradeId);
            }
        }
    }

    private static JSONObject buildIrAutoCall() {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", "IR_AC_20251231_001");
        trade.put("PRODUCT_CODE", "IR_AUTO_CALL");
        trade.put("BUY_OR_SELL", "B");
        trade.put("CURRENCY_CODE", "CNY");
        trade.put("NOTIONAL", 10000000);
        trade.put("BARRIER", 0.03);
        trade.put("BARRIER_DIRECTION", "UP");
        trade.put("START_DATE", "20250102");
        trade.put("SETTLE_DATE", "20270102");
        trade.put("OBS_DATE", "20260331,20260630,20260930,20261231");
        trade.put("PAYOFF_RATE", 0.09);
        trade.put("PREMIUM_RATE", 0.02);
        trade.put("DISCOUNT_CURVE", "IR_CURVE_CNY");
        trade.put("VOLATILITY_SURFACE", "IR_VOL_CNY");
        trade.put("REFERENCE_CURVE", "IR_CURVE_CNY");
        trade.put("PATH_NB", 10000);
        trade.put("FIXING_ID", "FIXING_IR_CNY");
        trade.put("RATE_TYPE", "PAR");
        trade.put("TERM_CODE", "5Y");
        trade.put("UNDERLYING_TERM", "5Y");
        trade.put("TERM_FREQ", "1Y");
        return trade;
    }

    private static JSONObject buildIrSharkFin() {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", "IR_SHARK_20251231_001");
        trade.put("PRODUCT_CODE", "IR_SHARKFIN");
        trade.put("BUY_OR_SELL", "B");
        trade.put("CONTRACT_SIZE", 1);
        trade.put("TRADE_DATE", "20251231");
        trade.put("OPTION_TYPE", "double");
        trade.put("TOUCH_RATE", 0.07);
        trade.put("BASE_RATE", 0.025);
        trade.put("NOTIONAL", 5000000);
        trade.put("UNDERLYING_CURRENCY_CODE", "CNY");
        trade.put("BASE_CURRENCY_CODE", "CNY");
        trade.put("START_DATE", "20250102");
        trade.put("MATURITY_DATE", "20261231");
        trade.put("SETTLE_DATE", "20270102");
        trade.put("SETTLE_TYPE", "CASH");
        trade.put("BASE_DISCOUNT_CURVE", "IR_CURVE_CNY");
        trade.put("UNDERLYING_DISCOUNT_CURVE", "IR_CURVE_CNY");
        trade.put("DISCOUNT_CURVE", "IR_CURVE_CNY");
        trade.put("VOLATILITY_SURFACE", "IR_VOL_CNY");
        trade.put("SETTLE_CURRENCY_CODE", "CNY");
        trade.put("DOWN_BARRIER", 0.015);
        trade.put("UPPER_BARRIER", 0.04);
        trade.put("STRIKE_PRICE", 0.025);
        trade.put("CALL_OPTION", "true");
        trade.put("REFERENCE_CURVE", "IR_CURVE_CNY");
        trade.put("CURRENCY_CODE", "CNY");
        trade.put("FIXING_ID", "FIXING_IR_CNY");
        trade.put("RATE_TYPE", "PAR");
        trade.put("TERM_CODE", "5Y");
        trade.put("TERM_FREQ", "1Y");
        return trade;
    }

    private static JSONObject buildIrSpreadOpt() {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", "IR_SPREAD_20251231_001");
        trade.put("PRODUCT_CODE", "IR_SPREADOPT");
        trade.put("TRADE_DATE", "20251231");
        trade.put("OPTION_TYPE", "Call");
        trade.put("CALL_OR_PUT", "CALL");
        trade.put("BUY_OR_SELL", "B");
        trade.put("CONTRACT_SIZE", 1);
        trade.put("SETTLE_TYPE", "CASH");
        trade.put("START_DATE", "20250102");
        trade.put("MATURITY_DATE", "20261231");
        trade.put("SETTLE_DATE", "20270102");
        trade.put("VOLATILITY_SURFACE", "IR_VOL_CNY");
        trade.put("DOWN_BARRIER", 0.015);
        trade.put("UPPER_BARRIER", 0.035);
        trade.put("NOTIONAL", 5000000);
        trade.put("INITIAL_PRICE", 0.01);
        trade.put("CURRENCY_CODE", "CNY");
        trade.put("STRIKE_PRICE", 0.025);
        trade.put("FIXING_ID", "FIXING_IR_CNY");
        trade.put("DISCOUNT_CURVE", "IR_CURVE_CNY");
        trade.put("REFERENCE_CURVE", "IR_CURVE_CNY");
        trade.put("RATE_TYPE", "PAR");
        trade.put("TERM_CODE", "5Y");
        trade.put("TERM_FREQ", "1Y");
        return trade;
    }
}
