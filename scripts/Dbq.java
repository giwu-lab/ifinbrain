import java.sql.*;

public class Dbq {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://192.168.3.160:5433/ifinbrain";
        String user = "ifinbrain";
        String pass = System.getenv("PGPW");
        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement s = c.createStatement()) {

            System.out.println("== flyway_schema_history (last 10) ==");
            try (ResultSet r = s.executeQuery(
                    "SELECT version, description, success FROM flyway_schema_history "
                  + "ORDER BY installed_rank DESC LIMIT 10")) {
                while (r.next()) {
                    System.out.println("v" + r.getString(1) + " | " + r.getString(2) + " | success=" + r.getBoolean(3));
                }
            }

            System.out.println("== 表是否存在 ==");
            for (String t : new String[]{"sys_report", "sys_bi_dashboard", "sys_query"}) {
                try (ResultSet r = s.executeQuery(
                        "SELECT to_regclass('public." + t + "') IS NOT NULL")) {
                    r.next();
                    System.out.println(t + " = " + r.getBoolean(1));
                }
            }

            System.out.println("== report 权限码 ==");
            try (ResultSet r = s.executeQuery(
                    "SELECT code FROM sys_permission WHERE code LIKE 'report:%' ORDER BY code")) {
                while (r.next()) System.out.println("  " + r.getString(1));
            }

            System.out.println("== /bi 下的菜单 ==");
            try (ResultSet r = s.executeQuery(
                    "SELECT m.path, m.component, m.sort FROM sys_menu m "
                  + "WHERE m.parent_id = (SELECT id FROM sys_menu WHERE path='/bi' AND type='DIR') "
                  + "ORDER BY m.sort")) {
                while (r.next()) {
                    System.out.println("  " + r.getString(1) + " | " + r.getString(2) + " | sort=" + r.getInt(3));
                }
            }

            System.out.println("== 可用于报表的查询(sys_query) ==");
            try (ResultSet r = s.executeQuery(
                    "SELECT id, name, status, length(coalesce(params_schema_json,'')) "
                  + "FROM sys_query ORDER BY id LIMIT 10")) {
                while (r.next()) {
                    System.out.println("  id=" + r.getInt(1) + " | " + r.getString(2)
                            + " | status=" + r.getObject(3) + " | schemaLen=" + r.getInt(4));
                }
            }
        }
    }
}
