# T11-B9 / P04 — Contadores y cuotas institucionales persistentes

14 de octubre de 2026, America/Lima. Alcance exclusivo: preparación P04 mediante
mantenedor interno y bases ficticias. Base esperada, local y remota comprobada
con fetch: `f2a2b082b919688ae51a515aa6bf255b14d3fbba`; no avances posteriores.
Checkout aislado `.codex-work/t11b9-backend`, rama
`feature/t11b9-cuotas-institucionales`. Sin AGENTS.md aplicables. Leídos T11-A,
T11-C, B3–B8 y contratos T07-B/T08. No se delegó trabajo a otros agentes.

## Contrato de medición y procedencia

**ROW_JSON_UTF8_V1** mide cada fila almacenada de las 67 fuentes enumeradas en
`t11b9_contract`: una unidad ROW y el tamaño en octetos de
`convert_to(to_jsonb(fila)::text,'UTF8')`. La representación JSONB incluye
metadatos, espacios canónicos, escapes, arrays, JSON y bytea hexadecimal.
No equivale al tamaño físico PostgreSQL ni al tamaño original del archivo:
el hex almacenado en una fotografía también consume su representación completa.
No se confía en file_size/final_snapshot_size declarados para calcular bytes.
Los tamaños originales verificables se conservan en las fuentes y en T07-B.
La medición fija TimeZone UTC, bytea_output hex y DateStyle ISO/YMD durante
la función: otra zona horaria de sesión no cambia bytes o checksum. No convierte
timestamps históricos locales ni modifica las fuentes.

| Categoría | Fuentes / unidad y vínculo verificable |
| --- | --- |
| ORIGINAL | Conceptos y sus colecciones ordenadas, asignaciones; evaluaciones, preguntas/opciones, intentos y respuestas; participantes. ROW + JSON UTF-8 completo. Institución desde ancla B3/B8 o contexto de pizarra B4 |
| MATERIAL | Cada fila original concept_materials, enlace o archivo, incluidos metadatos/bytea. Una PK se mide una vez; el mismo archivo copiado en otra fila consume nuevamente |
| CAPTURE | Fila original whiteboard_sessions con estado, configuración y captura final íntegros. La fila sin captura también consume; un nuevo tamaño de captura modifica sus bytes. No duplica la fila por participantes/clases ni por la vista capture_contexts |
| REVISION | Cada revisión B3/B8 realmente almacenada con su payload completo y PK propia; publicar no crea una segunda copia de esa misma fila |
| AUXILIARY | Recursos, entregas, destinatarios, raíces/presupuestos/consumos, conciliaciones y sus fotografías/eventos B3/B4; ownership/tombstones, eventos/ajustes de intento, uso/auditoría; co-docencia, grants, reservas e historia B6; comandos/creaciones/intentos ficticios e historia B8; reservas académicas institucionales B5 |
| GLOBAL_IDENTITY | Pruebas/privilegios globales, candidatos/bindings/conflictos e historia de identidad B5; identificadores/claims/pruebas/revisiones/historia B7. Son GLOBAL incluso si la cuenta pertenece a varios colegios. La reserva académica B5 tiene institución explícita y es AUXILIARY |
| CONTROL, global separado | Metadatos B9, mediciones, deltas, comandos, límites y su auditoría T08: recuento directo `t11b9_control_observation`. Excluidos del conjunto que ellos mismos concilian para evitar autorreferencia; el contador técnico propio limita las inserciones/actualizaciones de items/commands/deltas/limits a 1 000 000 filas / 256 MiB. Esa guarda no sustituye T08 ni imputa sus logs a un colegio |

La tabla contractual contiene fuente tipada, columnas de PK, categoría y versión,
certificadas por fingerprint. Las colecciones sin PK declarada usan la clave
estable `(concept_content_id,orden)`; teaching_slots usa su pareja de referencias
responsable/co-docente con exclusividad B6. Claves repetidas o inválidas impiden
preparar la fotografía completa: no se fusionan filas ni se descarta evidencia.
No se usan ctid, posición en un JOIN, cuenta/clase como identidad del consumo.

Cada item se identifica por `(source_table,source_pk JSONB)`. El contador agrega
filas y bytes por scope/owner/category. No cuenta los resultados de joins ni las
vistas, que son referencias al mismo almacenamiento. Entregas y destinatarios
consumen sus propias filas/metadatos, sin multiplicar el payload referenciado.
Los eventos y conciliaciones que almacenan otra fotografía sí tienen consumo
propio: esa copia existe realmente. Los contadores ACCOUNT/SYSTEM legacy y los
institucionales son dimensiones distintas del mismo almacenamiento; **no se
suman** para presentar un total. B9 tampoco suma budgets de intentos a bytes.

