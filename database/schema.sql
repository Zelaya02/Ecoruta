-- =====================================================
-- NEXO / ECORUTA - Esquema de Base de Datos (PostgreSQL)
-- Soporta: Gestión de clientes, rutas de reparto,
-- denuncias ciudadanas de residuos, agente IA de filtro
-- y bot de Telegram para conductores recolectores.
-- =====================================================

-- --------------------------------------------------
-- USUARIOS
-- Roles: superadmin, gestor (admin), ciudadano, chofer
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS usuarios (
    id SERIAL PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password TEXT NOT NULL,
    nombre TEXT,
    rol TEXT DEFAULT 'gestor',
    activo BOOLEAN DEFAULT true
);

-- Migración: asegurar columnas en BD existentes
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS activo BOOLEAN DEFAULT true;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS rol TEXT DEFAULT 'gestor';

-- Unificar rol 'admin' -> 'gestor'
UPDATE usuarios SET rol = 'gestor' WHERE rol = 'admin';

-- Usuarios por defecto (idempotente)
INSERT INTO usuarios (username, password, nombre, rol, activo) VALUES
    ('admin', 'nexo2025', 'Gestor de Rutas y Denuncias', 'gestor', true),
    ('superadmin', 'supernexo2025', 'Super Administrador', 'superadmin', true),
    ('gestor', 'gestor2026', 'Gestor de Rutas y Denuncias', 'gestor', true),
    ('ciudadano', 'ciudadano2026', 'Ciudadano Demo', 'ciudadano', true)
ON CONFLICT (username) DO NOTHING;

-- --------------------------------------------------
-- CLIENTES (para ruteo de reparto)
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS clientes (
    id SERIAL PRIMARY KEY,
    nombre TEXT NOT NULL,
    tipo_cliente TEXT,
    latitud DOUBLE PRECISION,
    longitud DOUBLE PRECISION,
    ciudad TEXT,
    cadena TEXT,
    activo BOOLEAN DEFAULT true,
    url_google TEXT,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

ALTER TABLE clientes ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE;
ALTER TABLE clientes ADD COLUMN IF NOT EXISTS url_google TEXT;

-- --------------------------------------------------
-- REGLAS DE RUTEO (límites por categoría por móvil)
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS reglas_ruteo (
    id SERIAL PRIMARY KEY,
    categoria TEXT UNIQUE NOT NULL,
    limite_por_movil INTEGER NOT NULL DEFAULT 0,
    activo BOOLEAN DEFAULT true,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

ALTER TABLE reglas_ruteo ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE;

-- --------------------------------------------------
-- CATEGORIAS (editables desde Configuración)
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS categorias (
    id SERIAL PRIMARY KEY,
    nombre TEXT NOT NULL,
    activo BOOLEAN DEFAULT true,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_categorias_nombre_usuario ON categorias (LOWER(nombre), usuario_id);
ALTER TABLE categorias ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE;

-- --------------------------------------------------
-- CHOFERES
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS choferes (
    id SERIAL PRIMARY KEY,
    nombre TEXT NOT NULL,
    telefono TEXT,
    activo BOOLEAN DEFAULT true,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

ALTER TABLE choferes ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE;

-- --------------------------------------------------
-- VEHICULOS
-- --------------------------------------------------
CREATE TABLE IF NOT EXISTS vehiculos (
    id SERIAL PRIMARY KEY,
    nombre TEXT NOT NULL,
    chapa TEXT,
    tipo TEXT DEFAULT 'camion mediano',
    activo BOOLEAN DEFAULT true,
    usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE
);

ALTER TABLE vehiculos ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE;

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

ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS estado TEXT DEFAULT 'en_curso';
ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS chofer_id INTEGER REFERENCES choferes(id) ON DELETE SET NULL;
ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS vehiculo_id INTEGER REFERENCES vehiculos(id) ON DELETE SET NULL;
ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS chofer_nombre TEXT;
ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS vehiculo_nombre TEXT;
ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS usuario_id INTEGER DEFAULT 1 REFERENCES usuarios(id) ON DELETE CASCADE;

-- --------------------------------------------------
-- ENTREGAS (puntos de cada ruta)
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

ALTER TABLE entregas ADD COLUMN IF NOT EXISTS fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- --------------------------------------------------
-- DENUNCIAS CIUDADANAS (EcoRuta - recolección de residuos)
-- Estados: pendiente -> en_proceso -> cerrada | rechazada
-- pendiente: creada por ciudadano / agente IA
-- en_proceso: su barrio/cliente entro en ruta de recolección
-- cerrada: chofer finalizo el punto vinculado
-- rechazada: agente IA o gestor la descarta
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

ALTER TABLE denuncias ADD COLUMN IF NOT EXISTS usuario_id INTEGER REFERENCES usuarios(id) ON DELETE SET NULL;
ALTER TABLE denuncias ADD COLUMN IF NOT EXISTS cliente_id INTEGER REFERENCES clientes(id) ON DELETE SET NULL;
ALTER TABLE denuncias ADD COLUMN IF NOT EXISTS ruta_token TEXT REFERENCES rutas_generadas(token) ON DELETE SET NULL;
ALTER TABLE denuncias ADD COLUMN IF NOT EXISTS observacion_cierre TEXT;
ALTER TABLE denuncias ADD COLUMN IF NOT EXISTS fecha_cierre TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_denuncias_estado ON denuncias(estado);
CREATE INDEX IF NOT EXISTS idx_denuncias_barrio ON denuncias(barrio);
CREATE INDEX IF NOT EXISTS idx_denuncias_ticket ON denuncias(ticket);
CREATE INDEX IF NOT EXISTS idx_denuncias_usuario ON denuncias(usuario_id);
CREATE INDEX IF NOT EXISTS idx_denuncias_fecha_creacion ON denuncias(fecha_creacion);

-- --------------------------------------------------
-- Fin de esquema
-- =====================================================