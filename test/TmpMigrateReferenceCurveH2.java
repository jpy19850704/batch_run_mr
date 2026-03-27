import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class TmpMigrateReferenceCurveH2 {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            c.setAutoCommit(false);
            String selectSql = "select id, product_type, trade_content_text from MR_TRADE_INPUT";
            String updateSql = "update MR_TRADE_INPUT set trade_content_text=? where id=?";
            int updated = 0;
            try (PreparedStatement q = c.prepareStatement(selectSql);
                 PreparedStatement u = c.prepareStatement(updateSql);
                 ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    String productType = rs.getString(2);
                    String text = rs.getString(3);
                    JSONObject json = JSON.parseObject(text);
                    boolean changed = false;

                    if (isSingleCurveProduct(productType)) {
                        changed = migrateReferenceCurve(json) || changed;
                    }
                    if (isBondFuture(productType)) {
                        changed = migrateBondFuture(json) || changed;
                    }

                    if (changed) {
                        u.setString(1, JSON.toJSONString(json));
                        u.setLong(2, id);
                        u.addBatch();
                        updated++;
                    }
                }
                u.executeBatch();
            }
            c.commit();
            System.out.println("UPDATED=" + updated);
        }
    }

    private static boolean isSingleCurveProduct(String productType) {
        if (productType == null) {
            return false;
        }
        switch (productType.toUpperCase()) {
            case "BOND":
            case "CAPFLOOR":
            case "STD_IRS":
            case "SWAPTION":
            case "IR_AUTO_CALL":
            case "IR_AUTOCALL":
            case "IR_BARRIER":
            case "IR_DIGITAL":
            case "IR_RANGE_ACCURE":
            case "IR_SHARKFIN":
            case "IR_SPREADOPT":
            case "IR_STEP_UP":
            case "IR_WEDDING_CAKE":
            case "COMM_AUTO_CALL":
            case "COMM_AUTOCALL":
            case "COMM_BARRIER":
            case "COMM_DIGITAL":
            case "COMM_FORWARD":
            case "COMM_FWD":
            case "COMMFWD":
            case "COMM_RANGE_ACCURE":
            case "COMM_SHARKFIN":
            case "COMM_SPREADOPT":
            case "COMM_STEP_UP":
            case "COMM_SWAP":
            case "COMMSWAP":
            case "COMMOPT":
            case "COMM_WEDDING_CAKE":
            case "EQ_AUTO_CALL":
            case "EQ_AUTOCALL":
            case "EQ_BARRIER":
            case "EQ_DIGITAL":
            case "EQ_RANGE_ACCURE":
            case "EQ_SHARKFIN":
            case "EQ_SPREADOPT":
            case "EQ_STEP_UP":
            case "EQ_WEDDING_CAKE":
                return true;
            default:
                return false;
        }
    }

    private static boolean isBondFuture(String productType) {
        return "BOND_FUTURE".equalsIgnoreCase(productType);
    }

    private static boolean migrateBondFuture(JSONObject json) {
        boolean changed = false;
        Object underlyingData = json.get("UNDERLYING_DATA");
        if (underlyingData instanceof Iterable) {
            for (Object item : (Iterable<?>) underlyingData) {
                if (item instanceof JSONObject) {
                    changed = migrateReferenceCurve((JSONObject) item) || changed;
                }
            }
        }
        return changed;
    }

    private static boolean migrateReferenceCurve(JSONObject json) {
        boolean changed = false;
        if (!json.containsKey("REFERENCE_CURVE")) {
            String referenceCurve = json.getString("FIXING_CURVE");
            if (isBlank(referenceCurve)) {
                referenceCurve = json.getString("PRICE_CURVE");
            }
            if (!isBlank(referenceCurve)) {
                json.put("REFERENCE_CURVE", referenceCurve);
                changed = true;
            }
        }
        if (json.containsKey("FIXING_CURVE")) {
            json.remove("FIXING_CURVE");
            changed = true;
        }
        if (json.containsKey("PRICE_CURVE")) {
            json.remove("PRICE_CURVE");
            changed = true;
        }
        return changed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
