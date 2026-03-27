import java.sql.*;
public class CheckCapFloorFields {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;AUTO_SERVER=TRUE";
    try (Connection c = DriverManager.getConnection(url, "sa", "")) {
      try (PreparedStatement ps = c.prepareStatement("select trade_id, trade_content_text from MR_TRADE_INPUT where product_type='CAPFLOOR' order by id")) {
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String s = rs.getString(2);
            System.out.println("TRADE=" + rs.getString(1));
            String[] keys = {"DAY_COUNT_BASIS","FLT_DAY_COUNT_BASIS","FLOATING_INDEX_FREQ","FIXING_FREQ","RESET_FREQ","REFERENCE_CURVE","FIXING_ID","INTEREST_STUB","FIXING_RULE","SETTLE_RULE"};
            for (String k : keys) {
              int idx = s.indexOf("\"" + k + "\"");
              if (idx >= 0) {
                int end = Math.min(s.length(), idx + 80);
                System.out.println("  " + s.substring(idx, end));
              } else {
                System.out.println("  MISSING:" + k);
              }
            }
          }
        }
      }
    }
  }
}
