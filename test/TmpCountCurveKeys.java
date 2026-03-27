import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class TmpCountCurveKeys {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "")) {
            String sql = "select product_type, count(*) total, "
                    + "sum(case when trade_content_text like '%PRICE_CURVE%' then 1 else 0 end) price_cnt, "
                    + "sum(case when trade_content_text like '%FIXING_CURVE%' then 1 else 0 end) fixing_cnt, "
                    + "sum(case when trade_content_text like '%REFERENCE_CURVE%' then 1 else 0 end) ref_cnt "
                    + "from MR_TRADE_INPUT group by product_type order by product_type";
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    System.out.println(rs.getString(1)
                            + "|TOTAL=" + rs.getInt(2)
                            + "|PRICE=" + rs.getInt(3)
                            + "|FIXING=" + rs.getInt(4)
                            + "|REF=" + rs.getInt(5));
                }
            }
        }
    }
}
