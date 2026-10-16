# T12-F — Ejecución institucional NEW

16 de octubre de 2026, America/Lima. Base `aaebf4cd0e1674df1f62b3e256a3c000ec0e9a0d`
(T12-E integrado). Checkout aislado `.codex-work/t12f-backend`, rama
`feature/t12f-ejecucion-new`. Sin AGENTS.md aplicables ni delegación.

## Contrato elegido antes de implementar

La ruta explícita `evaluation-execution/NEW/v1` identifica el motor F. Los IDs
de intento de esa ruta pertenecen exclusivamente a `t12f_<fixture>.attempts`.
Las rutas T12-A/B NEW conservan la simulación `t11b8_fixture_attempts`; C/D/E
LEGACY conservan `evaluation_attempts` y sus recibos C/D/E. Nunca se busca un ID
en tablas alternativas. La entrega y revisión NEW siguen siendo las filas B8
publicadas exactas, previamente preparadas mediante el mantenedor de fixtures.

El primer inicio F hace explícita la adopción de ejecución mediante su ruta,
recibo y versión persistida. Se exige una raíz EXPLICIT_NEW sin
intentos de simulación B8 y sin assignments/attempts legacy. Las claves B8
budgets creadas al admitir destinatarios son preparación, nunca saldo F. Una raíz simulada
no se migra ni recibe saldo real. Cualquier mezcla posterior se rechaza como
ambigua. No se modifican las restricciones de B8 ni sus filas históricas.
El mantenedor B8 rechaza reservar una simulación sobre una raíz ya ejecutada.
Las rutas A/B tampoco conceden intento/corrección B8 sobre una raíz adoptada F;
solo conservan la lectura de preparación compartida cuando no existe mezcla.
Así una simulación introducida fuera del protocolo no sirve para consultar
corrección mientras F tiene notas pendientes.

Las propias filas F son el único ledger de oportunidades: COUNT por
recurso/cuenta, numeración consecutiva y unique parcial de activo. No hay
contador de consumo duplicado, devoluciones, budgets B3/B8 operativos ni
filas legacy para el ancla NEW. Cambiar clase, episodio o revisión no reinicia
ese ledger. Otro activo solo se recupera desde su entrega exacta.

Cada intento conserva institución, raíz, revisión, entrega, destinatario,
cuenta, episodio, configuración publicada, checksum, orden, inicio y plazo.
Respuestas, revisión y ajustes son parte de su agregado académico persistente;
las preguntas y opciones se identifican dentro de la revisión fijada.

El sucesor `t12f-v1.sql` conserva íntegros los diecisiete SQL anteriores y
certifica E antes de instalar F. El inventario F amplía explícitamente la
observación B10 con los agregados académicos F; no los clasifica como CONTROL.
Contextos y cuotas se verifican bajo la última cabeza F08 de la raíz y de sus
relaciones preparadas. Un nuevo F08 nunca recupera una autorización histórica
por replay. Los recibos F incluyen fotografías del agregado y de la raíz B10,
contexto aprobado, actor, acción, contenido, auditoría y medición.

## Límites y estado

Desactivado por defecto; perfil único test, schema aprobado, base ficticia y
loopback. Sin creación/publicación HTTP, frontend, paneles, activación real,
merge, despliegue, DDL/backfill real ni rotaciones. T12 integral y etapa 4
permanecen abiertos. Evidencia y pendientes se completan con resultados reales.
Las rutas legacy restantes conservan sus contratos; esta entrega no las declara
institucionalmente aisladas ni acredita activación o capacidad productiva.

## Rutas y versión de almacenamiento

Prefijo F:

```text
/api/institutions/{institutionId}/classes/{classId}/evaluation-execution/NEW/v1/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}
```

