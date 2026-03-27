import java.sql.*;
public class TmpExactCountCurveKeys {
  public static void main(String[] args) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")) {
      String sql = "select sum(case when trade_content_text like '%\"PRICE_CURVE\"%' then 1 else 0 end), sum(case when trade_content_text like '%\"FIXING_CURVE\"%' then 1 else 0 end), sum(case when trade_content_text like '%\"REFERENCE_CURVE\"%' then 1 else 0 end) from MR_TRADE_INPUT";
      try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
        rs.next();
        System.out.println("PRICE="+rs.getInt(1)+"|FIXING="+rs.getInt(2)+"|REF="+rs.getInt(3));
      }
    }
  }
}
