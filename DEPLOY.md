# Guía de despliegue — ChemicalLab

Esta guía describe cómo desplegar el sistema completo para la demo:

- **Backend** (Spring Boot) + **PostgreSQL** en **Render**.
- **Frontend** (Angular) en **Vercel** (o Render Static Site).

El backend está preparado para leer toda su configuración sensible desde variables de
entorno, con valores por defecto solo para desarrollo local. No hace falta tocar el código
para desplegar: basta con configurar las variables en Render.

---

## 1. Crear la base de datos PostgreSQL en Render

1. En el panel de Render: **New > PostgreSQL**.
2. Asigna un nombre (ej: `chemicallab-db`) y región.
3. Plan: **Free** es suficiente para la demo.
4. Tras crearla, abre la base de datos y copia el valor **Internal Database URL**
   (formato `postgres://usuario:password@host:5432/nombre_bd`).

> Spring espera la URL con prefijo `jdbc:postgresql://`. Más abajo se explica cómo
> componer las variables `SPRING_DATASOURCE_*` a partir de los datos de la conexión.

---

## 2. Crear el Web Service del backend en Render

1. **New > Web Service** y conecta el repositorio `ChemicalLab-Backend`.
2. Rama: la que quieras desplegar (normalmente `develop` o `main`).
3. Runtime: **Docker** no es necesario; usa el entorno **Java**. Configura:
   - **Build Command:** `./mvnw clean package -DskipTests`
   - **Start Command:** `java -jar target/*.jar --spring.profiles.active=prod`
4. Plan: **Free**.

> Render inyecta automáticamente la variable `PORT`; la app ya la lee con
> `server.port=${PORT:8080}`, no hay que configurarla a mano.

---

## 3. Variables de entorno del backend (Render)

Configúralas en **Environment** del Web Service:

| Variable | Descripción | Ejemplo / valor |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Perfil obligatorio de despliegue | `prod` |
| `SPRING_DATASOURCE_URL` | URL JDBC de PostgreSQL | `jdbc:postgresql://HOST:5432/NOMBRE_BD` |
| `SPRING_DATASOURCE_USERNAME` | Usuario de la base de datos | (del panel de Render) |
| `SPRING_DATASOURCE_PASSWORD` | Contraseña de la base de datos | (del panel de Render) |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Estrategia de esquema | `update` (recomendado) |
| `SPRING_JPA_SHOW_SQL` | Mostrar SQL en logs | `false` (en despliegue) |
| `APP_JWT_SECRET` | Clave JWT en **Base64** (≥ 32 bytes decodificados) | generar una nueva (ver abajo) |
| `APP_JWT_EXPIRATION_MS` | Expiración del token en ms | `86400000` (1 día) |
| `APP_CORS_ALLOWED_ORIGINS` | Orígenes del frontend, separados por comas | `https://chemicallab.vercel.app` |

**Notas:**

- La URL JDBC se compone a partir del host/usuario/bd que da Render. Asegúrate del prefijo
  `jdbc:postgresql://`.
- `SPRING_JPA_HIBERNATE_DDL_AUTO` por defecto es `update`, que **no borra datos**. No uses
  `create` en despliegue (recrearía las tablas en cada arranque). El valor `create` solo es
  útil en desarrollo local si quieres reiniciar el esquema.
- Generar una clave JWT Base64 nueva:
  - PowerShell: usar `RandomNumberGenerator` según [configuración segura](docs/configuracion-segura.md).
  - OpenSSL: `openssl rand -base64 64`
- `APP_CORS_ALLOWED_ORIGINS` admite varios orígenes separados por comas, por ejemplo:
  `https://chemicallab.vercel.app,http://localhost:4200`.

---

## 4. Probar el endpoint de health

Una vez desplegado, verifica que el backend responde:

```
GET https://TU-BACKEND.onrender.com/api/health
```

Respuesta esperada (200 OK):

```json
{
  "status": "OK",
  "message": "API del Laboratorio Químico Digital funcionando correctamente"
}
```

Este endpoint es público (no requiere autenticación) y sirve para confirmar que la app
arrancó y que la conexión a la base de datos no impide el arranque.

---

## 5. Desplegar el frontend en Vercel

> Pasos detallados en `ChemicalLab-Frontend/DEPLOY.md`. Resumen:

1. **New Project** en Vercel e importa `ChemicalLab-Frontend`.
2. Framework preset: **Angular** (o "Other").
3. **Build Command:** `npm run build`
4. **Output Directory:** `dist/chemical-lab-frontend/browser`
5. El archivo `vercel.json` del repo ya configura el rewrite SPA hacia `index.html`.

### Alternativa: Render Static Site

- **Build Command:** `npm run build`
- **Publish Directory:** `dist/chemical-lab-frontend/browser`
- Añade una regla de rewrite para que las rutas de Angular funcionen:

  ```
  /*    /index.html   200
  ```

---

## 6. Configurar la URL del backend en el frontend

Antes de desplegar el frontend, edita
`ChemicalLab-Frontend/src/environments/environment.prod.ts` y reemplaza la URL por la del
backend real en Render:

```ts
export const environment = {
  production: true,
  apiUrl: 'https://chemicallab-backend.onrender.com/api'
};
```

`ng build` (configuración de producción) reemplaza automáticamente `environment.ts` por
`environment.prod.ts`.

---

## 7. Configurar CORS con la URL final del frontend

Una vez sepas la URL pública del frontend (ej: `https://chemicallab.vercel.app`), ponla en
la variable `APP_CORS_ALLOWED_ORIGINS` del backend en Render y reinicia el servicio. Si no
coincide exactamente (incluyendo `https://` y sin barra final), el navegador bloqueará las
peticiones por CORS.

---

## 8. Primer administrador y cuentas demo

No se crean cuentas por defecto. El perfil `prod` rechaza la clave JWT pública de
desarrollo, exige conexión PostgreSQL externa y bloquea demos incluso con
`APP_DEMO_ENABLED=true`. Sin perfil explícito también se aplica producción.

El primer administrador requiere `APP_BOOTSTRAP_ADMIN_ENABLED=true`,
`APP_BOOTSTRAP_ADMIN_USERNAME` y `APP_BOOTSTRAP_ADMIN_PASSWORD` externos, sin valores
predeterminados; `APP_BOOTSTRAP_ADMIN_EMAIL` es opcional. Tras crearlo, deshabilite
la opción y retire esas credenciales. Si ya existe cualquier administrador, no crea
otro ni sobrescribe su contraseña. Se conserva `temporaryPassword=true` y el flujo
actual de cambio; su cumplimiento central en la API es T02.

Las demos `docente` y `EST0001` solo se crean bajo `dev`/`test` con
`APP_DEMO_ENABLED=true`. Deshabilitarlo conserva cuentas y perfiles existentes.

Procedimientos completos de bootstrap, inventario previo de cuentas demo, generación
criptográfica/rotación de JWT y pruebas aisladas:
[configuración segura](docs/configuracion-segura.md).

## Resumen de comprobaciones

1. Seleccione `prod` y configure JWT y DB externos; el arranque debe fallar si faltan.
2. Confirme `GET /api/health`, perfil y versión de configuración sin mostrar secretos.
3. Cree el primer administrador solo mediante el bootstrap explícito, retírelo y cambie la contraseña temporal.
4. Verifique CORS y la comunicación del frontend. No utilice demos en producción.

La aplicación conserva `ddl-auto=update`; esto no sustituye migraciones versionadas,
backup/restauración ni una revisión de infraestructura. Esta tarea no realiza despliegues.
