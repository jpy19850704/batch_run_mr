import java.sql.*;
public class TmpListLegacyCurveKeys {
  public static void main(String[] args) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")) {
      String sql = "select product_type, trade_id, trade_content_text from MR_TRADE_INPUT where trade_content_text like '%\"PRICE_CURVE\"%' or trade_content_text like '%\"FIXING_CURVE\"%' order by product_type, trade_id";
      try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
        while (rs.next()) {
          String txt = rs.getString(3);
          System.out.println(rs.getString(1)+"|"+rs.getString(2)+"|"+txt);
        }
      }
    }
  }
}
