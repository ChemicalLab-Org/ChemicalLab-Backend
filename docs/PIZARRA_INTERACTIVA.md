# Pizarra interactiva

Diseño técnico de la pizarra interactiva de ChemicalLab. Este documento define alcance, roles,
permisos, modelo de datos, endpoints y arquitectura recomendada **antes** de implementar la
funcionalidad. No introduce entidades, endpoints ni componentes todavía: su objetivo es que la
siguiente sesión pueda construir el MVP sin improvisar.

> **Estado:** diseño aprobado. **El MVP es una pizarra interactiva _en vivo_ por sesiones**, como
> nuevo módulo del sistema disponible para `DOCENTE` y `ESTUDIANTE`. Nada de lo descrito aquí está
> implementado aún. Esta sesión **solo corrige documentación técnica**; no implementa
> funcionalidad. Las secciones marcadas como *futuro* quedan fuera del MVP.

## 1. Propósito

La pizarra interactiva es un **nuevo módulo de ChemicalLab**, al mismo nivel que *Tabla periódica*
o *Formación de compuestos*, disponible tanto para `DOCENTE` como para `ESTUDIANTE`. Funciona **por
sesiones de pizarra en vivo** y sirve para:

- Que el docente **explique conceptos químicos dibujando en tiempo real** durante una clase.
- Que los estudiantes de un grado/sección **se unan a la sesión activa y vean el dibujo en vivo**.
- Permitir **participación controlada** del estudiante (el docente la habilita/deshabilita de forma
  global o por alumno).
- Conservar un **historial** de sesiones cerradas con una **captura final** de la pizarra, visible
  para docente y estudiantes asignados.

Reutiliza los roles existentes (`Role`: `ADMINISTRADOR`, `DOCENTE`, `ESTUDIANTE`) y convive con los
módulos ya presentes (contenidos conceptuales, evaluaciones, resultados, logs, métricas,
trazabilidad y panel admin).

## 2. Tipo de pizarra: decisión

Se evaluaron tres enfoques:

| Opción | Descripción | Complejidad | Riesgo |
|---|---|---|---|
| **A. No en vivo** | El docente guarda la pizarra; el estudiante la revisa después. | Baja | Bajo |
| **B. En vivo** | Sesión en tiempo real; los estudiantes ven los trazos al instante y pueden participar si se les habilita. | Alta (WebSocket, sincronización, concurrencia) | Medio-alto |
| **C. Híbrida** | En vivo durante la sesión y, al cerrar, queda guardada como historial. | Media-alta | Medio |

**Decisión: el MVP es la opción B / híbrida en vivo.** La pizarra **sí debe ser en vivo**: durante
la sesión se sincronizan los trazos en tiempo real con **WebSocket/STOMP**, y al **finalizar** la
sesión queda guardada en el historial con una **captura final**. No se construye una variante
"no en vivo": la sesión nace en vivo y termina archivada.

> Quedan como **evolución futura** (fuera del MVP): exportación PDF, inserción de imágenes externas,
> integración con el motor químico, plantillas avanzadas, edición de sesiones finalizadas y varios
> docentes editando a la vez.

## 3. Asociación de la pizarra

La unidad principal es la **sesión de pizarra**. Asociaciones para el MVP:

| Asociación | ¿En el MVP? | Notas |
|---|---|---|
| **Docente creador** | Sí (obligatorio) | Toda sesión pertenece a un `DOCENTE` (vía `TeacherProfile`); ese docente la controla. |
| **Grado/sección** | Sí (obligatorio) | Se fija al crear la sesión. Define qué estudiantes pueden verla/unirse. Misma convención `grade`/`section` que `ConceptAssignment`. |
| **Nombre y descripción** | Sí | Nombre obligatorio; descripción opcional, capturados al crear la sesión. |
| **Contenido conceptual** | Opcional (futuro cercano) | Posible `linkedConceptId` para reforzar un contenido; no es obligatorio para el MVP. |
| **Evaluación** | **No** | Se evita mezclar evaluación con clase. |

