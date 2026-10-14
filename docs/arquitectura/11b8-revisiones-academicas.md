# T11-B8 / P03 — Revisiones académicas, publicación y entregas persistentes

13 de octubre de 2026, America/Lima. Base esperada y remota comprobada mediante
fetch: `d335f2519202ffdfce4829acc9ab3e5010e6c9e7`. Checkout aislado
`.codex-work/t11b8-backend`, rama `feature/t11b8-revisiones-academicas`.
Leídos T11-A, T11-C, B3–B7 y contratos pertinentes T05/T06/T07/T08/T10.
No AGENTS.md aplicables en el backend ni sus ancestros; no agentes delegados.
Alcance exclusivo: preparación persistente P03 y mantenedor/fixtures internos.

## Decisiones confirmadas

**F09:** evaluación lógica + cuenta es la unidad del presupuesto de evaluaciones
realmente nuevas, compartida entre todas sus clases, entregas y revisiones.
Cambiar clase, docente, matrícula o revisión nunca restituye intentos. Una
evaluación académica independiente requiere creación explícita de otra identidad.
Copiar técnicamente/versionar/asignar nunca crea esa identidad ni una raíz nueva.

Legacy conserva obligatoriamente evaluación/raíz B3, consumo completo, numeración,
único activo y cierres originales A13–A17. Una revisión publicada es inmutable;
la siguiente no cambia entregas, intentos, respuestas, notas, bytes ni autores
anteriores. EDIT_DRAFT, PUBLISH y ASSIGN siguen siendo facultades independientes
de B6: su autorización efectiva pertenece a T12, sin adopción en esta entrega.

## Persistencia y mantenedor

Expansión aditiva `src/main/resources/db/expansion/t11b8-v1.sql`; JDBC/records,
sin entidades JPA nuevas, instalación al arrancar, editor, endpoint, listener,
job ni permisos REST/STOMP. No se modifica ningún escritor productivo.

| Tabla `t11b8_` | Contrato |
| --- | --- |
| resources | Relación tipada exclusiva a concept_id o evaluation_id original, ambos con FK y unique. Institución/autor/membresía/procedencia/operación inmutables. Evaluation identifica la raíz lógica; LEGACY referencia obligatoriamente el grupo B3 exacto; EXPLICIT_NEW no puede tener grupo legacy. Máximo fijo y versión monotónica |
| creations | Comando académico explícito, append-only: su trigger crea una identidad original nueva e inerte DRAFT, su raíz y borrador 1 en la misma transacción. No recibe sourceId ni origen alternativo. SQL directo no puede registrar un ancla existente como NEW, incluso si se actualiza dentro de la transacción |
| revisions | Recurso/institución, número único secuencial, parent publicado del mismo recurso, base B3 tipada opcional, payload JSONB completo/checksum, versión, DRAFT/PUBLISHED, fechas DB y operación. Publicar solo congela el payload existente; no lo modifica durante esa transición |
| deliveries | Recurso/institución/clase/revisión publicada exactos mediante FKs compuestas, unique revisión/clase, ventana UTC inmutable, OPEN/CLOSED terminal y versión. Otra publicación no retargetea entregas |
| recipients | Unique entrega/cuenta, episodio de matrícula de esa clase/cuenta/institución, ADMITTED/WITHDRAWN y versión. Readmisión actualiza la misma identidad y conserva snapshots de episodios anteriores |
| budgets | PK recurso/cuenta, exclusivamente evaluaciones NEW. No crea presupuesto por clase, revisión, entrega, docente o episodio; la identidad no se borra/modifica |
| fixture_attempts | Simulación explícita de F09: número único raíz/cuenta, índice parcial de único activo, contexto de destinatario/revisión exactos, configuración/orden/inicio/plazo congelados. Finalización única conserva respuestas/nota/cierre; jamás escribe evaluation_attempts o interpreta un presupuesto B3 como saldo |
| events | Snapshots generados de cada creación/transición, append-only, sin inserción ordinaria, UPDATE/DELETE/TRUNCATE. Incluye payloads/bytes y episodios; no elimina evidencia al retirar disponibilidad |
| operations | UUID + acción/request exactos + resultado: reintento idéntico no repite mutaciones, copias, historia, cuota ni SUCCESS. Reusar UUID con otro mapa/versión/payload se rechaza |
| schema_version | Versión/fingerprint propios y fingerprint B7 predecesor; no sobrescribe ledgers históricos |

