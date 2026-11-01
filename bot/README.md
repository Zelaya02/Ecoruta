# 🚛 NEXO EcoRuta - Bot de Telegram para Conductores Recolectores

Microservicio interactivo en **Node.js** con [Telegraf](https://telegraf.js.org/) y **Express** diseñado para el despacho automático de denuncias de acumulación de residuos a los camiones recolectores asignados por zona.

---

## 📌 Arquitectura y Flujo del Negocio

```mermaid
sequenceDiagram
    autonumber
    actor Ciudadano
    participant Sistema as Backend EcoRuta / Gestor
    participant Micro as Bot Microservicio (Node.js :4000)
    participant TG as API Telegram
    actor Conductor as Conductor Camión (ID: 8727434553)
    participant DB as PostgreSQL (Local / Aiven)

    Ciudadano->>Sistema: Registra denuncia en "Zona Centro"
    Sistema->>Micro: POST /api/despacho/denuncia
    Micro->>DB: Consulta/Verifica estado de la denuncia
    Micro->>TG: sendMessage + sendLocation + Inline Buttons
    TG->>Conductor: Alerta con [✅ Aceptar Recolección] [❌ Rechazar]
    Conductor->>TG: Clic [✅ Aceptar Recolección]
    TG->>Micro: Callback Query (accept_ID)
    Micro->>DB: UPDATE denuncias SET estado = 'en_proceso'
    Micro->>TG: Edita mensaje a "En camino" con [🏁 Marcar como Recolectado]
    Conductor->>TG: Clic [🏁 Marcar como Recolectado]
    TG->>Micro: Callback Query (complete_ID)
    Micro->>DB: UPDATE denuncias SET estado = 'cerrada', fecha_cierre = NOW()
    Micro->>TG: Edita mensaje a "✅ Recolección finalizada con éxito"
```

---

## ⚙️ Parámetros de Prueba Configurados

* **Telegram Chat ID del Conductor de Pruebas:** `8727434553`
* **Zona de Cobertura Asignada:** `"Zona Centro"`
* **Vehículo por defecto:** `Camión Recolector #01`

---

## 🚀 Requisitos Previos

1. **Node.js** v18 o superior y **npm**.
2. **PostgreSQL** (base de datos `ruteo_db` local o PostgreSQL en Aiven).
3. **Token de Bot de Telegram** provisto por [@BotFather](https://t.me/BotFather).

---

## 🔑 1. Creación del Bot en Telegram (@BotFather)

1. Abre la aplicación de Telegram y busca al usuario verificado **[@BotFather](https://t.me/BotFather)**.
2. Inicia conversación y envía el comando:
   ```text
   /newbot
   ```
3. Elige un nombre visible para el bot (ejemplo: `EcoRuta Recolector`).
4. Elige un nombre de usuario que termine en `bot` (ejemplo: `ecoruta_recolector_bot`).
5. Copia el **HTTP API Token** generado (ejemplo: `7123456789:AAHk...`).

---

## 📝 2. Configuración de Variables de Entorno

En la carpeta `/bot`, copia el archivo `.env.example` como `.env`:

```bash
cp .env.example .env
```

Edita el archivo `.env` con tus valores:

```env
# Token obtenido de @BotFather
TELEGRAM_BOT_TOKEN=7123456789:AAHk_ejemplo_tu_token_aqui

# Telegram ID del conductor de pruebas
TEST_DRIVER_TELEGRAM_ID=8727434553

# Zona operativa asignada al conductor
DEFAULT_ZONE=Zona Centro

# Puerto del microservicio HTTP
PORT=4000

# URL del backend principal Java
BACKEND_API_URL=http://localhost:8085

# Cadena de conexión PostgreSQL (Local o Aiven)
DATABASE_URL=postgresql://postgres:Zelaya11@localhost:5432/ruteo_db
```

---

## 📦 3. Instalación de Dependencias

Ejecuta dentro del directorio `/bot`:

```bash
npm install
```

---

## 🏃 4. Ejecución del Servicio

### Modo Producción:
```bash
npm start
```

### Modo Desarrollo (con auto-recarga ante cambios):
```bash
npm run dev
```

El servicio iniciará:
* 🤖 **Bot de Telegram** (vía Long-polling).
* 🚀 **Servidor HTTP Express** en `http://localhost:4000`.

---

## 🧪 5. Prueba de Despacho de Punta a Punta

Hemos incluido un script ejecutable que simula la recepción o asunción de una denuncia en la `"Zona Centro"` y dispara la notificación interactiva:

```bash
npm run test:dispatch
```

### ¿Qué hace el test?
1. Detecta si el servidor HTTP en el puerto `4000` está activo.
2. Envía un `POST /api/despacho/denuncia` con una denuncia de prueba.
3. El microservicio busca al conductor de la `"Zona Centro"` (ID `8727434553`).
4. Si `TELEGRAM_BOT_TOKEN` está configurado:
   * El conductor recibe un mensaje en Telegram con foto, descripción y ubicación GPS.
   * Se envían los botones interactivos:
     * `[✅ Aceptar Recolección]`
     * `[❌ Rechazar]`
     * `[🗺️ Ver en Mapa / Navegar]`
5. Si no hay token configurado aún, se ejecuta en **Modo Simulación**, mostrando el resultado en consola para validación sin requerir token en entornos offline o CI/CD.

---

## 📡 Endpoints de la API del Microservicio

### `POST /api/despacho/denuncia`
Despacha una denuncia al camión recolector de la zona:

**Payload:**
```json
{
  "denunciaId": 105,
  "ticket": "ECO-20261012-A9F1",
  "zona": "Zona Centro",
  "direccion": "Palma e Independencia Nacional",
  "descripcion": "Acumulación de bolsas plásticas y ramas",
  "latitud": -25.2825,
  "longitud": -57.6351,
  "fotoUrl": "https://mi-servidor.com/foto.jpg"
}
```

**Respuesta (200 OK):**
```json
{
  "success": true,
  "mensaje": "Denuncia despachada al conductor asignado",
  "despacho": {
    "enviado": true,
    "targetChatId": "8727434553",
    "conductor": "Conductor Demo",
    "zona": "Zona Centro",
    "messageId": 142
  }
}
```

### `GET /health`
Consulta el estado de salud del servicio y parámetros configurados.

### `GET /api/conductores`
Lista todos los conductores vinculados y sus zonas activas.

### `POST /api/conductores/registrar`
Vincula un nuevo conductor dinámicamente (`telegramId`, `nombre`, `zona`, `vehiculo`).

---

## 📱 Experiencia del Conductor en Telegram

1. **Autenticación inicial:**
   * El conductor entra al bot y pulsa `/start`.
   * Si su ID coincide con `8727434553`, el bot lo saluda como conductor verificado y lo vincula a la `"Zona Centro"`.
2. **Recepción de Alerta:**
   * Recibe la alerta con detalles del vertedero/denuncia y el pin de mapa.
3. **Aceptar:**
   * Al pulsar `[✅ Aceptar Recolección]`, el mensaje cambia dinámicamente a `🚚 EN CAMINO A RECOLECCIÓN` y muestra el botón `[🏁 Marcar como Recolectado]`.
   * En PostgreSQL, la denuncia cambia a `en_proceso`.
4. **Finalizar:**
   * Al pulsar `[🏁 Marcar como Recolectado]`, la denuncia se marca como `cerrada` en PostgreSQL con fecha y hora exacta de culminación.
