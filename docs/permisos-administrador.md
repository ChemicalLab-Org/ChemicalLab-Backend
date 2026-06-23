# Auditoría de permisos del rol Administrador

Este documento define y deja registrada la auditoría del alcance real del rol
**ADMINISTRADOR** dentro de ChemicalLab. El criterio rector es que el administrador
ejerce supervisión institucional y gestión básica de usuarios, sin reemplazar al
docente en la gestión pedagógica diaria (creación, edición o calificación de
contenidos y evaluaciones).

Los tres roles del sistema son: `ADMINISTRADOR`, `DOCENTE` y `ESTUDIANTE`.

## Modelo de control de acceso

La autorización se aplica en dos capas que deben mantenerse alineadas:

- **Backend (fuente de verdad):** `SecurityConfig` declara las reglas de acceso por
  ruta y método HTTP sobre una API REST sin estado (JWT). Cualquier acción no
  permitida se rechaza en el servidor con `401` (sin sesión) o `403` (sesión válida
  sin permiso), independientemente de lo que muestre el frontend.
- **Frontend (experiencia):** las rutas de Angular se protegen con `authGuard`,
  `temporaryPasswordGuard` y `roleGuard`. El menú lateral del administrador
  (`ADMIN_NAV_ITEMS`) solo ofrece accesos a funciones permitidas, evitando que se
  muestren acciones que el backend bloquearía.

### Reglas de navegación

| Situación | Resultado |
|-----------|-----------|
| Usuario sin sesión que intenta abrir una ruta privada | Redirección a `/auth/login` |
| Usuario con sesión pero sin el rol requerido | Redirección a `/forbidden` |
| Ruta inexistente | Componente `not-found` (comodín `**`) |
| Sesión con contraseña temporal pendiente | Redirección a cambio obligatorio de contraseña |

El `roleGuard` envía a `/auth/login` cuando no hay rol en sesión y a `/forbidden`
cuando hay sesión pero el rol no está autorizado para la ruta.

## Matriz de permisos por módulo

Convenciones: **Sí** = permitido; **No** = no permitido; **Lectura** = solo
consulta, sin escritura.

| Módulo | Acción | Admin | Docente | Estudiante | Observación |
|--------|--------|:-----:|:-------:|:----------:|-------------|
| Login | Autenticarse | Sí | Sí | Sí | Endpoint público `POST /api/auth/login`. |
| Login | Cambio de contraseña temporal | Sí | Sí | Sí | `PATCH /api/auth/change-temporary-password`, requiere sesión. |
| Dashboard admin | Acceder al panel `/admin-dashboard` | Sí | No | No | Métricas de solo lectura vía `GET /api/admin/summary`. |
| Usuarios | Ver todos los usuarios del sistema | Sí | No | No | `GET /api/admin/users` lista cuentas de todos los roles, incluidos estudiantes creados por docentes. Nunca expone contraseñas. |
| Usuarios | Crear docentes | Sí | No | No | `POST /api/users/teachers`. |
| Usuarios | Crear estudiantes | Parcial | Sí | No | Hoy solo el docente los crea (`POST /api/users/teachers/{id}/students`), por la relación grado/sección/docente. Pendiente para admin (ver sección final). |
| Usuarios | Restablecer contraseña de usuarios gestionables | Sí | Parcial | No | `PATCH /api/admin/users/{id}/password/reset` opera sobre cualquier cuenta (no requiere correo). El docente solo restablece a sus estudiantes. El admin no puede restablecer su propia cuenta ni la de otro admin. |
| Usuarios | Activar / desactivar usuarios | Sí | Parcial | No | `PATCH /api/users/{id}/activate` y `/deactivate` (admin). El admin no puede autodesactivarse (cuenta «Protegida»). El docente solo desactiva a sus estudiantes. |
| Usuarios | Ver o registrar contraseñas / contraseñas temporales | No | No | No | El sistema solo expone un indicador booleano de «contraseña temporal pendiente», nunca el valor. |
| Docentes | Supervisar la existencia de docentes | Sí | — | — | Listado y gestión desde `/admin/teachers` y `/admin/users`. |
| Estudiantes | Supervisar la existencia de estudiantes | Sí | Sí | — | El admin los ve en el listado unificado; el docente gestiona los suyos. |
| Estudiantes | Reasignar o romper la relación docente-estudiante | No | No | No | No existe acción destructiva de reasignación; fuera de alcance. |
| Contenidos | Supervisar contenidos (lectura) | Sí | Sí | Sí | Endpoints de lectura: admin `GET /api/concepts/admin`; docente sobre los propios; estudiante sobre los asignados. Aún sin pantalla de supervisión en el panel admin. |
| Contenidos | Crear / editar / eliminar / reasignar | No | Sí | No | Exclusivo del docente (`/api/concepts/teacher/**`). |
| Evaluaciones | Supervisar evaluaciones (lectura) | Sí | Sí | Sí | Endpoints de lectura: admin `GET /api/evaluations/admin` y `/admin/{id}`; docente sobre las propias; estudiante sobre las asignadas. El admin ve información general y el contenido de las preguntas, pero **no** la alternativa correcta (ver detalle abajo). Aún sin pantalla de supervisión en el panel admin. |
| Evaluaciones | Visualizar la clave de respuestas (alternativa correcta) | No | Sí | No | El detalle admin usa un DTO específico (`AdminEvaluationDetailResponse`) que omite la alternativa correcta y la explicación. Solo el docente la ve en su propio detalle. |
| Evaluaciones | Crear / editar / publicar / archivar / asignar | No | Sí | No | Exclusivo del docente (`/api/evaluations/teacher/**`). |
| Evaluaciones | Modificar preguntas o alternativas | No | Sí | No | El admin no dispone de endpoints de escritura sobre evaluaciones. |
| Resultados | Consultar resultados (lectura) | Parcial | Sí | Sí | El docente ve los resultados de sus evaluaciones; el estudiante los propios. La supervisión agregada para admin queda pendiente (ver sección final). |
| Resultados | Alterar calificaciones, recalcular, editar intentos o puntajes | No | No | No | No existen endpoints de escritura sobre intentos; fuera de alcance. |
| Logs | Ver y filtrar la auditoría del sistema | Sí | No | No | `GET /api/admin/logs`, `/logs/{id}`, `/logs/summary`. Los registros no contienen contraseñas, contraseñas temporales ni tokens. |
| Estado del sistema | Ver estado de backend y base de datos | Sí | No | No | `GET /api/health` valida PostgreSQL con `SELECT 1` y devuelve `database.status` UP/DOWN con latencia; no expone credenciales ni la URL JDBC completa. |
| Rutas no autorizadas | Acceso de un rol a rutas de otro rol | No | No | No | El backend responde `403` y el frontend redirige a `/forbidden`. |

