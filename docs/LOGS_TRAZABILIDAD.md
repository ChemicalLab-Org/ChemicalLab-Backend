# Logs y trazabilidad del sistema

Este módulo registra los eventos importantes que ocurren durante el uso de la plataforma
y permite que el administrador los consulte desde el panel administrativo. El objetivo es
tener una bitácora de auditoría simple, segura y útil para soporte y revisión, sin
sobrecargar la base de datos ni exponer datos sensibles.

## Propósito

- Saber **quién** hizo **qué**, **sobre qué recurso** y **cuándo**.
- Apoyar la supervisión administrativa (inicios de sesión, gestión de usuarios,
  publicación y asignación de contenidos y evaluaciones, envío de intentos).
- Servir como base de auditoría para diagnóstico de incidencias.

## Modelo de datos

Entidad `SystemLog` (tabla `system_logs`):

| Campo           | Descripción                                                        |
|-----------------|--------------------------------------------------------------------|
| `id`            | Identificador del registro.                                        |
| `eventType`     | Tipo de evento (`LogEventType`).                                   |
| `category`      | Módulo del sistema (`LogCategory`), derivado del tipo de evento.   |
| `severity`      | `INFO`, `WARNING` o `ERROR` (`LogSeverity`).                       |
| `actorUserId`   | Id del usuario que realizó la acción (puede ser nulo).            |
| `actorUsername` | Usuario actor; en login fallido, el usuario/correo intentado.     |
| `actorRole`     | Rol del actor (puede ser nulo).                                   |
| `targetType`    | Tipo de recurso afectado (`UserAccount`, `Evaluation`, etc.).      |
| `targetId`      | Id del recurso afectado.                                           |
| `targetLabel`   | Etiqueta legible del recurso (nombre, título, código).            |
| `action`        | Verbo corto de la acción.                                          |
| `description`   | Descripción legible del evento.                                    |
| `ipAddress`     | Opcional. Reservado para contexto técnico.                        |
| `userAgent`     | Opcional. Reservado para contexto técnico.                        |
| `metadata`      | Texto/JSON simple, sin datos sensibles.                           |
| `createdAt`     | Fecha y hora del evento.                                           |

Criterios: **actor** = usuario que realiza la acción; **target** = recurso afectado;
**category** = módulo; **eventType** = tipo de evento; **metadata** = texto auxiliar.

### Enums

- `LogCategory`: `AUTH`, `USER_MANAGEMENT`, `CONCEPT_CONTENT`, `EVALUATION`, `RESULTS`,
  `ADMIN`, `SYSTEM`.
- `LogSeverity`: `INFO`, `WARNING`, `ERROR`.
- `LogEventType`: cada tipo conoce su categoría por defecto. Se deja preparada toda la
  estructura (login, usuarios, contenidos, evaluaciones, resultados, sistema), aunque no
  todos los tipos se emiten todavía.

## Eventos registrados actualmente

| Evento                          | Severidad | Origen                                  |
|---------------------------------|-----------|-----------------------------------------|
| `LOGIN_SUCCESS`                 | INFO      | `AuthService.login`                     |
| `LOGIN_FAILED`                  | WARNING   | `AuthService.login`                     |
| `USER_CREATED` (docente)        | INFO      | `UserManagementService.createTeacher`   |
| `USER_CREATED` (estudiante)     | INFO      | `UserManagementService.createStudent`   |
| `USER_UPDATED` (admin)          | INFO      | `AdminService.updateUser`               |
| `USER_UPDATED` (docente→estud.) | INFO      | `UserManagementService.updateStudent`   |
| `USER_DEACTIVATED`              | WARNING   | `UserManagementService` (varios)        |
| `USER_REACTIVATED`              | INFO      | `UserManagementService.activateUser`    |
| `PASSWORD_RESET`                | WARNING   | `UserManagementService` / `AdminService`|
| `CONCEPT_CREATED`               | INFO      | `ConceptContentService`                 |
| `CONCEPT_PUBLISHED`             | INFO      | `ConceptContentService`                 |
| `CONCEPT_ASSIGNED`              | INFO      | `ConceptContentService`                 |
| `EVALUATION_CREATED`            | INFO      | `EvaluationService`                     |
| `EVALUATION_PUBLISHED`          | INFO      | `EvaluationService`                     |
| `EVALUATION_ASSIGNED`           | INFO      | `EvaluationService`                     |
| `EVALUATION_ATTEMPT_SUBMITTED`  | INFO      | `EvaluationService.submitAttempt`       |

