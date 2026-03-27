import java.sql.*;
public class TmpShowTradeJsonKey2 {
  public static void main(String[] args) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")) {
      String sql = "select product_type, trade_id, trade_content_text from MR_TRADE_INPUT where product_type in ('IR_AUTO_CALL','STD_IRS','SWAPTION','COMM_AUTO_CALL','EQ_AUTO_CALL') order by product_type, trade_id";
      try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
        while (rs.next()) {
          String txt = rs.getString(3);
          System.out.println(rs.getString(1)+"|"+rs.getString(2)+"|"+txt.substring(0, Math.min(260, txt.length())));
        }
      }
    }
  }
}