**Resumen:** al crear una sesión, el docente indica **nombre, grado, sección y descripción
opcional**. El grado/sección determina la audiencia de estudiantes.

## 4. Roles y permisos

Se reutilizan los roles existentes (`Role`: `ADMINISTRADOR`, `DOCENTE`, `ESTUDIANTE`).

### DOCENTE

- Entra al módulo *Pizarra interactiva*.
- **Crea** una sesión antes de usarla (nombre, grado, sección, descripción opcional).
- **Entra** a una sesión activa propia.
- Escribe y **dibuja en vivo** en la pizarra.
- Usa las herramientas internas del editor (ver §6).
- **Activa/desactiva la interacción de todos** los alumnos (control global).
- **Activa/desactiva la interacción de un alumno específico** (control individual).
- **Pausa** la sesión para detenerla temporalmente.
- **Finaliza** la sesión, **con confirmación clara previa**.
- Solo edita **sus propias** sesiones.

### ESTUDIANTE

- Entra al módulo *Pizarra interactiva*.
- Ve las **sesiones activas** disponibles para su grado/sección.
- Se **une** a una sesión activa.
- Ve **en vivo** lo que el docente dibuja.
- **Interactúa solo si el docente lo habilita** (global o individualmente); si no, **solo
  visualiza**.
- Ve sus **sesiones cerradas** en el historial.
- **No** puede reabrir ni editar sesiones finalizadas.

### ADMINISTRADOR

- Supervisa las sesiones creadas (metadata institucional: docente, grado/sección, estado, fechas,
  conteos de participantes).
- **No** dibuja ni edita contenido docente en el MVP.
- No reemplaza ni suplanta al docente.

## 5. Participación de estudiantes

La participación es **en vivo y controlada por el docente** mediante dos niveles de permiso:

- **Permiso global de la sesión:** el docente habilita/deshabilita la interacción de **todos** los
  alumnos a la vez.
- **Permiso individual:** el docente habilita/deshabilita la interacción de **un alumno concreto**,
  independientemente del permiso global.

| Estado del alumno | Capacidad |
|---|---|
| Sin permiso (por defecto) | **Solo visualiza** los trazos del docente en vivo. |
| Con permiso (global o individual) | Puede **dibujar/aportar trazos** en la pizarra en vivo. |

El backend debe **validar el permiso efectivo** (global + individual) y el **estado de la sesión**
en cada evento de dibujo entrante. Un alumno nunca dibuja si la sesión está pausada o finalizada,
ni si no tiene permiso.

## 6. Elementos y herramientas de la pizarra

### Herramientas del editor (MVP)

- **Plumón** (trazo a mano alzada).
- **Color** del trazo.
- **Grosor** del trazo.
- **Borrador**.
- **Tamaño** del borrador.
- **Borrar toda la pizarra**.
- **Pausar sesión**.
- **Finalizar sesión** (con confirmación).

El control de interacción global/individual de alumnos también forma parte de la barra del docente.

### Contenido del lienzo (MVP)

- Trazos a mano alzada (plumón) con color y grosor.
- Texto/etiquetas simples (fórmulas químicas **como texto**, sin motor químico).

### Futuro (fuera del MVP)

- Inserción de imágenes externas.
- Integración con el motor químico (elementos/compuestos).
- Plantillas avanzadas (p. ej. tabla periódica).
- Exportación a PDF.

## 7. Historial

Durante la sesión, los trazos se sincronizan en vivo; **al finalizar** se conserva el registro de la
sesión cerrada.

Al cerrar una sesión se guarda:

- **Captura final de la pizarra** como imagen (**PNG o JPG**, lo que resulte más conveniente para
  Angular/backend). La captura puede generarse en el cliente (export del canvas) y subirse al
  backend al finalizar.
- Metadata: **nombre** de la sesión, **fecha**, **docente**, **estado**, grado/sección.

