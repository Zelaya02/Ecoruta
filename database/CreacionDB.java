import java.sql.*;
import java.nio.file.*;
import java.util.List;
import java.util.ArrayList;

public class CreacionDB {
    public static void main(String[] args) {
        int port = 5432;
        String dbName = System.getenv().getOrDefault("DB_NAME", "ruteo_db");
        String user = System.getenv().getOrDefault("DB_USER", "postgres");
        String pass = System.getenv().getOrDefault("DB_PASSWORD", "Zelaya11");

        System.out.println("Iniciando creacion de Base de Datos '" + dbName + "'...");

        for (int p : new int[]{5000, 5432}) {
            try (Connection test = DriverManager.getConnection("jdbc:postgresql://localhost:" + p + "/postgres", user, pass)) {
                port = p;
                System.out.println("✅ Puerto detectado: " + port);
                break;
            } catch (Exception e) {}
        }

        try {
            String rootUrl = "jdbc:postgresql://localhost:" + port + "/postgres";
            try (Connection rootConn = DriverManager.getConnection(rootUrl, user, pass);
                 Statement stmt = rootConn.createStatement()) {

                // Sin DROP: solo crea si no existe, nunca borra datos existentes.
                ResultSet existe = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'");
                if (existe.next()) {
                    System.out.println("ℹ️  La base de datos '" + dbName + "' ya existe. Se conserva su contenido.");
                } else {
                    stmt.executeUpdate("CREATE DATABASE " + dbName);
                    System.out.println("✅ Base de datos '" + dbName + "' creada.");
                }
            }

            String dbUrl = "jdbc:postgresql://localhost:" + port + "/" + dbName;
            try (Connection conn = DriverManager.getConnection(dbUrl, user, pass);
                 Statement dbStmt = conn.createStatement()) {
                
                // --- Esquema completo (schema.sql) ---
                System.out.println("Aplicando esquema (schema.sql)...");
                Path schemaPath = Paths.get("schema.sql");
                if (Files.exists(schemaPath)) {
                    String schema = Files.readString(schemaPath);
                    ejecutarScript(dbStmt, schema);
                    System.out.println("✅ Esquema aplicado.");
                } else {
                    System.out.println("⚠️  No se encontro schema.sql. Se omite la creacion de tablas.");
                }

                // --- Clientes por defecto (solo si la tabla esta vacia: no duplicar) ---
                int clientesActuales = 0;
                try (ResultSet rc = dbStmt.executeQuery("SELECT COUNT(*) FROM clientes")) {
                    if (rc.next()) clientesActuales = rc.getInt(1);
                }
                Path sqlPath = Paths.get("import.sql");
                if (clientesActuales > 0) {
                    System.out.println("ℹ️  Ya hay " + clientesActuales + " clientes. Se omite la carga inicial (sin duplicar).");
                } else if (Files.exists(sqlPath)) {
                    System.out.println("Cargando clientes...");
                    List<String> lines = Files.readAllLines(sqlPath);
                    int count = 0;
                    for (String line : lines) {
                        if (!line.trim().isEmpty() && !line.startsWith("--") && !line.contains("TRUNCATE")) {
                            try {
                                dbStmt.executeUpdate(line);
                                count++;
                            } catch (SQLException e) {
                                System.err.println("   (omitido) " + e.getMessage());
                            }
                        }
                    }
                    System.out.println("✅ " + count + " clientes cargados.");

                    // --- DATOS DE PRUEBA PARA ESTADISTICAS ---
                    System.out.println("Migrando datos de ejemplo para estadisticas...");
                    dbStmt.executeUpdate("INSERT INTO rutas_generadas (token, movil_numero, chofer_nombre, vehiculo_nombre, clientes_json, distancia_total, tiempo_estimado) " +
                                       "VALUES ('TOKEN-PROCESADO', 1, 'Sin asignar', 'Sin asignar', '[]', 25.4, 60)");
                    
                    for (int i = 1; i <= 10; i++) {
                        String estado = (i % 4 == 0) ? "rechazado" : "entregado";
                        dbStmt.executeUpdate("INSERT INTO entregas (ruta_token, cliente_id, estado, observacion, orden_en_ruta) " +
                                           "VALUES ('TOKEN-PROCESADO', " + i + ", '" + estado + "', 'Entrega realizada con exito', " + i + ")");
                    }
                    System.out.println("✅ Datos de entregas migrados correctamente.");
                }

                System.out.println("\nPROCESO FINALIZADO");
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /** Ejecuta un script SQL separandolo por ';' (evita depender de multi-queries). */
    private static void ejecutarScript(Statement stmt, String script) throws SQLException {
        List<String> sentencias = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String linea : script.split("\n")) {
            String limpia = linea.trim();
            if (limpia.startsWith("--") || limpia.isEmpty()) {
                continue;
            }
            actual.append(linea).append("\n");
            if (limpia.endsWith(";")) {
                sentencias.add(actual.toString());
                actual.setLength(0);
            }
        }
        if (actual.toString().trim().length() > 0) {
            sentencias.add(actual.toString());
        }
        for (String sql : sentencias) {
            String s = sql.trim();
            if (s.length() == 0) continue;
            try {
                stmt.executeUpdate(s);
            } catch (SQLException e) {
                System.err.println("   (omitido) " + e.getMessage());
            }
        }
    }
}