`AcademicRevisionMaintenanceService` expone únicamente operaciones internas:
createNew, attachLegacy, draftFromLegacy, nextDraft, edit, publish, assign,
recipient, closeDelivery, resourceState y simulaciones reserve/finishFixtureAttempt.
`AcademicRevisionRepository` observa el payload privado, sin DTO REST. Ningún
consumidor público llama al mantenedor. Un contexto de seguridad presente se
rechaza: no se transforma ADMINISTRADOR o una facultad institucional en permiso.
El actor de este ensayo controlado es null; T08 conserva target/ID/correlación
seguros, sin payload, respuestas, criterios, nombres o archivos en system_logs.

SQL exige `t11b5_fixture()`: DB `chemicallab_t01_test`, loopback 127.0.0.1 y schema
propio `t03_<32 hex>`. El rol DB del ensayo es la frontera de confianza interna;
SQL no autentica sesiones ni otorga poderes T12. Los contendientes SQL prueban
integridad; la atomicidad obligatoria T08 se verifica con el servicio transaccional.
No se certifican privilegios runtime productivos ni protección frente a DBA.

## Payload, inmutabilidad, ventanas y autoridad

El payload conserva el formato académico JSONB B3: `resource`, preguntas/opciones
con IDs, orden y configuración; en conceptos `materials`, `steps`, `points` y
`examples`, con metadatos y bytes hex originales. Bases B3 y copias técnicas
conservan íntegramente su payload; un borrador sucesor permite contenido distinto.
Las anotaciones de autor y procedencia del payload permanecen fijas al editar;
el mantenedor conserva los campos originales omitidos del nuevo contenido.
La autoría efectiva y el tenant siempre proceden de la relación tipada persistida,
nunca de un gestor, responsable actual, nombre o campo libre del payload.

Publicación: DRAFT versión N → PUBLISHED versión N+1, bajo locks de ensayo y
relectura. No se permite editar y publicar en un solo UPDATE ni modificar una
fila publicada. Nueva revisión tiene otra PK y parent; las anteriores y todos
sus consumidores permanecen intactos. Numeración bajo lock + unique evita
duplicados entre procesos; la carrera edit/publish exige una sola versión ganadora.
ARCHIVED es terminal. WITHDRAWN conserva evidencia y, para evaluaciones, fija
cierre de raíz: volver a AVAILABLE no devuelve oportunidades ni corrección oculta.

Destinatarios admitidos necesitan episodio/membresía/rol ESTUDIANTE actuales y
vigentes, clase/cuenta/institución coherentes y entrega abierta/no vencida.
El inicio ficticio revalida el episodio; una suspensión no se convierte en
permiso por mantener un recipient anterior. Retirar conserva el episodio;
readmitir registra otro snapshot sin cambiar intentos previos ni consumo.

En NEW el cierre considera **todas** las entregas del recurso, incluso futuras,
vacías y sin due_at; una evaluación sin entregas no cierra por padrón vacío.
No pueden ampliarse ventanas, reabrirse CLOSED ni añadirse entregas para eludir
una raíz cerrada. Corrección ficticia requiere cierre completo y todas las notas
cerradas. El plazo del intento es min(due_at, inicio + duración aplicable) y queda
congelado; una finalización vencida no admite respuestas nuevas. No hay devolución
de consumo, grace period ni exposición por entrega. La simulación no sustituye
el motor de evaluación, randomización o calificación productivos.

En LEGACY la raíz exacta B3 y sus snapshots permanecen intactos. Las decisiones
de nuevas entregas/admisiones consultan, bajo los locks de las fuentes originales,
el cierre B3 conservado y T05 sobre todas las asignaciones **actuales** originales.
Una edición, inicio o finalización legacy no convierte snapshots preparados en
saldo vivo. B8 jamás crea budget NEW ni admite/exhibe corrección propia para esa
raíz. Evaluation/student, sus locks, configuración y escritores legacy siguen
siendo la única autoridad de consumo/numeración/activo/finalización/exposición.
Adopción/sincronización/corte requiere T12 y otra transición autorizada, no dual-write.

## Límites, cuotas y atomicidad

Se reutilizan las categorías técnicas B3/T07-B REVISION_COUNT=1000 y
REVISION_BYTES=268435456 por cuenta autora; no cuotas institucionales P04 nuevas.
Cada fila/copia, payload/material, historia, comando y operación cobra conteo y
tamaño lógico JSONB completo (hex incluido). Editar cobra delta de fila y toda
la nueva evidencia. Contadores, negocio, anclas nuevas, historia y SUCCESS T08
confirman o revierten juntos. Un fallo temprano/tardío permite reintentar sin
datos parciales; las secuencias pueden dejar huecos tras rollback como PostgreSQL
habitual, sin renumerar evidencia existente.

