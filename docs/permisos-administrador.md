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
| Usuarios | Crear administradores | Sí | No | No | `POST /api/admin/users` con `role=ADMINISTRADOR`. El backend genera una contraseña temporal y la devuelve una sola vez. |
| Usuarios | Crear docentes | Sí | No | No | `POST /api/admin/users` con `role=DOCENTE` (crea `UserAccount` + `TeacherProfile`). Se mantiene el endpoint previo `POST /api/users/teachers`. |
| Usuarios | Crear estudiantes | Sí | Sí | No | El admin los crea con `POST /api/admin/users` (`role=ESTUDIANTE`), seleccionando un docente responsable **activo** (`GET /api/admin/users/teacher-options`). El docente conserva su flujo (`POST /api/users/teachers/{id}/students`). El modelo exige docente responsable; no se rompe la relación grado/sección/docente. |
| Usuarios | Editar datos básicos | Sí | Parcial | No | `PATCH /api/admin/users/{id}` actualiza nombres/apellidos, correo y, en estudiantes, grado/sección/docente responsable. No cambia usuario/código, rol ni contraseña. El docente edita a sus estudiantes por su propio flujo. |
| Usuarios | Restablecer contraseña de usuarios gestionables | Sí | Parcial | No | `PATCH /api/admin/users/{id}/password/reset` opera sobre cualquier cuenta (no requiere correo). El docente solo restablece a sus estudiantes. El admin no puede restablecer su propia cuenta. |
| Usuarios | Activar / desactivar usuarios | Sí | Parcial | No | `PATCH /api/admin/users/{id}/activate` y `/deactivate` (admin). El admin no puede autodesactivarse (cuenta «Protegida») ni desactivar al **último administrador activo**. No hay eliminación física. El docente solo desactiva a sus estudiantes. |
| Usuarios | Ver o registrar contraseñas / contraseñas temporales | No | No | No | El sistema solo expone un indicador booleano de «contraseña temporal pendiente», nunca el valor. |
| Docentes | Supervisar la existencia de docentes | Sí | — | — | Listado y gestión desde `/admin/teachers` y `/admin/users`. |
| Estudiantes | Supervisar la existencia de estudiantes | Sí | Sí | — | El admin los ve en el listado unificado; el docente gestiona los suyos. |
| Estudiantes | Reasignar o romper la relación docente-estudiante | No | No | No | No existe acción destructiva de reasignación; fuera de alcance. |
| Contenidos | Supervisar contenidos (lectura) | Sí | Sí | Sí | Endpoints de lectura: admin `GET /api/concepts/admin` y la supervisión académica `GET /api/admin/academic-supervision/concepts`; docente sobre los propios; estudiante sobre los asignados. Disponible en el panel admin (pantalla **Supervisión académica**). |
| Contenidos | Crear / editar / eliminar / reasignar | No | Sí | No | Exclusivo del docente (`/api/concepts/teacher/**`). |
| Evaluaciones | Supervisar evaluaciones (lectura) | Sí | Sí | Sí | Endpoints de lectura: admin `GET /api/evaluations/admin` y `/admin/{id}`, más la supervisión académica `GET /api/admin/academic-supervision/evaluations` (solo metadatos); docente sobre las propias; estudiante sobre las asignadas. El admin ve información general y el contenido de las preguntas, pero **no** la alternativa correcta (ver detalle abajo). Disponible en el panel admin (pantalla **Supervisión académica**). |
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

- `/api/admin/**` (resumen, actividad, y gestión completa de usuarios: listar,
  crear, editar, activar/desactivar, restablecer contraseña y opciones de docentes) y
  `/api/admin/logs/**`: solo `ADMINISTRADOR`. La gestión de usuarios usa
  `GET/POST /api/admin/users`, `PATCH /api/admin/users/{id}`,
  `PATCH /api/admin/users/{id}/activate|deactivate`,
  `PATCH /api/admin/users/{id}/password/reset` y `GET /api/admin/users/teacher-options`.
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

El `AdminService` concentra la gestión administrativa de usuarios. Para métricas,
listado y actividad reciente es de solo lectura. En las operaciones de escritura
(crear, editar, activar/desactivar y restablecer contraseña) aplica las validaciones
de seguridad en el servicio, no solo en el frontend:

- No permite nombre de usuario ni correo duplicados.
- Exige los campos obligatorios según el rol (p. ej. grado, sección y docente
  responsable **activo** para estudiantes).
- Impide desactivar la propia cuenta autenticada y al último administrador activo.
- No realiza eliminación física: desactivar conserva la información y el historial.
- Al crear o restablecer, la contraseña temporal se genera, se cifra con BCrypt y se
  devuelve en texto plano **una sola vez**; nunca se persiste en claro ni se registra
  en los logs de auditoría (los eventos `USER_CREATED`, `USER_UPDATED`,
  `USER_DEACTIVATED`, `USER_REACTIVATED` y `PASSWORD_RESET` no incluyen datos sensibles).

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

## Gestión completa de usuarios desde admin

A partir de esta sesión, el administrador gestiona usuarios de forma completa desde
`/admin/users`:

- **Crear** docentes, estudiantes y otros administradores con un formulario único de
  campos dinámicos por rol. Como el rol `ADMINISTRADOR` no tiene perfil con nombres,
  un administrador se crea solo con usuario y correo opcional.
- **Editar** datos básicos (nombres, apellidos, correo y, en estudiantes,
  grado/sección/docente responsable). No se permite cambiar usuario/código, rol ni
  contraseña desde la edición.
- **Activar / desactivar** usuarios, con las protecciones descritas (no autodesactivarse,
  no desactivar al último administrador activo, sin eliminación física).
- **Restablecer** la contraseña temporal de cualquier usuario gestionable.

La contraseña temporal generada al crear o restablecer se muestra una sola vez en un
modal copiable; el frontend no la guarda en `localStorage`/`sessionStorage`, no la
imprime en consola ni la envía por rutas o parámetros de consulta. **No se recomienda
cambiar el rol** de usuarios existentes: queda fuera de alcance para preservar la
consistencia con los perfiles.

## Resultado de la auditoría

El modelo de permisos del administrador se encuentra correctamente implementado y
alineado entre backend y frontend, incluida la gestión completa de usuarios. No se
detectaron rutas administrativas desprotegidas, accesos cruzados entre roles, enlaces
de menú rotos ni acciones de edición académica expuestas al administrador. Tampoco se
hallaron fugas de contraseñas, contraseñas temporales ni tokens en respuestas o logs.

## Pendientes para próximas sesiones

1. **Pantallas de supervisión académica (solo lectura).** Implementado: el panel
   admin cuenta con la pantalla **Supervisión académica** (`/admin/academic-supervision`)
   y los endpoints `GET /api/admin/academic-supervision/**`. Ver
   [supervision-academica-admin.md](supervision-academica-admin.md).
2. **Resultados agregados institucionales.** Definir una vista de solo lectura para
   que el admin consulte indicadores generales sin acceder al detalle de intentos.
