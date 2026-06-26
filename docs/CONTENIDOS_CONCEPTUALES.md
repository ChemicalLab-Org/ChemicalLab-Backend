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

## Contenido textual y materiales adjuntos

Un contenido conceptual combina **texto** (título, categoría, resumen, explicación,
pasos/secuencia, puntos clave, ejemplos y actividad sugerida) con **materiales de
apoyo**:

- un **archivo** principal (PDF, diapositivas PPT/PPTX o imagen PNG/JPG), almacenado
  como bytes en PostgreSQL; y
- uno o más **enlaces externos** de apoyo (video, simulador, recurso del colegio…).

Un contenido puede guardarse solo con texto, solo con material adjunto, solo con
enlaces o con cualquier combinación. Lo único siempre obligatorio es el **título** y la
**categoría**; la explicación pasó a ser opcional. Para **publicar** un contenido debe
aportar algo útil: texto **o**, al menos, un material de apoyo (no se publican
contenidos vacíos).

### Almacenamiento (decisión MVP)

Los archivos se guardan como **bytes en la base de datos PostgreSQL** (`bytea`) con un
**límite estricto de 10 MB por archivo**. Se evita el sistema de archivos local porque
el despliegue en Render **no garantiza un filesystem persistente** entre reinicios o
redespliegues. Para una escala mayor podría migrarse a un almacenamiento externo
(Amazon S3, Cloudinary, Supabase Storage u otro equivalente), pero **no** se implementa
en esta versión.

Los bytes del archivo **nunca** se devuelven en listados ni en respuestas de metadata:
los listados usan una proyección JPQL (`ConceptMaterialView`) que no selecciona la
columna binaria, y los bytes solo se cargan en el endpoint de descarga/visualización.

### Formatos permitidos y tamaño

| Tipo | `Content-Type` | Extensión | Previsualización |
|------|----------------|-----------|------------------|
| PDF | `application/pdf` | `.pdf` | En línea (inline) |
| PowerPoint | `application/vnd.ms-powerpoint` | `.ppt` | Solo descarga |
| PowerPoint | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | `.pptx` | Solo descarga |
| Imagen | `image/png` | `.png` | En línea (inline) |
| Imagen | `image/jpeg` | `.jpg`, `.jpeg` | En línea (inline) |

Cualquier otro tipo se **rechaza** (ejecutables `.exe`/`.bat`/`.cmd`/`.sh`, scripts
`.js`/`.html`, `.svg`, comprimidos `.zip`/`.rar`/`.7z`, etc.). La validación usa una
**lista blanca** de `Content-Type` y exige que la **extensión sea coherente** con él
(no se confía solo en la extensión). Tamaño máximo: **10 MB** (validado en el servicio
y por `spring.servlet.multipart.max-file-size`).

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
| `explanation` | Contenido principal / explicación (**opcional**, TEXT) |
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

### ConceptMaterial
Material de apoyo asociado a un `ConceptContent`. Para el MVP se admite **un archivo
principal** por contenido (subir uno nuevo reemplaza el anterior y lo elimina para no
dejar bytes huérfanos) y **varios enlaces** externos.

| Campo | Descripción |
|-------|-------------|
| `id` | Identificador |
| `conceptContent` | Contenido al que pertenece |
| `type` | `MaterialType`: `FILE` o `LINK` |
| `title` | Título/descripción breve (opcional, máx. 150) |
| `originalFileName` | Nombre original saneado (solo `FILE`) |
| `contentType` | Tipo MIME del archivo (solo `FILE`) |
| `fileSize` | Tamaño en bytes (solo `FILE`) |
| `fileData` | Bytes del archivo en `bytea` (solo `FILE`, `@Basic LAZY`, nunca en listados) |
| `url` | URL del enlace externo (solo `LINK`) |
| `uploadedBy` | Usuario docente que subió/registró el material |
| `active` | Marca de actividad |
| `createdAt` / `updatedAt` | Auditoría de fechas |

La tabla es `concept_materials`.

### Migración de esquema (categoría de enum a texto libre)

Cuando la categoría se mapeaba como enumeración, Hibernate generaba una restricción
CHECK `concept_contents_category_check` que solo admitía los códigos antiguos
(`OXIDOS`, `HIDROXIDOS`, …). Con `ddl-auto=update`, al cambiar la columna a texto libre
esa restricción **no** se elimina automáticamente y seguiría rechazando cualquier
categoría personalizada (e incluso las clásicas escritas de otra forma, como «Óxidos»),
provocando un error 500 al crear o editar contenidos en bases de datos ya existentes.

El componente `ConceptContentSchemaMigration` (un `ApplicationRunner`) elimina esa
restricción de forma **idempotente** al arrancar (`DROP CONSTRAINT IF EXISTS`). Es
seguro ejecutarlo siempre y un fallo nunca interrumpe el arranque. En bases de datos
nuevas la restricción no llega a crearse, por lo que el inicializador no hace nada.

El mismo componente relaja además el `NOT NULL` de `concept_contents.explanation`
(`ALTER COLUMN explanation DROP NOT NULL`, también idempotente), ya que la explicación
dejó de ser obligatoria al permitir contenidos apoyados solo en archivos o enlaces.

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
| POST | `/teacher/{conceptId}/materials/file` | Subir o reemplazar el archivo (multipart: `file`, `title` opcional) |
| POST | `/teacher/{conceptId}/materials/link` | Agregar un enlace externo de apoyo |
| DELETE | `/teacher/{conceptId}/materials/{materialId}` | Retirar un material (archivo o enlace) |

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