## Respaldo de la auditoría

### Backend — reglas vigentes (`SecurityConfig`)

- `/api/admin/**` (resumen, usuarios, actividad, restablecimiento de contraseña) y
  `/api/admin/logs/**`: solo `ADMINISTRADOR`.
- `POST`/`GET` `/api/users/teachers`, `PATCH /api/users/teachers/*/deactivate` y
  `/reset-password`: solo `ADMINISTRADOR`.
- `PATCH /api/users/*/activate` y `/deactivate`: solo `ADMINISTRADOR`.
- Gestión de estudiantes `/api/users/teachers/*/students/**`: solo `DOCENTE`.
- Contenidos y evaluaciones segmentados por sufijo de ruta:
  `/teacher/**` (DOCENTE), `/student/**` (ESTUDIANTE), `/admin/**` (ADMINISTRADOR).
- La supervisión admin del detalle de una evaluación (`GET /api/evaluations/admin/{id}`)
  responde con `AdminEvaluationDetailResponse`, un DTO de solo lectura que omite la
  alternativa correcta y la explicación de cada pregunta. El detalle del docente
  conserva la clave de respuestas para su propio flujo.
- `/api/health` y `POST /api/auth/login`: públicos. Cualquier otra ruta requiere
  autenticación.

El `AdminService` es de solo lectura para métricas, listado de usuarios y actividad
reciente; la única operación de escritura (restablecer contraseña) genera una clave
temporal cifrada con BCrypt, devuelve el texto plano una sola vez y **no lo registra**
en los logs.

### Frontend — rutas y navegación

- Todas las rutas `/admin/**` y `/admin-dashboard` exigen
  `[authGuard, temporaryPasswordGuard, roleGuard]` con `roles: ['ADMINISTRADOR']`.
- El menú del administrador (`ADMIN_NAV_ITEMS`) ofrece exactamente: Inicio,
  Gestión de docentes, Usuarios y roles, Logs del sistema y Estado del sistema.
  Todos apuntan a rutas existentes; no hay enlaces rotos ni accesos a edición
  académica.
- Los componentes `admin/users`, `admin/teachers`, `admin/logs` y
  `admin/system-status`, junto con `admin-dashboard`, consumen la misma fuente de
  navegación, evitando divergencias entre pantallas.

## Resultado de la auditoría

El modelo de permisos del administrador ya se encuentra correctamente implementado
y alineado entre backend y frontend. No se detectaron rutas administrativas
desprotegidas, accesos cruzados entre roles, enlaces de menú rotos ni acciones de
edición académica expuestas al administrador. Tampoco se hallaron fugas de
contraseñas, contraseñas temporales ni tokens en respuestas o logs.

## Pendientes para próximas sesiones

Estos puntos quedan documentados como base mínima para la sesión de **gestión
completa de usuarios desde admin** y la de **supervisión académica**:

1. **Creación de estudiantes desde el admin.** Requiere definir cómo se resuelven
   grado, sección y docente responsable cuando el creador no es un docente. Hoy se
   mantiene el flujo seguro existente (solo el docente los crea).
2. **Edición de datos básicos de usuarios desde el admin.** Actualmente la edición
   de estudiantes pertenece al docente; falta definir un endpoint de edición básica
   a nivel administrativo.
3. **Pantallas de supervisión académica (solo lectura).** Los endpoints
   `GET /api/concepts/admin` y `GET /api/evaluations/admin` ya existen, pero el
   panel admin todavía no tiene vistas que los consuman.
4. **Resultados agregados institucionales.** Definir una vista de solo lectura para
   que el admin consulte indicadores generales sin acceder al detalle de intentos.
