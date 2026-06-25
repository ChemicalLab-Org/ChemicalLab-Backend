# Contenidos conceptuales

Módulo del backend que permite a los docentes crear material conceptual y asignarlo
a los grados y secciones que tienen a cargo. Los estudiantes consultan únicamente el
material publicado que corresponde a su grado y sección.

La **categoría del contenido es texto libre**: el docente puede usar las categorías
químicas clásicas (óxidos, hidróxidos, ácidos, sales, oxisales, nomenclatura) o
registrar cualquier otro tema (enlace químico, reacciones químicas, tabla periódica,
valencias, laboratorio y seguridad, clase introductoria, actividad práctica, repaso
de evaluación, etc.). Esto evita que el módulo quede amarrado únicamente a los tipos
de compuestos predefinidos.

> Compatibilidad: la categoría siempre se almacenó como texto. Los contenidos creados
> con las categorías antiguas (`OXIDOS`, `HIDROXIDOS`, `ACIDOS`, `SALES_BINARIAS`,
> `OXISALES`, `NOMENCLATURA`, `GENERAL`) siguen siendo válidos y se muestran tal cual.

## Contenido textual vs. materiales adjuntos

Este módulo gestiona **contenido conceptual textual**: título, categoría, resumen,
explicación, pasos/secuencia, puntos clave, ejemplos y una actividad sugerida. **No**
gestiona la subida de archivos (PDF, diapositivas, imágenes). Los materiales adjuntos
se abordarán en una sesión futura específica y son independientes de los campos de
texto descritos aquí.

## Propósito del módulo

- Que el docente prepare contenidos teóricos y los mantenga como borrador hasta
  estar listos.
- Que el docente publique y asigne un mismo contenido a una o varias secciones.
- Que el estudiante vea solo lo publicado y asignado a su sección, sin acceso a
  borradores ni a contenidos archivados.
- Que el administrador pueda consultar todos los contenidos si se requiere.

## Entidades principales

### ConceptContent
Contenido conceptual creado por un docente.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `title` | Título del contenido (obligatorio, máx. 150) |
| `category` | Categoría como **texto libre** (obligatoria, máx. 100). Se recorta y se colapsan los espacios internos repetidos |
| `summary` | Resumen breve (opcional, TEXT, máx. 500) |
| `explanation` | Contenido principal / explicación (obligatorio, TEXT) |
| `formationSteps` | Lista de pasos o secuencia (`@ElementCollection`) |
| `keyPoints` | Lista de puntos clave (`@ElementCollection`) |
| `examples` | Lista de ejemplos (`@ElementCollection`) |
| `suggestedActivity` | Indicaciones o actividad sugerida (opcional, TEXT, máx. 2000) |
| `createdByTeacher` | Docente autor (`TeacherProfile`) |
| `status` | Estado (`ConceptStatus`: `DRAFT`, `PUBLISHED`, `ARCHIVED`) |
| `active` | Marca de actividad |
| `createdAt` / `updatedAt` | Auditoría de fechas |

Las listas se almacenan con `@ElementCollection` en tablas auxiliares
(`concept_content_formation_steps`, `concept_content_key_points`,
`concept_content_examples`). Es una solución simple y mantenible que no requiere
librerías nuevas ni JSON.

### ConceptAssignment
Asignación de un contenido a un grado y sección. Permite reutilizar un mismo
contenido en varias secciones y desactivar la asignación sin borrar el contenido.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `conceptContent` | Contenido asignado |
| `teacher` | Docente que realizó la asignación |
| `grade` | Grado |
| `section` | Sección |
| `active` | Si la asignación está vigente |
| `assignedAt` | Fecha de asignación |

### Categoría y catálogo de sugerencias

La categoría dejó de ser una enumeración cerrada. La clase `ConceptCategory` ya no es
un `enum`: solo expone una lista `DEFAULTS` de categorías sugeridas (las clásicas de
química más temas generales habituales) que el endpoint de sugerencias combina con las
categorías que el docente ya ha usado. No restringe los valores admitidos.

### Enum de estado

- `ConceptStatus`: `DRAFT`, `PUBLISHED`, `ARCHIVED`.

## Flujo del docente

