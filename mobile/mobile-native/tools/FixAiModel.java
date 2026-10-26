import java.sql.*;
public class FixAiModel {
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/kb", "kb", "kb");
             Statement s = c.createStatement()) {
            System.out.println("context_window: " + s.executeUpdate("UPDATE kb_ai_model SET context_window = 128000 WHERE context_window IS NULL"));
            System.out.println("vision: " + s.executeUpdate("UPDATE kb_ai_model SET vision = false WHERE vision IS NULL"));
        }
    }
}