| Método y sufijo | Contenido o acción |
| --- | --- |
| GET al prefijo | Entrega exacta sin corrección; `recipientAccountId` opcional solo selecciona supervisión metadata |
| POST `/own-attempts` | Inicio con `operationId` UUID |
| GET `/own-attempts/{attemptId}` | Recuperación propia persistida, sin DML ni corrección |
| POST `/own-attempts/{attemptId}/answers` | `operationId`, `answer` con revisionId/questionId/selectedOptionId/answerText |
| POST `/own-attempts/{attemptId}/submit` | `operationId`, colección `answers` opcional con el mismo formato |
| GET `/own-attempts/{attemptId}/correction` | Corrección propia únicamente cuando la política global la permite |
| GET `/attempts/{attemptId}/review` | Proyección docente unitaria con abiertas, criterios, atribución y ajustes |
| POST `/attempts/{attemptId}/review/answers/{answerId}` | Revisión abierta: operationId, score entero, feedback opcional |
| POST `/attempts/{attemptId}/review/complete` | Completar revisión, sin abiertas pendientes |
| POST `/attempts/{attemptId}/review/adjustments` | Ajuste con operationId, amount y reason |
| POST `/attempts/{attemptId}/review/adjustments/{adjustmentId}/void` | Anulación lógica |
| POST `/attempts/{attemptId}/review/feedback` | Feedback general |
| POST `/attempts/{attemptId}/review/close` | Cierre definitivo sin reapertura |

`answerId` F es la identidad de pregunta **dentro del intento y revisión de la
ruta**, no una PK de `evaluation_answers`. Los ajustes tienen identidad local
monótona en ese agregado. No se prueban tablas alternativas para resolverlos.
Todos los comandos incluyen UUID; las respuestas llevan no-store y proyecciones
tipadas. No se devuelve JSON académico, entidades, credenciales ni manifiestos.

| Ruta anterior | Almacenamiento que conserva |
| --- | --- |
| T12-A decide / T12-B evaluation-reads/NEW | Preparación y simulación B8, contrato anterior intacto |
| T12-B evaluation-reads/LEGACY | Tablas originales y procedencia B10/C/D/E |
| T12-C/D/E evaluation-writes/LEGACY | Motor original, recibos C/D/E según sucesor instalado |
| evaluation-writes/NEW | Sigue rechazado; no se transforma silenciosamente en F |
| evaluation-execution/NEW/v1 | Únicamente agregado académico F y publicaciones/entregas B8 exactas |

Desactivación devuelve 404; facultad/relación/regla incompatible 403 genérico;
sesión inválida o revocada 401; la contraseña temporal mantiene el filtro T02.
Entrada inválida 400, transporte excedido 413, UUID con otra petición 409 y
fallo de persistencia/auditoría/cuota/coordinación 503, sin datos académicos.

## Permisos y semántica académica

El actor siempre es SessionPrincipal T02. El servidor resuelve nuevamente
sesión, institución ACTIVE, pertenencia y roles efectivos, clase ACTIVE,
período OPEN, matrícula/episodio actual y destinatario ADMITTED. Los IDs y
selectores del cliente no son identidad ni facultad. En F las relaciones
físicas de entrega/destinatario deben concordar con la última resolución F08;
una corrección contradictoria no retargetea un intento histórico.

El inicio exige ESTUDIANTE propio y ventana exacta disponible. Fija desde el
servidor el episodio, configuración y checksum publicados, orden completo y
plazo mínimo entre dueAt y comienzo más duración. La aleatorización ocurre una
sola vez. El GET no crea, cambia orden, recalcula, repara o finaliza.
Si el inicio cruza la ventana antes de completar la unidad, su revalidación
final revierte también el recibo terminado, la auditoría y el consumo.

Guardar valida la revisión explícita, pregunta/opción de esa revisión, tipos
de respuesta y límite de texto; ONE_BY_ONE solo permite la pregunta del cursor
y avanza una vez. En envío normal se exigen abiertas obligatorias. El envío
ONE_BY_ONE ignora su colección de respuestas, como el motor anterior. Después
del plazo no se guardan respuestas nuevas: el envío ignora su colección y usa
solo lo guardado, dejando abiertas faltantes pendientes con un placeholder.
Una entrega retirada/cerrada no otorga ventana nueva para finalizar.

Las alternativas se califican automáticamente. Sin abiertas pendientes el envío
queda GRADED y cerrado automáticamente; con abiertas queda PENDING_MANUAL_REVIEW.
La revisión de la última abierta produce GRADED, pero el docente aún debe cerrar
la nota. EvaluationGradeMath comparte con LEGACY puntaje automático/manual,
nota base 0–20, suma de ajustes redondeada HALF_UP y resultado acotado [0,20].
No se consultan preguntas u opciones vivas del ancla DRAFT.