Procedencia institucional solo desde relaciones persistidas y demostradas: anclas
tipadas B3/B8, padres originales por PK/FK, contextos B4 y institution_id fijo de
relaciones B6/B8. Nunca se consulta la membresía actual del autor para atribuir
almacenamiento. B9 no corrige esos contextos ni declara una fotografía académica
vigente como autoridad de acceso; P05/T12 conservan esa responsabilidad.
Fuentes nuevas sin ancla quedan UNRESOLVED; contradicciones sin vínculo demostrado
quedan CONFLICT; hechos globales B4 y categorías globales B5/B7 quedan GLOBAL.
Todas conservan ROW/bytes, fuente/PK/checksum y procedencia, con owner=0.
No se reparte consumo entre colegios ni se inventa el primer colegio de una cuenta.
Las pertenencias B1/B2, cuentas/perfiles/sesiones y catálogos globales no se incorporan
como nuevos recursos institucionales: sus contratos previos y el inventario P05
permanecen separados. Esto no es un inventario universal de toda la instalación.

## Persistencia, límites y operaciones internas

Expansión `src/main/resources/db/expansion/t11b9-v1.sql`, JDBC y servicio Spring;
sin entidades JPA nuevas, instalación de arranque, endpoint, panel, job o listener.
Ningún consumidor productivo llama a InstitutionStorageMaintenanceService.
Un contexto autenticado presente se rechaza: el ensayo no concede autoridad T12.
SQL exige `t11b5_fixture`: loopback 127.0.0.1, DB chemicallab_t01_test y schema
`t03_<32 hex>`. Certificación/restore son posibles en otra DB; las mutaciones no.
El rol propietario del fixture es frontera de confianza, no defensa frente a DBA.

| Tabla B9 | Responsabilidad |
| --- | --- |
| contract | Descriptores congelados de medición/versiones/PK; sin inserción ordinaria, edición, borrado o truncate |
| limits | Máximos positivos de ROW/bytes por institución/categoría, configurados mediante comando de ensayo auditado. Reducir un límite conserva el exceso existente |
| counters | Consumo completo por scope/institución/categoría, con CHECK no negativo; sin edición o liberación manual |
| items | Última medición por fuente/PK, checksum/procedencia y comando. Identidad, ámbito, institución, categoría y procedencia inmutables. Fuente eliminada conserva identidad/evidencia con consumo actual cero |
| commands | UUID/request exactos, tipo BASELINE/SYNC/VERIFY/LIMIT, versión/checksum/resultado e instante. Append-only; un solo BASELINE para toda la fotografía |
| deltas | Antes/después de la medición, source/PK, ROW/bytes derivados de la fuente, commandId; append-only, sin inserción manual |
| control_usage | Tope global técnico del propio ensayo; no es un plan ni aforo productivo |
| schema_version | Ledger/fingerprint B9 y predecesor B8 propios; no sustituye firmas históricas |

BASELINE exige checksum completo exacto y evidencia cerrada FIXTURE-* explícita,
y se aplica una sola vez. Registra todos los históricos aunque excedan un límite,
sin truncar consumo, purgar datos o cobrar únicamente la parte admisible.
FIXTURE-* acredita una decisión del ensayo, no aprobación real F08. Repetición
del mismo UUID/request devuelve el resultado anterior sin consumo o SUCCESS nuevo;
otro UUID no puede reemplazar el baseline. No hay un método de rebase/repair.

SYNC calcula las diferencias desde fuentes reales bajo locks; nunca acepta
cantidades declaradas por el caller. Una institución necesita límite explícito
para cualquier incremento; los máximos de filas y bytes se comprueban por separado
contra el agregado resultante. Delta cero/negativo no exige que desaparezca el
exceso histórico. Un negativo solo procede de reducción real del contenido o
desaparición real de esa PK. Los ámbitos sin institución tienen techo técnico de
1 000 000 filas / 256 MiB por categoría para aumentos, conservando exceso inicial.
Cambiar el propietario/procedencia de un item se rechaza: corrección P05 posterior.

El mantenedor ensaya únicamente nextDraft/edit sobre B8 y eliminación física
explícita de un material ficticio existente. Mantiene la institución fija, consulta
source_current antes de mutar y registra los deltas reales después de la escritura.
Eliminar el original conserva consumo de todas las revisiones/fotografías que
todavía guardan su contenido. Revocar permisos, retirar/readmitir alumnos o cambiar
responsable no elimina esas fuentes ni devuelve bytes. Los escritores anteriores
siguen siendo legacy; su adopción coordinada corresponde íntegramente a T12.

