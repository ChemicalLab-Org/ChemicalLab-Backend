# T11-B10 / P05 — Conciliación exhaustiva y corrección aditiva

14 de octubre de 2026, America/Lima. Preparación persistente y mantenedor interno
exclusivos de P05, sobre `develop` `ab19dde3d724be7c3d581e20074661514146fdb6`,
reconfirmado mediante fetch. Rama `feature/t11b10-conciliacion-contextos`, checkout
aislado `.codex-work/t11b10-backend`. Leídos T11-A, T11-C y B1–B9; no AGENTS.md
aplicables en repositorio o ancestros. Sin delegación a otros agentes.

## F08 confirmado y límites

Los mapas institucionales **y sus correcciones** requieren aprobación de un
administrador de plataforma y un administrador de la institución destino.
Son cuentas distintas, aunque una tenga ambos privilegios. Cada aprobación
conserva cuenta, facultad concreta, fotografía de su procedencia, mapa/versión
exactos, checksum, evidencia e instante DB. Ninguna suple a la otra ni demuestra
evidencia ausente. Este acuerdo no identifica colegios, responsables ni mapas
reales. Todos los datos y constancias FIXTURE-* son sintéticos.

No hay endpoints, jobs, listeners, entidades JPA nuevas, instalación de arranque
ni nuevas autoridades REST/STOMP. Las mutaciones SQL exigen la guarda B5:
`127.0.0.1`, DB `chemicallab_t01_test`, schema propio `t03_<32 hex>`. Los métodos
de inventario/propuesta/aplicación rechazan contexto autenticado; approve exige
SessionPrincipal autenticado y obtiene de él la cuenta, nunca del mapa. SQL
comprueba la procedencia vigente pero no autentica sesiones: el mantenedor/rol
DB de fixtures es su frontera de confianza. No se certifican permisos reales
ni protección contra un DBA que pueda deshabilitar triggers o alterar ledgers.

## Catálogo y cobertura por PK

[Catálogo congelado P05_CATALOG_V1](t11b10/catalog.json): **113 fuentes**, las
112 tablas predecesoras y la fuente virtual `legacy_groups`. No se reutiliza
el catálogo de 67 fuentes de cuota B9 como inventario del dominio. Se enumeran
15 fuentes globales, 12 institucionales explícitas, 59 mixtas y 27 de control.
GLOBAL describe procedencia; **jamás significa acceso público**.

Cada descriptor contiene claves ordenadas y relaciones FK; uso/auditoría añaden
relación lógica de actor y destinos tipados. Se incluyen todas las cuentas,
sesiones, perfiles docentes/alumnos, pertenencias, períodos/clases/episodios,
contenidos y colecciones, materiales, evaluaciones/preguntas/opciones,
asignaciones, intentos/respuestas/ajustes/eventos, pizarras/participantes/objetos,
capturas dentro de su fila, uso/auditoría y estructuras B1–B9. Una PK sin recursos
tiene exactamente el mismo tratamiento de cobertura que una con actividad.

`legacy_groups` inventaría `(grade,section)` y sus referencias originales desde
perfiles, asignaciones y pizarras. Conserva ambigüedad, sin inferir período,
institución, matrícula o asistencia. Etiquetas iguales no fusionan clases ni
personas. No se elige primera membresía, docente actual, correo o prefijo.

El catálogo enumera **once exclusiones técnicas exactas**, todas tablas B10:
catalog, schema_version, usage, commands, runs, inventory, proposals, approvals,
events, versions y measurements. Su motivo es evitar autorreferencia del
inventario. Sus filas/fotografías sí se miden y limitan como CONTROL. Los logs
ContextResolution se inventarían como control; el SUCCESS posterior a una
fotografía pertenece al siguiente inventario. No se oculta ese crecimiento.