El contrato ejecutable admite como máximo 200 preguntas, 2000 opciones totales,
10 por pregunta, texto de respuesta 3000, feedback por abierta 2000, feedback
general 1500, motivo 500 y 200 ajustes históricos por intento. Se validan
escalares reales, identidades únicas, padres, tipos, puntajes, configuración y
una única opción correcta por alternativa. Una publicación incompatible con
este contrato se rechaza sin modificar su payload. El transporte T07-A incluye
la ruta F: límite de 1 MiB o el HTTP menor, límites estructurales y rechazo de
claves duplicadas incluso en cuerpos sin Content-Length o con otro Content-Type.
Exclusivamente F rechaza también IDs/puntajes fraccionarios o representados
como texto y textos numéricos antes de MVC; no cambia coerciones de contratos
anteriores ni la configuración global del ObjectMapper.
Los ajustes F acotan precisión a 32 dígitos y escala decimal a [-32,32] antes de
redondear. La longitud de un número por sí sola no limita su exponente.

| Actor | Facultad F |
| --- | --- |
| ESTUDIANTE propietario y relaciones vigentes | Entrega propia, inicio, recuperación, respuestas, envío y resultado permitido |
| DOCENTE + RESPONSABLE/CO_DOCENTE efectivos | Revisión unitaria ajena y operaciones docentes mientras la nota esté abierta |
| ADMIN_INSTITUCION solo | Supervisión metadata; no califica ni obtiene el detalle docente |
| Autor histórico sin asignación actual | La autoría no otorga revisión |
| Cuenta propietaria con roles docentes/administrativos | No revisa ni califica su propio intento |
| Asignación/rol revocados o vencidos | No revisa, escribe ni recupera recibos docentes |

Actor docente y cuenta propietaria se conservan separados. Cada abierta guarda
docente y fecha de revisión; ajustes conservan su autor y anulación; cierre
guarda actor/fecha. No hay reapertura. El alumno solo recibe sus respuestas
sin campos de corrección. La administración conserva su proyección de metadata.

El cierre global considera todas las entregas de la raíz B8, incluidas futuras,
vacías y sin dueAt, y **todos los intentos F**: ninguno puede estar en progreso,
pendiente o sin cierre de nota. Una raíz sin entregas no cierra por vacío.
Cerrar una nota individual no libera claves, explicaciones ni feedback.
Las simulaciones B8 no deciden la calificación del motor F.

## Inventario, contextos, presupuesto y coordinación

F separa almacenamiento sin ocultar los datos bajo CONTROL: `inventory()` enumera
la fuente académica `t12f_attempts` con versión NEW_EXECUTION_V1, PK, checksum,
snapshot, clasificación y raíz. `scan()` une esa fuente con el scanner congelado
B10. `measure()` incluye el agregado completo (respuestas/revisión/ajustes) como
ORIGINAL, con bytes JSON UTF-8 y procedencia raíz/revisión/entrega/institución/
clase/cuenta/episodio. La clasificación consulta la última cabeza F08 y conserva
incertidumbre/conflicto si esa cabeza no concuerda con el agregado fijado.
El catálogo, inventarios y versiones históricos B10 no se reescriben.

El journal F conserva antes/después del agregado y del manifiesto B10, contexto
del destinatario aprobado, versión raíz, cabezas, referencias, petición exacta,
actor, resultado, auditId, medición y XID. Cambios de borrador, publicación,
entrega o contexto preparados fuera de una operación F requieren la nueva
conciliación F08 explícita del arnés. No se recaptura evidencia para hacer pasar
una fotografía alterada ni para legitimar una modificación directa del agregado.
Una nueva cabeza F08 prevalece sobre recibos anteriores; un replay no restaura
autoridad retirada. Las fotografías F UTC no cambian la serialización histórica
de referencias B10.

Las filas de intento son el único consumo. No se eliminan y sus identidades,
revisión, orden, plazo y procedencia son inmutables. El guard exige una operación
abierta en el mismo XID. FINISH admite solo los campos y el hijo correspondientes
a esa acción; verifica que las otras filas y los datos de alumno ajenos a una
revisión docente no cambiaron. Un constraint trigger diferido impide confirmar
un recibo incompleto. Recibos completos y ledger rechazan UPDATE/DELETE/TRUNCATE.

