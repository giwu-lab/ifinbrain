import java.sql.*;

public class Dbq2 {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://192.168.3.160:5433/ifinbrain";
        String user = "ifinbrain";
        String pass = System.getenv("PGPW");
        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement s = c.createStatement()) {

            System.out.println("== sys_query 详情 ==");
            try (ResultSet r = s.executeQuery(
                    "SELECT id, name, datasource_id, sql_template, params_schema_json, status "
                  + "FROM sys_query ORDER BY id")) {
                while (r.next()) {
                    System.out.println("--- id=" + r.getInt(1) + " | " + r.getString(2)
                            + " | ds=" + r.getInt(3) + " | status=" + r.getObject(5));
                    System.out.println("SQL: " + r.getString(4));
                    System.out.println("PARAMS: " + r.getString(5));
                }
            }

            System.out.println("== 大表候选(用于虚拟滚动压测) ==");
            try (ResultSet r = s.executeQuery(
                    "SELECT relname, n_live_tup FROM pg_stat_user_tables "
                  + "WHERE n_live_tup > 1000 ORDER BY n_live_tup DESC LIMIT 6")) {
                while (r.next()) {
                    System.out.println("  " + r.getString(1) + " ≈ " + r.getLong(2) + " 行");
                }
            }
        }
    }
}