`inventory` guarda una entrada por fuente/clave observada, versión P05_ROW_V1,
checksum, fotografía, clasificación y motivo verificable. Las categorías son
GLOBAL, CONTROL, RESOLVED estructural, UNRESOLVED y CONFLICT. La clasificación
estructural de una pertenencia con institution_id no certifica permisos.
Se verifican padres ausentes, fuentes desconocidas, claves ausentes/ambiguas,
perfiles duplicados y coherencia evaluación/asignación/pregunta/respuesta.
Una clave declarada ambigua conserva **todas** sus filas físicas en
`ambiguousRows`; no elige una fila por orden. Los hashes incluyen referencias
de padres. Se detectan cambios de credenciales sin copiar passwords o secretos
al inventario. No se crean/fusionan cuentas o perfiles.

`inventory_differences(run)` contrasta de nuevo las fuentes y distingue PK
nueva/omitida, retirada, cambiada y deriva de estructura. Un run conserva su
instante; no afirma cobertura continua tras escrituras posteriores. La prueba
independiente enumera directamente PK de cada tabla, sin usar scan/manifiesto
o catálogo B9 para obtener los resultados esperados; publica recuentos por fuente.
Un dump completo sigue siendo evidencia de conservación, no de conciliación.

## Versiones, coherencia y proyección vigente

`proposals` es append-only: raíz tipada, versión secuencial, manifiesto íntegro
de su agregado, mapa exacto por fuente/PK, referencias de destino, antecedentes,
checksum de medición y motivo. Las raíces admitidas son concepto, evaluación,+pizarra, uso y auditoría. La clausura incluye padres/hijos originales y puentes
B3/B4/B8 relacionados; las fotografías e historias originales permanecen.
Cada PK del agregado debe aparecer exactamente una vez en el mapa, con evidencia
y resolución institucional o incertidumbre/conflicto. No se fabrica matrícula
para un intento sin asignación. Los demás perfiles/grupos sin prueba permanecen
inventariados con incertidumbre y motivo, sin necesidad de inventar propuestas.

`approvals` conserva ambas cuentas distintas mediante dos UNIQUE, privilegio
global B5 o grant ADMIN_INSTITUCION del destino, snapshot exacto y checksum de
fuente/mapa/referencias/antecedentes/medición. Se comprueban cuenta activa,
institución/membresía/grant y ventanas con reloj DB. Un administrador de otro
colegio, checksum/evidencia ausentes, permisos revocados o versiones cambiadas
no autorizan aplicar. Las aprobaciones anteriores no se actualizan ni borran.

`events` registra PROPOSED, APPROVED, REJECTED, APPLIED o STALE. Aplicar sin las
dos aprobaciones falla sin emitir SUCCESS. Una fuente/referencia/permiso/medición
o antecedente desactualizados produce STALE terminal sin versiones válidas.
Una incoherencia de mapa falla y permite registrar rechazo explícito conservando
propuesta/aprobaciones; no corrige el mapa. Un nuevo mapa exige nueva propuesta.

`versions` agrega una versión por fuente/PK, enlace al predecesor, contexto
anterior (incluido el puente histórico B3/B4), razón y fotografía comprobada.
No actualiza tenant, IDs, autores, bytes, cuentas, notas, respuestas, fechas o
relaciones originales. Una corrección entre colegios abarca el agregado entero;
no copia el recurso a ambos para eludir una contradicción. Clase/colegio/cuenta/
episodio y hecho original deben concordar; episodios se comprueban en la fecha
del hecho con zona explícita. Un participante existente no se crea desde una
matrícula; una cuenta desconocida continúa desconocida.
Respuestas, eventos y ajustes comprueban la cuenta a través del intento original;
el intento académico B8 comprueba su destinatario. Las fixtures conservan una
respuesta calificada, feedback, ajuste, evento, nota cerrada y consumo F09 al
corregir el contexto, además de rechazar opciones/preguntas de padres distintos.
Un grant de biblioteca B6 del colegio anterior conserva su institución y
facultades originales: debe permanecer incierto/conflictivo en esa corrección,
sin convertirse en autorización del destino por heredar el nuevo recurso.