### Edición de usuarios

Se registra un evento `USER_UPDATED` (severidad **Información**) cuando:

- un **administrador** edita los datos básicos de cualquier usuario gestionable
  (administrador, docente o estudiante), desde `AdminService.updateUser`;
- un **docente** edita los datos básicos de un estudiante bajo su gestión, desde
  `UserManagementService.updateStudent`.

El evento se registra **solo si la edición se completó correctamente**; si la operación
falla por validación (por ejemplo, el estudiante no pertenece al docente o el usuario no
existe), no se registra ningún log de edición.

La trazabilidad guarda el actor y su rol (resueltos del contexto de seguridad), el usuario
afectado (id y código/usuario) y una descripción segura. En `metadata` se incluye el rol del
usuario afectado y, cuando aplica, **solo el nombre de los campos modificados** (por ejemplo
`role=ESTUDIANTE; campos=nombres, sección`), **nunca sus valores anteriores ni nuevos**.

## Datos que SÍ se guardan

- Tipo de evento, categoría y severidad.
- Actor (id, usuario, rol) cuando hay sesión; en login fallido, solo el usuario intentado.
- Recurso afectado (tipo, id y etiqueta legible).
- Descripción legible y metadata no sensible (por ejemplo `role=ESTUDIANTE`).
- Fecha y hora.

## Datos que NO se guardan

- Contraseñas y **contraseñas temporales** (ni siquiera en `PASSWORD_RESET`).
- Tokens JWT.
- Respuestas completas de exámenes ni el detalle de respuestas de un intento.
- Stacktraces completos.
- Cualquier dato innecesario o sensible.

## Endpoints

Base: `/api/admin/logs` — solo rol **ADMINISTRADOR**.

| Método | Ruta                       | Descripción                              |
|--------|----------------------------|------------------------------------------|
| GET    | `/api/admin/logs`          | Lista paginada con filtros.              |
| GET    | `/api/admin/logs/{id}`     | Detalle de un log.                       |
| GET    | `/api/admin/logs/summary`  | Resumen de conteos por severidad/módulo. |

Parámetros de consulta del listado: `category`, `eventType`, `severity`, `actorRole`,
`search` (busca en usuario actor, etiqueta del recurso y descripción), `from`, `to`
(ISO date-time), `page` (desde 0), `size` (máx. 100). El resultado se ordena por
`createdAt` descendente.

## Seguridad

- `SecurityConfig` restringe `/api/admin/logs/**` (y `/api/admin/**`) a `ADMINISTRADOR`.
- Docentes y estudiantes reciben 403 al intentar consultar logs.
- Los registros no exponen contraseñas ni tokens por diseño del modelo.

## Robustez

- El guardado del log ocurre en una transacción independiente (`AuditLogWriter` con
  `REQUIRES_NEW`) y cualquier error se captura en `AuditLogService`. Así, un fallo de
  trazabilidad **nunca** impide iniciar sesión, crear un usuario o publicar/asignar
  contenidos y evaluaciones.

## Limitaciones del MVP

- No se registra cada clic, navegación ni visualización simple.
- No se registran las respuestas individuales de las evaluaciones.
- `ipAddress` y `userAgent` quedan reservados; aún no se completan.
- `RESULT_VIEWED` y `SYSTEM_HEALTH_CHECK` quedan disponibles en el enum pero no se emiten
  todavía para no generar ruido.
