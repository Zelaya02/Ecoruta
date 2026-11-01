package com.ruteo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.UUID;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.ruteo.model.Usuario;
import com.ruteo.repository.UsuarioRepository;

public class Main {
    private static final Gson gson = new Gson();
    private static String DB_URL = "jdbc:postgresql://localhost:5000/ruteo_db"; // se auto-detecta al inicio
    private static String DB_USER = getEnvOrDefault("DB_USER", "postgres");
    private static String DB_PASSWORD = getEnvOrDefault("DB_PASSWORD", "Zelaya11");
    private static final String ORS_KEY = getEnvOrDefault("ORS_API_KEY", "");
    private static final String FRONTEND_DIR = getEnvOrDefault("FRONTEND_DIR", "../frontend");

    // Modelos de Reglas
    static class Regla {
        int id;
        String categoria;
        int limite_por_movil;
        boolean activo;

        Regla(int id, String categoria, int limite, boolean activo) {
            this.id = id;
            this.categoria = categoria;
            this.limite_por_movil = limite;
            this.activo = activo;
        }
    }

    static class UserSession {
        long creationTime;
        String username;
        String rol;
        int userId;
        Integer choferId;

        UserSession(long creationTime, String username, String rol, int userId) {
            this(creationTime, username, rol, userId, null);
        }

        UserSession(long creationTime, String username, String rol, int userId, Integer choferId) {
            this.creationTime = creationTime;
            this.username = username;
            this.rol = rol;
            this.userId = userId;
            this.choferId = choferId;
        }
    }

    private static UsuarioRepository usuarioRepo; // se inicializa después de auto-detectar puerto
    private static final Map<String, UserSession> activeTokens = new ConcurrentHashMap<>();
    private static final long TOKEN_TTL_MS = 8 * 60 * 60 * 1000; // 8 horas