1. Crea un contenido → nace en estado `DRAFT`.
2. Edita el contenido cuantas veces necesite.
3. Lo publica (`PUBLISHED`) cuando está listo.
4. Lo asigna a uno o varios grados/secciones.
5. Puede archivarlo (`ARCHIVED`); un contenido archivado no admite nuevas
   asignaciones ni puede volver a publicarse.
6. Puede desactivar una asignación puntual sin perder el contenido.

El docente solo opera sobre sus propios contenidos. La identidad del docente se
obtiene del usuario autenticado, no de identificadores enviados por el cliente.

## Flujo del estudiante

1. Inicia sesión; su grado y sección provienen de su perfil.
2. Lista los contenidos publicados asignados a su grado/sección.
3. Abre el detalle de un contenido concreto si está asignado a su sección.

El estudiante nunca ve borradores, contenidos archivados ni contenidos de otras
secciones.

## Endpoints principales

Base: `/api/concepts`

### Docente (`ROLE DOCENTE`)
| Método | Ruta | Acción |
|--------|------|--------|
| POST | `/teacher` | Crear contenido |
| GET | `/teacher` | Listar contenidos propios |
| GET | `/teacher/categories` | Categorías sugeridas (defaults + usadas por el docente) |
| GET | `/teacher/{conceptId}` | Ver detalle propio |
| PUT | `/teacher/{conceptId}` | Editar contenido propio |
| PATCH | `/teacher/{conceptId}/publish` | Publicar |
| PATCH | `/teacher/{conceptId}/archive` | Archivar |
| POST | `/teacher/{conceptId}/assignments` | Asignar a grado/sección |
| PATCH | `/teacher/{conceptId}/assignments/{assignmentId}/deactivate` | Desactivar asignación |

### Estudiante (`ROLE ESTUDIANTE`)
| Método | Ruta | Acción |
|--------|------|--------|
| GET | `/student` | Listar contenidos publicados de su sección |
| GET | `/student/{conceptId}` | Ver contenido publicado de su sección |

### Administrador (`ROLE ADMINISTRADOR`)
| Método | Ruta | Acción |
|--------|------|--------|
| GET | `/admin` | Listar todos los contenidos |
| GET | `/admin/{conceptId}` | Ver cualquier contenido |

## Reglas de validación y seguridad

- Título, categoría y explicación (contenido principal) son obligatorios.
- La categoría se valida también en el servicio: se recorta, se colapsan espacios,
  no puede quedar vacía y no puede superar 100 caracteres (defensa adicional a la
  validación de los DTOs).
- El resumen admite hasta 500 caracteres y la actividad sugerida hasta 2000.
- Un docente no puede editar, publicar, archivar ni asignar contenidos de otro
  docente.
- La edición **conserva las asignaciones existentes** (no se tocan al actualizar el
  contenido) y **no cambia el docente autor**.
- No se puede asignar ni publicar un contenido archivado.
- No se permite una segunda asignación activa para el mismo grado/sección.
- El estudiante solo recibe contenidos publicados y asignados a su sección.
- El acceso por rol se configura en `SecurityConfig` para `/api/concepts/**`.

## Trazabilidad

Se registran eventos de auditoría (sin volcar el contenido completo ni datos
sensibles, solo metadatos como la categoría) para:

- `CONCEPT_CREATED` — creación.
- `CONCEPT_UPDATED` — edición.
- `CONCEPT_PUBLISHED` — publicación.
- `CONCEPT_ARCHIVED` — archivado (cambio de estado).
- `CONCEPT_ASSIGNED` — asignación a grado/sección.

Ejemplos de descripción segura: «Se actualizó el contenido conceptual ‘Enlace
químico’.», «Se asignó el contenido ‘Valencias’ a 3° A.».

## Pruebas

`ConceptContentServiceTest` cubre los casos principales: creación con categoría
clásica y con categoría personalizada, normalización de espacios en la categoría,
rechazo de categoría vacía y de categoría demasiado larga, edición que registra log
y conserva las asignaciones, listado, publicación, asignación, visibilidad correcta
por sección, ocultamiento de borradores, control de pertenencia entre docentes, no
duplicación de asignaciones, bloqueo de asignación de contenidos archivados y
combinación de categorías sugeridas (catálogo por defecto + usadas por el docente).