El registro de una sesión cerrada es **visible tanto para el docente como para los estudiantes
asignados** a ese grado/sección. En el historial, el estudiante ve: **nombre de la sesión, fecha,
docente, estado y captura final**.

Reglas:

- **No se permite reabrir** una sesión finalizada.
- Una sesión finalizada **no se edita**.
- Para sincronización en vivo no se persiste cada trazo individual como fila; el transporte en vivo
  va por WebSocket y solo se conserva la **captura final** (y, opcionalmente, un snapshot del estado
  si más adelante se requiere). Esto mantiene el almacenamiento ligero.

## 8. Arquitectura recomendada

La pizarra **es en vivo**. Arquitectura del MVP:

- **WebSocket/STOMP** con Spring Boot para **sincronizar los eventos de dibujo** en tiempo real
  entre el docente y los estudiantes conectados a la sesión.
- **REST** para la gestión de sesiones: crear, listar, entrar, pausar, finalizar, historial y
  permisos (global/individual).
- **Angular** maneja el **lienzo** con Canvas/SVG/HTML (según convenga; Canvas es lo natural para
  trazo libre).
- El **backend valida permisos y estado de la sesión** en cada evento (no se confía en el cliente).
- El **docente controla** la sesión; los **alumnos se unen como participantes**.
- La interacción del alumno depende de **permisos globales o individuales**.

### Flujo en vivo (resumen)

1. El docente crea la sesión (REST) → estado `ACTIVE`.
2. Docente y alumnos se suscriben al canal de la sesión (WebSocket/STOMP).
3. El docente dibuja → los eventos se difunden a los suscriptores en tiempo real.
4. Un alumno con permiso puede emitir eventos de dibujo; el backend valida permiso + estado.
5. El docente puede **pausar** (`PAUSED`, se bloquea el dibujo) o **finalizar** (`CLOSED`).
6. Al finalizar, el cliente envía la **captura final**; la sesión queda en historial.

### Compatibilidad con despliegue local / LAN

El diseño debe ser **compatible con despliegue local en red LAN** (todas las computadoras en la
misma red). **No se detalla ni se implementa** la configuración de despliegue en esta etapa. Queda
como **consideración posterior**: dirección IP del backend, **CORS**, **URL del WebSocket** (host/
puerto accesibles en la LAN) y reglas de **firewall**. Por ahora las pruebas se hacen desde una sola
PC.

### Fases futuras (fuera del MVP)

- Snapshots intermedios / historial de versiones.
- Exportación a PDF.
- Varios docentes editando a la vez.
- Integración con el motor químico e inserción de imágenes.

## 9. Modelo de datos propuesto

> Propuesta de diseño. **No se crean entidades en esta sesión.** Paquete previsto:
> `com.morales.chemicallab.entity` (mismo que el resto del dominio).

### Entidades sugeridas

| Entidad | Propósito | ¿MVP? |
|---|---|---|
| `WhiteboardSession` | Sesión de pizarra y su metadata/estado. | Sí |
| `WhiteboardParticipant` | Estudiante unido a una sesión y su permiso de interacción. | Sí |
| `WhiteboardEvent` | Evento de dibujo en vivo. | Transporte por WebSocket; **no** se persiste fila por fila en el MVP. |
| `WhiteboardSnapshot` / captura | Imagen final (PNG/JPG) de la sesión cerrada. | Sí (como archivo + referencia) |

> No se usa una entidad `Whiteboard` separada como contenedor: en este diseño la **sesión** es la
> unidad principal (se crea, se usa en vivo y se archiva).

### `WhiteboardSession`