Cuotas reutilizan límites B9 y atribución F08, incluidas derivaciones C/D/E.
C/D/E consultan la medición sucesora cuando F está instalado para contar también
sus datos académicos; sin F conservan su operación anterior. ACCOUNT/SYSTEM y
presupuestos LEGACY no se modifican ni se suman como otra oportunidad. El journal
tiene techo técnico independiente de 10000 recibos/64 MiB; no se podan históricos.

Cada escritura es REQUIRES_NEW/READ_COMMITTED, timeout 30 s, lock_timeout 5 s,
synchronous_commit=on. Se adquiere evaluations EXCLUSIVE (SHARE en GET), luego
las demás tablas del fixture en orden estable y las tablas F. El protocolo
incluye escritores, contextos, relaciones, revocaciones y fantasmas. No hay mutex
solo de JVM. Los locks permiten observar el commit anterior después de esperar.

Negocio, consumo, estado, contexto/recibo, historia, auditoría obligatoria T08 y
cuotas confirman o revierten juntos. Los SUCCESS solo contienen acción fija,
actor, UUID y IDs seguros, nunca respuestas, notas, motivos, criterios o feedback.
Una transición a revisión pendiente o revisión completada añade su evento
obligatorio en la misma unidad. Sesión y acceso se revalidan después del journal;
un fallo final también lo revierte.

Los replays se resuelven después de coordinar. El recibo vincula actor, acción,
selectores y contenido exactos. Se revalida acceso actual antes de devolverlo y
no se exige la precondición modificada por esa operación: un inicio puede
recuperarse tras vencer la ventana, y un SAVE/ADD/CLOSE puede recuperar su recibo
tras envío/cierre sin repetir consumo, ajuste, auditoría o cuota. Los contratos
anteriores conservan sus propias precondiciones de replay.

## Certificación, recuperación y conjunto de regresiones

La instalación es explícita y transaccional sobre fixtures certificados E.
No hay DDL/backfill al arrancar. Repetir el SQL certifica columnas, constraints,
FK, funciones, índices/estado, triggers/enablement y secuencias, ligado al
fingerprint E. Ante deriva se rechaza; no se recalculan fingerprints históricos.
El propietario/DBA capaz de falsificar funciones/evidencias queda fuera de la
frontera certificada. Se conservan los diecisiete archivos anteriores intactos.

La restauración incluye t03, t06, t12c, t12d, t12e y t12f juntos, negocio,
aprobaciones, auditoría, cuotas y recibos. El ensayo físico usa el arnés existente
con pg_basebackup/WAL streaming, pg_verifybackup, copia en puerto propio,
certificación F, comparación de todas las tablas y HTTP/replays. Es recuperación
del estado F, sin reatribuir los ensayos PITR históricos B11; no reemplaza WAL
ni respalda archivos externos o escritores ajenos.

Antes de suites lentas se definió este conjunto afectado: NewEvaluationExecutionDbTest
completa y las trece clases previas AcademicRevisionDbTest, EvaluationDeliveryAccessDbTest,
InstitutionalEvaluationReadDbTest, InstitutionalAnswerSaveDbTest,
InstitutionalAttemptLifecycleDbTest, InstitutionalAttemptReviewDbTest,
EvaluationWindowHttpDbTest, ConcurrencyDbTest, EvaluationServiceTest,
EvaluationOpenQuestionPersistenceDbTest, EvaluationAccessPolicyTest,
TransportInputFilterTest e InputBudgetsTest. No se ejecuta todo el repositorio.

La evidencia local se guarda en `.codex-work/t12f-evidence/`, fuera del PR:
comando, SHA/parche, logs/exit, XML y artefactos nativos por ejecución. Los fallos
se conservan y las selecciones no se suman a suites completas. La comprobación
de preservación abarca 192 archivos anteriores, incluidos los 16 E2E, documentos,
evidencias y auditoría, además de los checkouts principal/frontend y pares.

