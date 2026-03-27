import java.sql.*;


public class TmpUpdateIrTradesToUsd {
    private static String updateCommon(String json) {
        return json
                .replace("\"CURRENCY_CODE\":\"CNY\"", "\"CURRENCY_CODE\":\"USD\"")
                .replace("\"CURRENCY_PAIR\":\"CNY/CNY\"", "\"CURRENCY_PAIR\":\"USD/CNY\"")
                .replace("\"DISCOUNT_CURVE\":\"IR_CURVE_CNY\"", "\"DISCOUNT_CURVE\":\"IR_CURVE_USD\"")
                .replace("\"REFERENCE_CURVE\":\"IR_CURVE_CNY\"", "\"REFERENCE_CURVE\":\"IR_CURVE_USD\"")
                .replace("\"FIXING_CURVE\":\"IR_CURVE_CNY\"", "\"REFERENCE_CURVE\":\"IR_CURVE_USD\"")
                .replace("\"PRICE_CURVE\":\"IR_CURVE_CNY\"", "\"REFERENCE_CURVE\":\"IR_CURVE_USD\"")
                .replace("\"VOLATILITY_SURFACE\":\"IR_VOL_CNY\"", "\"VOLATILITY_SURFACE\":\"IR_VOL_USD\"")
                .replace("\"FIXING_ID\":\"FIXING_IR_CNY\"", "\"FIXING_ID\":\"FIXING_IR_USD\"")
                .replace("\"SETTLE_CURRENCY_CODE\":\"CNY\"", "\"SETTLE_CURRENCY_CODE\":\"USD\"");
    }

    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE";
        String[] tradeIds = {
                "114666.SH_BONDTR_T",
                "BONDFUT05_BONDDER_T",
                "CAPFLOOR_0000030",
                "STD_IRS_TEST_001",
                "SWPTN_1_20211230",
                "IR_RA_20241231_001",
                "IR_STEPUP_20241231_001"
        };
        String insertFixSql =
                "insert into MR_RISKFACTOR_DATA (data_date, riskfactor_type, riskfactor_id, curve_type, curve_code, term_code, term_days, obs_date, \"value\", currency, source_system, version_no, modifier, updated_at) " +
                "select data_date, riskfactor_type, ?, curve_type, curve_code, term_code, term_days, obs_date, \"value\", ?, source_system, version_no, modifier, updated_at " +
                "from MR_RISKFACTOR_DATA where data_date='2025-12-31' and riskfactor_type='FIXING' and riskfactor_id='FIXING_IR_CNY'";
        String insertVolSql =
                "insert into MR_RISKFACTOR_DATA (data_date, riskfactor_type, riskfactor_id, curve_type, curve_code, term_code, term_days, obs_date, \"value\", currency, source_system, version_no, modifier, updated_at) " +
                "select data_date, riskfactor_type, ?, curve_type, curve_code, term_code, term_days, obs_date, \"value\", ?, source_system, version_no, modifier, updated_at " +
                "from MR_RISKFACTOR_DATA where data_date='2025-12-31' and riskfactor_type='IR_VOL' and riskfactor_id='IR_VOL_CNY'";

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.setAutoCommit(false);
            try (PreparedStatement q = c.prepareStatement("select trade_content_text from MR_TRADE_INPUT where data_date='2025-12-31' and trade_id=?");
                 PreparedStatement u = c.prepareStatement("update MR_TRADE_INPUT set trade_content_text=?, updated_at=? where data_date='2025-12-31' and trade_id=?");
                 PreparedStatement copyFix = c.prepareStatement(insertFixSql);
                 PreparedStatement delFix = c.prepareStatement("delete from MR_RISKFACTOR_DATA where data_date='2025-12-31' and riskfactor_type='FIXING' and riskfactor_id='FIXING_IR_USD'");
                 PreparedStatement copyVol = c.prepareStatement(insertVolSql);
                 PreparedStatement delVol = c.prepareStatement("delete from MR_RISKFACTOR_DATA where data_date='2025-12-31' and riskfactor_type='IR_VOL' and riskfactor_id='IR_VOL_USD'")
            ) {
                delFix.executeUpdate();
                copyFix.setString(1, "FIXING_IR_USD");
                copyFix.setString(2, "USD");
                int insFix = copyFix.executeUpdate();

                delVol.executeUpdate();
                copyVol.setString(1, "IR_VOL_USD");
                copyVol.setString(2, "USD");
                int insVol = copyVol.executeUpdate();

                int upd = 0;
                for (String tradeId : tradeIds) {
                    q.setString(1, tradeId);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) {
                            String json = rs.getString(1);
                            String newJson = updateCommon(json);
                            u.setString(1, newJson);
                            u.setLong(2, System.currentTimeMillis());
                            u.setString(3, tradeId);
                            upd += u.executeUpdate();
                        }
                    }
                }
                c.commit();
                System.out.println("INSERT_FIXING_USD=" + insFix);
                System.out.println("INSERT_IR_VOL_USD=" + insVol);
                System.out.println("UPDATED_TRADES=" + upd);
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }
}

