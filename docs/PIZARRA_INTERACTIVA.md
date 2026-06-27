# Pizarra interactiva

Diseño técnico **inicial** de la pizarra interactiva de ChemicalLab. Este documento define
alcance, roles, permisos, modelo de datos, endpoints y arquitectura recomendada **antes** de
implementar la funcionalidad. No introduce entidades, endpoints ni componentes todavía: su
objetivo es que la siguiente sesión pueda construir el MVP sin improvisar.

> **Estado:** diseño aprobado para MVP por fases. Nada de lo descrito aquí está implementado
> aún. Las secciones marcadas como *futuro* quedan fuera del MVP.

## 1. Propósito

La pizarra interactiva es una **herramienta del docente para explicar conceptos químicos** de
forma visual dentro de ChemicalLab. Sirve para:

- Apoyar la explicación de temas durante clases o sesiones (reacciones, estructuras,
  procedimientos, esquemas).
- Asociarse opcionalmente a un **contenido conceptual** existente, reforzándolo con un esquema
  visual.
- Quedar **guardada como recurso de clase** para que el estudiante la revise después.
- Permitir, en fases posteriores, **participación controlada** del estudiante.

Encaja con los módulos ya existentes (contenidos conceptuales, evaluaciones, resultados, logs,
métricas, trazabilidad y panel admin) y reutiliza los mismos roles del sistema: `ADMINISTRADOR`,
`DOCENTE`, `ESTUDIANTE`.

## 2. Tipo de pizarra: decisión

Se evaluaron tres enfoques:

| Opción | Descripción | Complejidad | Riesgo |
|---|---|---|---|
| **A. No en vivo** | El docente crea y guarda la pizarra; el estudiante la revisa después. | Baja | Bajo |
| **B. En vivo** | Sesión en tiempo real; los estudiantes ven cambios al instante. | Alta (WebSocket, sincronización, concurrencia) | Alto |
| **C. Híbrida** | Funciona como sesión en vivo y, al cerrar, queda guardada como historial. | Media-alta | Medio |

**Recomendación: enfoque híbrido implementado por fases.** No se construye todo de golpe.

- **Fase 1 — MVP:** pizarra **no en vivo**. El docente crea y edita; el estudiante **solo
  visualiza** lo guardado. Sin WebSocket.
- **Fase 2 — En vivo:** tiempo real con **WebSocket/STOMP** sobre Spring Boot, con canales por
  sesión y snapshots persistidos al cerrar.
- **Fase 3 — Colaboración:** participación activa del estudiante (trazos/elementos) bajo control
  del docente, historial de versiones y exportación.

El MVP es el alcance de la siguiente sesión. Las fases 2 y 3 son evolución y **no** se diseñan al
detalle aquí más allá de dejar el modelo preparado.

## 3. Asociación de la pizarra

Una pizarra podrá relacionarse con varios elementos del sistema. Recomendación para el MVP:

| Asociación | ¿En el MVP? | Notas |
|---|---|---|
| **Docente creador** | Sí (obligatorio) | Toda pizarra pertenece a un `DOCENTE` (vía `TeacherProfile`). |
| **Sesión libre** | Sí | Caso por defecto: el docente crea una pizarra sin vincularla a nada más. |
| **Contenido conceptual** | Sí, opcional | `linkedConceptId` opcional para reforzar un contenido existente. |
| **Grado/sección** | Sí, al asignar | Necesario para que sea visible a estudiantes (misma convención `grade`/`section` que `ConceptAssignment`). |
| **Evaluación** | **No** | Se evita mezclar evaluación con clase en el MVP. Posible en fases futuras. |
| **Sesión específica** | Parcial | El modelo prevé `WhiteboardSession`, pero en el MVP la sesión es solo el contenedor del estado guardado, no una sesión en vivo. |

**Resumen:** la pizarra se crea como **sesión libre del docente**, puede vincularse opcionalmente
a un contenido conceptual y debe asignarse a **grado/sección** si será visible para estudiantes.
En el MVP **no** se vincula a evaluaciones.

## 4. Roles y permisos

Se reutilizan los roles existentes (`Role`: `ADMINISTRADOR`, `DOCENTE`, `ESTUDIANTE`).

### DOCENTE

- Crear pizarras.
- Editar **únicamente sus propias** pizarras.
- Guardar el contenido/estado de la pizarra.
- Publicar y asignar pizarras a grado/sección.
- Cerrar la sesión de una pizarra.
- Ver el historial de sus pizarras.
- **Archivar** sus pizarras (preferido frente a eliminar, para conservar trazabilidad).

### ESTUDIANTE

