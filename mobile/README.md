# EcoRuta Mobile 🌿📱 (Flutter App)

Aplicación móvil oficial de **EcoRuta** desarrollada en **Flutter** para ciudadanos. Permite registrarse, iniciar sesión, capturar focos de residuos con la cámara y geolocalización GPS exacta, y enviar las evidencias al **Agente de Validación & Filtro Anti-Spam** para su verificación preliminar antes del despacho a los camiones recolectores.

---

## ✨ Características Principales

1. **Autenticación Ciudadana**:
   - **Registro**: Formulario con nombre completo, usuario/alias, teléfono y contraseña (`POST /api/registro` en backend EcoRuta).
   - **Inicio de Sesión**: Autenticación directa contra PostgreSQL con hashing BCrypt (`POST /api/login`).
   - Cierre de sesión y persistencia de sesión activa.

2. **Captura Fotográfica de Evidencia**:
   - Integración nativa con la cámara del teléfono (`image_picker`).
   - Opción para seleccionar fotografía desde la galería del dispositivo.
   - Marca de hora y previsualización inmediata.

3. **Geolocalización GPS de Alta Precisión**:
   - Obtención automática de coordenadas GPS (`geolocator` con `LocationAccuracy.high`) al momento exacto de tomar la fotografía.
   - Mapa interactivo integrado (`flutter_map` con OpenStreetMap) que muestra el punto exacto y permite ajuste fino tocando el mapa.
   - Medición de precisión y control de permisos de ubicación.

4. **Clasificación Estándar de Residuos EcoRuta**:
   - `VERTEDERO_CLANDESTINO` (Vertederos clandestinos / basurales)
   - `CONTENEDOR_LLENO` (Contenedores públicos desbordados)
   - `RESIDUO_VOLUMINOSO` (Muebles en desuso / chatarras)
   - `RESTOS_PODA` (Ramas y desechos vegetales)
   - `ANIMAL_MUERTO` (Retiro urgente en calzada pública)

5. **Envío y Verificación con Agente IA Anti-Spam**:
   - Envío de reporte y fotografía real al endpoint del Agente:
     `POST http://<IP>:5050/api/denuncias/validar-y-enviar`
   - Respuesta y veredicto en tiempo real:
     * **`VALIDA`** ➔ Estado `pendiente` con asignación de ticket único (`ECO-XXXX`).
     * **`SOSPECHOSA`** ➔ Estado `requiere_revision` (alerta de duplicado cercano en ventana de 48h o revisión humana).
     * **`RECHAZADA`** ➔ Bloqueo por filtro anti-spam (texto incoherente, publicidad o fuera de Asunción).

6. **Seguimiento y Rastreo de Camión**:
   - Búsqueda de cualquier ticket (`ECO-XXXX`).
   - Línea de tiempo del estado (Pendiente ➔ En Proceso ➔ Cerrada).
   - Datos del móvil o cuadrilla asignada.

---

## 🚀 Cómo Ejecutar la App en tu Celular

### Requisitos
- Dispositivo Android con depuración USB activada (ej. Pixel 8 Pro) o Emulador.
- Conexión a la misma red Wi-Fi de la computadora (`192.168.100.x`).

### Ejecución
```bash
cd mobile

# Verificar dispositivos conectados
flutter devices

# Ejecutar directamente en tu teléfono
flutter run
```

### Configuración de IP del Servidor
Al abrir la app, puedes tocar el icono de **engranaje ⚙️** en la esquina superior derecha de la pantalla de login para configurar la IP de la computadora donde corre EcoRuta (por defecto `192.168.100.213`).