| Campo | Tipo | Notas |
|---|---|---|
| `id` | `Long` | PK. |
| `name` | `String` | Obligatorio (nombre de la sesión). |
| `description` | `String` | Opcional. |
| `teacher` | `TeacherProfile` | Docente creador y controlador. |
| `grade` | `String` | Obligatorio. Misma convención que `ConceptAssignment`. |
| `section` | `String` | Obligatorio. |
| `status` | enum `WhiteboardSessionStatus` | `ACTIVE`, `PAUSED`, `CLOSED`. |
| `interactionEnabled` | `boolean` | Permiso **global** de interacción de alumnos. |
| `finalSnapshotUrl` | `String` | Ruta/identificador de la captura final (PNG/JPG); nulo hasta cerrar. |
| `createdAt` | `LocalDateTime` | |
| `startedAt` | `LocalDateTime` | Inicio de la sesión activa. |
| `closedAt` | `LocalDateTime` | Nulo hasta finalizar. |

### `WhiteboardParticipant`

| Campo | Tipo | Notas |
|---|---|---|
| `id` | `Long` | PK. |
| `sessionId` | `Long` | FK a `WhiteboardSession`. |
| `studentId` | `Long` | Estudiante participante. |
| `canInteract` | `boolean` | Permiso **individual** (se combina con el global). |
| `joinedAt` | `LocalDateTime` | |
| `lastSeenAt` | `LocalDateTime` | Opcional, para presencia/métricas. |

> **Estados (`WhiteboardSessionStatus` = `ACTIVE`/`PAUSED`/`CLOSED`):** se mantiene la convención del
> proyecto (inglés, mayúsculas), como en `Evaluation` (`DRAFT/PUBLISHED/ARCHIVED/CLOSED`).
> Recordatorio: con `ddl-auto=update`, **ampliar un enum persistido puede dejar un CHECK heredado** y
> provocar 500; preverlo en la migración correspondiente cuando se implemente.

## 10. Endpoints propuestos

> Propuesta. **No se implementan en esta sesión.** Se sigue la **convención real del proyecto**:
> `/api/<módulo>/<rol>/...` (como `/api/concepts/teacher/...` y `/api/evaluations/student/...`).
> El transporte en vivo va por WebSocket/STOMP; REST solo gestiona sesiones/permisos/historial.

### Docente — REST (`/api/whiteboards/teacher`)

| Método | Ruta | Acción |
|---|---|---|
| `GET` | `/api/whiteboards/teacher` | Listar sesiones propias (activas, pausadas, cerradas). |
| `POST` | `/api/whiteboards/teacher` | Crear sesión (nombre, grado, sección, descripción). |
| `GET` | `/api/whiteboards/teacher/{id}` | Detalle de una sesión propia. |
| `POST` | `/api/whiteboards/teacher/{id}/pause` | Pausar sesión. |
| `POST` | `/api/whiteboards/teacher/{id}/resume` | Reanudar una sesión pausada. |
| `POST` | `/api/whiteboards/teacher/{id}/close` | Finalizar sesión (envía la captura final). |
| `PATCH` | `/api/whiteboards/teacher/{id}/interaction` | Activar/desactivar interacción **global**. |
| `PATCH` | `/api/whiteboards/teacher/{id}/participants/{studentId}` | Activar/desactivar interacción **individual**. |
| `GET` | `/api/whiteboards/teacher/{id}/participants` | Ver participantes de la sesión. |

### Estudiante — REST (`/api/whiteboards/student`)

| Método | Ruta | Acción |
|---|---|---|
| `GET` | `/api/whiteboards/student/active` | Sesiones **activas** de su grado/sección. |
| `POST` | `/api/whiteboards/student/{id}/join` | Unirse a una sesión activa. |
| `GET` | `/api/whiteboards/student/history` | Sesiones **cerradas** de su grado/sección. |
| `GET` | `/api/whiteboards/student/{id}` | Detalle de una sesión cerrada (nombre, fecha, docente, estado, captura). |

### Administrador — REST (`/api/whiteboards/admin`)

| Método | Ruta | Acción |
|---|---|---|
| `GET` | `/api/whiteboards/admin/summary` | Resumen institucional (conteos, metadata). |
| `GET` | `/api/whiteboards/admin` | Listado supervisado (sin dibujar ni editar contenido). |

### WebSocket / STOMP (en vivo, **sí es MVP**)