Negocio B8/original, cuotas legacy, contadores/items/deltas B9, historia pertinente
y SUCCESS obligatorio T08 usan la misma transacción Spring. Fallo temprano o
tardío de cuota/auditoría revierte todo. Los huecos de secuencia tras rollback son
comportamiento PostgreSQL; no se renumeran IDs/evidencia. AuditSafety añade solo
el target seguro StorageMeasurement; ADMIN_ACTION/CHECK/guards/retención T08
permanecen intactos. No payload, respuestas, archivos, aliases ni evidencia libre
en system_logs. Las filas B9 de este target se separan del dataset conciliado:
son control global, sin institución imputada.

## Conciliación y concurrencia

VERIFY registra versión, checksum, diferencias por fuente/PK, diferencias del
agregado independiente de fuentes frente a counters y estado de la observación.
No modifica fuentes, items o counters. Repetir el UUID conserva ese informe
histórico; para otro instante debe pedirse otro VERIFY. Una divergencia requiere
investigación explícita, no una sobrescritura silenciosa.

`t11b9_observation` distingue prepared/source_current y declara siempre
**live_authoritative=false**. Compara las fuentes actuales con items y los
agregados persistidos con counters. Cambios legacy posteriores, incluidas historia
y auditoría, invalidan la fotografía; la operación B9 controlada se rechaza hasta
que se resuelva el caso mediante una evolución autorizada. La verificación no
adopta ni corrige escritores. No se presenta este ensayo como contador vivo.

Locks de tablas amplios exclusivos del fixture: mismo orden inicial B8, fuentes
contractuales ordenadas y tablas de control, antes de leer/medir/escribir. Otro
proceso no puede cambiar fuentes durante la medición/comprobación de cuota.
CHECK, FKs, unique y guards PostgreSQL protegen también SQL directo ordinario.
Los contendientes independientes prueban integridad SQL; la auditoría obligatoria
se verifica aparte mediante el mantenedor, sin presentar SQL de ensayo como
escritor productivo auditado. No se promete protocolo de rendimiento productivo
ni ausencia de deadlocks para escritores arbitrarios que inviertan locks.

## Instalación, certificación sucesora y recuperación

B8 es el certificador de partida. B9 incorpora su certificador íntegro congelado,
lo exige instalado y agrega objetos/fingerprint propios. **Los once SQL históricos
y sus ledgers/fingerprints quedan intactos**. No añade triggers a tablas anteriores,
no sustituye funciones/checks/guards/cuotas legacy y no usa Hibernate como reparación.
B9 v1 es el certificador sucesor; B8 también sigue certificando su catálogo intacto.

Cadena: B1 v1 → B2 v1 → B2 v2 → B3 v1 → B3 v2 → B4 v1 → B4 v2 → B5 v1 →
B6 v1 → B7 v1 → B8 v1 → **B9 v1**. Aplicar solo el sucesor que falte, no volver
a instalar archivos anteriores que deliberadamente rechazan sus evoluciones.

```powershell
# Procedimiento futuro, solo con autorización operativa; esta entrega usa fixtures.
# Confirmar destino/search_path y B8; writers detenidos y backup probado.
psql --set ON_ERROR_STOP=1 --command 'select current_database(),current_schema();'
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b9-v1.sql
# Repetición certifica; no reinstala, inicializa contadores o altera ledgers.
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b9-v1.sql
```

DO atómico/advisory lock. Firma cubre columnas/defaults, CHECK/FK/unique, índices y
su estado, triggers/enablement, funciones/vistas/secuencias y contrato de fuentes.
Límites configurables y datos no forman parte del fingerprint de catálogo.
Objetos sin ledger, versión/predecesor desconocidos o divergencia se rechazan
sin reparar/reescribir firmas. Datos, permisos, OIDs, uso de secuencias y espacio
físico no se certifican con ese fingerprint.

Ante fallo de instalación comprobar rollback y resolver la causa. Commit
desconocido: investigar UUID/request/resultado/deltas/historia/SUCCESS y repetir
exactamente la operación, sin crear otro baseline o recurso. Recuperación:
dump completo, restore a DB aislada con --single-transaction --exit-on-error,
search_path original, certificar B9 y comparar firmas de todas las tablas,
IDs/checksums/bytes/counters/cuotas/logs. Restore instala triggers después de
cargar datos; no deshabilitar guards sobre negocio vivo. Revertir binario conserva
tablas inertes; DROP/CASCADE destruye evidencia. Restore estático no es P06 ni
recuperación de escrituras posteriores. P05 conserva corrección de contextos.