Los 21 casos de `NewEvaluationExecutionDbTest` cubren el recorrido HTTP y las
fronteras siguientes. El XML de cada ejecución conserva nombre, duración y
resultado de cada caso; esta tabla no suma selecciones como otra suite.

| Frontera | Demostración en la suite F |
| --- | --- |
| Publicación exacta y recuperación | Recorrido completo, dos publicaciones y cambios de borrador, orden persistido y GET sin DML |
| Consumo compartido | Clases/entregas/revisiones, readmisión con otro episodio y última oportunidad entre dos JVM |
| Namespaces e identidad | Simulación B8/NEW/LEGACY con IDs coincidentes, dos instituciones y selectores manipulados |
| Respuestas y plazos | Pregunta/opción/revisión exactas, abiertas obligatorias, ONE_BY_ONE, vencimiento real y cruce de ventana durante inicio |
| Revisión y exposición | Co-docencia, revocación, administrador solo, autocalificación, nota cerrada y cierre global con entregas futuras/vacías/sin plazo |
| Reintentos y atomicidad | Petición/actor/sesión vinculados, replays tras transición, fallo de auditoría/cuota y revocación final con rollback del recibo completo |
| Procedencia y compatibilidad | Última F08, cambios ajenos al protocolo, cuota compartida con recibo C original sin reescritura histórica |
| Instalación y recuperación | Certificación y repetición del SQL, rechazo de deriva y backup físico/WAL con comparación de tablas y HTTP/replays restaurados |
| Desactivación y transporte | Flags, fixture, tipos publicados incompatibles y cuerpos/colecciones/tipos HTTP acotados |

## Evidencia de desarrollo y pendientes

Entorno local Windows, JDK 17.0.12 y Maven 3.9.15 offline, PostgreSQL 18.4
desechable propio: 127.0.0.1:55449/chemicallab_t01_test, rol ficticio t12f_fixture,
max_connections=100, shared_buffers=32MB y max_locks_per_transaction=512.
Zona académica America/Lima, perfil único test. No H2 ni persistencia simulada.

| Ejecución | SHA | Resultado y límite |
| --- | --- | --- |
| compile-1 | Implementación inicial luego conservada en `10cf590` | BUILD SUCCESS; solo compilación |
| selección inicial / setup | `10cf590` | Sandbox denegó loopback y clean; sin prueba de negocio |
| selection-2 | `10cf590` | 1/1/0/0; guardado revertido por representación temporal de fotografía F |
| selection-3 | `83264b7` | 1/0/1/0; sintaxis CASE del nuevo validador SQL, sin recorrido aprobado |
| selection-4 | `79ee3b5` | 1/1/0/0; nulo en childId del recibo de inicio; rollback |
| selection-5 | `0c560cd` | 1/1/0/0; referencias B10 serializadas bajo otra zona; rollback |
| selection-6 | `9c2a5bb` | 1/0/0/0; recorrido completo alumno/docente y replays, selección de 2 min 39 s |
| critical-1 | `b0e4728` | 5/1/0/0; cuatro casos aprobados, falla el fixture de vencimiento antes del guardado por ventana inicial de 18 s; selección de 7 min 28 s |
| critical-2 | `c4adecd` | testCompile fallido por Enrollment.Status.ENDED inexistente; no ejecutó JUnit |
| critical-3 | `56e057c` | 7/1/0/0; seis casos aprobados, incluidos vencimiento, readmisión, cierre global, dos JVM y recuperación física; falla una aserción del arnés que esperaba 403 cuando el flag legacy seguía desactivado (404). Selección de 10 min 54 s |
| new-complete-1 | `736f328` | Candidata interrumpida deliberadamente antes de completar la clase para endurecer tipos JSON F; log/exit conservados. No es una suite completa aprobada |
| transport-final | `bd7c0ad` | 11/1/0/0; pasan dos casos F y InputBudgets completa; falla una aserción del test de transporte que confundía el nombre answerText con el valor Text |
| transport-2 | `9852993` | 10/0/0/0; TransportInputFilterTest e InputBudgetsTest completas y selección de inicio que cruza la ventana. BUILD SUCCESS en 1 min 33 s |
| new-complete-2 | `9852993` | 21/0/0/0; suite F completa, BUILD SUCCESS en 31 min 49 s. No se atribuye al endurecimiento de escala posterior |
| scale-guard | `cffa48a` | 9/0/0/0; TransportInputFilterTest (4) e InputBudgetsTest (5) completas, incluido rechazo de exponentes extremos antes del redondeo |
| final-package | `cffa48a` | Interrumpida sin exit registrado, causa no determinada; al retomar estaban ausentes Maven y el clúster propio. AcademicRevisionDbTest (34) y ConcurrencyDbTest (17) tienen XML completos 0/0/0; no se atribuye éxito a las demás clases ni al empaquetado |
| final-resume | `cffa48a` | EvaluationDeliveryAccessDbTest (22) y EvaluationWindowHttpDbTest (10) completas, 0/0/0. Corte planificado de la JVM propia verificada después de conservar esos XML para continuar las diez clases restantes con dos forks; salida Maven 1 por esa parada, sin empaquetado aprobado |
| final-two-forks / ciclo | `cffa48a` | InstitutionalAttemptLifecycleDbTest completa: 27/1/0/0. La carrera SAVE/SUBMIT recibe 503 donde esperaba 200; PostgreSQL confirma lock_timeout de 5 s en `lock table evaluations in exclusive mode` a las 09:44:24 Lima. No se relaja el límite ni se cuenta esta clase como aprobada. Se conserva el XML y se repite completa de forma secuencial tras las demás suites |
| final-two-forks / NEW | `cffa48a` | NewEvaluationExecutionDbTest completa: 21/1/0/0; el fixture de vencimiento recibe 403 al guardar cuando esperaba 200. La ventana inicial de 40 s se consumió durante preparación/conciliación/inicio bajo carga. La ejecución agrupada termina 226/2/0/0, BUILD FAILURE; las doce clases completas aprobadas aportan 261 casos sin contar las dos clases fallidas |

