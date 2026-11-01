# NEXO / ECORUTA - Sistema de Ruteo Inteligente + Denuncias Ciudadanas 🚚🗑️

Plataforma completa para optimizar la logística de reparto y gestión de denuncias de residuos urbanos en Gran Asunción. Incluye:

| Componente | Carpeta | Puerto | Tecnología | Función |
|------------|---------|--------|------------|---------|
| **Backend** | `backend/` | 8080 | Java 21 + PostgreSQL | API principal, gestión de clientes, rutas, autenticación, denuncias |
| **Agente IA de Filtro** | `agent/` | 5050 | Node.js + Express | Valida denuncias (spam, geolocalización, duplicados, Gemini Vision) y las reenvía al backend |
| **Bot de Telegram** | `bot/` | 4000 | Node.js + Telegraf | Despacha rutas y denuncias a conductores recolectores por Telegram |
| **Frontend** | `frontend/` | Servido por backend | HTML/JS/CSS | Panel de gestión, formulario de denuncias, seguimiento |

---

## 🛠️ Requisitos Previos

Antes de comenzar, asegúrate de tener instalado:

1. **Java JDK 17 o superior**
2. **PostgreSQL** (puerto `5432` por defecto; también funciona en `5000`)
3. **Node.js v18 o superior** + npm
4. Un navegador web (Chrome, Edge o Firefox)

---

## 🚀 Guía de Instalación y Ejecución

Los tres servicios deben ejecutarse en **terminales separadas y simultáneas**.

### 1. Base de Datos (PostgreSQL)

Crea la base de datos:

```sql
CREATE DATABASE ruteo_db;
```

Usuario por defecto: `postgres` / Contraseña: `Zelaya11`  
(modificable en los archivos `.env`)

### 2. Backend Java (Puerto 8080)

```bash
cd backend
.\inicializar_db.bat    # Crea tablas y usuarios por defecto
.\compilar.bat           # Compila y levanta el servidor en http://localhost:8080
```

**Credenciales por defecto:**
- `admin` / `nexo2025` (gestor)
- `gestor` / `gestor2026`
- `ciudadano` / `ciudadano2026`
- `superadmin` / `supernexo2025`

---

### 3. Agente IA de Filtro (Puerto 5050)

```bash
cd agent
npm install              # Solo la primera vez
npm start
```

El agente queda escuchando en `http://localhost:5050`. Valida cada denuncia entrante:
- ✅ Geovalidación (coordenadas dentro de Gran Asunción)
- ✅ Detección de spam y texto incoherente
- ✅ Detección de duplicados cercanos (radio 150m, ventana 48h)
- ✅ Coherencia texto-foto (opcional con Gemini Vision)
- ✅ Reenvío automático al backend si la denuncia es válida

**Opcional - Gemini Vision:** Para análisis visual profundo con IA, obtén una API key en [Google AI Studio](https://aistudio.google.com/) y pégala en `agent/.env`:

```ini
GEMINI_API_KEY=tu_api_key_aqui
```

---

### 4. Bot de Telegram (Puerto 4000)

```bash
cd bot
npm install              # Solo la primera vez
npm start
```

**Para envío real de mensajes por Telegram:**
1. Abre Telegram y busca **@BotFather**
2. Envía `/newbot` y sigue los pasos
3. Copia el **HTTP API Token**
4. Pégalo en `bot/.env`:

```ini
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
```

Si no configuras el token, el bot funciona en **modo simulación** (imprime en consola, no envía mensajes reales).

---

### 5. Frontend (App)

1. Asegúrate de que el **backend** esté corriendo (Paso 2)
2. Abre el navegador: `http://localhost:8080/`
3. Inicia sesión con `admin` / `nexo2025`

---

## 🔄 Flujo Completo de Uso

```
Ciudadano reporta denuncia
         ↓
Frontend → Agente IA (puerto 5050)
         ↓
Agente valida (spam, geo, duplicados, foto)
         ↓
Si es válida → Backend (puerto 8080) → Tabla denuncias (estado: pendiente)
         ↓
Gestor genera ruta desde el panel
         ↓
Frontend → Bot de Telegram (puerto 4000)
         ↓
Conductor recibe alerta en Telegram con botones: Aceptar / Rechazar / Ver mapa
         ↓
Conductor acepta → estado cambia a 'en_proceso'
         ↓
Conductor marca como recolectado → estado cambia a 'cerrada'
```

---

## 📂 Estructura del Proyecto

```
/backend        - Código Java 21, librerías, scripts .bat
/agent          - Agente IA de validación y filtro antispam (Node.js)
/bot            - Bot de Telegram para despacho a conductores (Node.js)
/frontend       - Interfaz de usuario (HTML, CSS, JS)
/database       - schema.sql, scripts de inicialización y respaldo
/muni-demo      - Landing portal municipal
```

---

## 🔑 Endpoints Principales

| Servicio | Puerto | Endpoint | Descripción |
|----------|--------|----------|-------------|
| Backend | 8080 | `POST /api/denuncias` | Crear denuncia (requiere auth) |
| Backend | 8080 | `GET /api/denuncias?ticket=ECO-...` | Consultar denuncia por ticket (público) |
| Agente IA | 5050 | `POST /api/denuncias/validar-y-enviar` | Validar y reenviar denuncia al backend |
| Agente IA | 5050 | `POST /api/denuncias/evaluar` | Solo evaluar (dry-run, no envía) |
| Agente IA | 5050 | `GET /api/denuncias/rastreo/:ticket` | Rastreo público de ticket |
| Agente IA | 5050 | `GET /health` | Estado del agente |
| Bot TG | 4000 | `POST /api/despacho/denuncia` | Despachar denuncia a conductor |
| Bot TG | 4000 | `POST /api/despacho/ruta` | Despachar hoja de ruta completa |
| Bot TG | 4000 | `GET /health` | Estado del bot |

---

## ⚙️ Variables de Entorno (.env)

Los archivos `.env` ya están creados en `agent/` y `bot/`. Edítalos según tu entorno:

**`agent/.env`** (clave):
```ini
PORT=5050
ECORUTA_BACKEND_URL=http://localhost:8080
GEMINI_API_KEY=              # Opcional
DATABASE_URL=postgresql://postgres:Zelaya11@localhost:5432/ruteo_db
```

**`bot/.env`** (clave):
```ini
TELEGRAM_BOT_TOKEN=          # Requerido para Telegram real
PORT=4000
BACKEND_API_URL=http://localhost:8080
DATABASE_URL=postgresql://postgres:Zelaya11@localhost:5432/ruteo_db
```

---

## 🧪 Pruebas Automatizadas

```bash
# Probar el agente IA (validar spam, geo, duplicados)
cd agent && npm run test:report-flow

# Probar el bot de Telegram (despacho simulado)
cd bot && npm run test:dispatch
```

---

## 📚 Documentación Adicional

- `MANUAL_USUARIO_NEXO.md` - Manual de usuario del panel de gestión
- `agent/README.md` - Documentación detallada del Agente IA
- `bot/README.md` - Documentación detallada del Bot de Telegram
- `documentacion_diagramas.html` - Diagramas de arquitectura

---

## 📄 Licencia

**Uso interno - NEXO / ECORUTA**  
Desarrollado para optimización logística y gestión de residuos - Gran Asunción, Paraguay