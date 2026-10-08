import java.sql.*;

/**
 * 建一个用于验证「报表虚拟滚动」的测试查询 + 测试报表。
 * 用 generate_series 造 5 万行，直接命中「大数据量」这个真实场景。
 * 复用已有数据源 id=1（主库），不新建数据源（新建要处理密码加密，徒增变量）。
 */
public class SeedReport {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://192.168.3.160:5433/ifinbrain";
        String user = "ifinbrain";
        String pass = System.getenv("PGPW");
        String marker = "zrep_test_";
        long dsId = 1L;

        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement s = c.createStatement()) {

            // ---- 1. 测试查询：2 个数字参数 + 5 万行 ----
            String sql = "SELECT g AS id, "
                    + "       ('user_' || (g % 500)) AS username, "
                    + "       (g * 7 % 1000)::numeric(12,2) AS amount, "
                    + "       ('2024-01-01'::date + (g % 365)) AS order_date, "
                    + "       CASE WHEN g % 3 = 0 THEN 'PAID' WHEN g % 3 = 1 THEN 'PENDING' ELSE 'CANCELLED' END AS status "
                    + "FROM generate_series(1, 50000) g "
                    + "WHERE g >= :minId AND g <= :maxId";

            String params = "[{\"name\":\"minId\",\"label\":\"起始ID\",\"type\":\"number\",\"required\":true,\"defaultValue\":\"1\"},"
                    + "{\"name\":\"maxId\",\"label\":\"结束ID\",\"type\":\"number\",\"required\":true,\"defaultValue\":\"50000\"}]";

            long qid = -1;
            try (ResultSet r = s.executeQuery("SELECT id FROM sys_query WHERE name='" + marker + "q'")) {
                if (r.next()) qid = r.getLong(1);
            }
            if (qid < 0) {
                s.executeUpdate("INSERT INTO sys_query (name, description, datasource_id, sql_template, "
                        + "params_schema_json, status, created_by, created_at, updated_at) VALUES ('"
                        + marker + "q', '报表虚拟滚动验证用：5 万行 + 2 个数字参数', " + dsId + ", '"
                        + sql.replace("'", "''") + "', '" + params + "', 1, 'admin', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                try (ResultSet r = s.executeQuery("SELECT max(id) FROM sys_query")) { r.next(); qid = r.getLong(1); }
                System.out.println("query created id=" + qid);
            } else {
                System.out.println("query exists id=" + qid);
            }

            // ---- 2. 测试报表：pageSize=200, maxRows=50000 ----
            long rid = -1;
            try (ResultSet r = s.executeQuery("SELECT id FROM sys_report WHERE code='" + marker + "r'")) {
                if (r.next()) rid = r.getLong(1);
            }
            if (rid < 0) {
                String header = "{\"visible\":[\"minId\",\"maxId\"],\"collapsed\":false,\"columns\":4}";
                String defaults = "{\"minId\":\"1\",\"maxId\":\"50000\"}";
                s.executeUpdate("INSERT INTO sys_report (code, name, description, query_id, header_json, "
                        + "columns_json, default_params, page_size, max_rows, status, created_by, "
                        + "created_at, updated_at) VALUES ('" + marker + "r', '报表虚拟滚动验证', "
                        + "'测试用：查询头 2 个参数 + 5 万行虚拟滚动', " + qid + ", '" + header + "', "
                        + "'[]', '" + defaults + "', 200, 50000, 1, 'admin', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                try (ResultSet r = s.executeQuery("SELECT max(id) FROM sys_report")) { r.next(); rid = r.getLong(1); }
                System.out.println("report created id=" + rid);
            } else {
                System.out.println("report exists id=" + rid);
            }

            System.out.println("REPORT_ID=" + rid);
            System.out.println("QUERY_ID=" + qid);
        }
    }
}