`t11b10_current` ofrece solo la última versión de cada PK, con antecedente,
status, context_valid y **access_authorized=false**. Revalida fuente, relaciones
y permisos aprobadores. UNRESOLVED/CONFLICT/STALE nunca recupera una versión
anterior válida por orden/fallback. Uso/auditoría con destino tipado requiere
padre B10 vigente, misma institución y clase; no se suple desde membresía actual
o un vínculo B4 antiguo. Las consultas legacy siguen usando su contrato actual:
T12 adoptará explícitamente esta proyección junto con autorización integral.

Los casos B3 «nueva fotografía con raíz/contexto existente» y B4 v2 «vínculo
histórico incorrecto» se reproducen: el nuevo mantenedor no reinserta sus PK ni
modifica sus tenants; añade otra resolución y conserva las tablas y conflictos
anteriores. Los doce SQL históricos y sus filas/fingerprints quedan intactos.

## Presupuestos, cuotas y atomicidad

P05 solo corrige contexto. A13–A17/T05 y F09 conservan raíces, consumo completo,
números, único activo, máximo y cierre. No crea budgets NEW ni devuelve saldo;
no reabre asignaciones/ventanas o expone correcciones. Legacy sigue siendo la
única autoridad viva de intentos y revisión; una raíz preparada stale sigue
sin ser saldo. Las regresiones de B3/B8 conservan su alcance propio.

B9 conserva contract/items/counters/commands/deltas sin sobrescritura.
`measurements` añade fotografías sucesoras ROW_JSON_UTF8_V1, enlace al antecedente,
hashes B9 y diferencias explícitas before/after por fuente/PK: corrección de
atribución o cambio medido de fuente. La aprobación liga el checksum completo
de las fuentes a medir; una deriva intermedia impide aplicar. La nueva fotografía
incluye cambios físicos explícitos y conserva todas las mediciones anteriores.

Cada fila original se mide una vez. Una nueva referencia no multiplica bytes
de material/captura/revisión; las revisiones y fotografías que **sí almacenan**
otra copia siguen consumiendo. El almacenamiento de snapshots/puentes históricos
B3–B8 mantiene su atribución histórica; corregir la referencia vigente no borra
esas copias. Las nuevas filas B10 y sus fotos completas consumen CONTROL bajo
techo técnico de 1 000 000 filas / 256 MiB UTF-8, incluidos catálogo/ledger/
medidor y auditoría propia; no es dimensionamiento productivo.

`storage_counters` proyecta la última medición sin sumarla a B9 ni a ACCOUNT/
SYSTEM legacy: son dimensiones/versiones diferentes del mismo almacenamiento.
`storage_observation` detecta deriva y declara live_authoritative=false. Los
límites institucionales B9 gobiernan incrementos de atribución/almacenamiento;
el exceso histórico se conserva sin truncar. No se descuentan oportunidades.

Locks amplios exclusivos de fixtures, en orden B9 → fuentes ordenadas → control,
relectura y comprobación de antecedentes, serializan procesos. UNIQUE/FK/guards
PostgreSQL impiden reescribir aprobaciones, versiones, historia y contadores.
Comandos UUID + request exactos hacen idempotente el reintento; otro request con
el mismo UUID falla. Negocio, versiones, deltas/medición, cuotas e historial y
SUCCESS obligatorio T08 comparten la transacción Spring. Un fallo de cuota o
destino T08 revierte todo. No se prometen rendimiento o ausencia de deadlocks
para escritores arbitrarios con orden distinto. Contendientes SQL ensayan
integridad; las pruebas del servicio demuestran por separado la atomicidad T08.

## Instalación, repetición, divergencia y recuperación

