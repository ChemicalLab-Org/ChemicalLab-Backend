# Contenidos conceptuales

Módulo del backend que permite a los docentes crear material conceptual de química
(óxidos, ácidos, nomenclatura, etc.) y asignarlo a los grados y secciones que
tienen a cargo. Los estudiantes consultan únicamente el material publicado que
corresponde a su grado y sección.

> Nota: la pantalla `/concepts` del frontend todavía trabaja con datos locales.
> La integración con estos endpoints y la pantalla de gestión del docente se
> realizarán en una etapa posterior. Esta entrega cubre solo el backend.

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
| `title` | Título del contenido (obligatorio) |
| `category` | Categoría (`ConceptCategory`, obligatoria) |
| `summary` | Resumen breve (opcional, TEXT) |
| `explanation` | Explicación teórica (obligatoria, TEXT) |
| `formationSteps` | Lista de pasos de formación (`@ElementCollection`) |
| `keyPoints` | Lista de puntos clave (`@ElementCollection`) |
| `examples` | Lista de ejemplos (`@ElementCollection`) |
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

### Enums

- `ConceptCategory`: `OXIDOS`, `HIDROXIDOS`, `ACIDOS`, `SALES_BINARIAS`,
  `OXISALES`, `NOMENCLATURA`, `GENERAL`.
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

- Título, categoría y explicación son obligatorios.
- Un docente no puede editar, publicar, archivar ni asignar contenidos de otro
  docente.
- No se puede asignar ni publicar un contenido archivado.
- No se permite una segunda asignación activa para el mismo grado/sección.
- El estudiante solo recibe contenidos publicados y asignados a su sección.
- El acceso por rol se configura en `SecurityConfig` para `/api/concepts/**`.

## Pruebas

`ConceptContentServiceTest` cubre los casos principales: creación, listado,
publicación, asignación, visibilidad correcta por sección, ocultamiento de
borradores, control de pertenencia entre docentes, no duplicación de
asignaciones y bloqueo de asignación de contenidos archivados.