| Canal | Tipo | Uso |
|---|---|---|
| `/topic/whiteboards/{sessionId}` | Suscripción (servidor → clientes) | Difusión de eventos de dibujo y de control (pausa, interacción, cierre). |
| `/app/whiteboards/{sessionId}/draw` | Envío (cliente → servidor) | Evento de dibujo; el backend valida permiso + estado antes de difundir. |
| `/app/whiteboards/{sessionId}/presence` | Envío (cliente → servidor) | Unión/salida y presencia de participantes. |

## 11. Frontend propuesto

> Pantallas previstas. **No se crean componentes Angular en esta sesión.** Deben mantener la
> identidad ChemicalLab: verde principal, fondo claro, cards limpias, bordes suaves, pills de estado,
> íconos consistentes y estilo educativo. El módulo se ubica junto a *Tabla periódica* y *Formación
> de compuestos*, accesible para docente y estudiante.

### Docente

- Listado de sesiones (con pills de estado: activa / pausada / cerrada).
- Crear sesión (formulario: nombre, grado, sección, descripción opcional).
- **Editor de pizarra en vivo** (lienzo Canvas) con barra de herramientas: plumón, color, grosor,
  borrador, tamaño de borrador, borrar todo, pausar, finalizar.
- Panel de participantes con interruptor de **interacción global** y por **alumno individual**.
- Confirmación clara antes de **finalizar**.
- Historial de sesiones cerradas (con captura final).

### Estudiante

- Sesiones activas disponibles para su grado/sección + botón **unirse**.
- **Visor en vivo** de la pizarra; si el docente lo habilita, herramientas de dibujo; si no, solo
  lectura.
- Historial de sesiones cerradas con: nombre, fecha, docente, estado y **captura final**.

### Administrador

- Supervisión institucional de sesiones (metadata, sin dibujar ni editar).

## 12. Seguridad y privacidad

- El docente solo controla/edita **sus propias** sesiones (validar propiedad por `TeacherProfile`).
- El estudiante solo ve y se une a sesiones **activas de su grado/sección**; solo ve el historial de
  su grado/sección.
- El estudiante **solo dibuja si tiene permiso** (global o individual); el backend valida el
  **permiso efectivo** y el **estado de la sesión** en cada evento de dibujo.
- Una sesión **pausada o cerrada** rechaza eventos de dibujo en el servidor.
- Una sesión **finalizada no se reabre ni se edita** (validación de estado en backend).
- El administrador supervisa metadata; **no** dibuja ni edita contenido en el MVP.
- No se permite acceso anónimo ni edición pública.
- Validar **tamaño/forma de los eventos de dibujo** y de la **captura final** (tipo de imagen, peso
  máximo) para evitar payloads enormes.
- No se guarda información innecesaria.

## 13. Logs, métricas y trazabilidad

ChemicalLab separa **logs de auditoría** (`SystemLog` / `system_logs`, vía `AuditLogService`) de
**métricas de uso** (`UsageEvent` / `usage_events`, vía `UsageMetricService`). La pizarra debe
respetar esa separación: una métrica de uso **nunca** se convierte en log de auditoría.

### Logs de auditoría (acciones importantes del docente/admin)

- Creación de sesión.
- Pausa / reanudación de sesión.
- Cambio de interacción global.
- Cambio de interacción individual de un alumno.
- Finalización (cierre) de sesión.

> Implementación futura: requerirá nuevas entradas en `LogEventType`/`LogCategory` (categoría/eventos
> de pizarra). Se documentará al implementar.

### Métricas de uso

- Acceso al módulo de pizarra.
- Unión de un estudiante a una sesión (participación).
- Apertura de sesión.
- Número de estudiantes conectados / que participaron.
- (Opcional) tiempo de visualización.

> Implementación futura: probable nuevo valor en `UsageModule` y tipos en `UsageEventType`.

### Trazabilidad de sesión en vivo

- Inicio, pausa, reanudación y cierre.
- Participantes (unión/salida).
- Cambios de permisos de interacción.