El fallo temporal se corrigió normalizando solo snapshots académicos F mediante
su inventario UTC. No se cambian referencias, SQL ni fingerprints previos.
Las ejecuciones posteriores, suites completas, JAR y publicación se registran
con sus SHA reales. Los reportes de clases completas se conservan aunque la
ejecución conjunta haya sido interrumpida; nunca se completa una clase sumando
selecciones parciales.
El fixture de vencimiento se amplió inicialmente a 40 s sin cambiar el límite académico ni
el timeout de operaciones HTTP; sigue esperando el vencimiento real. La
readmisión usa la transición existente WITHDRAWN. No se omiten esas verificaciones.
El caso de namespaces habilita explícitamente el flag del escritor anterior
antes de exigir su rechazo de NEW: así comprueba contrato, no desactivación.
Un sondeo aislado con JDK17 y heap de 32 MiB confirmó OutOfMemoryError al
redondear 1e-100000000. Se conserva su log y se acotó el exponente mediante la
escala F antes de llegar al cálculo. Los contratos anteriores permanecen
intactos. La ejecución conjunta final vuelve a incluir la suite F completa.
Tras el fallo bajo dos forks, esa ventana de fixture se amplía a 90 s para
incluir su preparación/conciliación e inicio. Conserva todas las aserciones,
espera el vencimiento real y comprueba el rechazo de respuestas tardías. No
cambia el plazo calculado por el servidor ni los timeouts de 30 s/5 s. Se depuran
los dos casos fallidos de forma focalizada antes de repetir completas ambas
clases con un solo fork.

## Pendientes para etapas posteriores

La coordinación conserva locks de tablas del fixture y escaneos completos de
inventario/contexto. Serializa operaciones de recursos distintos y no acredita
capacidad, latencia ni concurrencia productiva. Cambiar ese protocolo requiere
su propio diseño y ensayos; no se reduce la coordinación para acelerar estas
pruebas.

El contrato ejecutable admite solo el subconjunto publicado validado arriba.
No convierte simulaciones B8 ni migra intentos existentes. Una raíz simulada o
ambigua necesita una decisión de migración fuera de esta entrega. Permanecen
pendientes la creación/publicación externa, interfaces, activación y operación
real. La recuperación física F con WAL streaming no constituye un nuevo ensayo
PITR histórico ni certifica escritores externos. T12 integral y etapa 4 siguen
abiertos; el PR se entrega en borrador para revisión.