- Ver las pizarras **asignadas** a su grado/sección.
- Entrar a una pizarra publicada y revisarla.
- Revisar el historial de pizarras de su clase.
- **No edita** por defecto.
- Participar **solo** si el docente lo habilita (fase posterior, no MVP).

### ADMINISTRADOR

- Supervisar las pizarras creadas (metadata institucional: docente, grado/sección, estado,
  fechas, conteos).
- **No edita** contenido docente en el MVP.
- No reemplaza ni suplanta al docente.

## 5. Participación de estudiantes

| Nivel | Capacidad | Fase |
|---|---|---|
| **Nivel 1** | Solo visualizar. | **MVP** |
| **Nivel 2** | Enviar respuestas/comentarios al docente (sin tocar el lienzo). | Futuro |
| **Nivel 3** | Participar con trazos/elementos en la pizarra, previa autorización del docente. | Futuro |

**Recomendación:** en el MVP el estudiante **solo visualiza**. La participación (niveles 2 y 3)
se habilita en fases posteriores y siempre **bajo control del docente**.

## 6. Elementos de la pizarra

### MVP

- Texto / etiquetas.
- Dibujos o trazos simples (lápiz/mano alzada).
- Figuras básicas (rectángulo, círculo, línea).
- Flechas.
- Fórmulas químicas **como texto** (no motor químico).
- Colores básicos.
- Borrador.
- Limpiar pizarra.
- Guardar.

### Futuro

- Insertar elementos químicos y compuestos formados (integración con el motor químico).
- Insertar imágenes.
- Plantilla de tabla periódica.
- Exportar como imagen o PDF.

## 7. Historial

El historial guarda el **estado de la pizarra como JSON estructurado** en backend, **no** como
imagen, en el MVP. Esto mantiene el payload ligero, versionable y editable.

Metadatos a conservar:

- Estado final de la pizarra (contenido serializado).
- Fecha de creación y de última actualización.
- Docente responsable.
- Grado/sección asignados.
- Título y estado.
- (Opcional) versión, si más adelante se decide historial de versiones.
- Sesiones cerradas.

### Estructura del contenido (JSON)

```json
{
  "canvasWidth": 1280,
  "canvasHeight": 720,
  "elements": [
    {
      "id": "el-1",
      "type": "TEXT",
      "position": { "x": 120, "y": 80 },
      "style": { "color": "#1b8a5a", "fontSize": 18 },
      "content": "2 H2 + O2 -> 2 H2O"
    },
    {
      "id": "el-2",
      "type": "ARROW",
      "position": { "x": 200, "y": 160 },
      "style": { "color": "#333", "strokeWidth": 2 },
      "content": null
    }
  ]
}
```

Cada elemento tiene `type`, `position`, `style` y `content`. **No** se guardan capturas pesadas en
el MVP. El tamaño del JSON debe **validarse y acotarse** (ver §12).

## 8. Arquitectura recomendada

### MVP

- **REST API** sobre el monolito modular Spring Boot existente.
- Persistencia en **PostgreSQL**.
- Lienzo en el frontend con **Canvas/SVG/HTML** (Angular).
- El docente edita; el estudiante visualiza.
- Guardado **manual** o autosave simple (debounce); sin sincronización en tiempo real.

### Fase en vivo (futuro)

- **WebSocket/STOMP** con Spring Boot.
- Canales por sesión de pizarra.
- Control de participantes conectados.
- Sincronización de eventos y persistencia de **snapshots** al cerrar.

### Fase avanzada (futuro)

- Colaboración controlada.
- Historial de versiones.
- Exportación (imagen/PDF).
- Participación estudiantil con trazos.

## 9. Modelo de datos propuesto

> Propuesta de diseño. **No se crean entidades en esta sesión.** Paquete previsto:
> `com.morales.chemicallab.entity` (mismo que el resto del dominio).

### Entidades sugeridas

| Entidad | Propósito | ¿MVP? |
|---|---|---|
| `Whiteboard` | Pizarra y su metadata. | Sí |
| `WhiteboardSession` | Estado/snapshot guardado de la pizarra. | Sí (como contenedor del contenido, no en vivo) |
| `WhiteboardAssignment` | Asignación a grado/sección (análogo a `ConceptAssignment`). | Sí |
| `WhiteboardElement` | Elemento individual del lienzo. | **No** (en el MVP los elementos van dentro del JSON, no como filas) |
| `WhiteboardParticipant` | Participantes de una sesión en vivo. | No (fase en vivo) |
| `WhiteboardEvent` | Trazabilidad de eventos de sesión en vivo. | No (fase en vivo) |

### `Whiteboard`

