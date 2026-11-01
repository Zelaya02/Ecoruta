# 📋 MANUAL DE USUARIO - NEXO Ruteo Inteligente

**Versión 2.0** | **Deploy en Render** | **Última actualización: 2025**

---

## 🎯 **¿ QUÉ ES NEXO

**NEXO** es un sistema de ruteo inteligente para logística de última milla. Permite:
- 📍 **Gestionar clientes** con geolocalización exacta
- 🚚 **Generar rutas óptimas** para múltiples vehículos
- 👷 **Asignar conductores** y vehículos a cada ruta
- 📱 **Seguimiento en tiempo real** desde el panel del conductor
- 📊 **Reportes y estadísticas** de operación

---

## 🌐 ACCESO AL SISTEMA

### **URL de Producción (Render)**
```
https://nexo-app-ruteo.onrender.com
```

> ⚠️ **Nota:** El primer acceso puede tardar 30-60 segundos porque Render "despierta" el servicio gratuito.

---

## 🔐 CREDENCIALES DE ACCESO

### **Administrador (Panel Principal)**
| Campo | Valor |
|-------|-------|
| **Usuario** | `admin` |
| **Contraseña** | `nexo2025` |

> 🔒 **Importante:** Cambia la contraseña en el primer acceso desde Configuración → Perfil.

### **Conductores (App Móvil)**
Los conductores acceden en:
```
https://nexo-app-ruteo.onrender.com/dashboard-chofer.html
```
- **ID Conductor:** Número asignado por admin
- **Token:** Generado por admin al crear conductor

---

## 🖥️ PANEL PRINCIPAL - GUÍA RÁPIDA

### **1. Barra de Búsqueda y Filtros**
| Elemento | Función |
|----------|---------|
| 🔍 **Buscar cliente** | Filtra por nombre en tiempo real |
| 🏷️ **Categoría** | Filtra: Todas / Minorista / Supermercado / Mayorista |
| ↕️ **Ordenar** | A-Z (alfabético) / Recién agregado |
| 🔢 **Contador** | Muestra seleccionados / total clientes |

### **2. Tabla de Clientes**
| Columna | Descripción |
|---------|-------------|
| ☑️ **Checkbox** | Seleccionar individual / todos (encabezado) |
| 🏪 **Cliente / Sucursal** | Nombre + tipo + dirección |
| ➕ **Agregar Cliente** | Botón en encabezado (abre modal) |

### **3. Acciones por Cliente** (iconos en cada fila)
| Icono | Acción |
|-------|--------|
| ✏️ **Editar** | Modificar nombre, tipo, URL Google Maps |
| 🔗 **Copiar URL** | Copia enlace Google Maps al portapapeles |
| 🗑️ **Eliminar** | Borra cliente (con confirmación) |

### **4. Generar Rutas**
1. **Selecciona** clientes con checkboxes
2. **Ajusta** número de móviles (vehículos)
3. **Clic** en **"Generar Rutas"**
4. **Espera** optimización (ahora 10x más rápido ⚡)
5. **Se abre** pestaña con resultados

---

## ➕ AGREGAR / EDITAR CLIENTES

### **Modal "Añadir Cliente" / "Editar Cliente"**
| Campo | Obligatorio | Descripción |
|-------|-------------|-------------|
| **Nombre** | ✅ Sí | Nombre comercial del cliente |
| **Tipo** | ✅ Sí | Minorista / Supermercado / Mayorista |
| **URL Google Maps** | ⚠️ Recomendada | Pegar link de Google Maps (extrae coordenadas auto) |

> 💡 **Tip:** Si pegas `https://maps.google.com/?q=-25.28,-57.63`, el sistema extrae lat/lon automáticamente.

### **Validación de Duplicados**
El sistema **compara coordenadas (lat/lon)**, no nombres.
- ❌ **No permite** dos clientes en la misma ubicación
- ✅ **Permite** mismo nombre en ubicaciones diferentes
- 🔄 **Aplica** tanto en ingreso manual como importación KML

---

## 📥 IMPORTAR CLIENTES DESDE KML/KMZ

### **Pasos:**
1. **Configuración** (⚙️) → **Importar/Exportar KML**
2. **Subir archivo** `.kml` o `.kmz` (arrastrar o clic)
3. **Vista previa** muestra clientes nuevos vs duplicados
4. **Confirmar Importación** → Solo importan coordenadas nuevas

### **Resultado:**
- ✅ **Importados:** Clientes con coordenadas nuevas
- ⚠️ **Omitidos:** Clientes en ubicaciones ya existentes

---

## ⚙️ CONFIGURACIÓN AVANZADA

### **Botón "Configurar" (⚙️) abre panel lateral:**

