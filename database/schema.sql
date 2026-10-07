-- =====================================================
-- NEXO - Esquema de Base de Datos (PostgreSQL)
-- =====================================================

-- --------------------------------------------------
-- USUARIOS
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS usuarios (
    id SERIAL PRIMARY KEY,
    username TEXT UNIQUE,
    password TEXT,
    nombre TEXT,
    rol TEXT,
    activo BOOLEAN DEFAULT true
);

-- Usuarios por defecto (idempotente)
-- Roles unificados: superadmin (gestiona perfiles), gestor (rutas + denuncias, alias admin),
-- ciudadano (crea/consulta denuncias), chofer (acceso por token de ruta)
INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES ('admin', 'nexo2025', 'Gestor de Rutas y Denuncias', 'gestor', true)
    ON CONFLICT (username) DO NOTHING;
-- Migración rol unificado: el antiguo 'admin' pasa a 'gestor'
UPDATE usuarios SET rol = 'gestor' WHERE rol = 'admin';
INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES ('superadmin', 'supernexo2025', 'Super Administrador', 'superadmin', true)
    ON CONFLICT (username) DO NOTHING;
INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES ('gestor', 'gestor2026', 'Gestor de Rutas y Denuncias', 'gestor', true)
    ON CONFLICT (username) DO NOTHING;
INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES ('ciudadano', 'ciudadano2026', 'Ciudadano Demo', 'ciudadano', true)
    ON CONFLICT (username) DO NOTHING;

-- --------------------------------------------------
-- CLIENTES
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS clientes (
    id SERIAL PRIMARY KEY,
    nombre TEXT,
    tipo_cliente TEXT,
    latitud DOUBLE PRECISION,
    longitud DOUBLE PRECISION,
    ciudad TEXT,
    cadena TEXT,
    activo BOOLEAN DEFAULT true,
    url_google TEXT,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

-- --------------------------------------------------
-- REGLAS DE RUTEO (límites por categoría por móvil)
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS reglas_ruteo (
    id SERIAL PRIMARY KEY,
    categoria TEXT UNIQUE,
    limite_por_movil INTEGER,
    activo BOOLEAN DEFAULT true,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

-- --------------------------------------------------
-- CATEGORIAS (editables desde Configuración)
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS categorias (
    id SERIAL PRIMARY KEY,
    nombre TEXT,
    activo BOOLEAN DEFAULT true,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);
-- Nombre único por usuario (case-insensitive)
CREATE UNIQUE INDEX IF NOT EXISTS uq_categorias_nombre_usuario ON categorias (LOWER(nombre), usuario_id);

-- --------------------------------------------------
-- CHOFERES
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS choferes (
    id SERIAL PRIMARY KEY,
    nombre TEXT,
    telefono TEXT,
    activo BOOLEAN DEFAULT true,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

-- --------------------------------------------------
-- VEHICULOS
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS vehiculos (
    id SERIAL PRIMARY KEY,
    nombre TEXT,
    chapa TEXT,
    tipo TEXT DEFAULT 'camion mediano',
    activo BOOLEAN DEFAULT true,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

-- --------------------------------------------------
-- RUTAS GENERADAS
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS rutas_generadas (
    token TEXT PRIMARY KEY,
    movil_numero INTEGER,
    clientes_json TEXT,
    distancia_total DOUBLE PRECISION,
    tiempo_estimado INTEGER,
    chofer_id INTEGER REFERENCES choferes(id) ON DELETE SET NULL,
    vehiculo_id INTEGER REFERENCES vehiculos(id) ON DELETE SET NULL,
    chofer_nombre TEXT,
    vehiculo_nombre TEXT,
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    estado TEXT DEFAULT 'en_curso',
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

-- --------------------------------------------------
-- ENTREGAS
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS entregas (
    id SERIAL PRIMARY KEY,
    ruta_token TEXT REFERENCES rutas_generadas(token) ON DELETE CASCADE,
    cliente_id INTEGER REFERENCES clientes(id) ON DELETE CASCADE,
    estado TEXT DEFAULT 'pendiente',
    observacion TEXT,
    orden_en_ruta INTEGER,
    fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- --------------------------------------------------
-- DENUNCIAS CIUDADANAS (port del repo recoleccion-basura-app)
-- Estados: pendiente -> en_proceso -> cerrada | rechazada
-- pendiente: creada por ciudadano. en_proceso: su barrio/cliente entro en ruta.
-- cerrada: chofer finalizo el punto vinculado. rechazada: gestor la descarta.
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS denuncias (
    id SERIAL PRIMARY KEY,
    ticket TEXT UNIQUE NOT NULL,
    nombre_ciudadano TEXT,
    telefono TEXT,
    descripcion TEXT NOT NULL,
    categoria TEXT DEFAULT 'VERTEDERO_CLANDESTINO',
    barrio TEXT NOT NULL,
    direccion_referencia TEXT,
    latitud DOUBLE PRECISION NOT NULL,
    longitud DOUBLE PRECISION NOT NULL,
    foto_url TEXT,
    estado TEXT DEFAULT 'pendiente',
    usuario_id INTEGER REFERENCES usuarios(id) ON DELETE SET NULL,
    cliente_id INTEGER REFERENCES clientes(id) ON DELETE SET NULL,
    ruta_token TEXT REFERENCES rutas_generadas(token) ON DELETE SET NULL,
    observacion_cierre TEXT,
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fecha_cierre TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_denuncias_estado ON denuncias(estado);
CREATE INDEX IF NOT EXISTS idx_denuncias_barrio ON denuncias(barrio);
CREATE INDEX IF NOT EXISTS idx_denuncias_ticket ON denuncias(ticket);
CREATE INDEX IF NOT EXISTS idx_denuncias_usuario ON denuncias(usuario_id);