Cadena para schema **nuevo de ensayo**: B1 v1 → B2 v1 → B2 v2 → B3 v1 → B3 v2 →
B4 v1 → B4 v2 → B5 v1 → B6 v1 → B7 v1 → B8 v1 → B9 v1 → **B10 v1**.
En un schema B9 certificado aplicar solamente el sucesor B10 que falta.
B10 incorpora íntegro el certificador B9 congelado y es el certificador sucesor
actual. B9 sigue certificando su catálogo intacto. No se repiten predecesores
que rechazan correctamente sus evoluciones, ni se usa Hibernate como reparación.
Las funciones nuevas usan `t11p05_*`: el patrón histórico `t11b1_%` también
coincidiría con funciones llamadas `t11b10_*`. Este espacio separado evita esa
colisión sin excluir objetos del certificador, modificar B1/B9 o recalcular firmas.
Las tablas/vistas conservan el prefijo `t11b10_`.

```powershell
# Solo un fixture explícitamente autorizado; conexión/destino revisados y pgpass.
psql --set ON_ERROR_STOP=1 --command 'select current_database(),current_schema();'
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b10-v1.sql
# Repetición: verifica cadena, catálogo/fingerprint y estructura fuente, sin reparar.
psql --single-transaction --set ON_ERROR_STOP=1 --file src/main/resources/db/expansion/t11b10-v1.sql
```

Cada archivo completo en transacción; DO/advisory lock. Objetos sin ledger,
versión/predecesor desconocidos, fuente o catálogo/guard divergentes se rechazan.
Fingerprint cubre columnas/defaults, constraints/validación, índices/estado,
triggers/enablement, funciones/vistas/secuencias y descriptor congelado. El
checksum MD5 identifica deriva de JSONB; no es firma humana/criptográfica.
Revisar por separado checksum Git de archivos. Nunca recalcular un ledger para
ocultar deriva. No firma datos reales, privilegios operativos o espacio físico.
La firma adicional de estructura legacy normaliza casts redundantes de literales
enum varchar/text y de sus arrays ANY que PostgreSQL reexpresa al restaurar.
Conserva valores, operadores y paréntesis; la prueba añade un valor UNKNOWN y
exige divergencia. No modifica restricciones ni las firmas B1–B9.

Tras fallo: comprobar rollback, resolver la causa y repetir la misma operación
UUID/request. Ante commit desconocido investigar comando/resultado/eventos/
antecedentes/SUCCESS antes de reintentar. Recuperación ensayada: dump completo,
restore aislado con --single-transaction --exit-on-error, mismo schema/search_path,
certificación B10 y comparación de firmas de **todas** las tablas. La restauración
carga datos antes de triggers, sin deshabilitarlos sobre negocio vivo. Revertir
binario conserva tablas inertes; DROP/CASCADE perdería evidencia.

Esto no implementa checkpoints, lotes, caída de proceso ni recuperación de
escrituras posteriores: **P06 sigue abierto y no se inicia**. Tampoco inicia
T12, merge, despliegues, DDL/backfill escolar, retención o activación institucional.
Los mapas reales se mantienen fuera de repositorio, PR y logs públicos.

## Verificación y cierre acotado

ContextReconciliationDbTest contrasta PKs independientes, perfiles sin recursos,
grupos/huérfanos/claves ambiguas, aprobaciones F08, stale, corrección entre clases/
colegios con cuenta compartida, casos B3/B4, incertidumbre sin fallback, consumos/
ventanas, cuotas, rollback T08, idempotencia, cadena/divergencia, dos procesos y
dump/restore. El resultado final y SHA probado se registrarán tras la ejecución
completa, sin sumar selecciones ni evidencia histórica a los resultados nuevos.

El cierre corresponde únicamente a P05 persistente implementado/verificado.
T11-B agregado y etapa 3 permanecen abiertos por P06 y demás gates vigentes;
P08 mapas reales, T12 adopción/autorización y SEC-13/VER-01 continúan pendientes.
Auditoría original, documentos históricos, frontend y 16 E2E se conservan.