| Sección | Opciones |
|---------|----------|
| **🌙 Modo Oscuro** | Toggle para cambiar tema |
| **🚀 Prioridad** | Sin prioridad / Supermercados primero / Mayoristas primero / Minoristas primero |
| **📍 Punto de Inicio** | URL Google Maps del depósito/centro de distribución |
| **📋 Reglas Avanzadas** | Link a `reglas.html` (límites por categoría por móvil) |
| **🚛 Gestión de Transportes** | Conductores + Vehículos (ver abajo) |

---

## 🚛 GESTIÓN DE TRANSPORTES (CONDUCTORES + VEHÍCULOS)

### **Acceso:** Configuración → **Gestión de Transportes**

### **Pestaña "Choferes"**
| Acción | Descripción |
|--------|-------------|
| ➕ **Agregar** | Nombre + Teléfono |
| ✏️ **Editar** | Modificar datos |
| 🚫 **Inhabilitar** | Oculta de asignaciones (no borra historial) |

### **Pestaña "Vehículos"**
| Campo | Descripción |
|-------|-------------|
| **Modelo/Marca** | Ej: "Mercedes Sprinter" |
| **Chapa/Patente** | Opcional |
| **Tipo** | Camión Mediano / Grande / Motocarga / Personal |

> 🔑 **Importante:** Al crear conductor, se genera **token único** para acceso a app móvil.

---

## 📱 PANEL DEL CONDUCTOR (APP MÓVIL)

### **URL:** `https://nexo-app-ruteo.onrender.com/dashboard-chofer.html`

### **Login Conductor:**
1. Ingresar **ID Conductor** (número)
2. Ingresar **Token** (proporcionado por admin)
3. Clic **"Ingresar al Panel"**

### **Vista del Conductor:**
- 📋 **Lista de rutas asignadas** (solo las suyas)
- 🟢 **Estado:** ACTIVA / FINALIZADA / ESPERANDO
- 🏁 **Botón "Finalizar Ruta"** (solo en rutas activas)
- 🔒 **Regla:** No puede crear nueva ruta hasta finalizar la actual

---

## 📊 OTRAS SECCIONES DEL MENÚ SUPERIOR

| Botón | Descripción | URL |
|-------|-------------|-----|
| 📈 **Estadísticas** | Dashboard con métricas, gráficos, KPIs | `estadisticas.html` |
| ✅ **Estado Entregas** | Seguimiento tiempo real por móvil | `seguimiento.html` |
| 🌍 **Ver Mapa** | Mapa Google My Maps con todas las rutas | Link externo |
| 🔄 **Importar/Exportar KML** | Gestión masiva de clientes | Modal |

---

## 🔄 FLUJO DE TRABAJO TÍPICO

### **Diario (Admin):**
1. **Login** → Ver clientes pendientes
2. **Seleccionar** clientes del día
3. **Configurar** móviles + prioridad + punto inicio
4. **Generar Rutas** → Revisar optimización
5. **Asignar** conductores/vehículos (opcional)
6. **Compartir** tokens con conductores

### **Diario (Conductor):**
1. **Login** en app móvil
2. **Ver** ruta asignada del día
3. **Recorrer** paradas en orden
3. **Marcar** entregas: ✅ Entregado / ❌ No entregado
4. **Finalizar Ruta** al terminar

### **Semanal (Admin):**
1. **Estadísticas** → Revisar KPIs
2. **Estado Entregas** → Ver cumplimiento
3. **Editar Rutas Generadas** → Ver/modificar rutas históricas (hasta 7 días)

---

## 🚨 SOLUCIÓN DE PROBLEMAS COMUNES

| Problema | Solución |
|----------|----------|
| **"Sitio no carga / Error 502"** | Render duerme el servicio gratis. Esperar 30-60 seg y recargar. |
| **"Sesión expirada"** | Volver a login. Token dura 8 horas. |
| **"No hay choferes/vehículos suficientes"** | Agregar en Configuración → Gestión de Transportes. |
| **"Cliente duplicado"** | El sistema compara coordenadas. Verificar si ya existe en esa ubicación. |
| **"No finaliza ruta"** | Conductor debe usar su panel móvil y botón "Finalizar Ruta". |
| **"Rutas lentas"** | Primera vez es lento (cache frío). Las siguientes son 10x más rápidas. |

---

## 🔧 CONFIGURACIÓN DE RENDER (SOLO ADMIN TÉCNICO)

### **Variables de Entorno Requeridas:**
```bash
DB_URL=postgresql://user:pass@host:port/dbname
DB_USER=postgres
DB_PASSWORD=***
ORS_API_KEY=tu_openrouteservice_key  # Opcional: para distancias reales
FRONTEND_DIR=../frontend
```

