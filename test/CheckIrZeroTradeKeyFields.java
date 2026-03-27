import java.sql.*;
import java.util.regex.*;
public class CheckIrZeroTradeKeyFields {
  static String find(String s, String k) {
    Matcher m = Pattern.compile("\\\"" + k + "\\\":\\\"([^\\\"]*)\\\"").matcher(s);
    return m.find() ? m.group(1) : "";
  }
  public static void main(String[] args) throws Exception {
    String url = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;AUTO_SERVER=TRUE";
    String sql = "select trade_id, product_type, trade_content_text from MR_TRADE_INPUT where trade_id in ('IR_RA_20241231_001','IR_STEPUP_20241231_001','STD_IRS_TEST_003','XYD5002282','TEST001','TEST002','TEST003','TEST004','TEST005','TEST007','TEST008','TEST010') order by product_type, trade_id";
    try (Connection c = DriverManager.getConnection(url, "sa", "")) {
      try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String t = rs.getString(3);
          System.out.println(rs.getString(1)+"|"+rs.getString(2)
            +"|MATURITY="+find(t,"MATURITY_DATE")
            +"|START="+find(t,"START_DATE")
            +"|SETTLE="+find(t,"SETTLE_DATE")
            +"|FIXING_DATE="+find(t,"FIXING_DATE")
            +"|PAY_CURVE="+find(t,"PAY_DISCOUNT_CURVE")
            +"|REC_CURVE="+find(t,"REC_DISCOUNT_CURVE")
            +"|DISC="+find(t,"DISCOUNT_CURVE")
            +"|REF="+find(t,"REFERENCE_CURVE")
            +"|VOL="+find(t,"VOLATILITY_SURFACE"));
        }
      }
    }
  }
}