    /** Parsea DATABASE_URL (formato Render o estandar de heroku) a JDBC */
    private static void parseDatabaseUrl() {
        String dbUrlEnv = System.getenv("DATABASE_URL");
        if (dbUrlEnv == null || dbUrlEnv.isEmpty()) {
            dbUrlEnv = System.getenv("DB_URL");
        }
        if (dbUrlEnv != null && !dbUrlEnv.isEmpty()) {
            try {
                if (dbUrlEnv.startsWith("postgres://") || dbUrlEnv.startsWith("postgresql://")) {
                    String cleanUrl = dbUrlEnv.substring(dbUrlEnv.indexOf("//") + 2);
                    String userInfo = "";
                    String hostPortDb = cleanUrl;
                    if (cleanUrl.contains("@")) {
                        int atIndex = cleanUrl.indexOf("@");
                        userInfo = cleanUrl.substring(0, atIndex);
                        hostPortDb = cleanUrl.substring(atIndex + 1);
                    }
                    
                    if (!userInfo.isEmpty() && userInfo.contains(":")) {
                        String[] parts = userInfo.split(":", 2);
                        DB_USER = java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8);
                        DB_PASSWORD = java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
                    }
                    
                    DB_URL = "jdbc:postgresql://" + hostPortDb;
                    System.out.println("✅ DATABASE_URL parseada correctamente (sslmode conservado si viene en la URL).");
                } else {
                    DB_URL = dbUrlEnv;
                }
            } catch (Exception e) {
                System.err.println("⚠️ Error al parsear DATABASE_URL, usando valores por defecto: " + e.getMessage());
            }
        } else {
            DB_URL = detectDbUrl();
        }
    }

    /** Inicializa el esquema de la base de datos si las tablas no existen */
    private static void initializeDatabaseSchema() {
        System.out.println("Comprobando esquema de base de datos...");
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement()) {
            
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS usuarios (" +
                    "id SERIAL PRIMARY KEY, " +
                    "username TEXT UNIQUE, " +
                    "password TEXT, " +
                    "nombre TEXT, " +
                    "rol TEXT, " +
                    "activo BOOLEAN DEFAULT true)");

            try { stmt.executeUpdate("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS activo BOOLEAN DEFAULT true"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS rol TEXT DEFAULT 'admin'"); } catch (SQLException ignored) {}
            
            // Rol unificado: admin -> gestor (misma cuenta admin/nexo2025, rol gestor).
            stmt.executeUpdate("UPDATE usuarios SET rol = 'gestor' WHERE rol = 'admin'");
            ResultSet rsAdmin = stmt.executeQuery("SELECT COUNT(*) FROM usuarios WHERE username = 'admin'");
            if (rsAdmin.next() && rsAdmin.getInt(1) == 0) {
                stmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES ('admin', 'nexo2025', 'Gestor de Rutas y Denuncias', 'gestor', true)");
                System.out.println("✅ Usuario gestor por defecto creado (admin/nexo2025, rol gestor).");
            } else {
                stmt.executeUpdate("UPDATE usuarios SET rol = 'gestor', activo = true WHERE username = 'admin'");
            }

            ResultSet rsSuper = stmt.executeQuery("SELECT COUNT(*) FROM usuarios WHERE username = 'superadmin'");
            if (rsSuper.next() && rsSuper.getInt(1) == 0) {
                stmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES ('superadmin', 'supernexo2025', 'Super Administrador', 'superadmin', true)");
                System.out.println("✅ Usuario super administrador por defecto creado (superadmin/supernexo2025).");
            }
            
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS clientes (" +
                    "id SERIAL PRIMARY KEY, " +
                    "nombre TEXT, " +
                    "tipo_cliente TEXT, " +
                    "latitud DOUBLE PRECISION, " +
                    "longitud DOUBLE PRECISION, " +
                    "ciudad TEXT, " +
                    "cadena TEXT, " +
                    "activo BOOLEAN DEFAULT true, " +
                    "url_google TEXT)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS reglas_ruteo (" +
                    "id SERIAL PRIMARY KEY, " +
                    "categoria TEXT UNIQUE, " +
                    "limite_por_movil INTEGER, " +
                    "activo BOOLEAN DEFAULT true)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS categorias (" +
                    "id SERIAL PRIMARY KEY, " +
                    "nombre TEXT, " +
                    "activo BOOLEAN DEFAULT true, " +
                    "usuario_id INTEGER DEFAULT 1)");
            try { stmt.executeUpdate("ALTER TABLE categorias DROP CONSTRAINT IF EXISTS categorias_nombre_key"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_categorias_nombre_usuario ON categorias (LOWER(nombre), usuario_id)"); } catch (SQLException ignored) {}

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS choferes (" +
                    "id SERIAL PRIMARY KEY, " +
                    "nombre TEXT, " +
                    "telefono TEXT, " +
                    "activo BOOLEAN DEFAULT true)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS vehiculos (" +
                    "id SERIAL PRIMARY KEY, " +
                    "nombre TEXT, " +
                    "chapa TEXT, " +
                    "tipo TEXT DEFAULT 'camion mediano', " +
                    "activo BOOLEAN DEFAULT true)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS rutas_generadas (" +
                    "token TEXT PRIMARY KEY, " +
                    "movil_numero INTEGER, " +
                    "clientes_json TEXT, " +
                    "distancia_total DOUBLE PRECISION, " +
                    "tiempo_estimado INTEGER, " +
                    "chofer_id INTEGER, " +
                    "vehiculo_id INTEGER, " +
                    "chofer_nombre TEXT, " +
                    "vehiculo_nombre TEXT, " +
                    "fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "estado TEXT DEFAULT 'en_curso')");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS entregas (" +
                    "id SERIAL PRIMARY KEY, " +
                    "ruta_token TEXT, " +
                    "cliente_id INTEGER, " +
                    "estado TEXT DEFAULT 'pendiente', " +
                    "observacion TEXT, " +
                    "orden_en_ruta INTEGER, " +
                    "fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            // Modificaciones para asegurar que columnas nuevas existan en BD viejas
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS estado TEXT DEFAULT 'en_curso'"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS chofer_id INTEGER"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS vehiculo_id INTEGER"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS chofer_nombre TEXT"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS vehiculo_nombre TEXT"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE entregas ADD COLUMN IF NOT EXISTS fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE clientes ADD COLUMN IF NOT EXISTS url_google TEXT"); } catch (SQLException ignored) {}
            
            // Columnas Multi-inquilino
            try { stmt.executeUpdate("ALTER TABLE clientes ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE choferes ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE vehiculos ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE reglas_ruteo ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE categorias ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1"); } catch (SQLException ignored) {}

            // Claves foráneas (Foreign Keys)
            try { stmt.executeUpdate("ALTER TABLE clientes ADD CONSTRAINT fk_clientes_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE choferes ADD CONSTRAINT fk_choferes_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE vehiculos ADD CONSTRAINT fk_vehiculos_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE reglas_ruteo ADD CONSTRAINT fk_reglas_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE categorias ADD CONSTRAINT fk_categorias_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD CONSTRAINT fk_rutas_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD CONSTRAINT fk_rutas_chofer FOREIGN KEY (chofer_id) REFERENCES choferes(id) ON DELETE SET NULL"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE rutas_generadas ADD CONSTRAINT fk_rutas_vehiculo FOREIGN KEY (vehiculo_id) REFERENCES vehiculos(id) ON DELETE SET NULL"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE entregas ADD CONSTRAINT fk_entregas_ruta FOREIGN KEY (ruta_token) REFERENCES rutas_generadas(token) ON DELETE CASCADE"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE entregas ADD CONSTRAINT fk_entregas_cliente FOREIGN KEY (cliente_id) REFERENCES clientes(id) ON DELETE CASCADE"); } catch (SQLException ignored) {}

            // ---- Usuarios por rol unificado (idempotente) ----
            try {
                stmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES ('gestor', 'gestor2026', 'Gestor de Rutas y Denuncias', 'gestor', true) ON CONFLICT (username) DO NOTHING");
                stmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES ('ciudadano', 'ciudadano2026', 'Ciudadano Demo', 'ciudadano', true) ON CONFLICT (username) DO NOTHING");
            } catch (SQLException ignored) {}

            // ---- Tabla denuncias ciudadanas (port recoleccion-basura-app) ----
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS denuncias (" +
                    "id SERIAL PRIMARY KEY, " +
                    "ticket TEXT UNIQUE NOT NULL, " +
                    "nombre_ciudadano TEXT, " +
                    "telefono TEXT, " +
                    "descripcion TEXT NOT NULL, " +
                    "categoria TEXT DEFAULT 'VERTEDERO_CLANDESTINO', " +
                    "barrio TEXT NOT NULL, " +
                    "direccion_referencia TEXT, " +
                    "latitud DOUBLE PRECISION NOT NULL, " +
                    "longitud DOUBLE PRECISION NOT NULL, " +
                    "foto_url TEXT, " +
                    "estado TEXT DEFAULT 'pendiente', " +
                    "usuario_id INTEGER, " +
                    "cliente_id INTEGER, " +
                    "ruta_token TEXT, " +
                    "observacion_cierre TEXT, " +
                    "fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "fecha_cierre TIMESTAMP)");
            try { stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_denuncias_estado ON denuncias(estado)"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_denuncias_barrio ON denuncias(barrio)"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_denuncias_ticket ON denuncias(ticket)"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE denuncias ADD CONSTRAINT fk_denuncias_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE SET NULL"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE denuncias ADD CONSTRAINT fk_denuncias_cliente FOREIGN KEY (cliente_id) REFERENCES clientes(id) ON DELETE SET NULL"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE denuncias ADD CONSTRAINT fk_denuncias_ruta FOREIGN KEY (ruta_token) REFERENCES rutas_generadas(token) ON DELETE SET NULL"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS chofer_id INTEGER"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE denuncias ADD COLUMN IF NOT EXISTS chofer_id INTEGER"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE denuncias ADD COLUMN IF NOT EXISTS cuadrilla TEXT"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE denuncias ADD COLUMN IF NOT EXISTS asignado_a TEXT"); } catch (SQLException ignored) {}
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS alertas_operativas (" +
                    "id SERIAL PRIMARY KEY, " +
                    "tipo TEXT NOT NULL, " +
                    "descripcion TEXT, " +
                    "chofer_id INTEGER, " +
                    "chofer_nombre TEXT, " +
                    "denuncia_id INTEGER, " +
                    "denuncia_ticket TEXT, " +
                    "latitud DOUBLE PRECISION, " +
                    "longitud DOUBLE PRECISION, " +
                    "estado TEXT DEFAULT 'activa', " +
                    "fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            System.out.println("✅ Esquema de base de datos verificado/creado con relaciones.");
            
            // Clientes de ejemplo omitidos: la base de datos se mantiene limpia para datos reales
            System.out.println("ℹ️ Inicialización de clientes de ejemplo deshabilitada.");
        } catch (SQLException e) {
            System.err.println("❌ Error al inicializar el esquema de base de datos: " + e.getMessage());
        }
    }

    private static void loadDefaultClients(Connection conn) {
        String[] paths = {"database/import.sql", "import.sql", "../database/import.sql"};
        File sqlFile = null;
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) {
                sqlFile = f;
                break;
            }
        }
        
        if (sqlFile == null) {
            System.out.println("ℹ️ Archivo import.sql no encontrado. No se inicializaron clientes por defecto.");
            return;
        }

        System.out.println("Cargando clientes por defecto desde " + sqlFile.getPath() + "...");
        try (BufferedReader reader = new BufferedReader(new FileReader(sqlFile, StandardCharsets.UTF_8));
             Statement stmt = conn.createStatement()) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty() && !line.startsWith("--") && !line.contains("TRUNCATE")) {
                    try {
                        stmt.executeUpdate(line);
                        count++;
                    } catch (SQLException e) {
                        // Ignorar errores individuales (como duplicados)
                    }
                }
            }
            System.out.println("✅ " + count + " clientes cargados exitosamente.");
        } catch (Exception e) {
            System.err.println("⚠️ Error al cargar los clientes por defecto: " + e.getMessage());
        }
    }

    private static String getEnvOrDefault(String key, String def) {
        String val = System.getenv(key);
        return val != null && !val.isEmpty() ? val : def;
    }

    /** Detecta el puerto PostgreSQL disponible y asegura que ruteo_db exista. */
    private static String detectDbUrl() {
        int[] puertos = {5432, 5000};
        
        // 1. Intentar asegurar que ruteo_db exista conectando a la BD sistema postgres
        for (int p : puertos) {
            String sysUrl = "jdbc:postgresql://localhost:" + p + "/postgres";
            try (Connection c = DriverManager.getConnection(sysUrl, DB_USER, DB_PASSWORD);
                 Statement stmt = c.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'ruteo_db'");
                if (!rs.next()) {
                    stmt.executeUpdate("CREATE DATABASE ruteo_db");
                    System.out.println("✅ Base de datos 'ruteo_db' creada automáticamente en puerto " + p);
                }
                String dbUrl = "jdbc:postgresql://localhost:" + p + "/ruteo_db";
                System.out.println("✅ Conectado a PostgreSQL en: " + dbUrl);
                return dbUrl;
            } catch (SQLException ignored) {}
        }
        
        // 2. Probador directo si la BD ya existia
        for (int p : puertos) {
            String url = "jdbc:postgresql://localhost:" + p + "/ruteo_db";
            try (Connection c = DriverManager.getConnection(url, DB_USER, DB_PASSWORD)) {
                System.out.println("✅ Conectado a PostgreSQL en: " + url);
                return url;
            } catch (SQLException e) {
                System.out.println("⚠️  Puerto no disponible: " + url + " (" + e.getMessage().split("\n")[0] + ")");
            }
        }
        System.err.println("❌ No se pudo conectar a PostgreSQL en ningún puerto conocido.");
        return "jdbc:postgresql://localhost:5432/ruteo_db"; // fallback
    }

    public static void main(String[] args) throws IOException {
        // Auto-detección del puerto de PostgreSQL
        parseDatabaseUrl();
        initializeDatabaseSchema();

        if (System.getenv("DB_PASSWORD") == null || System.getenv("DB_PASSWORD").isEmpty()) {
            System.out.println("⚠️  ADVERTENCIA: Usando contraseña por defecto. Configure DB_PASSWORD como variable de entorno para producción.");
        }
        if (System.getenv("ORS_API_KEY") == null || System.getenv("ORS_API_KEY").isEmpty()) {
            System.out.println("ℹ️  ORS_API_KEY no configurada. Las distancias usarán Haversine (estimación lineal).");
        }

        usuarioRepo = new UsuarioRepository(DB_URL, DB_USER, DB_PASSWORD);

        int port = Integer.parseInt(getEnvOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        // Archivos estaticos
        server.createContext("/", new StaticHandler());

        // Configurar API
        server.createContext("/api/clientes", new ClientesHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/generar-rutas", new RutasHandler());
        server.createContext("/api/asignar-recursos", new AsignarRecursosHandler());
        server.createContext("/api/ruta", new RutaTokenHandler());
        server.createContext("/api/actualizar-estado", new EstadoHandler());
        server.createContext("/api/finalizar-ruta", new FinalizarRutaHandler());
        server.createContext("/api/rutas-todas", new RutasTodasHandler());
        server.createContext("/api/estadisticas", new EstadisticasHandler());
        server.createContext("/api/reportes", new ReportesHandler());
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/reglas", new ReglasHandler());
        server.createContext("/api/choferes", new ChoferesHandler());
        server.createContext("/api/vehiculos", new VehiculosHandler());
        server.createContext("/api/categorias", new CategoriasHandler());
        server.createContext("/api/kml/importar", new KmlImportHandler());
        server.createContext("/api/kml/exportar", new KmlExportHandler());
        server.createContext("/api/admin/usuarios", new AdminUsuariosHandler());
        server.createContext("/api/denuncias", new DenunciasHandler());
        server.createContext("/api/denuncias/asignar-punto", new DenunciasAsignarPuntoHandler());
        server.createContext("/api/alertas", new AlertasHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("🚀 Servidor iniciado en el puerto: " + port);
        System.out.println("Presiona Ctrl+C para detener");
    }

    /** Resuelve un archivo estatico: 1) dentro de FRONTEND_DIR, 2) muni-demo
     *  hermano del frontend (layout del repo en desarrollo local). Devuelve
     *  null si queda fuera de las raices permitidas. */
    private static File resolveStaticFile(String path) throws IOException {
        String rel = path.startsWith("/") ? path.substring(1) : path;
        File frontendRoot = new File(FRONTEND_DIR).getCanonicalFile();
        File candidate = new File(frontendRoot, rel);
        boolean inFrontend = candidate.getCanonicalPath().startsWith(frontendRoot.getCanonicalPath());
        if (inFrontend && candidate.exists()) {
            return candidate;
        }
        if (rel.startsWith("muni-demo/")) {
            File repoSibling = new File(frontendRoot.getParentFile(), rel).getCanonicalFile();
            File repoRoot = frontendRoot.getParentFile().getCanonicalFile();
            if (repoSibling.getCanonicalPath().startsWith(repoRoot.getCanonicalPath()) && repoSibling.exists()) {
                return repoSibling;
            }
        }
        // Si no se encuentra en frontend, buscar en muni-demo (util cuando muni-demo es la landing en /)
        File inMuniInside = new File(new File(frontendRoot, "muni-demo"), rel).getCanonicalFile();
        if (inMuniInside.getCanonicalPath().startsWith(frontendRoot.getCanonicalPath()) && inMuniInside.exists()) {
            return inMuniInside;
        }
        File repoRoot = frontendRoot.getParentFile().getCanonicalFile();
        File inMuniSibling = new File(new File(repoRoot, "muni-demo"), rel).getCanonicalFile();
        if (inMuniSibling.getCanonicalPath().startsWith(repoRoot.getCanonicalPath()) && inMuniSibling.exists()) {
            return inMuniSibling;
        }
        return inFrontend ? candidate : null;
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                // Puerta de entrada: portal municipal (muni-demo). Si no existe
                // (desarrollo local sin copiar), cae al panel como antes.
                File portal = resolveStaticFile("/muni-demo/index.html");
                path = (portal != null && portal.exists()) ? "/muni-demo/index.html" : "/index.html";
            }
            path = path.replaceAll("\\.\\./", "").replaceAll("\\.\\.", "").replaceAll("//+", "/");
            if (path.contains("..") || path.contains("%") || path.contains(":") || path.contains("~")) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            File file = resolveStaticFile(path);
            if (file == null) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            String canonicalPath = file.getCanonicalPath();
            if (file.exists() && !file.isDirectory()) {
                String contentType = "text/html";
                if (path.endsWith(".css"))
                    contentType = "text/css";
                else if (path.endsWith(".js"))
                    contentType = "application/javascript";
                else if (path.endsWith(".png"))
                    contentType = "image/png";
                else if (path.endsWith(".jpg") || path.endsWith(".jpeg"))
                    contentType = "image/jpeg";
                else if (path.endsWith(".gif"))
                    contentType = "image/gif";
                else if (path.endsWith(".svg"))
                    contentType = "image/svg+xml";
                else if (path.endsWith(".ico"))
                    contentType = "image/x-icon";

                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, file.length());
                try (OutputStream os = exchange.getResponseBody();
                        FileInputStream fs = new FileInputStream(file)) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = fs.read(buffer)) != -1) {
                        os.write(buffer, 0, count);
                    }
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    static class ClientesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) {
                sendError(exchange, 401, "No autorizado");
                return;
            }

            boolean manager = canManageRutas(exchange);

            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = manager
                            ? "SELECT * FROM clientes WHERE activo = true ORDER BY id DESC"
                            : "SELECT * FROM clientes WHERE activo = true AND usuario_id = ? ORDER BY id DESC";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();

                    List<Map<String, Object>> clientes = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> cliente = new HashMap<>();
                        cliente.put("id", rs.getInt("id"));
                        cliente.put("nombre", rs.getString("nombre"));
                        cliente.put("direccion", rs.getString("ciudad"));
                        cliente.put("tipo_cliente", rs.getString("tipo_cliente"));
                        cliente.put("latitud", rs.getDouble("latitud"));
                        cliente.put("longitud", rs.getDouble("longitud"));
                        cliente.put("cadena", rs.getString("cadena"));
                        cliente.put("url_google", rs.getString("url_google"));
                        cliente.put("seleccionado", false);
                        clientes.add(cliente);
                    }
                    sendResponse(exchange, 200, gson.toJson(clientes));
                } catch (SQLException e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error de base de datos");
                }
            } else if ("POST".equals(exchange.getRequestMethod()) || "PUT".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);

                    String nombre = (String) req.get("nombre");
                    String tipo = (String) req.get("tipo_cliente");
                    String url = (String) req.get("url");
                    String cadena = (String) req.get("cadena");

                    double lat = 0, lon = 0;
                    if (url != null && !url.isEmpty()) {
                        double[] coords = parseGoogleMapsUrl(url);
                        lat = coords[0];
                        lon = coords[1];
                    } else if (req.containsKey("latitud")) {
                        lat = (Double) req.get("latitud");
                        lon = (Double) req.get("longitud");
                    }

                    String ciudad = determinarCiudad(lat, lon);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        if ("POST".equals(exchange.getRequestMethod())) {
                            String sql = "INSERT INTO clientes (nombre, tipo_cliente, latitud, longitud, ciudad, cadena, activo, usuario_id) VALUES (?, ?, ?, ?, ?, ?, true, ?) RETURNING id";
                            PreparedStatement pstmt = conn.prepareStatement(sql);
                            pstmt.setString(1, nombre);
                            pstmt.setString(2, tipo);
                            pstmt.setDouble(3, lat);
                            pstmt.setDouble(4, lon);
                            pstmt.setString(5, ciudad);
                            pstmt.setString(6, cadena);
                            pstmt.setInt(7, userId);
                            ResultSet rsId = pstmt.executeQuery();
                            int newId = rsId.next() ? rsId.getInt(1) : 0;
                            Map<String, Object> respCli = new HashMap<>();
                            respCli.put("status", "created");
                            respCli.put("id", newId);
                            sendResponse(exchange, 201, gson.toJson(respCli));
                        } else {
                            int id = ((Double) req.get("id")).intValue();
                            String sql = manager
                                    ? "UPDATE clientes SET nombre=?, tipo_cliente=?, latitud=?, longitud=?, ciudad=?, cadena=? WHERE id=?"
                                    : "UPDATE clientes SET nombre=?, tipo_cliente=?, latitud=?, longitud=?, ciudad=?, cadena=? WHERE id=? AND usuario_id=?";
                            PreparedStatement pstmt = conn.prepareStatement(sql);
                            pstmt.setString(1, nombre);
                            pstmt.setString(2, tipo);
                            pstmt.setDouble(3, lat);
                            pstmt.setDouble(4, lon);
                            pstmt.setString(5, ciudad);
                            pstmt.setString(6, cadena);
                            pstmt.setInt(7, id);
                            if (!manager) {
                                pstmt.setInt(8, userId);
                            }
                            pstmt.executeUpdate();
                            sendResponse(exchange, 200, "{\"status\":\"updated\"}");
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    if (query == null || !query.contains("id=")) {
                        sendError(exchange, 400, "ID requerido");
                        return;
                    }
                    int id = Integer.parseInt(query.split("id=")[1].split("&")[0]);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = manager
                                ? "UPDATE clientes SET activo = false WHERE id = ?"
                                : "UPDATE clientes SET activo = false WHERE id = ? AND usuario_id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setInt(1, id);
                        if (!manager) {
                            pstmt.setInt(2, userId);
                        }
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class RutasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));

                    Map<String, Object> request = gson.fromJson(body, Map.class);
                    System.out.println("Generando rutas con: " + body);

                    Integer userId = getUserIdFromSession(exchange);
                    if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }

                    List<Double> cIds = (List<Double>) request.get("cliente_ids");
                    Object numMovilesObj = request.get("num_moviles");
                    int numMoviles = 1;
                    if (numMovilesObj instanceof Double)
                        numMoviles = ((Double) numMovilesObj).intValue();
                    else if (numMovilesObj instanceof Integer)
                        numMoviles = (Integer) numMovilesObj;

                    if (cIds == null || cIds.isEmpty()) {
                        sendError(exchange, 400, "cliente_ids es requerido");
                        return;
                    }
                    List<Integer> clienteIds = cIds.stream().map(Double::intValue).collect(Collectors.toList());

                    List<Cliente> clientes = new ArrayList<>();
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String placeholders = clienteIds.stream().map(id -> "?").collect(Collectors.joining(","));
                        String sql = "SELECT id, nombre, latitud, longitud, tipo_cliente FROM clientes WHERE activo = true AND id IN ("
                                + placeholders + ")";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        for (int i = 0; i < clienteIds.size(); i++) {
                            pstmt.setInt(i + 1, clienteIds.get(i));
                        }
                        ResultSet rs = pstmt.executeQuery();
                        while (rs.next()) {
                            clientes.add(new Cliente(rs.getInt("id"), rs.getString("nombre"), rs.getDouble("latitud"),
                                    rs.getDouble("longitud"), rs.getString("tipo_cliente")));
                        }
                    }

                    String prioridad = (String) request.getOrDefault("prioridad", "ninguna");
                    Object usarReglasObj = request.get("usar_reglas");
                    boolean usarReglas = true;
                    if (usarReglasObj instanceof Boolean)
                        usarReglas = (Boolean) usarReglasObj;

                    // Obtener reglas activas (solo si el usuario quiere aplicarlas)
                    Map<String, Integer> reglasActivas = new HashMap<>();
                    if (usarReglas) {
                        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                            boolean manager = canManageRutas(exchange);
                            String sqlReg = manager
                                    ? "SELECT categoria, limite_por_movil FROM reglas_ruteo WHERE activo = true"
                                    : "SELECT categoria, limite_por_movil FROM reglas_ruteo WHERE activo = true AND usuario_id = ?";
                            PreparedStatement stmtReg = conn.prepareStatement(sqlReg);
                            if (!manager) {
                                stmtReg.setInt(1, userId);
                            }
                            ResultSet rsReg = stmtReg.executeQuery();
                            while (rsReg.next()) {
                                reglasActivas.put(rsReg.getString("categoria").toLowerCase(),
                                        rsReg.getInt("limite_por_movil"));
                            }
                        }
                    }

                    // K-Means adaptado con REGLAS
                    List<List<Cliente>> clusters = kmeans(clientes, numMoviles, reglasActivas);

                    List<Map<String, Object>> movilesRespuesta = new ArrayList<>();

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        for (int i = 0; i < clusters.size(); i++) {
                            List<Cliente> cluster = clusters.get(i);
                            if (cluster.isEmpty())
                                continue;

                            // Vecino mas cercano para ordenar con prioridad
                            List<Cliente> ordenados = nearestNeighborWithPriority(cluster, prioridad);

                            // PREPARE COORDINATE PAIRS FOR PARALLEL DISTANCE CALCULATION
                            List<double[]> coordinatePairs = new ArrayList<>();
                            for (int j = 0; j < ordenados.size() - 1; j++) {
                                Cliente c = ordenados.get(j);
                                Cliente siguiente = ordenados.get(j + 1);
                                coordinatePairs.add(new double[]{c.lat, c.lon, siguiente.lat, siguiente.lon});
                            }

                            // PARALLEL DISTANCE CALCULATION USING CompletableFuture
                            List<RouteService.RouteInfo> distances = new ArrayList<>();
                            if (!coordinatePairs.isEmpty()) {
                                List<CompletableFuture<RouteService.RouteInfo>> futures = coordinatePairs.stream()
                                    .map(pair -> CompletableFuture.supplyAsync(() -> {
                                        try {
                                            return RouteService.getRoute(pair[0], pair[1], pair[2], pair[3]);
                                        } catch (Exception e) {
                                            double dist = haversine(pair[0], pair[1], pair[2], pair[3]) * 1.3;
                                            return new RouteService.RouteInfo(dist, 0);
                                        }
                                    }, ForkJoinPool.commonPool()))
                                    .collect(Collectors.toList());
                                
                                // Wait for all to complete
                                distances = futures.stream()
                                    .map(CompletableFuture::join)
                                    .collect(Collectors.toList());
                            }

                            double distTotal = 0;
                            List<Map<String, Object>> clientesJson = new ArrayList<>();
                            for (int j = 0; j < ordenados.size(); j++) {
                                Cliente c = ordenados.get(j);
                                double dist = 0;
                                if (j < ordenados.size() - 1) {
                                    RouteService.RouteInfo info = distances.get(j);
                                    dist = info.distanceKm;
                                    distTotal += dist;
                                }
                                Map<String, Object> cmap = new HashMap<>();
                                cmap.put("id", c.id);
                                cmap.put("nombre", c.nombre);
                                cmap.put("latitud", c.lat);
                                cmap.put("longitud", c.lon);
                                cmap.put("distancia_siguiente", Math.round(dist * 100.0) / 100.0);
                                boolean esDen = (c.tipo != null && "denuncia".equalsIgnoreCase(c.tipo))
                                        || (c.nombre != null && c.nombre.toLowerCase().startsWith("denuncia"));
                                if (esDen) {
                                    cmap.put("es_denuncia", true);
                                    cmap.put("tipo", "denuncia");
                                }
                                clientesJson.add(cmap);
                            }

                            distTotal = Math.round(distTotal * 100.0) / 100.0;
                            int tiempoEstimado = (int) Math.ceil((distTotal / 40.0) * 60.0);
                            String token = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

                            // Buscar asignación para este móvil
                            Integer choferId = null;
                            Integer vehiculoId = null;
                            String choferNombre = "";
                            String vehiculoNombre = "";

                            if (request.containsKey("asignaciones")) {
                                List<Map<String, Object>> asigs = (List<Map<String, Object>>) request.get("asignaciones");
                                for (Map<String, Object> asig : asigs) {
                                    Object movObj = asig.get("movil");
                                    int movNum = 0;
                                    if (movObj instanceof Double) movNum = ((Double) movObj).intValue();
                                    else if (movObj instanceof Integer) movNum = (Integer) movObj;

                                    if (movNum == i + 1) {
                                        Object cId = asig.get("chofer_id");
                                        Object vId = asig.get("vehiculo_id");
                                        if (cId != null) choferId = ((Double) cId).intValue();
                                        if (vId != null) vehiculoId = ((Double) vId).intValue();
                                        
                                        // Buscar nombres
                                        if (choferId != null) {
                                            try (PreparedStatement pName = conn.prepareStatement("SELECT nombre FROM choferes WHERE id = ?")) {
                                                pName.setInt(1, choferId);
                                                ResultSet rsName = pName.executeQuery();
                                                if (rsName.next()) choferNombre = rsName.getString("nombre");
                                            }
                                        }
                                        if (vehiculoId != null) {
                                            try (PreparedStatement pName = conn.prepareStatement("SELECT nombre FROM vehiculos WHERE id = ?")) {
                                                pName.setInt(1, vehiculoId);
                                                ResultSet rsName = pName.executeQuery();
                                                if (rsName.next()) vehiculoNombre = rsName.getString("nombre");
                                            }
                                        }
                                        break;
                                    }
                                }
                            }

                            // Insertar ruta
                            String insertRuta = "INSERT INTO rutas_generadas (token, movil_numero, clientes_json, distancia_total, tiempo_estimado, chofer_id, vehiculo_id, chofer_nombre, vehiculo_nombre, usuario_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                            PreparedStatement pstmt = conn.prepareStatement(insertRuta);
                            pstmt.setString(1, token);
                            pstmt.setInt(2, i + 1);
                            pstmt.setString(3, gson.toJson(clientesJson));
                            pstmt.setDouble(4, distTotal);
                            pstmt.setInt(5, tiempoEstimado);
                            if (choferId != null) pstmt.setInt(6, choferId); else pstmt.setNull(6, java.sql.Types.INTEGER);
                            if (vehiculoId != null) pstmt.setInt(7, vehiculoId); else pstmt.setNull(7, java.sql.Types.INTEGER);
                            pstmt.setString(8, choferNombre);
                            pstmt.setString(9, vehiculoNombre);
                            pstmt.setInt(10, userId);
                            pstmt.executeUpdate();

                            // Insertar entregas
                            String insertEntrega = "INSERT INTO entregas (ruta_token, cliente_id, estado, orden_en_ruta) VALUES (?, ?, 'pendiente', ?)";
                            PreparedStatement pstmt2 = conn.prepareStatement(insertEntrega);
                            for (int j = 0; j < ordenados.size(); j++) {
                                pstmt2.setString(1, token);
                                pstmt2.setInt(2, ordenados.get(j).id);
                                pstmt2.setInt(3, j + 1);
                                pstmt2.executeUpdate();
                            }

                            // Conexion denuncias -> ruta: si un punto de la ruta viene de una
                            // denuncia pendiente, pasa a en_proceso y queda vinculada al token.
                            // Tambien cubre denuncias del mismo barrio ya validadas como cliente.
                            try {
                                List<Integer> idsRuta = ordenados.stream().map(c -> c.id).collect(Collectors.toList());
                                String ph = idsRuta.stream().map(x -> "?").collect(Collectors.joining(","));
                                String sqlDen = "UPDATE denuncias SET estado = 'en_proceso', ruta_token = ? WHERE cliente_id IN (" + ph + ") AND estado IN ('pendiente', 'en_proceso') AND (ruta_token IS NULL OR ruta_token = '')";
                                PreparedStatement pDen = conn.prepareStatement(sqlDen);
                                pDen.setString(1, token);
                                for (int k = 0; k < idsRuta.size(); k++) pDen.setInt(k + 2, idsRuta.get(k));
                                int nDen = pDen.executeUpdate();
                                if (nDen > 0) System.out.println("📌 " + nDen + " denuncia(s) -> en_proceso por ruta " + token);
                            } catch (Exception exDen) {
                                System.err.println("⚠️ No se pudo vincular denuncias a ruta " + token + ": " + exDen.getMessage());
                            }

                            Map<String, Object> movil = new HashMap<>();
                            movil.put("movil", i + 1);
                            movil.put("token", token);
                            movil.put("clientes", clientesJson);
                            movil.put("distancia_total", distTotal);
                            movil.put("tiempo_estimado", tiempoEstimado);
                            movil.put("chofer_id", choferId);
                            movil.put("vehiculo_id", vehiculoId);
                            movil.put("chofer_nombre", choferNombre);
                            movil.put("vehiculo_nombre", vehiculoNombre);
                            movilesRespuesta.add(movil);
                        }
                    }

                    Map<String, Object> respuestaFinal = new HashMap<>();
                    respuestaFinal.put("moviles", movilesRespuesta);
                    sendResponse(exchange, 200, gson.toJson(respuestaFinal));

                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error generando rutas");
                }
            }
        }
    }

    static class RutaTokenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod()))
                return;

            if ("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String token = null;
                Integer choferId = null;

                if (query != null && query.contains("token=")) {
                    token = query.split("token=")[1].split("&")[0];
                }
                if (query != null && query.contains("chofer_id=")) {
                    try {
                        choferId = Integer.parseInt(query.split("chofer_id=")[1].split("&")[0]);
                    } catch (Exception ignored) {}
                }

                // Si no hay token explicito ni chofer_id en URL, detectar chofer por sesion
                if ((token == null || token.isEmpty()) && choferId == null) {
                    choferId = getChoferIdFromSession(exchange);
                    if (choferId == null) {
                        String uName = getUsernameFromSession(exchange);
                        if (uName != null) {
                            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                                try (PreparedStatement psC = conn.prepareStatement("SELECT id FROM choferes WHERE LOWER(nombre) = LOWER(?) OR LOWER(nombre) LIKE LOWER(?) LIMIT 1")) {
                                    psC.setString(1, uName);
                                    psC.setString(2, "%" + uName + "%");
                                    ResultSet rsC = psC.executeQuery();
                                    if (rsC.next()) choferId = rsC.getInt(1);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    PreparedStatement pstmt;
                    if (token != null && !token.isEmpty()) {
                        String sql = "SELECT * FROM rutas_generadas WHERE token = ?";
                        pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, token);
                    } else if (choferId != null) {
                        String sql = "SELECT * FROM rutas_generadas WHERE chofer_id = ? AND estado = 'en_curso' ORDER BY fecha DESC LIMIT 1";
                        pstmt = conn.prepareStatement(sql);
                        pstmt.setInt(1, choferId);
                    } else {
                        sendError(exchange, 400, "Token o chofer_id requerido");
                        return;
                    }

                    ResultSet rs = pstmt.executeQuery();
                    // Si se busca por chofer y no hay ruta en_curso, buscar la ultima (incluso finalizada)
                    if (!rs.next() && choferId != null && (token == null || token.isEmpty())) {
                        String sqlLast = "SELECT * FROM rutas_generadas WHERE chofer_id = ? ORDER BY fecha DESC LIMIT 1";
                        PreparedStatement pstmtLast = conn.prepareStatement(sqlLast);
                        pstmtLast.setInt(1, choferId);
                        rs = pstmtLast.executeQuery();
                    }

                    if (rs != null && (rs.isFirst() || rs.next())) {
                        String actualToken = rs.getString("token");
                        java.sql.Timestamp fechaRuta = rs.getTimestamp("fecha");
                        if (fechaRuta != null
                                && (System.currentTimeMillis() - fechaRuta.getTime()) > 7L * 24 * 60 * 60 * 1000) {
                            sendError(exchange, 403, "Esta ruta ha expirado.");
                            return;
                        }

                        Map<String, Object> ruta = new HashMap<>();
                        ruta.put("token", actualToken);
                        ruta.put("movil", rs.getInt("movil_numero"));
                        ruta.put("distancia_total", rs.getDouble("distancia_total"));
                        ruta.put("tiempo_estimado", rs.getInt("tiempo_estimado"));
                        String estadoRuta = rs.getString("estado");
                        ruta.put("estado", estadoRuta != null ? estadoRuta : "en_curso");
                        ruta.put("finalizada", "finalizada".equalsIgnoreCase(estadoRuta));
                        ruta.put("chofer_id", rs.getInt("chofer_id"));
                        ruta.put("vehiculo_id", rs.getInt("vehiculo_id"));
                        ruta.put("chofer_nombre", rs.getString("chofer_nombre"));
                        ruta.put("vehiculo_nombre", rs.getString("vehiculo_nombre"));

                        List<Map<String, Object>> clientes = gson.fromJson(rs.getString("clientes_json"), List.class);
                        if (clientes == null) clientes = new ArrayList<>();

                        // Agregar estados actuales de entregas
                        String sql2 = "SELECT cliente_id, estado, observacion FROM entregas WHERE ruta_token = ?";
                        PreparedStatement pstmt2 = conn.prepareStatement(sql2);
                        pstmt2.setString(1, actualToken);
                        ResultSet rs2 = pstmt2.executeQuery();
                        Map<Integer, String> estados = new HashMap<>();
                        Map<Integer, String> obs = new HashMap<>();
                        while (rs2.next()) {
                            estados.put(rs2.getInt("cliente_id"), rs2.getString("estado"));
                            obs.put(rs2.getInt("cliente_id"), rs2.getString("observacion"));
                        }

                        // Obtener detalles de denuncias vinculadas a esta ruta o cliente
                        Map<Integer, Map<String, Object>> denMap = new HashMap<>();
                        try (PreparedStatement pD = conn.prepareStatement("SELECT id, ticket, categoria, barrio, descripcion, cliente_id, foto_url FROM denuncias WHERE ruta_token = ? OR chofer_id = ?")) {
                            pD.setString(1, actualToken);
                            pD.setInt(2, rs.getInt("chofer_id"));
                            ResultSet rsD = pD.executeQuery();
                            while (rsD.next()) {
                                int cId = rsD.getInt("cliente_id");
                                if (cId > 0) {
                                    Map<String, Object> dm = new HashMap<>();
                                    dm.put("id", rsD.getInt("id"));
                                    dm.put("ticket", rsD.getString("ticket"));
                                    dm.put("categoria", rsD.getString("categoria"));
                                    dm.put("barrio", rsD.getString("barrio"));
                                    dm.put("descripcion", rsD.getString("descripcion"));
                                    dm.put("foto_url", rsD.getString("foto_url"));
                                    denMap.put(cId, dm);
                                }
                            }
                        } catch (Exception ignored) {}

                        for (Map<String, Object> c : clientes) {
                            int cid = ((Double) c.get("id")).intValue();
                            c.put("estado", estados.getOrDefault(cid, "pendiente"));
                            c.put("observacion", obs.getOrDefault(cid, ""));

                            String cNom = (String) c.get("nombre");
                            boolean isDen = Boolean.TRUE.equals(c.get("es_denuncia"))
                                    || "denuncia".equalsIgnoreCase((String) c.get("tipo"))
                                    || (cNom != null && cNom.toLowerCase().startsWith("denuncia"))
                                    || denMap.containsKey(cid);

                            if (isDen) {
                                c.put("es_denuncia", true);
                                c.put("tipo", "denuncia");
                                if (denMap.containsKey(cid)) {
                                    Map<String, Object> dm = denMap.get(cid);
                                    if (!c.containsKey("ticket") || c.get("ticket") == null) c.put("ticket", dm.get("ticket"));
                                    if (!c.containsKey("categoria") || c.get("categoria") == null) c.put("categoria", dm.get("categoria"));
                                    if (!c.containsKey("descripcion") || c.get("descripcion") == null) c.put("descripcion", dm.get("descripcion"));
                                    if (!c.containsKey("foto_url") || c.get("foto_url") == null) c.put("foto_url", dm.get("foto_url"));
                                }
                            }
                        }

                        ruta.put("clientes", clientes);
                        sendResponse(exchange, 200, gson.toJson(ruta));
                    } else {
                        sendError(exchange, 404, "Ruta no encontrada");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error interno del servidor: " + e.getMessage());
                }
            }
        }
    }

    static class EstadoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod()))
                return;

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines()
                            .collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String token = (String) req.get("token");
                    int clienteId = ((Double) req.get("cliente_id")).intValue();
                    String estado = (String) req.get("estado");
                    String observacion = req.containsKey("observacion") ? (String) req.get("observacion") : "";

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String checkSql = "SELECT fecha FROM rutas_generadas WHERE token = ?";
                        PreparedStatement pCheck = conn.prepareStatement(checkSql);
                        pCheck.setString(1, token);
                        ResultSet rsCheck = pCheck.executeQuery();
                        if (rsCheck.next()) {
                            java.sql.Timestamp fechaRuta = rsCheck.getTimestamp("fecha");
                            if (fechaRuta != null
                                    && (System.currentTimeMillis() - fechaRuta.getTime()) > 2L * 24 * 60 * 60 * 1000) {
                                sendError(exchange, 403, "Esta ruta ha expirado (han pasado más de 2 días).");
                                return;
                            }
                        } else {
                            sendError(exchange, 404, "Ruta no encontrada");
                            return;
                        }

                        String sql = "UPDATE entregas SET estado = ?, observacion = ?, fecha_actualizacion = CURRENT_TIMESTAMP WHERE ruta_token = ? AND cliente_id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, estado);
                        pstmt.setString(2, observacion);
                        pstmt.setString(3, token);
                        pstmt.setInt(4, clienteId);
                        pstmt.executeUpdate();

                        // Cierre de denuncia cuando el chofer finaliza el punto:
                        // entregado -> cerrada | rechazado -> en_proceso (reintento) con nota
                        try {
                            if ("entregado".equalsIgnoreCase(estado) || "resuelto".equalsIgnoreCase(estado)) {
                                // El punto de ruta es 1:1 con la denuncia (se crea por denuncia),
                                // por eso se matchea por cliente_id aunque el ruta_token no se haya
                                // estampado (ej: gestor marco en_proceso antes de generar la ruta).
                                String sqlC = "UPDATE denuncias SET estado = 'cerrada', ruta_token = ?, fecha_cierre = CURRENT_TIMESTAMP, observacion_cierre = ? WHERE cliente_id = ? AND estado IN ('pendiente','en_proceso') AND (ruta_token = ? OR ruta_token IS NULL OR ruta_token = '')";
                                PreparedStatement pC = conn.prepareStatement(sqlC);
                                pC.setString(1, token);
                                pC.setString(2, observacion != null ? observacion : "");
                                pC.setInt(3, clienteId);
                                pC.setString(4, token);
                                int n = pC.executeUpdate();
                                if (n > 0) System.out.println("✅ " + n + " denuncia(s) cerrada(s) por punto " + clienteId + " ruta " + token);
                            } else if ("rechazado".equalsIgnoreCase(estado)) {
                                String sqlR = "UPDATE denuncias SET estado = 'en_proceso', observacion_cierre = ? WHERE cliente_id = ? AND estado IN ('pendiente','en_proceso') AND (ruta_token = ? OR ruta_token IS NULL OR ruta_token = '')";
                                PreparedStatement pR = conn.prepareStatement(sqlR);
                                pR.setString(1, observacion != null ? observacion : "Rechazado en punto de ruta");
                                pR.setInt(2, clienteId);
                                pR.setString(3, token);
                                pR.executeUpdate();
                            }
                        } catch (Exception exDen) {
                            System.err.println("⚠️ No se pudo actualizar denuncia vinculada: " + exDen.getMessage());
                        }
                        sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class FinalizarRutaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod()))
                return;

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines()
                            .collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String token = (String) req.get("token");
                    if (token == null || token.isEmpty()) {
                        sendError(exchange, 400, "Token requerido");
                        return;
                    }

                    Integer userId = getUserIdFromSession(exchange);
                    boolean manager = canManageRutas(exchange);
                    Integer choferId = getChoferIdFromSession(exchange);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql;
                        PreparedStatement pstmt;
                        if (manager || userId == null) {
                            // Si es gestor o llamada desde el enlace publico de ruta con token
                            sql = "UPDATE rutas_generadas SET estado = 'finalizada' WHERE token = ?";
                            pstmt = conn.prepareStatement(sql);
                            pstmt.setString(1, token);
                        } else if (choferId != null) {
                            sql = "UPDATE rutas_generadas SET estado = 'finalizada' WHERE token = ? AND (chofer_id = ? OR usuario_id = ?)";
                            pstmt = conn.prepareStatement(sql);
                            pstmt.setString(1, token);
                            pstmt.setInt(2, choferId);
                            pstmt.setInt(3, userId);
                        } else {
                            sql = "UPDATE rutas_generadas SET estado = 'finalizada' WHERE token = ? AND usuario_id = ?";
                            pstmt = conn.prepareStatement(sql);
                            pstmt.setString(1, token);
                            pstmt.setInt(2, userId);
                        }

                        int updated = pstmt.executeUpdate();
                        if (updated > 0) {
                            sendResponse(exchange, 200, "{\"status\":\"ok\",\"message\":\"Ruta finalizada exitosamente\"}");
                        } else {
                            sendError(exchange, 404, "Ruta no encontrada o sin permisos");
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class RutasTodasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }
            boolean manager = canManageRutas(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    // Obtener las ultimas 100 rutas generadas
                    String sql = manager
                            ? "SELECT token, movil_numero, clientes_json, distancia_total, chofer_nombre, vehiculo_nombre, fecha, estado FROM rutas_generadas ORDER BY fecha DESC LIMIT 100"
                            : "SELECT token, movil_numero, clientes_json, distancia_total, chofer_nombre, vehiculo_nombre, fecha, estado FROM rutas_generadas WHERE usuario_id = ? ORDER BY fecha DESC LIMIT 100";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();
                    List<Map<String, Object>> rutasList = new ArrayList<>();
                    
                    while (rs.next()) {
                        Map<String, Object> r = new HashMap<>();
                        r.put("token", rs.getString("token"));
                        r.put("movil_numero", rs.getInt("movil_numero"));
                        r.put("distancia_total", rs.getDouble("distancia_total"));
                        r.put("chofer_nombre", rs.getString("chofer_nombre"));
                        r.put("vehiculo_nombre", rs.getString("vehiculo_nombre"));
                        r.put("fecha", rs.getTimestamp("fecha") != null ? rs.getTimestamp("fecha").toString() : "");
                        r.put("estado", rs.getString("estado"));
                        
                        // Parsear clientes para saber la cantidad
                        String clientesJson = rs.getString("clientes_json");
                        int cantidadClientes = 0;
                        if (clientesJson != null && !clientesJson.isEmpty()) {
                            try {
                                com.google.gson.JsonArray array = JsonParser.parseString(clientesJson).getAsJsonArray();
                                cantidadClientes = array.size();
                            } catch (Exception ignored) {}
                        }
                        r.put("cantidad_clientes", cantidadClientes);
                        rutasList.add(r);
                    }
                    sendResponse(exchange, 200, gson.toJson(rutasList));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines()
                            .collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String token = (String) req.get("token");
                    String password = req.containsKey("password") ? (String) req.get("password") : null;

                    if (token == null || token.isEmpty()) {
                        sendError(exchange, 400, "Token requerido");
                        return;
                    }

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        // Verificar antiguedad de la ruta
                        String checkSql = manager
                                ? "SELECT fecha FROM rutas_generadas WHERE token = ?"
                                : "SELECT fecha FROM rutas_generadas WHERE token = ? AND usuario_id = ?";
                        PreparedStatement checkStmt = conn.prepareStatement(checkSql);
                        checkStmt.setString(1, token);
                        if (!manager) {
                            checkStmt.setInt(2, userId);
                        }
                        ResultSet rs = checkStmt.executeQuery();
                        
                        if (!rs.next()) {
                            sendError(exchange, 404, "Ruta no encontrada");
                            return;
                        }
                        
                        Timestamp fechaRuta = rs.getTimestamp("fecha");
                        long diffMs = System.currentTimeMillis() - fechaRuta.getTime();
                        long diffDias = diffMs / (1000 * 60 * 60 * 24);
                        
                        // Si tiene mas de 7 dias, requiere contrasena
                        if (diffDias >= 7) {
                            if (password == null || password.isEmpty()) {
                                sendError(exchange, 403, "REQUIERE_PASSWORD");
                                return;
                            }
                            // Verificar contrasena contra la BD del usuario actual en sesion
                            String currentUsername = getUsernameFromSession(exchange);
                            Usuario usuario = usuarioRepo.login(currentUsername != null ? currentUsername : "admin", password);
                            if (usuario == null) {
                                sendError(exchange, 403, "Contrasena incorrecta");
                                return;
                            }
                        }
                        
                        // Eliminar entregas asociadas primero para evitar error de clave foránea
                        try (PreparedStatement delEntregasStmt = conn.prepareStatement(
                            "DELETE FROM entregas WHERE ruta_token = ?")) {
                            delEntregasStmt.setString(1, token);
                            delEntregasStmt.executeUpdate();
                        }

                        // Eliminar la ruta
                        String delSql = manager
                                ? "DELETE FROM rutas_generadas WHERE token = ?"
                                : "DELETE FROM rutas_generadas WHERE token = ? AND usuario_id = ?";
                        PreparedStatement deleteStmt = conn.prepareStatement(delSql);
                        deleteStmt.setString(1, token);
                        if (!manager) {
                            deleteStmt.setInt(2, userId);
                        }
                        int deleted = deleteStmt.executeUpdate();
                        
                        if (deleted > 0) {
                            sendResponse(exchange, 200, "{\"status\":\"ok\",\"message\":\"Ruta eliminada\"}");
                        } else {
                            sendError(exchange, 404, "Ruta no encontrada");
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class ReglasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }
            boolean manager = canManageRutas(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = manager
                            ? "SELECT * FROM reglas_ruteo ORDER BY categoria"
                            : "SELECT * FROM reglas_ruteo WHERE usuario_id = ? ORDER BY categoria";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();
                    List<Regla> reglas = new ArrayList<>();
                    while (rs.next()) {
                        reglas.add(new Regla(rs.getInt("id"), rs.getString("categoria"), rs.getInt("limite_por_movil"),
                                rs.getBoolean("activo")));
                    }
                    sendResponse(exchange, 200, gson.toJson(reglas));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    List<Map<String, Object>> reqReglas = gson.fromJson(body, List.class);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        for (Map<String, Object> r : reqReglas) {
                            String cat = (String) r.get("categoria");
                            int lim = ((Double) r.get("limite_por_movil")).intValue();
                            boolean act = (boolean) r.get("activo");

                            if (lim < 0) {
                                sendError(exchange, 400, "El limite por movil no puede ser negativo");
                                return;
                            }

                            String checkSql = manager
                                    ? "SELECT id FROM reglas_ruteo WHERE LOWER(categoria) = LOWER(?)"
                                    : "SELECT id FROM reglas_ruteo WHERE LOWER(categoria) = LOWER(?) AND usuario_id = ?";
                            PreparedStatement checkStmt = conn.prepareStatement(checkSql);
                            checkStmt.setString(1, cat);
                            if (!manager) {
                                checkStmt.setInt(2, userId);
                            }
                            ResultSet rsCheck = checkStmt.executeQuery();
                            if (rsCheck.next()) {
                                int reglaId = rsCheck.getInt("id");
                                PreparedStatement updStmt = conn.prepareStatement("UPDATE reglas_ruteo SET limite_por_movil = ?, activo = ? WHERE id = ?");
                                updStmt.setInt(1, lim);
                                updStmt.setBoolean(2, act);
                                updStmt.setInt(3, reglaId);
                                updStmt.executeUpdate();
                                updStmt.close();
                            } else {
                                PreparedStatement insStmt = conn.prepareStatement("INSERT INTO reglas_ruteo (categoria, limite_por_movil, activo, usuario_id) VALUES (?, ?, ?, ?)");
                                insStmt.setString(1, cat);
                                insStmt.setInt(2, lim);
                                insStmt.setBoolean(3, act);
                                insStmt.setInt(4, userId);
                                insStmt.executeUpdate();
                                insStmt.close();
                            }
                            checkStmt.close();
                        }
                        sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    if (query == null || !query.contains("categoria=")) {
                        sendError(exchange, 400, "Categoría requerida");
                        return;
                    }
                    String cat = java.net.URLDecoder.decode(query.split("categoria=")[1].split("&")[0], "UTF-8")
                            .toLowerCase();
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = manager
                                ? "DELETE FROM reglas_ruteo WHERE LOWER(categoria) = ?"
                                : "DELETE FROM reglas_ruteo WHERE LOWER(categoria) = ? AND usuario_id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, cat);
                        if (!manager) {
                            pstmt.setInt(2, userId);
                        }
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }

    }

    static class CategoriasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }

            boolean manager = canManageRutas(exchange);
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                asegurarCategoriasDefault(conn, userId);

                if ("GET".equals(exchange.getRequestMethod())) {
                    List<Map<String, Object>> categorias = new ArrayList<>();
                    String catSql = manager
                            ? "SELECT MIN(id) AS id, nombre FROM categorias WHERE activo = true GROUP BY nombre ORDER BY nombre"
                            : "SELECT id, nombre FROM categorias WHERE activo = true AND usuario_id = ? ORDER BY nombre";
                    PreparedStatement stmt = conn.prepareStatement(catSql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();

                    Map<String, Integer> usos = new HashMap<>();
                    String usosSql = manager
                            ? "SELECT LOWER(tipo_cliente) AS tc, COUNT(*) FROM clientes WHERE activo = true AND tipo_cliente IS NOT NULL AND tipo_cliente <> '' GROUP BY LOWER(tipo_cliente)"
                            : "SELECT LOWER(tipo_cliente) AS tc, COUNT(*) FROM clientes WHERE activo = true AND usuario_id = ? AND tipo_cliente IS NOT NULL AND tipo_cliente <> '' GROUP BY LOWER(tipo_cliente)";
                    PreparedStatement usosStmt = conn.prepareStatement(usosSql);
                    if (!manager) {
                        usosStmt.setInt(1, userId);
                    }
                    ResultSet rsUsos = usosStmt.executeQuery();
                    while (rsUsos.next()) {
                        usos.put(rsUsos.getString("tc"), rsUsos.getInt("count"));
                    }
                    usosStmt.close();

                    while (rs.next()) {
                        Map<String, Object> cat = new HashMap<>();
                        cat.put("id", rs.getInt("id"));
                        cat.put("nombre", rs.getString("nombre"));
                        String clave = rs.getString("nombre").toLowerCase();
                        cat.put("clientes", usos.getOrDefault(clave, 0));
                        categorias.add(cat);
                    }
                    stmt.close();
                    sendResponse(exchange, 200, gson.toJson(categorias));
                } else if ("POST".equals(exchange.getRequestMethod())) {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String nombre = req.get("nombre") == null ? "" : ((String) req.get("nombre")).trim();
                    if (nombre.isEmpty()) {
                        sendError(exchange, 400, "El nombre de la categoría es obligatorio");
                        return;
                    }
                    String checkSql = manager
                            ? "SELECT id FROM categorias WHERE LOWER(nombre) = LOWER(?)"
                            : "SELECT id FROM categorias WHERE LOWER(nombre) = LOWER(?) AND usuario_id = ?";
                    PreparedStatement check = conn.prepareStatement(checkSql);
                    check.setString(1, nombre);
                    if (!manager) {
                        check.setInt(2, userId);
                    }
                    ResultSet rsCheck = check.executeQuery();
                    if (rsCheck.next()) {
                        check.close();
                        sendError(exchange, 409, "Ya existe una categoría con ese nombre");
                        return;
                    }
                    check.close();
                    PreparedStatement ins = conn.prepareStatement("INSERT INTO categorias (nombre, activo, usuario_id) VALUES (?, true, ?)", Statement.RETURN_GENERATED_KEYS);
                    ins.setString(1, nombre);
                    ins.setInt(2, userId);
                    ins.executeUpdate();
                    ResultSet keyRs = ins.getGeneratedKeys();
                    int newId = keyRs.next() ? keyRs.getInt(1) : 0;
                    ins.close();

                    Map<String, Object> r = new HashMap<>();
                    r.put("id", newId);
                    r.put("nombre", nombre);
                    r.put("clientes", 0);
                    sendResponse(exchange, 201, gson.toJson(r));
                } else if ("PUT".equals(exchange.getRequestMethod())) {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    int id = ((Double) req.get("id")).intValue();
                    String nombre = req.get("nombre") == null ? "" : ((String) req.get("nombre")).trim();
                    if (nombre.isEmpty()) {
                        sendError(exchange, 400, "El nombre de la categoría es obligatorio");
                        return;
                    }
                    String findSql = manager
                            ? "SELECT nombre FROM categorias WHERE id = ?"
                            : "SELECT nombre FROM categorias WHERE id = ? AND usuario_id = ?";
                    PreparedStatement find = conn.prepareStatement(findSql);
                    find.setInt(1, id);
                    if (!manager) {
                        find.setInt(2, userId);
                    }
                    ResultSet rsFind = find.executeQuery();
                    if (!rsFind.next()) {
                        find.close();
                        sendError(exchange, 404, "Categoría no encontrada");
                        return;
                    }
                    String nombreAnterior = rsFind.getString("nombre");
                    find.close();

                    if (!nombreAnterior.equalsIgnoreCase(nombre)) {
                        String checkSql = manager
                                ? "SELECT id FROM categorias WHERE LOWER(nombre) = LOWER(?) AND id <> ?"
                                : "SELECT id FROM categorias WHERE LOWER(nombre) = LOWER(?) AND id <> ? AND usuario_id = ?";
                        PreparedStatement check = conn.prepareStatement(checkSql);
                        check.setString(1, nombre);
                        check.setInt(2, id);
                        if (!manager) {
                            check.setInt(3, userId);
                        }
                        ResultSet rsCheck = check.executeQuery();
                        if (rsCheck.next()) {
                            check.close();
                            sendError(exchange, 409, "Ya existe una categoría con ese nombre");
                            return;
                        }
                        check.close();

                        PreparedStatement updCat = conn.prepareStatement("UPDATE categorias SET nombre = ? WHERE id = ?");
                        updCat.setString(1, nombre);
                        updCat.setInt(2, id);
                        updCat.executeUpdate();
                        updCat.close();

                        String updCliSql = manager
                                ? "UPDATE clientes SET tipo_cliente = ? WHERE LOWER(tipo_cliente) = LOWER(?)"
                                : "UPDATE clientes SET tipo_cliente = ? WHERE LOWER(tipo_cliente) = LOWER(?) AND usuario_id = ?";
                        PreparedStatement updClientes = conn.prepareStatement(updCliSql);
                        updClientes.setString(1, nombre);
                        updClientes.setString(2, nombreAnterior);
                        if (!manager) {
                            updClientes.setInt(3, userId);
                        }
                        updClientes.executeUpdate();
                        updClientes.close();

                        String updRegSql = manager
                                ? "UPDATE reglas_ruteo SET categoria = ? WHERE LOWER(categoria) = LOWER(?)"
                                : "UPDATE reglas_ruteo SET categoria = ? WHERE LOWER(categoria) = LOWER(?) AND usuario_id = ?";
                        PreparedStatement updReglas = conn.prepareStatement(updRegSql);
                        updReglas.setString(1, nombre);
                        updReglas.setString(2, nombreAnterior);
                        if (!manager) {
                            updReglas.setInt(3, userId);
                        }
                        updReglas.executeUpdate();
                        updReglas.close();
                    }
                    Map<String, Object> r = new HashMap<>();
                    r.put("id", id);
                    r.put("nombre", nombre);
                    sendResponse(exchange, 200, gson.toJson(r));
                } else if ("DELETE".equals(exchange.getRequestMethod())) {
                    String query = exchange.getRequestURI().getQuery();
                    if (query == null || !query.contains("id=")) {
                        sendError(exchange, 400, "ID requerido");
                        return;
                    }
                    int id = Integer.parseInt(query.split("id=")[1].split("&")[0]);

                    String findSql = manager
                            ? "SELECT nombre FROM categorias WHERE id = ?"
                            : "SELECT nombre FROM categorias WHERE id = ? AND usuario_id = ?";
                    PreparedStatement find = conn.prepareStatement(findSql);
                    find.setInt(1, id);
                    if (!manager) {
                        find.setInt(2, userId);
                    }
                    ResultSet rsFind = find.executeQuery();
                    if (!rsFind.next()) {
                        find.close();
                        sendError(exchange, 404, "Categoría no encontrada");
                        return;
                    }
                    String nombre = rsFind.getString("nombre");
                    find.close();

                    String countSql = manager
                            ? "SELECT COUNT(*) FROM clientes WHERE activo = true AND LOWER(tipo_cliente) = LOWER(?)"
                            : "SELECT COUNT(*) FROM clientes WHERE activo = true AND LOWER(tipo_cliente) = LOWER(?) AND usuario_id = ?";
                    PreparedStatement countStmt = conn.prepareStatement(countSql);
                    countStmt.setString(1, nombre);
                    if (!manager) {
                        countStmt.setInt(2, userId);
                    }
                    ResultSet rsCount = countStmt.executeQuery();
                    rsCount.next();
                    int enUso = rsCount.getInt(1);
                    countStmt.close();
                    if (enUso > 0) {
                        Map<String, Object> err = new HashMap<>();
                        err.put("error", "categoria_en_uso");
                        err.put("message", "No se puede eliminar: hay " + enUso + " cliente(s) con esta categoría asignada.");
                        err.put("cliente_count", enUso);
                        sendResponse(exchange, 400, gson.toJson(err));
                        return;
                    }

                    PreparedStatement delCat = conn.prepareStatement("DELETE FROM categorias WHERE id = ?");
                    delCat.setInt(1, id);
                    delCat.executeUpdate();
                    delCat.close();

                    PreparedStatement delRegla = conn.prepareStatement("DELETE FROM reglas_ruteo WHERE LOWER(categoria) = LOWER(?)");
                    delRegla.setString(1, nombre);
                    delRegla.executeUpdate();
                    delRegla.close();

                    sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Error interno del servidor");
            }
        }
    }

    static class EstadisticasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }
            boolean manager = canManageRutas(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String periodo = "dia";
                if (query != null && query.contains("periodo=")) {
                    periodo = query.split("periodo=")[1].split("&")[0];
                }

                String dateFilter = switch (periodo) {
                    case "semana" -> "COALESCE(r.fecha, CURRENT_TIMESTAMP) >= CURRENT_DATE - INTERVAL '7 days'";
                    case "mes" -> "COALESCE(r.fecha, CURRENT_TIMESTAMP) >= CURRENT_DATE - INTERVAL '30 days'";
                    default -> "CAST(COALESCE(r.fecha, CURRENT_TIMESTAMP) AS DATE) = CURRENT_DATE";
                };

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    // Totales por Estado
                    String sql = "SELECT e.estado, COUNT(*) as cantidad FROM entregas e JOIN rutas_generadas r ON e.ruta_token = r.token WHERE "
                            + dateFilter + (manager ? "" : " AND r.usuario_id = ?") + " GROUP BY e.estado";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();

                    int entregados = 0, pendientes = 0, rechazados = 0;
                    while (rs.next()) {
                        String estado = rs.getString("estado");
                        if (estado.equals("entregado"))
                            entregados = rs.getInt("cantidad");
                        else if (estado.equals("rechazado"))
                            rechazados = rs.getInt("cantidad");
                        else
                            pendientes = rs.getInt("cantidad");
                    }
                    int total = entregados + pendientes + rechazados;
                    double exito = total > 0 ? (entregados * 100.0 / total) : 0;

                    Map<String, Object> res = new HashMap<>();
                    res.put("total_entregas", total);
                    res.put("entregados", entregados);
                    res.put("rechazados", rechazados);
                    res.put("pendientes", pendientes);
                    res.put("porcentaje_exito", Math.round(exito * 100.0) / 100.0);

                    // Rendimiento por móvil
                    String sqlRend = "SELECT r.movil_numero, " +
                            "ch.nombre as chofer, " +
                            "v.nombre as vehiculo, " +
                            "SUM(CASE WHEN e.estado = 'entregado' THEN 1 ELSE 0 END) as ent, " +
                            "COUNT(e.id) as tot " +
                            "FROM rutas_generadas r " +
                            "JOIN entregas e ON r.token = e.ruta_token " +
                            "LEFT JOIN choferes ch ON r.chofer_id = ch.id " +
                            "LEFT JOIN vehiculos v ON r.vehiculo_id = v.id " +
                            "WHERE " + dateFilter + (manager ? " " : " AND r.usuario_id = ? ") +
                            "GROUP BY r.movil_numero, ch.nombre, v.nombre";
                    PreparedStatement stmtRend = conn.prepareStatement(sqlRend);
                    if (!manager) {
                        stmtRend.setInt(1, userId);
                    }
                    ResultSet rsRend = stmtRend.executeQuery();
                    List<Map<String, Object>> rendimientos = new ArrayList<>();
                    while (rsRend.next()) {
                        Map<String, Object> rm = new HashMap<>();
                        rm.put("movil", rsRend.getInt("movil_numero"));
                        rm.put("chofer", rsRend.getString("chofer"));
                        rm.put("vehiculo", rsRend.getString("vehiculo"));
                        rm.put("entregados", rsRend.getInt("ent"));
                        rm.put("total", rsRend.getInt("tot"));
                        rendimientos.add(rm);
                    }
                    res.put("rendimiento_por_movil", rendimientos);

                    // Historial (Tendencia)
                    String sqlHist = "SELECT CAST(r.fecha AS DATE) as fecha_dia, " +
                            "SUM(CASE WHEN e.estado = 'entregado' THEN 1 ELSE 0 END) as ent " +
                            "FROM rutas_generadas r JOIN entregas e ON r.token = e.ruta_token " +
                            "WHERE " + dateFilter + (manager ? " " : " AND r.usuario_id = ? ") +
                            "GROUP BY CAST(r.fecha AS DATE) ORDER BY fecha_dia ASC";
                    PreparedStatement stmtHist = conn.prepareStatement(sqlHist);
                    if (!manager) {
                        stmtHist.setInt(1, userId);
                    }
                    ResultSet rsHist = stmtHist.executeQuery();
                    List<Map<String, Object>> historial = new ArrayList<>();
                    while (rsHist.next()) {
                        Map<String, Object> h = new HashMap<>();
                        h.put("fecha", rsHist.getDate("fecha_dia").toString());
                        h.put("entregados", rsHist.getInt("ent"));
                        historial.add(h);
                    }
                    res.put("historial", historial);

                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class ReportesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }
            boolean manager = canManageRutas(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT e.id, c.nombre as cliente, e.estado, e.observacion, e.fecha_actualizacion, r.movil_numero, "
                            +
                            "ch.nombre as chofer, v.nombre as vehiculo " +
                            "FROM entregas e " +
                            "JOIN clientes c ON e.cliente_id = c.id " +
                            "JOIN rutas_generadas r ON e.ruta_token = r.token " +
                            "LEFT JOIN choferes ch ON r.chofer_id = ch.id " +
                            "LEFT JOIN vehiculos v ON r.vehiculo_id = v.id " +
                            "WHERE e.estado != 'pendiente' " + (manager ? "" : "AND r.usuario_id = ? ") +
                            "ORDER BY e.fecha_actualizacion DESC LIMIT 50";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();

                    List<Map<String, Object>> reportes = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> rep = new HashMap<>();
                        rep.put("id", rs.getInt("id"));
                        rep.put("cliente", rs.getString("cliente"));
                        rep.put("estado", rs.getString("estado"));
                        rep.put("observacion", rs.getString("observacion"));
                        rep.put("fecha", rs.getTimestamp("fecha_actualizacion").toString());
                        rep.put("movil", rs.getInt("movil_numero"));
                        rep.put("chofer", rs.getString("chofer"));
                        rep.put("vehiculo", rs.getString("vehiculo"));
                        reportes.add(rep);
                    }
                    sendResponse(exchange, 200, gson.toJson(reportes));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class DenunciasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String method = exchange.getRequestMethod();
            try {
                if ("GET".equals(method)) {
                    handleList(exchange);
                } else if ("POST".equals(method)) {
                    handleCreate(exchange);
                } else if ("PUT".equals(method)) {
                    handleUpdate(exchange);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Error interno en denuncias");
            }
        }

        private Map<String, String> queryParams(HttpExchange exchange) {
            Map<String, String> map = new HashMap<>();
            String q = exchange.getRequestURI().getRawQuery();
            if (q == null) return map;
            for (String p : q.split("&")) {
                int i = p.indexOf('=');
                if (i > 0) {
                    try {
                        map.put(java.net.URLDecoder.decode(p.substring(0, i), "UTF-8"),
                                java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8"));
                    } catch (Exception ignored) {}
                }
            }
            return map;
        }

        private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
            Map<String, Object> d = new HashMap<>();
            d.put("id", rs.getInt("id"));
            d.put("ticket", rs.getString("ticket"));
            d.put("nombre_ciudadano", rs.getString("nombre_ciudadano"));
            d.put("telefono", rs.getString("telefono"));
            d.put("descripcion", rs.getString("descripcion"));
            d.put("categoria", rs.getString("categoria"));
            d.put("barrio", rs.getString("barrio"));
            d.put("direccion_referencia", rs.getString("direccion_referencia"));
            d.put("latitud", rs.getDouble("latitud"));
            d.put("longitud", rs.getDouble("longitud"));
            d.put("foto_url", rs.getString("foto_url"));
            d.put("estado", rs.getString("estado"));
            d.put("usuario_id", rs.getObject("usuario_id"));
            d.put("cliente_id", rs.getObject("cliente_id"));
            d.put("ruta_token", rs.getString("ruta_token"));
            d.put("observacion_cierre", rs.getString("observacion_cierre"));
            d.put("chofer_id", rs.getObject("chofer_id"));
            d.put("cuadrilla", rs.getString("cuadrilla"));
            d.put("asignado_a", rs.getString("asignado_a"));
            d.put("fecha_creacion", rs.getTimestamp("fecha_creacion") != null ? rs.getTimestamp("fecha_creacion").toString() : null);
            d.put("fecha_cierre", rs.getTimestamp("fecha_cierre") != null ? rs.getTimestamp("fecha_cierre").toString() : null);
            return d;
        }

        /** GET /api/denuncias?ticket=ECO-... (publico) | ?estado=&barrio=&mine=1 (auth/publico) */
        private void handleList(HttpExchange exchange) throws IOException, SQLException {
            Map<String, String> qp = queryParams(exchange);
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                if (qp.containsKey("ticket") && !qp.get("ticket").isEmpty()) {
                    PreparedStatement ps = conn.prepareStatement("SELECT * FROM denuncias WHERE ticket = ?");
                    ps.setString(1, qp.get("ticket").trim().toUpperCase());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        Map<String, Object> d = rowToMap(rs);
                        // Vista publica sanitizada: sin telefono ni usuario_id
                        d.remove("telefono");
                        d.remove("usuario_id");
                        sendResponse(exchange, 200, gson.toJson(d));
                    } else {
                        sendError(exchange, 404, "Ticket no encontrado");
                    }
                    return;
                }
                Integer userId = getUserIdFromSession(exchange);
                boolean manager = canManageRutas(exchange);
                boolean chofer = isChofer(exchange);
                Integer sesionChoferId = getChoferIdFromSession(exchange);
                String sesionUsername = getUsernameFromSession(exchange);

                StringBuilder sql = new StringBuilder("SELECT * FROM denuncias WHERE 1=1");
                List<Object> params = new ArrayList<>();

                if (chofer && !manager) {
                    if (sesionChoferId != null) {
                        sql.append(" AND (chofer_id = ? OR cuadrilla ILIKE ? OR asignado_a ILIKE ?)");
                        params.add(sesionChoferId);
                        params.add("%" + sesionUsername + "%");
                        params.add("%" + sesionUsername + "%");
                    } else {
                        sql.append(" AND (cuadrilla ILIKE ? OR asignado_a ILIKE ?)");
                        params.add("%" + sesionUsername + "%");
                        params.add("%" + sesionUsername + "%");
                    }
                } else if (!manager && userId != null && "1".equals(qp.get("mine"))) {
                    sql.append(" AND usuario_id = ?");
                    params.add(userId);
                }
                if (qp.containsKey("estado") && !qp.get("estado").isEmpty() && !"todos".equalsIgnoreCase(qp.get("estado"))) {
                    sql.append(" AND estado = ?");
                    params.add(qp.get("estado").toLowerCase());
                }
                if (qp.containsKey("barrio") && !qp.get("barrio").isEmpty()) {
                    sql.append(" AND barrio ILIKE ?");
                    params.add("%" + qp.get("barrio") + "%");
                }
                if (qp.containsKey("chofer_id") && !qp.get("chofer_id").isEmpty()) {
                    try {
                        sql.append(" AND chofer_id = ?");
                        params.add(Integer.parseInt(qp.get("chofer_id")));
                    } catch (Exception ignored) {}
                }
                if (qp.containsKey("cuadrilla") && !qp.get("cuadrilla").isEmpty()) {
                    sql.append(" AND (cuadrilla ILIKE ? OR asignado_a ILIKE ?)");
                    params.add("%" + qp.get("cuadrilla") + "%");
                    params.add("%" + qp.get("cuadrilla") + "%");
                }
                sql.append(" ORDER BY fecha_creacion DESC LIMIT 300");
                PreparedStatement ps = conn.prepareStatement(sql.toString());
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                ResultSet rs = ps.executeQuery();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = rowToMap(rs);
                    if (!manager && !chofer) {
                        row.remove("telefono");
                    }
                    out.add(row);
                }
                sendResponse(exchange, 200, gson.toJson(out));
            }
        }

        private String optString(JsonObject json, String key, String def) {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                try { return json.get(key).getAsString(); } catch (Exception ignored) {}
            }
            return def;
        }

        /** POST /api/denuncias: crea y persiste en BD siempre (con o sin sesión) */
        private void handleCreate(HttpExchange exchange) throws IOException, SQLException {
            Integer userId = getUserIdFromSession(exchange);
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            JsonObject json;
            try {
                json = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) { sendError(exchange, 400, "JSON invalido"); return; }
            String descripcion = optString(json, "descripcion", "").trim();
            String barrio = optString(json, "barrio", "").trim();
            if (descripcion.isEmpty() || barrio.isEmpty()) { sendError(exchange, 400, "descripcion y barrio son obligatorios"); return; }
            if (!json.has("latitud") || json.get("latitud").isJsonNull() || !json.has("longitud") || json.get("longitud").isJsonNull()) {
                sendError(exchange, 400, "latitud y longitud son obligatorias");
                return;
            }
            double lat = json.get("latitud").getAsDouble();
            double lon = json.get("longitud").getAsDouble();
            String categoria = optString(json, "categoria", "VERTEDERO_CLANDESTINO");
            String direccion = optString(json, "direccion_referencia", "");
            String telefono = optString(json, "telefono", "");
            String nombre = optString(json, "nombre_ciudadano", "");
            String foto = optString(json, "foto_url", "").trim();
            if (!foto.isEmpty()) {
                // Medidas de seguridad: Límite estricto de tamaño (máx 5MB)
                if (foto.length() > 5 * 1024 * 1024) {
                    sendError(exchange, 400, "La fotografía excede el tamaño máximo permitido (máx 5MB)");
                    return;
                }
                // Medidas de seguridad: Validar que sea un formato seguro de imagen (JPEG/PNG/WebP o URL)
                boolean isSafe = foto.startsWith("data:image/jpeg;base64,")
                        || foto.startsWith("data:image/png;base64,")
                        || foto.startsWith("data:image/webp;base64,")
                        || foto.startsWith("http://")
                        || foto.startsWith("https://");
                if (!isSafe) {
                    sendError(exchange, 400, "Formato de fotografía no válido o inseguro");
                    return;
                }
            }
            String fecha = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
            String ticket = "ECO-" + fecha + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO denuncias (ticket, nombre_ciudadano, telefono, descripcion, categoria, barrio, direccion_referencia, latitud, longitud, foto_url, estado, usuario_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pendiente', ?) RETURNING id");
                ps.setString(1, ticket);
                ps.setString(2, nombre);
                ps.setString(3, telefono);
                ps.setString(4, descripcion);
                ps.setString(5, categoria);
                ps.setString(6, barrio);
                ps.setString(7, direccion);
                ps.setDouble(8, lat);
                ps.setDouble(9, lon);
                ps.setString(10, foto);
                if (userId != null) {
                    ps.setInt(11, userId);
                } else {
                    ps.setNull(11, java.sql.Types.INTEGER);
                }
                ResultSet rs = ps.executeQuery();
                int id = rs.next() ? rs.getInt(1) : 0;
                Map<String, Object> resp = new HashMap<>();
                resp.put("id", id);
                resp.put("ticket", ticket);
                resp.put("estado", "pendiente");
                sendResponse(exchange, 201, gson.toJson(resp));
            }
        }

        /** PUT /api/denuncias: cambia estado y vincula cliente/ruta (gestor o chofer) */
        private void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
            if (!isAuthorized(exchange)) { sendError(exchange, 401, "No autorizado"); return; }
            if (!canManageRutas(exchange) && !isChofer(exchange)) { sendError(exchange, 403, "Solo gestor o chofer asignado"); return; }
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            JsonObject json;
            try {
                json = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) { sendError(exchange, 400, "JSON invalido"); return; }
            if (!json.has("id")) { sendError(exchange, 400, "id requerido"); return; }
            int id = json.get("id").getAsInt();
            String nuevoEstado = json.has("estado") ? json.get("estado").getAsString().toLowerCase() : null;
            if (nuevoEstado == null || (!nuevoEstado.equals("pendiente") && !nuevoEstado.equals("en_proceso")
                    && !nuevoEstado.equals("cerrada") && !nuevoEstado.equals("rechazada"))) {
                sendError(exchange, 400, "estado invalido (pendiente|en_proceso|cerrada|rechazada)");
                return;
            }
            Integer clienteId = (json.has("cliente_id") && !json.get("cliente_id").isJsonNull()) ? json.get("cliente_id").getAsInt() : null;
            String rutaToken = (json.has("ruta_token") && !json.get("ruta_token").isJsonNull()) ? json.get("ruta_token").getAsString() : null;
            String obs = (json.has("observacion_cierre") && !json.get("observacion_cierre").isJsonNull()) ? json.get("observacion_cierre").getAsString() : "";
            Integer choferId = (json.has("chofer_id") && !json.get("chofer_id").isJsonNull()) ? json.get("chofer_id").getAsInt() : null;
            String cuadrilla = optString(json, "cuadrilla", null);
            String asignadoA = optString(json, "asignado_a", null);
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                PreparedStatement cur = conn.prepareStatement("SELECT estado FROM denuncias WHERE id = ?");
                cur.setInt(1, id);
                ResultSet rs = cur.executeQuery();
                if (!rs.next()) { sendError(exchange, 404, "Denuncia no encontrada"); return; }
                String actual = rs.getString("estado");
                if ("cerrada".equals(actual) && "cerrada".equals(nuevoEstado)) { sendError(exchange, 400, "La denuncia ya esta cerrada"); return; }
                // Transiciones validas: pendiente->en_proceso|rechazada|pendiente, en_proceso->cerrada|rechazada|pendiente|en_proceso, rechazada->pendiente|en_proceso, cerrada->en_proceso (reapertura)
                boolean ok = ("pendiente".equals(actual) && ("en_proceso".equals(nuevoEstado) || "rechazada".equals(nuevoEstado) || "pendiente".equals(nuevoEstado)))
                        || ("en_proceso".equals(actual) && ("cerrada".equals(nuevoEstado) || "rechazada".equals(nuevoEstado) || "pendiente".equals(nuevoEstado) || "en_proceso".equals(nuevoEstado)))
                        || ("rechazada".equals(actual) && ("pendiente".equals(nuevoEstado) || "en_proceso".equals(nuevoEstado)))
                        || ("cerrada".equals(actual) && "en_proceso".equals(nuevoEstado));
                if (!ok) { sendError(exchange, 400, "Transicion no permitida: " + actual + " -> " + nuevoEstado); return; }
                String extraCols = (clienteId != null ? ", cliente_id = " + clienteId : "")
                        + (rutaToken != null ? ", ruta_token = '" + rutaToken.replace("'", "''") + "'" : "")
                        + (choferId != null ? ", chofer_id = " + choferId : "")
                        + (cuadrilla != null ? ", cuadrilla = '" + cuadrilla.replace("'", "''") + "'" : "")
                        + (asignadoA != null ? ", asignado_a = '" + asignadoA.replace("'", "''") + "'" : "");
                String sql;
                if ("cerrada".equals(nuevoEstado)) {
                    sql = "UPDATE denuncias SET estado = ?, observacion_cierre = ?, fecha_cierre = CURRENT_TIMESTAMP"
                            + extraCols + " WHERE id = ?";
                } else {
                    sql = "UPDATE denuncias SET estado = ?, observacion_cierre = ?, fecha_cierre = NULL"
                            + extraCols + " WHERE id = ?";
                }
                PreparedStatement ps = conn.prepareStatement(sql);
                ps.setString(1, nuevoEstado);
                ps.setString(2, obs);
                ps.setInt(3, id);
                ps.executeUpdate();
                sendResponse(exchange, 200, "{\"status\":\"ok\",\"estado\":\"" + nuevoEstado + "\"}");
            }
        }
    }

    static class DenunciasAsignarPuntoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if (!canManageRutas(exchange)) {
                sendError(exchange, 403, "Solo gestor o administrador puede asignar puntos de ruta");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                    if (!json.has("denuncia_id") || json.get("denuncia_id").isJsonNull()) {
                        sendError(exchange, 400, "denuncia_id es requerido");
                        return;
                    }
                    if (!json.has("chofer_id") || json.get("chofer_id").isJsonNull()) {
                        sendError(exchange, 400, "chofer_id es requerido");
                        return;
                    }

                    int denunciaId = json.get("denuncia_id").getAsInt();
                    int choferId = json.get("chofer_id").getAsInt();
                    Integer vehiculoId = (json.has("vehiculo_id") && !json.get("vehiculo_id").isJsonNull()) ? json.get("vehiculo_id").getAsInt() : null;
                    String nombrePunto = (json.has("nombre_punto") && !json.get("nombre_punto").isJsonNull()) ? json.get("nombre_punto").getAsString().trim() : "";
                    String observacion = (json.has("observacion") && !json.get("observacion").isJsonNull()) ? json.get("observacion").getAsString().trim() : "";

                    Integer userId = getUserIdFromSession(exchange);
                    if (userId == null) userId = 1;

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        conn.setAutoCommit(false);
                        try {
                            // 1. Obtener la denuncia
                            String sqlDen = "SELECT * FROM denuncias WHERE id = ?";
                            PreparedStatement psDen = conn.prepareStatement(sqlDen);
                            psDen.setInt(1, denunciaId);
                            ResultSet rsDen = psDen.executeQuery();
                            if (!rsDen.next()) {
                                conn.rollback();
                                sendError(exchange, 404, "Denuncia no encontrada");
                                return;
                            }

                            String ticket = rsDen.getString("ticket");
                            String barrio = rsDen.getString("barrio");
                            String categoria = rsDen.getString("categoria");
                            String descripcion = rsDen.getString("descripcion");
                            String fotoUrl = rsDen.getString("foto_url");
                            double lat = rsDen.getDouble("latitud");
                            double lon = rsDen.getDouble("longitud");
                            Integer existingClienteId = (Integer) rsDen.getObject("cliente_id");

                            if (nombrePunto.isEmpty()) {
                                nombrePunto = "Denuncia " + ticket + " - " + barrio;
                            }

                            // 2. Obtener o crear cliente (punto de recolección)
                            int clienteId;
                            if (existingClienteId != null && existingClienteId > 0) {
                                clienteId = existingClienteId;
                                try (PreparedStatement pu = conn.prepareStatement("UPDATE clientes SET nombre = ?, tipo_cliente = 'denuncia', latitud = ?, longitud = ? WHERE id = ?")) {
                                    pu.setString(1, nombrePunto);
                                    pu.setDouble(2, lat);
                                    pu.setDouble(3, lon);
                                    pu.setInt(4, clienteId);
                                    pu.executeUpdate();
                                }
                            } else {
                                String insertCli = "INSERT INTO clientes (nombre, tipo_cliente, latitud, longitud, ciudad, cadena, activo, usuario_id) VALUES (?, 'denuncia', ?, ?, ?, ?, true, ?) RETURNING id";
                                PreparedStatement psCli = conn.prepareStatement(insertCli);
                                psCli.setString(1, nombrePunto);
                                psCli.setDouble(2, lat);
                                psCli.setDouble(3, lon);
                                psCli.setString(4, barrio != null ? barrio : "Asunción");
                                psCli.setString(5, barrio != null ? barrio : "Asunción");
                                psCli.setInt(6, userId);
                                ResultSet rsCli = psCli.executeQuery();
                                clienteId = rsCli.next() ? rsCli.getInt(1) : 0;
                            }

                            // 3. Obtener nombres del chofer y vehiculo
                            String choferNombre = "";
                            try (PreparedStatement psCh = conn.prepareStatement("SELECT nombre FROM choferes WHERE id = ?")) {
                                psCh.setInt(1, choferId);
                                ResultSet rsCh = psCh.executeQuery();
                                if (rsCh.next()) choferNombre = rsCh.getString("nombre");
                            }

                            String vehiculoNombre = "";
                            if (vehiculoId != null && vehiculoId > 0) {
                                try (PreparedStatement psVh = conn.prepareStatement("SELECT nombre FROM vehiculos WHERE id = ?")) {
                                    psVh.setInt(1, vehiculoId);
                                    ResultSet rsVh = psVh.executeQuery();
                                    if (rsVh.next()) vehiculoNombre = rsVh.getString("nombre");
                                }
                            }

                            // 4. Buscar si el chofer ya tiene una ruta activa (estado = 'en_curso')
                            String sqlRutaActiva = "SELECT token, movil_numero, clientes_json, distancia_total, tiempo_estimado, vehiculo_id, vehiculo_nombre FROM rutas_generadas WHERE chofer_id = ? AND estado = 'en_curso' ORDER BY fecha DESC LIMIT 1";
                            PreparedStatement psRuta = conn.prepareStatement(sqlRutaActiva);
                            psRuta.setInt(1, choferId);
                            ResultSet rsRuta = psRuta.executeQuery();

                            String routeToken;
                            boolean esNuevaRuta;
                            int totalParadas;
                            int movilNumero = 1;

                            if (rsRuta.next()) {
                                // ---- Caso A: El chofer ya tiene ruta en curso -> agregar parada extra ----
                                esNuevaRuta = false;
                                routeToken = rsRuta.getString("token");
                                movilNumero = rsRuta.getInt("movil_numero");
                                if (vehiculoId == null || vehiculoId <= 0) {
                                    vehiculoId = (Integer) rsRuta.getObject("vehiculo_id");
                                    vehiculoNombre = rsRuta.getString("vehiculo_nombre");
                                }
                                double distTotal = rsRuta.getDouble("distancia_total");
                                int tiempoEstimado = rsRuta.getInt("tiempo_estimado");
                                String jsonStr = rsRuta.getString("clientes_json");
                                List<Map<String, Object>> clientesList = gson.fromJson(jsonStr, List.class);
                                if (clientesList == null) clientesList = new ArrayList<>();

                                // Calcular distancia desde la ultima parada actual hasta el nuevo punto
                                double distExtra = 0.0;
                                if (!clientesList.isEmpty()) {
                                    Map<String, Object> ultimo = clientesList.get(clientesList.size() - 1);
                                    double lastLat = ((Double) ultimo.get("latitud")).doubleValue();
                                    double lastLon = ((Double) ultimo.get("longitud")).doubleValue();
                                    distExtra = haversine(lastLat, lastLon, lat, lon) * 1.3;
                                    distExtra = Math.round(distExtra * 100.0) / 100.0;
                                    ultimo.put("distancia_siguiente", distExtra);
                                }

                                // Nuevo punto diferenciado como denuncia asignada
                                Map<String, Object> nuevoPunto = new HashMap<>();
                                nuevoPunto.put("id", (double) clienteId);
                                nuevoPunto.put("nombre", nombrePunto);
                                nuevoPunto.put("latitud", lat);
                                nuevoPunto.put("longitud", lon);
                                nuevoPunto.put("distancia_siguiente", 0.0);
                                nuevoPunto.put("es_denuncia", true);
                                nuevoPunto.put("tipo", "denuncia");
                                nuevoPunto.put("ticket", ticket);
                                nuevoPunto.put("categoria", categoria);
                                nuevoPunto.put("descripcion", descripcion);
                                nuevoPunto.put("foto_url", fotoUrl);
                                nuevoPunto.put("estado", "pendiente");
                                nuevoPunto.put("observacion", observacion);

                                clientesList.add(nuevoPunto);
                                totalParadas = clientesList.size();
                                distTotal = Math.round((distTotal + distExtra) * 100.0) / 100.0;
                                tiempoEstimado = (int) Math.ceil((distTotal / 40.0) * 60.0);

                                // Actualizar rutas_generadas
                                String updateRutaSql = "UPDATE rutas_generadas SET clientes_json = ?, distancia_total = ?, tiempo_estimado = ? WHERE token = ?";
                                PreparedStatement psUp = conn.prepareStatement(updateRutaSql);
                                psUp.setString(1, gson.toJson(clientesList));
                                psUp.setDouble(2, distTotal);
                                psUp.setInt(3, tiempoEstimado);
                                psUp.setString(4, routeToken);
                                psUp.executeUpdate();

                                // Insertar en entregas
                                String insEntrega = "INSERT INTO entregas (ruta_token, cliente_id, estado, observacion, orden_en_ruta) VALUES (?, ?, 'pendiente', ?, ?)";
                                PreparedStatement psEnt = conn.prepareStatement(insEntrega);
                                psEnt.setString(1, routeToken);
                                psEnt.setInt(2, clienteId);
                                psEnt.setString(3, observacion);
                                psEnt.setInt(4, totalParadas);
                                psEnt.executeUpdate();

                            } else {
                                // ---- Caso B: Chofer sin ruta activa -> Generar nueva ruta activa ----
                                esNuevaRuta = true;
                                routeToken = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                                totalParadas = 1;

                                // Si no selecciono vehiculo, tomar el primero activo
                                if (vehiculoId == null || vehiculoId <= 0) {
                                    try (PreparedStatement psV1 = conn.prepareStatement("SELECT id, nombre FROM vehiculos WHERE activo = true LIMIT 1")) {
                                        ResultSet rsV1 = psV1.executeQuery();
                                        if (rsV1.next()) {
                                            vehiculoId = rsV1.getInt("id");
                                            vehiculoNombre = rsV1.getString("nombre");
                                        }
                                    }
                                }

                                Map<String, Object> nuevoPunto = new HashMap<>();
                                nuevoPunto.put("id", (double) clienteId);
                                nuevoPunto.put("nombre", nombrePunto);
                                nuevoPunto.put("latitud", lat);
                                nuevoPunto.put("longitud", lon);
                                nuevoPunto.put("distancia_siguiente", 0.0);
                                nuevoPunto.put("es_denuncia", true);
                                nuevoPunto.put("tipo", "denuncia");
                                nuevoPunto.put("ticket", ticket);
                                nuevoPunto.put("categoria", categoria);
                                nuevoPunto.put("descripcion", descripcion);
                                nuevoPunto.put("foto_url", fotoUrl);
                                nuevoPunto.put("estado", "pendiente");
                                nuevoPunto.put("observacion", observacion);

                                List<Map<String, Object>> clientesList = new ArrayList<>();
                                clientesList.add(nuevoPunto);

                                String insertRuta = "INSERT INTO rutas_generadas (token, movil_numero, clientes_json, distancia_total, tiempo_estimado, chofer_id, vehiculo_id, chofer_nombre, vehiculo_nombre, usuario_id, estado) VALUES (?, ?, ?, 0.0, 15, ?, ?, ?, ?, ?, 'en_curso')";
                                PreparedStatement psNewRuta = conn.prepareStatement(insertRuta);
                                psNewRuta.setString(1, routeToken);
                                psNewRuta.setInt(2, movilNumero);
                                psNewRuta.setString(3, gson.toJson(clientesList));
                                psNewRuta.setInt(4, choferId);
                                if (vehiculoId != null) psNewRuta.setInt(5, vehiculoId); else psNewRuta.setNull(5, Types.INTEGER);
                                psNewRuta.setString(6, choferNombre);
                                psNewRuta.setString(7, vehiculoNombre);
                                psNewRuta.setInt(8, userId);
                                psNewRuta.executeUpdate();

                                String insEntrega = "INSERT INTO entregas (ruta_token, cliente_id, estado, observacion, orden_en_ruta) VALUES (?, ?, 'pendiente', ?, 1)";
                                PreparedStatement psEnt = conn.prepareStatement(insEntrega);
                                psEnt.setString(1, routeToken);
                                psEnt.setInt(2, clienteId);
                                psEnt.setString(3, observacion);
                                psEnt.executeUpdate();
                            }

                            // 5. Actualizar la denuncia para vincularla a la ruta y chofer
                            String updDenSql = "UPDATE denuncias SET estado = 'en_proceso', cliente_id = ?, chofer_id = ?, ruta_token = ?, asignado_a = ?, observacion_cierre = COALESCE(?, observacion_cierre) WHERE id = ?";
                            PreparedStatement psUpDen = conn.prepareStatement(updDenSql);
                            psUpDen.setInt(1, clienteId);
                            psUpDen.setInt(2, choferId);
                            psUpDen.setString(3, routeToken);
                            psUpDen.setString(4, choferNombre);
                            psUpDen.setString(5, observacion.isEmpty() ? null : observacion);
                            psUpDen.setInt(6, denunciaId);
                            psUpDen.executeUpdate();

                            conn.commit();

                            Map<String, Object> resp = new HashMap<>();
                            resp.put("success", true);
                            resp.put("token", routeToken);
                            resp.put("es_nueva_ruta", esNuevaRuta);
                            resp.put("movil_numero", movilNumero);
                            resp.put("chofer_id", choferId);
                            resp.put("chofer_nombre", choferNombre);
                            resp.put("vehiculo_id", vehiculoId);
                            resp.put("vehiculo_nombre", vehiculoNombre);
                            resp.put("cliente_id", clienteId);
                            resp.put("total_paradas", totalParadas);
                            resp.put("ticket", ticket);
                            resp.put("message", esNuevaRuta 
                                ? "Nueva ruta activa generada para el chofer " + choferNombre + " con la denuncia " + ticket
                                : "Denuncia " + ticket + " agregada exitosamente como parada #" + totalParadas + " a la ruta activa del chofer " + choferNombre);

                            sendResponse(exchange, 200, gson.toJson(resp));

                        } catch (Exception ex) {
                            conn.rollback();
                            throw ex;
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error asignando punto de ruta: " + e.getMessage());
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class AlertasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            String method = exchange.getRequestMethod();
            try {
                if ("GET".equals(method)) {
                    handleList(exchange);
                } else if ("POST".equals(method)) {
                    handleCreate(exchange);
                } else if ("PUT".equals(method)) {
                    handleUpdate(exchange);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Error en alertas: " + e.getMessage());
            }
        }

        private void handleList(HttpExchange exchange) throws IOException, SQLException {
            boolean manager = canManageRutas(exchange);
            boolean chofer = isChofer(exchange);
            Integer choferId = getChoferIdFromSession(exchange);
            String username = getUsernameFromSession(exchange);

            StringBuilder sql = new StringBuilder("SELECT * FROM alertas_operativas WHERE 1=1");
            List<Object> params = new ArrayList<>();

            if (chofer && !manager) {
                if (choferId != null) {
                    sql.append(" AND (chofer_id = ? OR chofer_nombre = ?)");
                    params.add(choferId);
                    params.add(username);
                } else {
                    sql.append(" AND chofer_nombre = ?");
                    params.add(username);
                }
            }

            sql.append(" ORDER BY fecha DESC LIMIT 100");

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                ResultSet rs = ps.executeQuery();
                List<Map<String, Object>> list = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> a = new HashMap<>();
                    a.put("id", rs.getInt("id"));
                    a.put("tipo", rs.getString("tipo"));
                    a.put("descripcion", rs.getString("descripcion"));
                    a.put("chofer_id", rs.getObject("chofer_id"));
                    a.put("chofer_nombre", rs.getString("chofer_nombre"));
                    a.put("denuncia_id", rs.getObject("denuncia_id"));
                    a.put("denuncia_ticket", rs.getString("denuncia_ticket"));
                    a.put("latitud", rs.getObject("latitud"));
                    a.put("longitud", rs.getObject("longitud"));
                    a.put("estado", rs.getString("estado"));
                    a.put("fecha", rs.getTimestamp("fecha") != null ? rs.getTimestamp("fecha").toString() : null);
                    list.add(a);
                }
                sendResponse(exchange, 200, gson.toJson(list));
            }
        }

        private void handleCreate(HttpExchange exchange) throws IOException, SQLException {
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            JsonObject json;
            try {
                json = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                sendError(exchange, 400, "JSON inválido");
                return;
            }

            String tipo = json.has("tipo") ? json.get("tipo").getAsString().trim() : "averia_vehiculo";
            String descripcion = json.has("descripcion") ? json.get("descripcion").getAsString().trim() : "";
            Integer choferId = getChoferIdFromSession(exchange);
            String choferNombre = getUsernameFromSession(exchange);
            if (json.has("chofer_nombre") && !json.get("chofer_nombre").getAsString().trim().isEmpty()) {
                choferNombre = json.get("chofer_nombre").getAsString().trim();
            }

            Integer denunciaId = (json.has("denuncia_id") && !json.get("denuncia_id").isJsonNull())
                    ? json.get("denuncia_id").getAsInt() : null;
            String denunciaTicket = (json.has("denuncia_ticket") && !json.get("denuncia_ticket").isJsonNull())
                    ? json.get("denuncia_ticket").getAsString().trim() : null;

            Double lat = (json.has("latitud") && !json.get("latitud").isJsonNull()) ? json.get("latitud").getAsDouble() : null;
            Double lon = (json.has("longitud") && !json.get("longitud").isJsonNull()) ? json.get("longitud").getAsDouble() : null;

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                String sql = "INSERT INTO alertas_operativas (tipo, descripcion, chofer_id, chofer_nombre, denuncia_id, denuncia_ticket, latitud, longitud, estado) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'activa') RETURNING id";
                PreparedStatement ps = conn.prepareStatement(sql);
                ps.setString(1, tipo);
                ps.setString(2, descripcion);
                if (choferId != null) ps.setInt(3, choferId); else ps.setNull(3, Types.INTEGER);
                ps.setString(4, choferNombre != null ? choferNombre : "Chofer");
                if (denunciaId != null) ps.setInt(5, denunciaId); else ps.setNull(5, Types.INTEGER);
                ps.setString(6, denunciaTicket);
                if (lat != null) ps.setDouble(7, lat); else ps.setNull(7, Types.DOUBLE);
                if (lon != null) ps.setDouble(8, lon); else ps.setNull(8, Types.DOUBLE);

                ResultSet rs = ps.executeQuery();
                int alertId = rs.next() ? rs.getInt(1) : 0;

                // Si está vinculada a una denuncia, registrar observación operativa
                if (denunciaId != null) {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE denuncias SET observacion_cierre = COALESCE(observacion_cierre || E'\\n', '') || ? WHERE id = ?")) {
                        upd.setString(1, "[ALERTA CHOFER: " + tipo + " - " + descripcion + "]");
                        upd.setInt(2, denunciaId);
                        upd.executeUpdate();
                    }
                }

                Map<String, Object> resp = new HashMap<>();
                resp.put("status", "created");
                resp.put("id", alertId);
                resp.put("tipo", tipo);
                resp.put("mensaje", "Alerta operativa emitida con éxito");
                sendResponse(exchange, 201, gson.toJson(resp));
            }
        }

        private void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            JsonObject json;
            try {
                json = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                sendError(exchange, 400, "JSON inválido");
                return;
            }
            if (!json.has("id")) {
                sendError(exchange, 400, "id es requerido");
                return;
            }
            int id = json.get("id").getAsInt();
            String nuevoEstado = json.has("estado") ? json.get("estado").getAsString().toLowerCase() : "resuelta";

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement("UPDATE alertas_operativas SET estado = ? WHERE id = ?")) {
                ps.setString(1, nuevoEstado);
                ps.setInt(2, id);
                ps.executeUpdate();
                sendResponse(exchange, 200, "{\"status\":\"ok\",\"estado\":\"" + nuevoEstado + "\"}");
            }
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            sendResponse(exchange, 200, "{\"status\":\"OK\"}");
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));

                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String username = "";
                    String password = "";

                    if (json.has("auth")) {
                        // Soporte para el nuevo formato del frontend (Base64)
                        try {
                            String authPayload = json.get("auth").getAsString();
                            byte[] decodedBytes = java.util.Base64.getDecoder().decode(authPayload);
                            String decodedJson = new String(decodedBytes, StandardCharsets.UTF_8);
                            JsonObject authJson = JsonParser.parseString(decodedJson).getAsJsonObject();
                            username = authJson.has("username") ? authJson.get("username").getAsString() : "";
                            password = authJson.has("password") ? authJson.get("password").getAsString() : "";
                        } catch (Exception e) {
                            System.err.println("Error decodificando auth payload: " + e.getMessage());
                        }
                    } else {
                        // Formato tradicional
                        username = json.has("username") ? json.get("username").getAsString() : "";
                        password = json.has("password") ? json.get("password").getAsString() : "";
                    }

                    Map<String, Object> response = new HashMap<>();

                    // Consultar credenciales reales en la base de datos
                    Usuario usuario = usuarioRepo.login(username, password);

                    if (usuario != null) {
                        if (!usuario.isActivo()) {
                            response.put("success", false);
                            response.put("message", "Usuario desactivado. Contacte a un Administrador.");
                            sendResponse(exchange, 401, gson.toJson(response));
                            return;
                        }
                        String sessionToken = java.util.UUID.randomUUID().toString();
                        String rolSesion = normalizarRol(usuario.getRol());
                        activeTokens.put(sessionToken, new UserSession(System.currentTimeMillis(), usuario.getUsername(), rolSesion, usuario.getId(), usuario.getChoferId()));
                        response.put("success", true);
                        response.put("message", "Login exitoso");
                        response.put("usuario", usuario.getNombre());
                        response.put("rol", rolSesion);
                        response.put("token", sessionToken);
                        if (usuario.getChoferId() != null) {
                            response.put("chofer_id", usuario.getChoferId());
                        }
                        if ("chofer".equalsIgnoreCase(rolSesion)) {
                            response.put("redirect", "chofer.html");
                        } else {
                            response.put("redirect", "index.html");
                        }
                        sendResponse(exchange, 200, gson.toJson(response));
                    } else {
                        response.put("success", false);
                        response.put("message", "Usuario o contraseña incorrectos");
                        sendResponse(exchange, 401, gson.toJson(response));
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class AdminUsuariosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!isSuperAdmin(exchange)) {
                sendError(exchange, 403, "Acceso denegado. Se requieren permisos de Super Admin.");
                return;
            }

            String method = exchange.getRequestMethod();

            if ("GET".equals(method)) {
                List<Usuario> usuarios = usuarioRepo.listarUsuarios();
                sendResponse(exchange, 200, gson.toJson(usuarios));
            } else if ("POST".equals(method)) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                    String action = json.has("action") ? json.get("action").getAsString() : "";
                    if ("reset_password".equals(action)) {
                        int id = json.get("id").getAsInt();
                        String password = json.get("password").getAsString();
                        boolean ok = usuarioRepo.resetPassword(id, password);
                        if (ok) sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                        else sendError(exchange, 400, "No se pudo cambiar la contraseña");
                        return;
                    }

                    String username = json.get("username").getAsString().trim();
                    String nombre = json.get("nombre").getAsString().trim();
                    String rol = normalizarRol(json.has("rol") ? json.get("rol").getAsString() : "gestor");
                    String password = json.get("password").getAsString().trim();

                    if (username.isEmpty() || password.isEmpty() || nombre.isEmpty()) {
                        sendError(exchange, 400, "Todos los campos son obligatorios");
                        return;
                    }

                    boolean ok = usuarioRepo.crearUsuario(username, nombre, rol, password);
                    if (ok) {
                        sendResponse(exchange, 201, "{\"status\":\"created\"}");
                    } else {
                        sendError(exchange, 400, "El nombre de usuario ya existe o hubo un error");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error procesando la solicitud");
                }
            } else if ("PUT".equals(method)) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                    int id = json.get("id").getAsInt();
                    String nombre = json.get("nombre").getAsString().trim();
                    String rol = normalizarRol(json.has("rol") ? json.get("rol").getAsString() : "gestor");
                    boolean activo = json.has("activo") ? json.get("activo").getAsBoolean() : true;

                    boolean ok = usuarioRepo.actualizarUsuario(id, nombre, rol, activo);
                    if (ok) {
                        sendResponse(exchange, 200, "{\"status\":\"updated\"}");
                    } else {
                        sendError(exchange, 400, "Error al actualizar el usuario");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error procesando la solicitud");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private static void setCORS(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static boolean isAuthorized(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) {
            return false;
        }
        String authHeader = authHeaders.get(0);
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            UserSession session = activeTokens.get(token);
            if (session == null) return false;
            if (System.currentTimeMillis() - session.creationTime > TOKEN_TTL_MS) {
                activeTokens.remove(token);
                return false;
            }
            return true;
        }
        return false;
    }

    private static boolean isSuperAdmin(HttpExchange exchange) {
        if (!isAuthorized(exchange)) return false;
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        String token = authHeaders.get(0).substring(7).trim();
        UserSession session = activeTokens.get(token);
        return session != null && "superadmin".equalsIgnoreCase(session.rol);
    }

    private static Integer getUserIdFromSession(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) return null;
        String authHeader = authHeaders.get(0);
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            UserSession session = activeTokens.get(token);
            if (session != null) {
                return session.userId;
            }
        }
        return null;
    }

    private static String getUsernameFromSession(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) return null;
        String authHeader = authHeaders.get(0);
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            UserSession session = activeTokens.get(token);
            if (session != null) return session.username;
        }
        return null;
    }

    private static String getRolFromSession(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) return null;
        String authHeader = authHeaders.get(0);
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            UserSession session = activeTokens.get(token);
            if (session != null) return normalizarRol(session.rol);
        }
        return null;
    }

    /** Rol unificado: el antiguo 'admin' se trata como 'gestor'. */
    private static String normalizarRol(String rol) {
        if (rol == null) return "gestor";
        String r = rol.toLowerCase();
        if ("admin".equals(r)) return "gestor";
        return r;
    }

    /** Gestor y superadmin pueden administrar denuncias y rutas. */
    private static boolean canManageRutas(HttpExchange exchange) {
        String rol = getRolFromSession(exchange);
        return rol != null && ("gestor".equals(rol) || "admin".equals(rol) || "superadmin".equals(rol));
    }

    private static Integer getChoferIdFromSession(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) return null;
        String authHeader = authHeaders.get(0);
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            UserSession session = activeTokens.get(token);
            if (session != null) {
                return session.choferId;
            }
        }
        return null;
    }

    private static boolean isChofer(HttpExchange exchange) {
        String rol = getRolFromSession(exchange);
        return "chofer".equalsIgnoreCase(rol);
    }

    /** Crea las categorías por defecto si todavía no existe ninguna definida en el sistema. */
    private static void asegurarCategoriasDefault(Connection conn, int userId) throws SQLException {
        try (Statement countStmt = conn.createStatement();
             ResultSet rs = countStmt.executeQuery("SELECT COUNT(*) FROM categorias WHERE activo = true")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        List<String> nombres = new ArrayList<>(List.of("supermercado", "mayorista / distribuidor", "minorista/gastronómico"));
        try (Statement usadosStmt = conn.createStatement();
             ResultSet rsUsados = usadosStmt.executeQuery("SELECT DISTINCT tipo_cliente FROM clientes WHERE activo = true AND tipo_cliente IS NOT NULL AND tipo_cliente <> ''")) {
            while (rsUsados.next()) {
                String tc = rsUsados.getString(1).trim();
                boolean existe = false;
                for (String n : nombres) {
                    if (n.equalsIgnoreCase(tc)) { existe = true; break; }
                }
                if (!existe) nombres.add(tc);
            }
        }

        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO categorias (nombre, activo, usuario_id) VALUES (?, true, ?)")) {
            for (String n : nombres) {
                ins.setString(1, n);
                ins.setInt(2, userId);
                try {
                    ins.executeUpdate();
                } catch (SQLException ignored) { }
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, response.getBytes(StandardCharsets.UTF_8).length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        sendResponse(exchange, statusCode, gson.toJson(error));
    }

    // -- Optimized RouteService with Caching & Parallel Execution --
    static class RouteService {
        private static final String ORS_API_KEY = ORS_KEY.equals("PONER_AQUI_TU_API_KEY") ? System.getenv("ORS_API_KEY")
                : ORS_KEY;
        private static final String ORS_BASE_URL = "https://api.openrouteservice.org/v2/directions/driving-car";
        private static final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        
        // Thread-safe cache for distances
        private static final ConcurrentHashMap<String, Double> distanceCache = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
        private static final long CACHE_TTL_HOURS = 24;
        private static final Semaphore apiSemaphore = new Semaphore(5); // Max 5 concurrent calls

        static class RouteInfo {
            double distanceKm;
            double durationSec;

            RouteInfo(double distanceKm, double durationSec) {
                this.distanceKm = distanceKm;
                this.durationSec = durationSec;
            }
        }

        /**
         * Get route distance with intelligent caching and rate limiting.
         * Falls back to Haversine with road factor (1.3x) if API unavailable.
         */
        public static RouteInfo getRoute(double startLat, double startLon, double endLat, double endLon)
                throws IOException, InterruptedException {
            
            String cacheKey = String.format(java.util.Locale.US, "%.6f,%.6f-%.6f,%.6f", 
                startLat, startLon, endLat, endLon);
            
            // 1. Check cache first
            if (isCacheValid(cacheKey)) {
                return new RouteInfo(distanceCache.get(cacheKey), 0);
            }
            
            // 2. No API key or invalid? Use Haversine with road factor
            if (ORS_API_KEY == null || ORS_API_KEY.isEmpty() || ORS_API_KEY.equals("PONER_AQUI_TU_API_KEY")) {
                double dist = haversine(startLat, startLon, endLat, endLon) * 1.3;
                cacheResult(cacheKey, dist);
                return new RouteInfo(dist, 0);
            }
            
            // 3. Rate limit: acquire semaphore
            apiSemaphore.acquire();
            try {
                String url = String.format(java.util.Locale.US, "%s?api_key=%s&start=%.6f,%.6f&end=%.6f,%.6f",
                    ORS_BASE_URL, ORS_API_KEY, startLon, startLat, endLon, endLat);
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .GET()
                        .header("Accept", "application/json")
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    // Fallback to Haversine with road factor
                    double dist = haversine(startLat, startLon, endLat, endLon) * 1.3;
                    cacheResult(cacheKey, dist);
                    return new RouteInfo(dist, 0);
                }
                
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject summary = json.getAsJsonArray("features").get(0).getAsJsonObject()
                        .getAsJsonObject("properties").getAsJsonObject("summary");
                double distanceKm = summary.get("distance").getAsDouble() / 1000.0;
                double durationSec = summary.get("duration").getAsDouble();
                
                cacheResult(cacheKey, distanceKm);
                return new RouteInfo(distanceKm, durationSec);
                
            } catch (Exception e) {
                // Fallback on any error
                double dist = haversine(startLat, startLon, endLat, endLon) * 1.3;
                cacheResult(cacheKey, dist);
                return new RouteInfo(dist, 0);
            } finally {
                apiSemaphore.release();
            }
        }
        
        private static boolean isCacheValid(String key) {
            Long timestamp = cacheTimestamps.get(key);
            if (timestamp == null) return false;
            return (System.currentTimeMillis() - timestamp) < CACHE_TTL_HOURS * 60 * 60 * 1000;
        }
        
        private static void cacheResult(String key, double distance) {
            distanceCache.put(key, distance);
            cacheTimestamps.put(key, System.currentTimeMillis());
            
            // Cleanup old entries periodically
            if (distanceCache.size() > 10000) {
                long cutoff = System.currentTimeMillis() - CACHE_TTL_HOURS * 60 * 60 * 1000;
                cacheTimestamps.entrySet().removeIf(e -> e.getValue() < cutoff);
                distanceCache.keySet().removeIf(k -> !cacheTimestamps.containsKey(k));
            }
        }
    }

    // Existing handler classes continue below ...
    static class Cliente {
        int id;
        String nombre;
        double lat, lon;
        String tipo;

        Cliente(int id, String nombre, double lat, double lon, String tipo) {
            this.id = id;
            this.nombre = nombre;
            this.lat = lat;
            this.lon = lon;
            this.tipo = tipo;
        }
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private static List<List<Cliente>> kmeans(List<Cliente> clientes, int k, Map<String, Integer> reglas) {
        if (clientes.isEmpty()) {
            List<List<Cliente>> res = new ArrayList<>();
            for (int i = 0; i < k; i++) res.add(new ArrayList<>());
            return res;
        }

        if (clientes.size() <= k) {
            List<List<Cliente>> res = new ArrayList<>();
            for (Cliente c : clientes)
                res.add(new ArrayList<>(Arrays.asList(c)));
            while (res.size() < k) {
                res.add(new ArrayList<>());
            }
            return res;
        }

        List<double[]> centroids = new ArrayList<>();
        // Inicialización K-Means++
        Cliente c0 = clientes.get(0);
        centroids.add(new double[] { c0.lat, c0.lon });

        Set<Integer> chosenIndices = new HashSet<>();
        chosenIndices.add(0);

        for (int i = 1; i < k; i++) {
            double maxDist = -1;
            int bestIdx = 0;
            for (int j = 0; j < clientes.size(); j++) {
                if (chosenIndices.contains(j)) continue;
                Cliente c = clientes.get(j);
                double minDistToCentroids = Double.MAX_VALUE;
                for (double[] cent : centroids) {
                    double d = haversine(c.lat, c.lon, cent[0], cent[1]);
                    if (d < minDistToCentroids) {
                        minDistToCentroids = d;
                    }
                }
                if (minDistToCentroids > maxDist) {
                    maxDist = minDistToCentroids;
                    bestIdx = j;
                }
            }
            chosenIndices.add(bestIdx);
            Cliente bestC = clientes.get(bestIdx);
            centroids.add(new double[] { bestC.lat, bestC.lon });
        }

        List<List<Cliente>> clusters = new ArrayList<>();
        for (int i = 0; i < k; i++)
            clusters.add(new ArrayList<>());

        boolean changed = true;
        int maxIter = 50;
        while (changed && maxIter-- > 0) {
            for (List<Cliente> cl : clusters)
                cl.clear();

            for (Cliente c : clientes) {
                int bestK = -1;
                double bestDist = Double.MAX_VALUE;

                // Intentar asignar al más cercano que cumpla las reglas
                for (int i = 0; i < k; i++) {
                    double d = haversine(c.lat, c.lon, centroids.get(i)[0], centroids.get(i)[1]);
                    if (validarRegla(c, clusters.get(i), reglas)) {
                        if (d < bestDist - 0.0001) {
                            bestDist = d;
                            bestK = i;
                        } else if (Math.abs(d - bestDist) <= 0.0001) {
                            if (bestK == -1 || clusters.get(i).size() < clusters.get(bestK).size()) {
                                bestDist = d;
                                bestK = i;
                            }
                        }
                    }
                }

                // Si ninguna cumple por regla, asignar al más cercano por fuerza bruta
                if (bestK == -1) {
                    for (int i = 0; i < k; i++) {
                        double d = haversine(c.lat, c.lon, centroids.get(i)[0], centroids.get(i)[1]);
                        if (d < bestDist - 0.0001) {
                            bestDist = d;
                            bestK = i;
                        } else if (Math.abs(d - bestDist) <= 0.0001) {
                            if (bestK == -1 || clusters.get(i).size() < clusters.get(bestK).size()) {
                                bestDist = d;
                                bestK = i;
                            }
                        }
                    }
                }

                clusters.get(bestK).add(c);
            }

            // REBALANCING / EMPTY CLUSTER RECOVERY
            for (int i = 0; i < k; i++) {
                if (clusters.get(i).isEmpty()) {
                    int maxClusterIdx = -1;
                    int maxCount = 0;
                    for (int j = 0; j < k; j++) {
                        if (clusters.get(j).size() > maxCount) {
                            maxCount = clusters.get(j).size();
                            maxClusterIdx = j;
                        }
                    }

                    if (maxClusterIdx != -1 && maxCount > 1) {
                        List<Cliente> maxCluster = clusters.get(maxClusterIdx);
                        double[] cent = centroids.get(maxClusterIdx);
                        int furthestIdxInCluster = 0;
                        double maxD = -1;
                        for (int m = 0; m < maxCluster.size(); m++) {
                            Cliente cm = maxCluster.get(m);
                            double dist = haversine(cm.lat, cm.lon, cent[0], cent[1]);
                            if (dist > maxD) {
                                maxD = dist;
                                furthestIdxInCluster = m;
                            }
                        }
                        Cliente moved = maxCluster.remove(furthestIdxInCluster);
                        clusters.get(i).add(moved);
                    }
                }
            }

            // Recalcular centroides
            changed = false;
            for (int i = 0; i < k; i++) {
                if (clusters.get(i).isEmpty())
                    continue;
                double sumLat = 0, sumLon = 0;
                for (Cliente c : clusters.get(i)) {
                    sumLat += c.lat;
                    sumLon += c.lon;
                }
                double newLat = sumLat / clusters.get(i).size();
                double newLon = sumLon / clusters.get(i).size();
                if (Math.abs(centroids.get(i)[0] - newLat) > 0.0001
                        || Math.abs(centroids.get(i)[1] - newLon) > 0.0001) {
                    changed = true;
                }
                centroids.get(i)[0] = newLat;
                centroids.get(i)[1] = newLon;
            }
        }
        return clusters;
    }

    private static boolean validarRegla(Cliente c, List<Cliente> cluster, Map<String, Integer> reglas) {
        if (c.tipo == null)
            return true;
        String cat = c.tipo.toLowerCase();
        if (!reglas.containsKey(cat))
            return true;

        int limite = reglas.get(cat);
        if (limite <= 0)
            return true; // Sin límite

        long actual = cluster.stream().filter(cl -> cl.tipo != null && cl.tipo.toLowerCase().equals(cat)).count();
        return actual < limite;
    }

    private static List<Cliente> nearestNeighborWithPriority(List<Cliente> cluster, String prioridad) {
        if (cluster.isEmpty())
            return cluster;
        List<Cliente> unvisitedPriority = new ArrayList<>();
        List<Cliente> unvisitedNormal = new ArrayList<>();

        for (Cliente c : cluster) {
            if (c.tipo != null && c.tipo.equalsIgnoreCase(prioridad))
                unvisitedPriority.add(c);
            else
                unvisitedNormal.add(c);
        }

        List<Cliente> result = new ArrayList<>();
        double currentLat = -25.3396; // Base Central
        double currentLon = -57.5173;

        // Primero los prioritarios
        while (!unvisitedPriority.isEmpty()) {
            Cliente best = findNearest(unvisitedPriority, currentLat, currentLon);
            result.add(best);
            currentLat = best.lat;
            currentLon = best.lon;
            unvisitedPriority.remove(best);
        }

        // Luego los normales
        while (!unvisitedNormal.isEmpty()) {
            Cliente best = findNearest(unvisitedNormal, currentLat, currentLon);
            result.add(best);
            currentLat = best.lat;
            currentLon = best.lon;
            unvisitedNormal.remove(best);
        }

        return result;
    }

    private static Cliente findNearest(List<Cliente> lista, double lat, double lon) {
        double bestDist = Double.MAX_VALUE;
        Cliente bestNext = null;
        for (Cliente c : lista) {
            double d = haversine(lat, lon, c.lat, c.lon);
            if (d < bestDist) {
                bestDist = d;
                bestNext = c;
            }
        }
        return bestNext;
    }

    private static double[] parseGoogleMapsUrl(String url) {
        try {
            url = url.trim();
            // Soporte para formato DMS: 25°07'53.7"S 57°20'51.7"W
            if (url.contains("°")) {
                return parseDMS(url);
            }
            // Soporte para coordenadas decimales simples: -25.123, -57.123
            if (url.contains(",") && !url.contains("http")) {
                String[] p = url.split(",");
                return new double[] { Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()) };
            }
            // Formato estándar @lat,lon de Google Maps
            if (url.contains("@")) {
                String part = url.split("@")[1];
                String[] coords = part.split(",");
                return new double[] { Double.parseDouble(coords[0]), Double.parseDouble(coords[1]) };
            }
            // Otros parámetros de búsqueda
            String[] patterns = { "q=", "ll=", "query=" };
            for (String p : patterns) {
                if (url.contains(p)) {
                    String part = url.split(p)[1].split("&")[0];
                    if (part.contains(",")) {
                        String[] coords = part.split(",");
                        return new double[] { Double.parseDouble(coords[0]), Double.parseDouble(coords[1]) };
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error parseando: " + url);
        }
        return new double[] { -25.286, -57.611 };
    }

    private static double[] parseDMS(String dms) {
        try {
            // Ejemplo: 25°07'53.7"S 57°20'51.7"W
            String[] parts = dms.split(" ");
            double lat = convertDMSToDecimal(parts[0]);
            double lon = convertDMSToDecimal(parts[1]);
            return new double[] { lat, lon };
        } catch (Exception e) {
            return new double[] { -25.286, -57.611 };
        }
    }

    private static double convertDMSToDecimal(String part) {
        // 25°07'53.7"S
        String degrees = part.split("°")[0];
        String minutes = part.split("°")[1].split("'")[0];
        String seconds = part.split("'")[1].split("\"")[0];
        String direction = part.substring(part.length() - 1);

        double dd = Math.abs(Double.parseDouble(degrees)) +
                (Double.parseDouble(minutes) / 60.0) +
                (Double.parseDouble(seconds) / 3600.0);

        if (direction.equalsIgnoreCase("S") || direction.equalsIgnoreCase("W")) {
            dd = dd * -1;
        }
        return dd;
    }

    private static String determinarCiudad(double lat, double lon) {
        Object[][] centros = {
                { "Asunción", -25.2864, -57.6115 },
                { "San Lorenzo", -25.3396, -57.5173 },
                { "Luque", -25.2691, -57.4851 },
                { "Lambaré", -25.3458, -57.6064 },
                { "Fernando de la Mora", -25.3261, -57.5544 },
                { "Capiatá", -25.3533, -57.4261 },
                { "Ñemby", -25.3941, -57.5352 },
                { "Mariano Roque Alonso", -25.2161, -57.5323 },
                { "Villa Elisa", -25.3671, -57.5901 },
                { "Itauguá", -25.3854, -57.3342 },
                { "Limpio", -25.1661, -57.4761 },
                { "Villa Hayes", -25.0931, -57.5250 },
                { "Benjamín Aceval", -25.0111, -57.3300 },
                { "Emboscada", -25.1141, -57.3481 },
                { "Arroyos y Esteros", -25.0661, -56.9331 }
        };
        String mejorCiudad = "Gran Asunción";
        double menorDistancia = 15.0; // Radio de búsqueda
        for (Object[] centro : centros) {
            double dist = haversine(lat, lon, (double) centro[1], (double) centro[2]);
            if (dist < menorDistancia) {
                menorDistancia = dist;
                mejorCiudad = (String) centro[0];
            }
        }
        return mejorCiudad;
    }

    static class ChoferesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    boolean manager = canManageRutas(exchange);
                    String sql = manager
                            ? "SELECT * FROM choferes WHERE activo = true ORDER BY nombre"
                            : "SELECT * FROM choferes WHERE activo = true AND usuario_id = ? ORDER BY nombre";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();
                    List<Map<String, Object>> choferes = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> c = new HashMap<>();
                        int cId = rs.getInt("id");
                        c.put("id", cId);
                        c.put("nombre", rs.getString("nombre"));
                        c.put("telefono", rs.getString("telefono"));

                        // Verificar si tiene ruta activa en_curso
                        try (PreparedStatement pR = conn.prepareStatement("SELECT token, movil_numero, vehiculo_nombre, clientes_json FROM rutas_generadas WHERE chofer_id = ? AND estado = 'en_curso' LIMIT 1")) {
                            pR.setInt(1, cId);
                            ResultSet rsR = pR.executeQuery();
                            if (rsR.next()) {
                                c.put("ruta_activa_token", rsR.getString("token"));
                                c.put("ruta_activa_movil", rsR.getInt("movil_numero"));
                                c.put("ruta_activa_vehiculo", rsR.getString("vehiculo_nombre"));
                                String jsonCli = rsR.getString("clientes_json");
                                int paradasCount = 0;
                                if (jsonCli != null) {
                                    List<?> l = gson.fromJson(jsonCli, List.class);
                                    if (l != null) paradasCount = l.size();
                                }
                                c.put("ruta_activa_paradas", paradasCount);
                            }
                        } catch (Exception ignored) {}

                        choferes.add(c);
                    }
                    sendResponse(exchange, 200, gson.toJson(choferes));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String nombre = (String) req.get("nombre");
                    String telefono = (String) req.get("telefono");

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "INSERT INTO choferes (nombre, telefono, usuario_id) VALUES (?, ?, ?)";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, nombre);
                        pstmt.setString(2, telefono);
                        pstmt.setInt(3, userId);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 201, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    int id = Integer.parseInt(query.split("id=")[1].split("&")[0]);
                    boolean manager = canManageRutas(exchange);
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = manager
                                ? "UPDATE choferes SET activo = false WHERE id = ?"
                                : "UPDATE choferes SET activo = false WHERE id = ? AND usuario_id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setInt(1, id);
                        if (!manager) {
                            pstmt.setInt(2, userId);
                        }
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    int id = ((Double) req.get("id")).intValue();
                    String nombre = (String) req.get("nombre");
                    String telefono = (String) req.get("telefono");
                    boolean manager = canManageRutas(exchange);
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = manager
                                ? "UPDATE choferes SET nombre = ?, telefono = ? WHERE id = ?"
                                : "UPDATE choferes SET nombre = ?, telefono = ? WHERE id = ? AND usuario_id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, nombre);
                        pstmt.setString(2, telefono);
                        pstmt.setInt(3, id);
                        if (!manager) {
                            pstmt.setInt(4, userId);
                        }
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"updated\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class VehiculosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }
            boolean manager = canManageRutas(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = manager
                            ? "SELECT * FROM vehiculos WHERE activo = true ORDER BY nombre"
                            : "SELECT * FROM vehiculos WHERE activo = true AND usuario_id = ? ORDER BY nombre";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();
                    List<Map<String, Object>> vehiculos = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> v = new HashMap<>();
                        v.put("id", rs.getInt("id"));
                        v.put("nombre", rs.getString("nombre"));
                        v.put("chapa", rs.getString("chapa"));
                        v.put("tipo", rs.getString("tipo"));
                        vehiculos.add(v);
                    }
                    sendResponse(exchange, 200, gson.toJson(vehiculos));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String nombre = (String) req.get("nombre");
                    String chapa = (String) req.get("chapa");
                    String tipo = (String) req.get("tipo");

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "INSERT INTO vehiculos (nombre, chapa, tipo, usuario_id) VALUES (?, ?, ?, ?)";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, nombre);
                        pstmt.setString(2, chapa);
                        pstmt.setString(3, tipo);
                        pstmt.setInt(4, userId);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 201, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    int id = Integer.parseInt(query.split("id=")[1].split("&")[0]);
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = manager
                                ? "UPDATE vehiculos SET activo = false WHERE id = ?"
                                : "UPDATE vehiculos SET activo = false WHERE id = ? AND usuario_id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setInt(1, id);
                        if (!manager) {
                            pstmt.setInt(2, userId);
                        }
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    int id = ((Double) req.get("id")).intValue();
                    String nombre = (String) req.get("nombre");
                    String chapa = (String) req.get("chapa");
                    String tipo = (String) req.get("tipo");
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = manager
                                ? "UPDATE vehiculos SET nombre = ?, chapa = ?, tipo = ? WHERE id = ?"
                                : "UPDATE vehiculos SET nombre = ?, chapa = ?, tipo = ? WHERE id = ? AND usuario_id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, nombre);
                        pstmt.setString(2, chapa);
                        pstmt.setString(3, tipo);
                        pstmt.setInt(4, id);
                        if (!manager) {
                            pstmt.setInt(5, userId);
                        }
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"updated\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
    static class AsignarRecursosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    List<Map<String, Object>> assignments = gson.fromJson(body, List.class);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        conn.setAutoCommit(false); // Usar transaccion
                        try {
                            for (Map<String, Object> asig : assignments) {
                                String token = (String) asig.get("token");
                                int cId = ((Double) asig.get("chofer_id")).intValue();
                                int vId = ((Double) asig.get("vehiculo_id")).intValue();

                                String cNombre = "";
                                String vNombre = "";

                                // Obtener nombres
                                try (PreparedStatement p1 = conn.prepareStatement("SELECT nombre FROM choferes WHERE id = ?")) {
                                    p1.setInt(1, cId);
                                    ResultSet rs1 = p1.executeQuery();
                                    if (rs1.next()) cNombre = rs1.getString("nombre");
                                }
                                try (PreparedStatement p2 = conn.prepareStatement("SELECT nombre FROM vehiculos WHERE id = ?")) {
                                    p2.setInt(1, vId);
                                    ResultSet rs2 = p2.executeQuery();
                                    if (rs2.next()) vNombre = rs2.getString("nombre");
                                }

                                // Verificar si el chofer ya tiene una ruta en curso
                                try (PreparedStatement pCheck = conn.prepareStatement("SELECT token FROM rutas_generadas WHERE chofer_id = ? AND estado = 'en_curso' AND token != ?")) {
                                    pCheck.setInt(1, cId);
                                    pCheck.setString(2, token);
                                    ResultSet rsCheck = pCheck.executeQuery();
                                    if (rsCheck.next()) {
                                        conn.rollback();
                                        sendError(exchange, 400, "El chofer " + cNombre + " ya tiene una ruta en curso y debe finalizarla primero.");
                                        return;
                                    }
                                }

                                // Si viene la lista de clientes (por cambios en el ruteo manual), actualizar clientes_json y entregas
                                if (asig.containsKey("clientes")) {
                                    List<Map<String, Object>> clientes = (List<Map<String, Object>>) asig.get("clientes");
                                    double distTot = (Double) asig.getOrDefault("distancia_total", 0.0);
                                    int tiempoEst = ((Double) asig.getOrDefault("tiempo_estimado", 0.0)).intValue();

                                    String sqlUpdate = "UPDATE rutas_generadas SET chofer_id = ?, vehiculo_id = ?, chofer_nombre = ?, vehiculo_nombre = ?, clientes_json = ?, distancia_total = ?, tiempo_estimado = ? WHERE token = ?";
                                    PreparedStatement pstmt = conn.prepareStatement(sqlUpdate);
                                    pstmt.setInt(1, cId);
                                    pstmt.setInt(2, vId);
                                    pstmt.setString(3, cNombre);
                                    pstmt.setString(4, vNombre);
                                    pstmt.setString(5, gson.toJson(clientes));
                                    pstmt.setDouble(6, distTot);
                                    pstmt.setInt(7, tiempoEst);
                                    pstmt.setString(8, token);
                                    pstmt.executeUpdate();

                                    // Actualizar entregas: Borrar anteriores y reinsertar según el nuevo orden/lista
                                    try (PreparedStatement pDel = conn.prepareStatement("DELETE FROM entregas WHERE ruta_token = ?")) {
                                        pDel.setString(1, token);
                                        pDel.executeUpdate();
                                    }
                                    
                                    String insertEntregas = "INSERT INTO entregas (ruta_token, cliente_id, estado, orden_en_ruta) VALUES (?, ?, 'pendiente', ?)";
                                    try (PreparedStatement pIns = conn.prepareStatement(insertEntregas)) {
                                        for (int i = 0; i < clientes.size(); i++) {
                                            int clientId = ((Double) clientes.get(i).get("id")).intValue();
                                            pIns.setString(1, token);
                                            pIns.setInt(2, clientId);
                                            pIns.setInt(3, i + 1);
                                            pIns.addBatch();
                                        }
                                        pIns.executeBatch();
                                    }
                                } else {
                                    // Solo actualizar recursos (formato antiguo o sin cambios de ruteo)
                                    String sql = "UPDATE rutas_generadas SET chofer_id = ?, vehiculo_id = ?, chofer_nombre = ?, vehiculo_nombre = ? WHERE token = ?";
                                    PreparedStatement pstmt = conn.prepareStatement(sql);
                                    pstmt.setInt(1, cId);
                                    pstmt.setInt(2, vId);
                                    pstmt.setString(3, cNombre);
                                    pstmt.setString(4, vNombre);
                                    pstmt.setString(5, token);
                                    pstmt.executeUpdate();
                                }
                            }
                            conn.commit();
                        } catch (Exception e) {
                            conn.rollback();
                            throw e;
                        }
                    }
                    sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                } catch (Exception e) {
                    e.printStackTrace();
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class KmlImportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String url = json.has("url") ? json.get("url").getAsString() : "";
                    
                    if (url.isEmpty()) { sendError(exchange, 400, "URL requerida"); return; }
                    
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        sendError(exchange, 400, "URL inválida");
                        return;
                    }

                    java.net.URI parsedUri = java.net.URI.create(url);
                    String host = parsedUri.getHost();
                    if (host == null) { sendError(exchange, 400, "URL inválida"); return; }
                    String hostLower = host.toLowerCase();
                    if (hostLower.equals("localhost") || hostLower.equals("127.0.0.1") || hostLower.equals("[::1]")
                            || hostLower.startsWith("10.") || hostLower.startsWith("172.") || hostLower.startsWith("192.168.")
                            || hostLower.startsWith("0.") || hostLower.endsWith(".local") || hostLower.endsWith(".internal")) {
                        sendError(exchange, 400, "URL no permitida");
                        return;
                    }
                    
                    if (url.contains("google.com/maps/d/")) {
                        if (url.contains("mid=")) {
                            String mid = url.split("mid=")[1].split("&")[0];
                            url = "https://www.google.com/maps/d/u/0/kml?mid=" + mid + "&forcekml=1";
                        }
                    }

                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest request = HttpRequest.newBuilder().uri(java.net.URI.create(url)).build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() != 200) {
                        sendError(exchange, 500, "Error al descargar KML: " + response.statusCode());
                        return;
                    }

                    String kml = response.body();
                    List<Map<String, String>> points = parseKml(kml);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "INSERT INTO clientes (nombre, latitud, longitud, ciudad, tipo_cliente, activo, usuario_id) VALUES (?, ?, ?, ?, 'General', true, ?)";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        for (Map<String, String> p : points) {
                            double lat = Double.parseDouble(p.get("lat"));
                            double lon = Double.parseDouble(p.get("lon"));
                            pstmt.setString(1, p.get("name"));
                            pstmt.setDouble(2, lat);
                            pstmt.setDouble(3, lon);
                            pstmt.setString(4, determinarCiudad(lat, lon));
                            pstmt.setInt(5, userId);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                    sendResponse(exchange, 200, "{\"status\":\"ok\", \"imported\":" + points.size() + "}");
                } catch (Exception e) {
                    e.printStackTrace();
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }

        private List<Map<String, String>> parseKml(String kml) {
            List<Map<String, String>> points = new ArrayList<>();
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("<Placemark>(.*?)</Placemark>", java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher m = p.matcher(kml);
                while (m.find()) {
                    String content = m.group(1);
                    String name = "Sin nombre";
                    if (content.contains("<name>")) {
                        name = content.split("<name>")[1].split("</name>")[0].replaceAll("<!\\[CDATA\\[(.*)\\]\\]>", "$1");
                    }
                    if (content.contains("<coordinates>")) {
                        String coords = content.split("<coordinates>")[1].split("</coordinates>")[0].trim();
                        String[] parts = coords.split(",");
                        if (parts.length >= 2) {
                            Map<String, String> point = new HashMap<>();
                            point.put("name", name);
                            point.put("lon", parts[0].trim());
                            point.put("lat", parts[1].trim());
                            points.add(point);
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
            return points;
        }
    }

    static class KmlExportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            Integer userId = getUserIdFromSession(exchange);
            if (userId == null) { sendError(exchange, 401, "No autorizado"); return; }
            boolean manager = canManageRutas(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = manager
                            ? "SELECT nombre, latitud, longitud FROM clientes WHERE activo = true"
                            : "SELECT nombre, latitud, longitud FROM clientes WHERE activo = true AND usuario_id = ?";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    if (!manager) {
                        stmt.setInt(1, userId);
                    }
                    ResultSet rs = stmt.executeQuery();

                    StringBuilder kml = new StringBuilder();
                    kml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                    kml.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
                    kml.append("<Document>\n");
                    kml.append("  <name>Clientes Ruteo</name>\n");

                    while (rs.next()) {
                        kml.append("  <Placemark>\n");
                        kml.append("    <name>").append(rs.getString("nombre")).append("</name>\n");
                        kml.append("    <Point>\n");
                        kml.append("      <coordinates>").append(rs.getDouble("longitud")).append(",").append(rs.getDouble("latitud")).append(",0</coordinates>\n");
                        kml.append("    </Point>\n");
                        kml.append("  </Placemark>\n");
                    }

                    kml.append("</Document>\n");
                    kml.append("</kml>");

                    byte[] resp = kml.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/vnd.google-earth.kml+xml");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"clientes_exportados.kml\"");
                    exchange.sendResponseHeaders(200, resp.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(resp);
                    os.close();
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }
}