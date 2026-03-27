import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.sql.*;
public class TmpShowIrZeroTrades {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;ACCESS_MODE_DATA=r;DB_CLOSE_DELAY=-1";
    String sql = "select product_type, trade_content_text from MR_TRADE_INPUT where data_date='2025-12-31' and product_type in ('CAPFLOOR','SWAPTION','IRSCCS','STD_IRS') order by id";
    try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        JSONObject jo = JSON.parseObject(rs.getString(2));
        String p = rs.getString(1);
        System.out.println("TRADE|"+p+"|"+jo.getString("INSTRUMENT_ID")+"|CUR="+jo.getString("CURRENCY_CODE")+"|DISC="+jo.getString("DISCOUNT_CURVE")+"|REF="+jo.getString("REFERENCE_CURVE")+"|VOL="+jo.getString("VOLATILITY_SURFACE")+"|BUYSELL="+jo.getString("BUY_OR_SELL")+"|MAT="+jo.getString("MATURITY_DATE"));
      }
    }
  }
}