### **Build Command:**
```bash
cd backend && ./compilar.bat
```

### **Start Command:**
```bash
cd backend && java -cp "target/classes;lib/*" com.ruteo.Main
```

---

---

## 🗑️ DENUNCIAS CIUDADANAS DE RESIDUOS (ECO RUTA)

El sistema permite a los ciudadanos reportar acumulación de basura, vertederos clandestinos, contenedores llenos, residuos voluminosos, restos de poda y animales muertos en vía pública.

### **Flujo de una denuncia:**

1. **El ciudadano reporta** desde el panel ciudadano (foto, descripción, categoría, ubicación)
2. **El Agente IA valida** (puerto 5050):
   - ¿Es spam o texto incoherente? → Rechazada
   - ¿Las coordenadas están en Gran Asunción? → Rechazada si no
   - ¿Es un duplicado cercano (<150m, últimas 48h)? → Sospechosa (requiere revisión)
   - ¿La foto muestra residuos? (Gemini Vision, opcional) → Rechazada si no
3. **Si es válida**, se guarda en la tabla `denuncias` con estado `pendiente` y se genera un ticket `ECO-XXXX`
4. **El gestor** ve la denuncia en el panel y puede incluirla en una ruta de recolección
5. **Se despacha al conductor** por Telegram con botones: Aceptar / Rechazar / Ver mapa
6. **El conductor** acepta → estado `en_proceso` → marca como recolectado → estado `cerrada`

### **Categorías soportadas:**
| Categoría | Descripción |
|-----------|-------------|
| `VERTEDERO_CLANDESTINO` | Montículos de basura arrojados ilegalmente |
| `CONTENEDOR_LLENO` | Contenedor desbordado en la acera |
| `RESIDUO_VOLUMINOSO` | Muebles, colchones, electrodomésticos, chatarra |
| `RESTOS_PODA` | Ramas, troncos, hojas acumulados |
| `ANIMAL_MUERTO` | Animal muerto que requiere retiro sanitario |

### **Estados de denuncia:**
| Estado | Significado |
|--------|-------------|
| `pendiente` | Creada, esperando ser asignada a ruta |
| `en_proceso` | Conductor asignado, en camino al punto |
| `cerrada` | Recolección finalizada |
| `rechazada` | Descartada por Agente IA o gestor |
| `requiere_revision` | Sospechosa (duplicado o baja confianza), espera revisión humana |

---

## 🤖 BOT DE TELEGRAM PARA CONDUCTORES

El bot despacha denuncias y rutas a los conductores recolectores mediante Telegram.

### **Comandos del conductor:**
| Comando | Acción |
|---------|--------|
| `/start` | Registrar y vincular al conductor con su zona |
| `/estado` | Consultar estado operativo del conductor |

### **Botones interactivos en cada alerta:**
- ✅ **Aceptar Recolección** → Cambia la denuncia a `en_proceso`
- ❌ **Rechazar** → Cambia la denuncia a `rechazada` (requiere reasignación)
- 🏁 **Marcar como Recolectado** → Cambia la denuncia a `cerrada`
- 🗺️ **Ver en Mapa** → Abre Google Maps con las coordenadas

### **Configuración:**
El token del bot se configura en `bot/.env`:
```ini
TELEGRAM_BOT_TOKEN=tu_token_de_BotFather
TEST_DRIVER_TELEGRAM_ID=8727434553
DEFAULT_ZONE=Zona Centro
```

Si no configuras el token, el bot funciona en **modo simulación** (solo imprime en consola).

---

## 📞 SOPORTE Y CONTACTO

| Canal | Disponibilidad |
|-------|----------------|
| 📧 **Email** | soporte@nexo-ruteo.com |
| 💬 **WhatsApp** | +595 992 267 868 |
| 🐛 **Bugs** | Reportar en GitHub Issues |

---

## 📝 HISTORIAL DE VERSIONES

| Versión | Fecha | Cambios Principales |
|---------|-------|---------------------|
| **2.0** | 2025 | • Optimización 10x generación rutas (paralelo + cache) |
| | | • Validación duplicados por coordenadas (no nombre) |
| | | • Botón "Agregar Cliente" en cabecera tabla |
| | | • Panel conductor con bloqueo hasta finalizar |
| | | • Múltiples conductores por ruta |
| **1.0** | 2024 | Versión inicial: CRUD clientes, rutas básicas, KML |

---

## 📄 LICENCIA

**Uso interno - NEXO Ruteo Inteligente**  
Desarrollado para optimización logística PyMEs - Gran Asunción

---

---

**¿Necesitas ayuda?**  
📱 **WhatsApp:** +595 992 267 868  
📧 **Email:** soporte@nexo-ruteo.com

*Última actualización: Junio 2025*