### Descarga / visualización de archivos (autenticado, control por rol)
| Método | Ruta | Acción |
|--------|------|--------|
| GET | `/{conceptId}/materials/{materialId}/download` | Descargar/visualizar un archivo |

Esta ruta **no** lleva el prefijo `teacher`/`student`/`admin`: requiere usuario
autenticado y el control de acceso fino se aplica en el servicio según el rol:

- **Docente**: solo sobre sus propios contenidos.
- **Estudiante**: solo si el contenido está publicado y asignado a su grado/sección.
- **Administrador**: en solo lectura, sobre cualquier contenido.

La respuesta usa el `Content-Type` real del archivo y un `Content-Disposition`
**`inline`** para PDF e imágenes (previsualización en el navegador) o **`attachment`**
para diapositivas PPT/PPTX (descarga). Los **enlaces no se descargan** por aquí: el
cliente los abre directamente desde su `url`.

La metadata de materiales (`materialId`, `type`, `title`, `originalFileName`,
`contentType`, `fileSize`, `url`, `previewAvailable`, `downloadUrl`) se incluye en el
detalle del docente, en el del estudiante y, de forma agregada (`materialCount`,
`hasAttachment`), en la supervisión administrativa. **Nunca** se exponen los bytes ni
rutas internas del servidor.

## Reglas de validación y seguridad

- Título y categoría son obligatorios; la explicación es opcional.
- La categoría se valida también en el servicio: se recorta, se colapsan espacios,
  no puede quedar vacía y no puede superar 100 caracteres (defensa adicional a la
  validación de los DTOs).
- El resumen admite hasta 500 caracteres y la actividad sugerida hasta 2000.
- **Publicación**: solo se publica un contenido que aporte algo útil (texto o, al
  menos, un material de apoyo). No se publican contenidos vacíos.
- Un docente no puede editar, publicar, archivar ni asignar contenidos de otro
  docente, ni agregar/retirar materiales en ellos.
- La edición **conserva las asignaciones existentes** (no se tocan al actualizar el
  contenido) y **no cambia el docente autor**.
- No se puede asignar ni publicar un contenido archivado.
- No se permite una segunda asignación activa para el mismo grado/sección.
- El estudiante solo recibe contenidos publicados y asignados a su sección.
- El acceso por rol se configura en `SecurityConfig` para `/api/concepts/**`.

### Validación de materiales

- **Archivos**: archivo requerido y no vacío; tamaño máximo 10 MB; `Content-Type` en
  la lista blanca; extensión coherente con el `Content-Type`; nombre de archivo
  saneado (se descartan rutas para evitar *path traversal* y caracteres de control que
  permitirían inyectar cabeceras). Los bytes nunca viajan en listados.
- **Enlaces**: URL requerida; solo `http://` o `https://`; se rechazan `javascript:`,
  `data:` y `file:`; longitud máxima 2048; título opcional (máx. 150).

## Trazabilidad

Se registran eventos de auditoría (sin volcar el contenido completo ni datos
sensibles, solo metadatos como la categoría) para:

- `CONCEPT_CREATED` — creación.
- `CONCEPT_UPDATED` — edición.
- `CONCEPT_PUBLISHED` — publicación.
- `CONCEPT_ARCHIVED` — archivado (cambio de estado).
- `CONCEPT_ASSIGNED` — asignación a grado/sección.
- `CONCEPT_MATERIAL_ADDED` — archivo agregado a un contenido.
- `CONCEPT_MATERIAL_REPLACED` — archivo reemplazado.
- `CONCEPT_MATERIAL_REMOVED` — material retirado.
- `CONCEPT_LINK_ADDED` — enlace de apoyo agregado.
- `CONCEPT_LINK_REMOVED` — enlace de apoyo retirado.

Ejemplos de descripción segura: «Se actualizó el contenido conceptual ‘Enlace
químico’.», «Se asignó el contenido ‘Valencias’ a 3° A.», «Se agregó un archivo de
apoyo en el contenido ‘Óxidos’.». Los logs **no** registran los bytes del archivo, su
contenido, rutas internas ni la URL completa de los enlaces.

## Pruebas

`ConceptContentServiceTest` cubre los casos principales: creación con categoría
clásica y con categoría personalizada, normalización de espacios en la categoría,
rechazo de categoría vacía y de categoría demasiado larga, edición que registra log
y conserva las asignaciones, listado, publicación, asignación, visibilidad correcta
por sección, ocultamiento de borradores, control de pertenencia entre docentes, no
duplicación de asignaciones, bloqueo de asignación de contenidos archivados y
combinación de categorías sugeridas (catálogo por defecto + usadas por el docente).

`ConceptMaterialServiceTest` cubre los materiales: subida de PDF válido, de PPTX como
descarga, reemplazo de archivo sin dejar huérfanos, rechazo de tipo no permitido, de
archivo vacío, de archivo mayor a 10 MB y de extensión incoherente con el tipo, control
de pertenencia del docente, aceptación de URL `https` válida, rechazo de URL
`javascript:`, descarga de un estudiante de contenido asignado y bloqueo de descarga de
contenido no asignado.

`ConceptContentPersistenceDbTest` (`@SpringBootTest` contra PostgreSQL) verifica que la
restricción heredada ya no exista y que se puedan persistir contenidos con categoría
personalizada y con categoría clásica sin violar restricciones.
