# EcoRuta - Agente de Validación y Filtro Anti-Spam de Denuncias 🛡️

Módulo inteligente de validación preliminar para el ecosistema **EcoRuta**. Actúa como barrera defensiva antes de persistir y despachar denuncias ciudadanas de residuos a las cuadrillas y camiones recolectores, garantizando que solo reportes legítimos y accionables saturen el sistema operativo.

---

## 🎯 Capacidades Principales

1. **Geovalidación Jurisdiccional**:
   - Valida que las coordenadas (`latitud`, `longitud`) se ubiquen dentro del Gran Asunción / municipio cubierto por EcoRuta (Bounding box configurable: Lat [-25.40, -25.18], Lon [-57.72, -57.48]).
   - Rechaza automáticamente reportes con coordenadas fuera de rango o nulas (0, 0).

2. **Detección de Duplicados y Spam de Alta Frecuencia**:
   - Calcula distancia geoespacial (fórmula de Haversine).
   - Detecta si ya existe un reporte para la misma categoría en un radio menor a **150 metros** en las últimas **48 horas**.
   - Asigna veredicto `SOSPECHOSA` (`requiere_revision`) para evitar duplicación de rutas de recolección.

3. **Coherencia Texto - Categoría - Evidencia**:
   - Valida coherencia léxica y semántica entre la descripción ciudadana y las 5 categorías oficiales:
     * `VERTEDERO_CLANDESTINO`
     * `CONTENEDOR_LLENO`
     * `RESIDUO_VOLUMINOSO`
     * `RESTOS_PODA`
     * `ANIMAL_MUERTO`
   - Detecta texto basura, caracteres repetitivos (`asdfasdf`, `aaaa...`), longitudes insuficientes o descripciones sin relación con residuos urbanos.
   - Valida URLs y formatos de fotografías de evidencia.
   - Soporte opcional para multimodal con Google Gemini API (`GEMINI_API_KEY`) para análisis visual profundo. Si no hay API key configurada, opera con heurísticas de alta precisión sin interrumpir el servicio.

4. **Veredicto y Despacho Automatizado**:
   - **`VALIDA`** ➔ Estado `pendiente`. Se remite automáticamente vía HTTP POST a EcoRuta (`/api/denuncias`).
   - **`SOSPECHOSA`** ➔ Estado `requiere_revision`. Se registra para moderación humana previa antes de generar ruta de camión.
   - **`RECHAZADA`** ➔ Estado `rechazada`. Se descarta con motivo detallado en la bitácora de auditoría.

---

## 🏗️ Endpoints Expuestos (Puerto `5050`)

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/api/denuncias/validar-y-enviar` | **Flujo Ciudadano Principal**: Recibe la denuncia, la evalúa y la reenvía al backend Ecoruta (`/api/denuncias`) con su estado correspondiente. |
| `POST` | `/api/denuncias/evaluar` | **Modo Diagnóstico**: Evalúa la denuncia y devuelve el veredicto del agente sin persistir en el backend. |
| `GET` | `/api/denuncias/rastreo/:ticket` | Proxy de consulta de estado y seguimiento de camión recolector para el ciudadano. |
| `GET` | `/health` | Chequeo de salud del agente y conectividad con Ecoruta. |

---

## ⚙️ Variables de Entorno (`.env`)

Copia el archivo `.env.example` a `.env` y configura los valores correspondientes:

```ini
PORT=5050

# URL del backend receptor Java EcoRuta
ECORUTA_BACKEND_URL=http://localhost:8085

# Clave de Gemini (opcional para análisis multimodal visual profundo)
GEMINI_API_KEY=

# Límites Geográficos (Gran Asunción)
GEO_MIN_LAT=-25.40
GEO_MAX_LAT=-25.18
GEO_MIN_LON=-57.72
GEO_MAX_LON=-57.48

# Reglas de Duplicados
DUPLICATE_RADIUS_METERS=150
DUPLICATE_HOURS_WINDOW=48
MIN_DESCRIPTION_LENGTH=10

# Base de datos PostgreSQL para verificación de duplicados históricos
DB_HOST=localhost
DB_PORT=5432
DB_NAME=ruteo_db
DB_USER=postgres
DB_PASSWORD=Zelaya11
```

---

## 🚀 Instalación y Puesta en Marcha

```bash
cd agent

# Instalar dependencias
npm install

# Iniciar agente en producción
npm start

# Iniciar agente en modo desarrollo
npm run dev
```

---

## 🧪 Pruebas Automatizadas de Integración (`test-report-flow`)

El script de pruebas valida automáticamente los flujos críticos del agente:
1. **Caso 1: Denuncia Legítima**: Pasa la validación, recibe estado `pendiente` y se despacha a EcoRuta generando ticket `ECO-XXXX`.
2. **Caso 2A: Denuncia Spam/Texto incoherente**: Detecta texto inválido (`asdfasdf`) y rechaza el reporte.
3. **Caso 2B: Geovalidación Fallida**: Detecta coordenadas en el Océano Atlántico / fuera de Asunción y rechaza el reporte.
4. **Caso 2C: Detección de Duplicados**: Reporta en el mismo punto exacto dentro de la ventana de 48h y marca como `requiere_revision`.

Para ejecutar el test suite:

```bash
npm run test:report-flow
```