| Campo | Tipo | Notas |
|---|---|---|
| `id` | `Long` | PK. |
| `title` | `String` | Obligatorio. |
| `description` | `String` | Opcional. |
| `teacher` | `TeacherProfile` | Docente creador (relación, no `teacherId` suelto). |
| `status` | enum `WhiteboardStatus` | `DRAFT`, `PUBLISHED`, `ARCHIVED` (alineado a la convención del proyecto). |
| `linkedConceptId` | `Long` | Opcional. Contenido conceptual asociado. |
| `grade` | `String` | Opcional hasta asignar. Misma convención que `ConceptAssignment`. |
| `section` | `String` | Opcional hasta asignar. |
| `createdAt` | `LocalDateTime` | |
| `updatedAt` | `LocalDateTime` | |
| `publishedAt` | `LocalDateTime` | Nulo hasta publicar. |
| `archivedAt` | `LocalDateTime` | Nulo hasta archivar. |

### `WhiteboardSession`

| Campo | Tipo | Notas |
|---|---|---|
| `id` | `Long` | PK. |
| `whiteboardId` | `Long` | FK a `Whiteboard`. |
| `startedAt` | `LocalDateTime` | |
| `endedAt` | `LocalDateTime` | Nulo mientras esté abierta. |
| `mode` | enum | `SAVED` en el MVP; `LIVE` en fase futura. |
| `isLive` | `boolean` | `false` en el MVP. |
| `contentSnapshot` | `String`/`jsonb` | JSON del estado de la pizarra (ver §7). |
| `createdBy` | `Long` | Usuario que generó el snapshot. |

> **Estados (`WhiteboardStatus`):** se usa `DRAFT`/`PUBLISHED`/`ARCHIVED` para mantener la misma
> convención en inglés y mayúsculas que `Evaluation` (`DRAFT`, `PUBLISHED`, `ARCHIVED`, `CLOSED`).
> Recordatorio del proyecto: con `ddl-auto=update`, **ampliar un enum persistido puede dejar un
> CHECK heredado** y provocar 500; preverlo en la migración correspondiente cuando se implemente.

## 10. Endpoints propuestos

> Propuesta. **No se implementan en esta sesión.** Se sigue la **convención real del proyecto**:
> `/api/<módulo>/<rol>/...` (como `/api/concepts/teacher/...` y `/api/evaluations/student/...`),
> **no** `/api/teacher/...`. Por eso se usa `/api/whiteboards/{teacher,student,admin}`.

### Docente (`/api/whiteboards/teacher`)

| Método | Ruta | Acción |
|---|---|---|
| `GET` | `/api/whiteboards/teacher` | Listar pizarras propias. |
| `POST` | `/api/whiteboards/teacher` | Crear pizarra. |
| `GET` | `/api/whiteboards/teacher/{id}` | Detalle de pizarra propia. |
| `PATCH` | `/api/whiteboards/teacher/{id}` | Editar metadata. |
| `PATCH` | `/api/whiteboards/teacher/{id}/content` | Guardar/actualizar el JSON del lienzo. |
| `POST` | `/api/whiteboards/teacher/{id}/publish` | Publicar. |
| `POST` | `/api/whiteboards/teacher/{id}/assign` | Asignar a grado/sección. |
| `POST` | `/api/whiteboards/teacher/{id}/archive` | Archivar. |
| `POST` | `/api/whiteboards/teacher/{id}/sessions` | Abrir/cerrar sesión (snapshot). |

### Estudiante (`/api/whiteboards/student`)

| Método | Ruta | Acción |
|---|---|---|
| `GET` | `/api/whiteboards/student` | Listar pizarras asignadas a su grado/sección. |
| `GET` | `/api/whiteboards/student/{id}` | Ver una pizarra publicada y asignada. |

### Administrador (`/api/whiteboards/admin`)

| Método | Ruta | Acción |
|---|---|---|
| `GET` | `/api/whiteboards/admin/summary` | Resumen institucional (conteos, metadata). |
| `GET` | `/api/whiteboards/admin` | Listado supervisado (sin editar contenido). |

### WebSocket (fase futura, no MVP)

- `/topic/whiteboards/{sessionId}` — difusión de cambios a los suscriptores.
- `/app/whiteboards/{sessionId}/events` — entrada de eventos del cliente.

## 11. Frontend propuesto

> Pantallas previstas. **No se crean componentes Angular en esta sesión.** Deben mantener la
> identidad ChemicalLab: verde principal, fondo claro, cards limpias, bordes suaves, pills de
> estado, íconos consistentes y estilo educativo. Se ubicarán dentro de los paneles por rol ya
> existentes (estudiante, docente, administrador).

### Docente

- Listado de pizarras (con pills de estado: borrador / publicada / archivada).
- Crear pizarra.
- Editor de pizarra (lienzo Canvas/SVG/HTML).
- Asignar a grado/sección.
- Historial / sesiones.
- Publicar / cerrar / archivar.