## Verificación y cierre limitado

InstitutionStorageDbTest comprueba recuentos independientes directos de cada
tabla, instituciones separadas con autor común, referencias vs copias almacenadas,
deltas positivos/negativos, cuota legacy vigente, exceso inicial, aprobación,
reintentos, fuentes globales/no resueltas/conflictivas, materiales/capturas,
cambio de docente/baja/readmisión/revocación, desactualización, no reparación,
rollback completo cuota/T08, expansión/repetición/divergencia, carrera entre dos
JVM por la última revisión disponible y dump/restore nativo con todas las firmas.
La evidencia definitiva y SHA probado se registran a continuación; las
selecciones diagnósticas no se suman a esa ejecución.

Se cierra únicamente **P04 persistente implementado**. P05/P06, T11-B agregado
y etapa 3 permanecen abiertos. T12 mantiene autoridad/adopción/corte de writers.
SEC-13/VER-01 siguen abiertos; máximos reales, espacio físico/WAL/backups,
rendimiento, permisos y políticas de retención permanecen operativos. Retención
desactivada, sin purgas/limpieza automática, planes comerciales, capacidad
productiva, merge, despliegue, DDL/backfill real o activación multiinstitucional.
Auditoría original, documentos históricos, frontend y 16 E2E conservados.

## Evidencia definitiva atribuida al commit

**SHA probado:** `d666635666efe749250acaa2a98a0043c2d2fefa`.
`clean package` completo terminó el **14 de octubre de 2026, 05:04:23
America/Lima**, en **17 min 59 s**: **63 suites, 849 pruebas, 0 fallos, 0 errores,
0 omitidas; BUILD SUCCESS y JAR generado**. Los **20 casos nuevos P04** están
incluidos en esas 849, junto con las regresiones T02–T10 y B1–B8. No se suman
selecciones diagnósticas ni carreras/restores nuevamente al total.

Entorno: Windows, JDK 17.0.12, Maven 3.9.15 offline y PostgreSQL 18.4
desechable propio, solo 127.0.0.1:55439, DB chemicallab_t01_test, rol ficticio
t11b9_fixture, perfil test, locale C, 300 conexiones y 32 MiB shared_buffers.
No se utilizó una base escolar. Los errores de auditoría/arranque deliberados
en fixtures son esperados; el resultado definitivo procede de Surefire.

Carrera P04: dos JVM/OS PID distintos y dos backends PostgreSQL observados
bloqueados; resultados exactos COMMITTED y REJECTED:P7501, consumo final de una
revisión nueva y medición vigente. Dump/restore nativo: 112 tablas con
firmas idénticas, B9 recertificado, mutaciones fuera de fixtures y truncate de
evidencia rechazados. Recuento independiente compara todas las 67 fuentes por
ROW y JSON UTF-8; la medición sigue declarando live_authoritative=false.

Empaquetado: doce SQL encontrados en BOOT-INF/classes/db/expansion, todos
coincidentes con sus fuentes (solo normalización LF/CRLF para comparación).
SHA-256 del JAR:
`86a92cc5b51925289f69624c1468ec4217bf9a006e71801e80afcc058d640bf2`.
Conservación: 43 archivos protegidos, incluidos 16 E2E y auditoría original,
sin diferencias; once SQL históricos byte a byte y certificador B8 congelado;
60 documentos anteriores intactos, solamente matrices T11-A/T11-C modificadas.
Frontend conserva su rama institucional y archivos E2E no versionados previos.

[Resumen verificable](t11b9/validation.json). Logs, XML archivado, JAR, dump y
manifiestos detallados permanecen locales en `.codex-work/t11b9-evidence`.
El commit posterior de cierre modifica exclusivamente documentación/evidencia;
src, pruebas, SQL y POM son idénticos al SHA probado, comprobados con git diff.
No se atribuye otra suite a ese HEAD documental ni se requiere repetirla.

Comando reproducible, únicamente con la base ficticia preparada:

```powershell
./mvnw.cmd -o clean package '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55439/chemicallab_t01_test' '-Dspring.datasource.username=t11b9_fixture' '-Dspring.datasource.password=<CLAVE_FICTICIA>'
```

[PR backend #76](https://github.com/ChemicalLab-Org/ChemicalLab-Backend/pull/76)
en borrador hacia develop. Cierre exclusivo P04 persistente; P05/P06, etapa 3,
T11-B agregado, T12 y SEC-13/VER-01 continúan abiertos. Retención apagada,
sin merge, despliegue, DDL/backfill reales o activación multiinstitucional.