### No registrar

- Datos sensibles.
- **Trazos individuales** en logs globales (es ruido, no auditoría); el dibujo va por WebSocket y no
  se vuelca a la bitácora.
- Payload completo de la pizarra.
- Contenido innecesario.

## 14. Decisiones para el MVP

**MVP de pizarra interactiva — _en vivo por sesiones_:**

- **Nuevo módulo** para `DOCENTE` y `ESTUDIANTE`.
- Funciona por **sesiones de pizarra** con estados **activa / pausada / finalizada**.
- **Pizarra en vivo** sincronizada con **WebSocket/STOMP**; REST para gestión de sesiones.
- **Herramientas de dibujo** del docente: plumón, color, grosor, borrador, tamaño de borrador,
  borrar todo, pausar, finalizar (con confirmación).
- **Control de interacción** de alumnos **global e individual**.
- El estudiante se une a sesiones activas de su grado/sección; **solo dibuja si se le habilita**.
- **Historial** de sesiones cerradas con **captura final** (PNG/JPG), visible para docente y
  estudiantes asignados.
- **No reapertura** ni edición de sesiones finalizadas.
- **Logs** de acciones importantes y **métricas** de acceso/participación.
- Diseño **compatible con despliegue local/LAN** (configuración de IP/CORS/WebSocket/firewall queda
  como consideración posterior).

**Fuera del MVP:**

- Exportación PDF.
- Inserción de imágenes externas.
- Integración con el motor químico.
- Plantillas avanzadas.
- Edición de sesiones finalizadas.
- Múltiples docentes editando a la vez.
- Configuración detallada de despliegue LAN.

## 15. Riesgos y pendientes

- **Sincronización en vivo:** WebSocket/STOMP introduce concurrencia y orden de eventos; definir
  formato de evento de dibujo y manejo de reconexión.
- **Validación en servidor:** cada evento de dibujo debe validar permiso (global+individual) y
  estado de sesión; cuidar el costo por mensaje.
- **Permisos de interacción:** combinar permiso global e individual sin estados inconsistentes.
- **Captura final:** decidir formato (PNG/JPG), dónde se genera (cliente vs. servidor) y dónde se
  almacena; validar peso máximo.
- **No reapertura:** garantizar a nivel de backend que `CLOSED` es terminal.
- **Compatibilidad móvil / lienzo táctil:** requiere pruebas específicas de trazo.
- **Rendimiento del canvas:** sesiones largas con muchos trazos; considerar simplificación.
- **Despliegue local/LAN:** IP, CORS, URL del WebSocket y firewall quedan pendientes para una etapa
  posterior (no en esta sesión).
- **Enum heredado con `ddl-auto=update`:** al introducir `WhiteboardSessionStatus`, prever el CHECK
  heredado para no provocar 500 (mismo patrón visto en evaluaciones).

## 16. Plan para la siguiente sesión

**Sesión propuesta: «Pizarra interactiva — MVP funcional (en vivo)».**

Debe incluir:

- Backend de sesiones de pizarra: entidades (`WhiteboardSession`, `WhiteboardParticipant`),
  repositorios, servicio y controladores REST por rol.
- **WebSocket/STOMP** para el dibujo en vivo, con validación de permisos y estado en el servidor.
- Frontend docente (listado, crear sesión, editor en vivo con herramientas, panel de participantes,
  pausar/finalizar con confirmación).
- Frontend estudiante (sesiones activas, unirse, visor en vivo, historial con captura final).
- Persistencia de la **captura final** (PNG/JPG) y metadata de la sesión.
- Reglas de **no reapertura** de sesiones finalizadas.
- Logs de acciones importantes y métricas de acceso/participación.

Quedan fuera de esa sesión: exportación PDF, imágenes externas, motor químico, plantillas avanzadas,
edición de sesiones cerradas, varios docentes simultáneos y la configuración detallada de despliegue
LAN (solo se deja preparada la compatibilidad).