### Estudiante

- Pizarras asignadas.
- Visor de pizarra (solo lectura).
- Historial de clase.

### Administrador

- Supervisión institucional de pizarras (metadata, sin edición de contenido).

## 12. Seguridad y privacidad

- El docente solo puede editar **sus propias** pizarras (validar propiedad por `TeacherProfile`).
- El estudiante solo ve pizarras **publicadas y asignadas** a su grado/sección.
- El administrador supervisa metadata; **no** edita contenido docente en el MVP.
- No se expone información sensible en el contenido ni en los metadatos.
- No se permite edición pública ni acceso anónimo.
- Se **valida el tamaño del JSON** de la pizarra y se **limita el número de elementos** para
  evitar payloads enormes.
- No se guarda información innecesaria en el snapshot.

## 13. Logs, métricas y trazabilidad

ChemicalLab separa **logs de auditoría** (`SystemLog` / `system_logs`, vía `AuditLogService`) de
**métricas de uso** (`UsageEvent` / `usage_events`, vía `UsageMetricService`). La pizarra debe
respetar esa separación: una métrica de uso **nunca** se convierte en log de auditoría.

### Logs de auditoría (acciones críticas del docente/admin)

- Creación de pizarra.
- Edición importante (no cada trazo).
- Publicación.
- Asignación a grado/sección.
- Cierre de sesión.
- Archivado.

> Implementación futura: requerirá nuevas entradas en `LogEventType`/`LogCategory` (p. ej. una
> categoría/eventos de pizarra). Se documentará al implementar.

### Métricas de uso

- Acceso a una pizarra (módulo de pizarra).
- Tiempo de visualización.
- Uso de herramientas del editor.
- Apertura de sesión.
- Estudiantes que visualizaron.

> Implementación futura: probable nuevo valor en `UsageModule` y tipos en `UsageEventType`.

### Trazabilidad de sesión (relevante en fase en vivo)

- Inicio y cierre de sesión.
- Participantes.
- Eventos importantes (solo en vivo).

### No registrar

- Datos sensibles.
- El payload completo cuando sea grande.
- Contenido innecesario.
- **Trazos individuales** en logs globales (eso es ruido, no auditoría).

## 14. Decisiones para el MVP

**MVP de pizarra interactiva:**

- **No en vivo** todavía.
- El docente crea y edita la pizarra.
- El contenido se guarda como **JSON** estructurado.
- Se asigna a **grado/sección**.
- El estudiante **visualiza**.
- Historial básico (estado guardado + metadata).
- Logs de acciones importantes.
- Métricas de visualización.

**No incluir todavía:**

- Colaboración en tiempo real.
- Participación de estudiantes (trazos/comentarios).
- WebSocket.
- Exportación PDF.
- Inserción de imágenes.
- Múltiples usuarios editando a la vez.

## 15. Riesgos y pendientes

- **Crecimiento del JSON:** definir límite de tamaño y de número de elementos; decidir
  `TEXT`/`jsonb` y validar en backend.
- **Sincronización en vivo futura:** la fase 2 (WebSocket/STOMP) añade complejidad de
  concurrencia; el modelo ya queda preparado pero requiere diseño propio.
- **Permisos de participación:** habilitar trazos de estudiantes exige control fino del docente.
- **Compatibilidad móvil:** el lienzo táctil necesita pruebas específicas.
- **Rendimiento del canvas:** muchos elementos pueden degradar el render; considerar paginación o
  simplificación.
- **Autosave:** decidir entre guardado manual y autosave con debounce.
- **Exportación futura:** si se exige PDF/imagen, evaluar generación en cliente vs. servidor.
- **Enum heredado con `ddl-auto=update`:** al introducir `WhiteboardStatus`/`mode`, prever el
  CHECK heredado para no provocar 500 (mismo patrón visto en evaluaciones).

## 16. Plan para la siguiente sesión

**Sesión propuesta: «Pizarra interactiva — MVP funcional».**

Debe incluir:

- Backend de pizarras (entidades `Whiteboard`, `WhiteboardSession`, `WhiteboardAssignment`;
  repositorios; servicio; controladores REST por rol).
- Frontend docente (listado, creación, editor de lienzo, asignación, publicar/archivar).
- Frontend estudiante (pizarras asignadas y visor de solo lectura).
- Persistencia del contenido como **JSON**.
- Asignación a grado/sección.
- Logs de acciones importantes.
- Métricas de visualización (si aplica en la sesión).

Quedan fuera de esa sesión: tiempo real, participación estudiantil, WebSocket, exportación,
imágenes y edición concurrente (fases posteriores).
