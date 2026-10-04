# Deploy EcoRuta unificado en Render

Stack final: **un solo servicio** (Java + frontend estático servido por el propio jar) + **una Postgres**.
No hay Node ni Supabase en producción: el módulo de denuncias del repo
`recoleccion-basura-app` fue portado a `Main.DenunciasHandler` + tabla `denuncias`.

## 1. Publicar

```bash
git add -A
git commit -m "Unifica denuncias + rutas por roles, listo para Render"
git push origin <tu-rama>
```

## 2. Crear en Render (Blueprint)

1. Render → New → **Blueprint** → conecta `Zelaya02/Ecoruta`.
2. Render crea `ecoruta` (web docker) + `ecoruta-db` (postgres) y enlaza `DATABASE_URL`.
3. Espera el build (~3-5 min, Maven compila el fat jar).
4. Verifica `https://<tu-app>.onrender.com/api/health` → `{"status":"OK"}`.

## 3. Usuarios iniciales (se crean solos al arrancar)

| Usuario | Clave | Rol | Entra a |
|---|---|---|---|
| `superadmin` | `supernexo2025` | superadmin | `admin.html` — gestiona perfiles |
| `admin` | `nexo2025` | admin | `index.html` — rutas + denuncias |
| `gestor` | `gestor2026` | gestor | `index.html` — rutas + denuncias |
| `ciudadano` | `ciudadano2026` | ciudadano | `ciudadano.html` — crea/consulta denuncias |

⚠️ Cambia estas claves en producción desde `admin.html` (reset de contraseña).

## 4. Probar el ciclo denuncia → ruta → cierre

1. Entra como `ciudadano` → crea denuncia con punto en mapa → anota el ticket `ECO-...`.
2. Entra como `gestor` → botón **Denuncias** → la verás en `pendiente` →
   **＋ Punto de ruta** (crea el cliente) → inclúyelo al **Generar Rutas**.
   Al generarse la ruta, la denuncia pasa a `en_proceso` automáticamente.
3. Abre el link del chofer (`ruta.html?token=...`) → marca el punto **Entregado** →
   la denuncia pasa a `cerrada`. El ciudadano lo ve con su ticket.

## 5. Portal gubernamental (pendiente de tu front)

Cuando pases el front institucional:

- Súbelo como `frontend/portal.html` (landing pública, sin auth).
- Cambia en `Main.StaticHandler` la ruta `/` de `/index.html` a `/portal.html`
  (una línea), o deja `/` como está y enlaza el portal desde el login.
- El portal tendrá el botón **Acceder** → `login.html`, y tras el login cada rol
  cae en su módulo (ver `login.html`, redirección por rol).

## 6. Notas

- `ORS_API_KEY` es opcional; sin ella las distancias usan Haversine.
- La base local (`ruteo_db` puerto 5000) solo es para desarrollo; en Render
  todo viaja por `DATABASE_URL`.
- El esquema es idempotente: `initializeDatabaseSchema()` + `schema.sql`
  pueden correrse varias veces sin duplicar nada.