Validación académica SQL: títulos/categoría/textos, max 1–10, duración 1–240,
preguntas/opciones con identidad y padres coherentes; al publicar preguntas
presentes y alternativas con exactamente una correcta (OPEN_TEXT sin opciones).
Concepto no vacío, colecciones acotadas, enlaces http/https y tamaño real de bytes.
Mantenedor: JSON estricto sin claves duplicadas, profundidad 12, 32 propiedades,
colecciones 10000, payload 32 MiB y textos según T06/T07/DTOs. La representación
hex de archivo admite hasta 10 MiB de bytes originales, con excepción explícita
a los límites de strings ordinarios necesaria para snapshots internos; no modifica
límites REST/STOMP. Publicar/editar/createNew validan archivos con el worker
aislado T06; conservan bytes originales, sin normalización silenciosa. SQL no
implementa un parser PDF/Office/imagen: adopción/descarga futura debe conservar
la validación estructural T06. No se abre una descarga nueva en esta entrega.

## Instalación, repetición, divergencia y recuperación

Cadena: B1 v1 → B2 v1 → B2 v2 → B3 v1 → B3 v2 → B4 v1 → B4 v2 → B5 v1 →
B6 v1 → B7 v1 → **B8 v1**. Los diez SQL históricos permanecen byte a byte,
junto con sus ledgers/fingerprints. B8 incorpora el certificador B7 congelado
sin cambios y exige B7 instalado antes de cualquier instalación propia.
B8 es el certificador sucesor actual; B7 sigue certificando su catálogo intacto.
No añade triggers a tablas originales o de predecesores. Los certificadores
antiguos que rechazan el sucesor B6 siguen sin servir como reparación.

```powershell
# Solo futura operación autorizada; no ejecutar en bases escolares en esta entrega.
# Sobre B7 v1 instalado/certificado, destino y search_path explícitos:
psql --set ON_ERROR_STOP=1 --command 'select current_database(),current_schema();'
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b8-v1.sql
# Repetición certifica toda la cadena y B8 sin reescribir negocio/ledgers.
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b8-v1.sql
```

DO atómico + advisory transaction lock común. Fingerprint cubre columnas/defaults,
CHECK/FK/unique, índices/estado, triggers/enablement, funciones, vistas y
configuración de secuencias B8. El certificador B7 verifica las cuotas heredadas.
Objetos sin ledger, versión/predecesor desconocidos o catálogo/guard divergente
se rechazan sin reparar. Fingerprint de catálogo no firma datos/espacio físico,
OIDs, saldo/consumo de secuencias, permisos ni actividad DBA. Ningún archivo se
instala al arrancar; Hibernate solo crea el schema legacy nuevo del arnés y nunca
repara expansiones o restores. Aplicar cada archivo en transacción completa.

Operación real futura exige autorización, writers detenidos, revisión de permisos,
backup probado y destino confirmado. Ante commit desconocido investigar UUID,
request, filas/historia/versión y correlación; reanudar operación idéntica, sin
recrear recursos/raíz o recalcular fingerprints. Restaurar dump completo a DB
aislada con pg_restore --single-transaction --exit-on-error, mismo schema,
certificar B8 y comparar todas las firmas/IDs/checksums/bytes/budgets/cuotas/logs.
Restore carga datos antes de triggers; no deshabilitar guards sobre negocio vivo.
Rollback de binario conserva tablas inertes; DROP/CASCADE perdería evidencia.
Restore estático no implementa P06/checkpoints ni recuperación de escrituras posteriores.

## Verificación y cierre acotado

`AcademicRevisionDbTest` prueba borrador diferente, publicación atómica/inmutable,
historia/bytes/autoría, FKs/contexto, F09 compartido, evaluación explícita distinta,
copias técnicas, legacy sin segunda autoridad, readmisión, ventanas/cierre/exposición,
versiones/reintentos, auditoría/cuota/rollback, JSON/materiales, expansión/repetición,
divergencia, carreras entre JVM y dump/restore nativos. Resultados definitivos y
SHA probado se añaden tras `clean package`; selecciones diagnósticas no se suman.

Se cierra únicamente **P03 persistente implementado**. P01/P02 conservan sus
evidencias; P04–P06, T11-B agregado y etapa 3 siguen abiertos. T12 mantiene
autorización y corte de writers; T13/T14 flujos/continuidad/incorporación;
SEC-13/VER-01, Linux/SCA/capacidad/permisos/operación conservan sus gates.
Auditoría, notas/cierres previos, frontend institucional y 16 E2E preservados.
PR backend borrador hacia develop; sin merge, despliegue, DDL/backfill real,
activación multiinstitucional o inicio de P04–P06/T12.
