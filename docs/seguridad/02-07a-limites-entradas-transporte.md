# T07-A — Límites de entradas, transporte y estado (SEC-13 parcial)

Etapa 2, seguridad actual. Fecha de trabajo: 11 de octubre de 2026, America/Lima.
**SEC-13 sigue abierto para T07-B. Este ensayo no cierra T10 ni certifica capacidad productiva.**
No hay instituciones, paginación, agregados SQL, cuotas de almacenamiento, retención,
merge, despliegue, migración productiva ni acceso a bases escolares en este bloque.

## Base y reproducción

Se consultaron ramas, remotos y worktrees. `origin/develop` coincide con backend
`77225b100c5374d37fd10f230e8d47c1b10410d8` y frontend
`d5e47a0e245ac9aec94480894386a8db73ae853d`. Se crearon worktrees aislados
`t07a-backend` / `t07a-frontend`, rama `security/t07a-limites-transporte`.
El checkout frontend institucional queda en `feature/validaciones-datos-institucionales`,
HEAD `e88589635a8269c50ee0f53fccf157f0d0985034`; no se editan sus 16 archivos e2e-selenium,
la auditoría ni documentos anteriores. No se encontraron AGENTS.md aplicables.

Antes de conectar los controles se ejecutaron dos reproducciones ficticias:
`WhiteboardDrawEventServiceTest#t07aRechazaCoordenadasNoFinitasSinDifundir` y
`UsageMetricServiceTest#t07aRechazaMapaExcesivoAntesDePersistir`.
La entrada `NaN` se aceptaba y difundía; un mapa con 33 entradas se saneaba y guardaba.
Resultado anterior: **2 pruebas, 2 fallos, 0 errores/omisiones**. Ambas pasan con T07-A.
Los logs de reproducción y los archivos de los arneses permanecen locales.

Límites anteriores conservados: estado 2.000.000 caracteres; dibujo 1000 puntos,
ids 100, texto 200 runs / 5000 caracteres, grosor 100, borrador 200, fuente 4–400;
saneamiento de telemetría a 8 entradas / 120 caracteres por valor / 500 total;
multipart 10 MiB por archivo / 12 MiB por petición; validación de archivos T06;
autenticación, revocación y presupuestos compartidos en PostgreSQL de T02/T04.

## Inventario acotado

| Superficie | Contrato/punto de aplicación |
|---|---|
| POST `/api/usage-metrics/events` | `RecordUsageEventRequest`; preflight HTTP, Bean Validation, `UsageMetricService`, saneamiento previo existente |
| POST `/api/evaluations/student/attempts/{id}/events` | `RegisterAttemptEventRequest`; mismo presupuesto de telemetría por cuenta; descripción 200 / source 60 conservados; `EvaluationService` mantiene propiedad, ventana y eventos de ciclo de vida exclusivos del servidor |
| REST `/api/whiteboards/{teacher,student,admin}/**` | Admisión HTTP después de autorización, incluidos lectura/creación/controles/join/presencia; JSON y multipart toman caminos distintos |
| GET `/api/whiteboards/{teacher,student}/{id}/state` | Autorización T03; validación completa de histórico antes de retornarlo; no escritura |
| PUT `/api/whiteboards/teacher/{id}/state` | `WhiteboardBoardStateRequest`; preflight del envelope y del JSON interno; solo acepta igualdad con la proyección del servidor, conforme a T03 |
| SEND `/app/whiteboards/{id}/draw` | Preflight antes de DTO, validación numérica y de colecciones en `WhiteboardDrawEventService`, esquema completo en `WhiteboardObjectState` |
| SEND `/app/whiteboards/{id}/presence` | Cuerpo vacío; presupuesto independiente; presencia/autorización anteriores |
| SUBSCRIBE `/topic/whiteboards/{id}` y `/user/queue/whiteboard-errors` | Destinos exactos T03, observación autorizada, topes por cuenta/conexión y rechazo de destino repetido en una conexión |
| CONNECT, UNSUBSCRIBE, heartbeat, DISCONNECT y `/ws/**` | Registro de transporte, validación periódica T03, buffers STOMP/nativos, admisión y liberación de recursos |
| Persistencia del lienzo | Misma transacción y lock de fila T03; proyección `current_state_json`, `state_revision`, ownership y tombstones; broadcast solo después del commit |

La identidad viene del principal/cuenta resuelta en servidor. `module`, `eventType`,
`source`, metadata y `clientEventId` declarados por el cliente son transporte/telemetría;
no autorizan acceso ni alteran calificaciones. No se añaden rutas ni permisos públicos.

## Matriz de límites

Todas las propiedades de entrada nuevas están en `TransportLimits`, prefijo
`app.transport.*` (también admite variables de entorno de Spring Boot, por ejemplo
`APP_TRANSPORT_DRAW_BURST`). Se validan valores positivos al arrancar. Los máximos
anteriores de DTO/servicios siguen siendo techos: configurar valores mayores no los elimina.
El período de expiración debe cubrir la recarga completa de todos los buckets.

| Unidad / propiedad después del prefijo | Valor inicial | Alcance y aplicación | Exceso |
|---|---:|---|---|
| Bytes JSON HTTP / `http-bytes` | 4.194.304 (4 MiB) | REST pizarra, después de autenticación/autorización; longitud declarada y lectura efectiva de máximo límite+1 | 413 `INPUT_LIMIT_EXCEEDED` |
| Bytes telemetría / `telemetry-bytes` | 32.768 | Ambos POST de eventos, antes de DTO/mapas, también con longitud desconocida/chunked | 413 |
| Bytes mensaje STOMP / `stomp-bytes` | 65.536 | Frame STOMP reensamblado; control framework antes del DTO; cuerpo comprobado de nuevo en interceptor | Cierre de transporte si no se puede decodificar; rechazo privado si llegó al interceptor |
| Bytes frame WebSocket / HTTP SockJS `xhr_send` | 2 × `stomp-bytes` = 131.072 | Envelope nativo antes de decodificación; buffer por conexión, límite efectivo HTTP | Cierre nativo / 413 HTTP |
| Bytes envío / `send-buffer-bytes` | 262.144 | Buffer por conexión; stream SockJS; cache SockJS 32 mensajes | Cierre del cliente lento al exceder el presupuesto |
| Milisegundos / `send-time-ms` | 10.000 | Semántica de sendTimeLimit de Spring, evaluada al intentar otro envío | Cierre de transporte; no se presenta como deadline absoluto de un único send bloqueado |
| Profundidad JSON / `json-depth` | 12 | Parser streaming local de HTTP/STOMP/estado; raíz incluida | 413 |
| Caracteres string / `json-string-chars` | 5000 | Constraints del parser ordinario antes de árboles/DTO; envelope `stateJson` usa `state-chars` | 413 |
| Propiedades por objeto / `json-properties` | 32 | Contadas en streaming; nombres hasta 100 caracteres (metadata hasta 60); números hasta 32 caracteres; duplicados y raíces múltiples prohibidos | 413 para exceso, 400 para sintaxis/duplicados |
| Elementos por colección JSON / `json-collection` | 10.000 | Streaming antes de deserializar; límites semánticos inferiores adicionales | 413 |
| Caracteres estado / `state-chars` | 2.000.000 | JSON interno antes del árbol y proyección final antes de asignarla a entidad | 413 |
| Objetos visibles por lienzo / `board-objects` | 2000 | Total strokes + texts + shapes, proyección histórica/candidata | Rechazo, rollback sin broadcast |
| Puntos acumulados / `board-points` | 40.000 | Todos los strokes del lienzo | Rechazo, rollback |
| Puntos por evento/trazo / `event-points` | 1000 | Payload y cada stroke del estado; TEXT exige 1, SHAPE 2 | Rechazo |
| Runs / `text-runs`; caracteres / `text-chars` | 200 / 5000 | Por texto, incluidas entradas irrelevantes del evento | Rechazo |
| Magnitud de coordenada / `coordinate` | 100.000 | Cada eje, admite valores negativos; solo números finitos | 400 `INPUT_INVALID` |
| IDs, colores y medidas | IDs 100; hex 3/6 dígitos; grosor (0,100], borrador (0,200], fuente [4,400] | Techos anteriores conservados y finitud agregada; IDs no vacíos; índice de trazo [0,100000] | Rechazo |
| Entradas metadata / `metadata-entries` | 32 | Mapas completos, antes de crearlos y también en servicio | 413 |
| Caracteres clave / `metadata-key-chars`; valor / `metadata-value-chars` | 60 / 300 | Input antes de saneamiento; solo string/null como valor, sin objetos anidados | 413 / 400 |
| Conexiones / `connections`; `account-connections` | 256 por instancia / 3 por cuenta | Registro sincronizado, incluye conexiones pendientes; máximo 5 s sin CONNECT válido | Cierre 4429 al superar admisión; watchdog libera pendientes |
| Suscripciones / `account-subscriptions` | 12 por cuenta | Suma de todas las conexiones de la cuenta; id de suscripción hasta 100 | Cierre 4429 de la conexión infractora |
| Destinos / `connection-targets` | 4 por conexión | Boards observados, incluidos SEND; error queue separada; repetición de destino en una conexión rechazada | Cierre 4429 |
| Buckets / `rate-accounts`; segundos / `rate-idle-seconds` | 4096 cuentas / 60 s inactivas | Registro de seis categorías fijas; no expulsa cuentas activas para hacer lugar | 503 `TRAFFIC_UNAVAILABLE` / cierre 4429 en protocolo |
| Tokens/s / burst: `draw-per-second`, `draw-burst` | 30 / 60 | Por cuenta en esta instancia, todas sus conexiones y boards | Privado 429 `TRAFFIC_RATE_LIMITED`; operación descartada |
| Tokens/s / burst: `presence-per-second`, `presence-burst` | 1 / 3 | Igual alcance, independiente de dibujo | Rechazo privado |
| Tokens/s / burst: `control-per-second`, `control-burst` | 5 / 10 | SUBSCRIBE/UNSUBSCRIBE, toda la cuenta | Cierre 4429 |
| Tokens/s / burst: `frame-per-second`, `frame-burst` | 120 / 240 | Incluye frames rechazados/heartbeat; antes de nueva consulta de sesión | Cierre 4429, evita inundar la cola con rechazos |
| Tokens/s / burst: `telemetry-per-second`, `telemetry-burst` | 3 / 30 | Los dos POST de telemetría comparten presupuesto por cuenta | HTTP 429 y Retry-After: 1 |
| Tokens/s / burst: `rest-per-second`, `rest-burst` | 5 / 30 | REST pizarra, incluidos GET y multipart | HTTP 429 y Retry-After: 1 |
| Hilos / tareas en canales STOMP | 4 / 256 en inbound y outbound | Por instancia, colas explícitamente acotadas; watchdog previo: 4 workers / cola 256 | Saturación del transporte; timeout/reconexión del cliente |
| Conexiones/hilos/cola TCP HTTP | 512 / 64 / 100; timeout conexión 10 s | Tomcat; `HTTP_MAX_CONNECTIONS`, `HTTP_MAX_THREADS`, `HTTP_ACCEPT_COUNT`, `HTTP_CONNECTION_TIMEOUT` | Admisión/rechazo a nivel socket, sin prometer un JSON en esa capa |
| Pendientes / segundos / cola remota Angular | 128 / 10 / 512 | Por conexión/componente cliente | Recuperación del objeto correlacionado; cola remota saturada bloquea dibujo y ofrece reconexión |

Estos valores dejan margen para trazos enviados al terminar un gesto, deshacer/rehacer,
texto con formato, reconexión y varias pestañas. 4 MiB acomoda el envelope habitual de
un JSON interno de hasta 2 M caracteres; caracteres con escapes pueden alcanzar primero
el límite de bytes. 40.000 puntos y 2000 objetos limitan procesamiento/reproducción de
una pizarra sin cambiar el formato v1. Son **presupuestos iniciales configurables**, no
una medición de cuántos alumnos soporta producción. La cache y los ejecutores del stack
son topes explícitos fijos; su dimensionamiento real queda para los ensayos pertinentes.

## Esquema, atomicidad e históricos

Solo se admite raíz objeto v1, `strokes`/`texts` arrays y `shapes` opcional; `revision`
entera no negativa. Cada tipo tiene una lista cerrada de propiedades. Los strokes
tienen evento DRAW/ERASE, points no vacío de objetos exclusivamente x/y y medidas
válidas; los textos posición wx/wy, color, tamaño y runs de text/string + tres booleanos;
las formas tipo RECTANGLE/CIRCLE/LINE/ARROW, cuatro coordenadas, color y grosor.
IDs son únicos dentro de cada tipo; la misma identidad en tipos distintos conserva T03.
`ownerUserId` es positivo o null; nunca constituye prueba de autoría. La revisión de
una proyección T03 debe coincidir con la revisión de la fila. Valores exponenciales que
desbordan double, NaN/infinito, arrays/objetos de tipos incorrectos y campos desconocidos
se rechazan. No se guarda ni difunde ningún estado inválido o excesivo.

El esquema se comprueba sobre el estado anterior y sobre el candidato antes de
serializar/asignar la entidad. Cualquier escritura de ownership realizada dentro de
`apply` revierte con la transacción si falla la validación final. Se conservan lock,
autoría, revisión monotónica, restauración y tombstones; el broadcast sigue afterCommit.
No se añade una escritura por punto: T03 ya persiste una operación/gesto con sus puntos;
T07-A añade lectura/validación en memoria y buckets, sin tabla nueva ni rate-write SQL.

Los históricos se validan al leer/editar. Si no cumplen, GET estado devuelve **409
HISTORICAL_STATE_INVALID**, sin retornar el payload ni borrar/reescribir la fila.
PUT/dibujo sobre un histórico inválido falla cerrado. Un histórico v1 válido de revisión
0 sigue ocultando autoría declarada; strokes sin id pueden recibir identidad neutral
en la importación conservadora T03 durante una operación válida. La autoría real
permanece en las filas ownership. La recuperación de históricos fuera de esquema es
una revisión operativa explícita, con respaldo y autorización, no una migración masiva
incluida aquí. No se abre un PUT para reemplazar arbitrariamente el lienzo.

## Errores, recuperación y alcance por instancia

HTTP conserva los 401/403 de autenticación/rol antes de inspeccionar entradas. Entrada
estructural inválida: 400 `INPUT_INVALID`; exceso: 413 `INPUT_LIMIT_EXCEEDED`;
frecuencia: 429 `TRAFFIC_RATE_LIMITED`; registro saturado: 503 `TRAFFIC_UNAVAILABLE`.
429/503 llevan Retry-After: 1. JSON malformado no cae en el 500 genérico. Las respuestas
incluyen mensaje fijo seguro; no se exponen payloads ni diagnósticos del parser.

Un SEND decodificable rechazado recibe solo en su conexión la cola privada con
`accepted:false`, `resync:true`, `code`, `error`, `clientEventId`. El header STOMP
`client-event-id` se limita a 100 y solo se usa para correlación, no para autorización.
La confirmación aceptada sigue siendo el evento del topic con revisión/actor del servidor;
un error nunca se trata como un ack. Clientes anteriores sin header conservan recuperación
genérica. Mensajes que exceden el presupuesto previo del protocolo pueden cerrar la
conexión antes de poder correlacionarse. Cierre 4429 mantiene sesión HTTP y reintenta a
4 s; 4001/4003 mantienen los contratos de sesión/acceso T03.

Angular retira solo la operación correlacionada, consulta el estado protegido y superpone
las intenciones posteriores; conserva también arrastres todavía no enviados. Un timeout
de 10 s significa resultado desconocido: se consulta el estado, no se reenvía automáticamente
una operación que quizá se confirmó. Los ack tardíos se siguen reconciliando por revisión.
La recuperación fallida bloquea dibujo y muestra la opción de reconexión. Todos los timers
se liberan al desconectar; saturación de cola remota exige reconexión sin crear un bucle
de HTTP. Rechazos/mensajes de error no vuelcan payloads en logs del controlador.

T07-A cuenta conexiones, destinos, suscripciones y frecuencia **por instancia**. Todas
las sesiones de la misma cuenta en ese proceso comparten buckets/topes: desconectar no
rellena el bucket. Conexiones/suscripciones/targets se retiran al cierre, revocación,
expiración, pérdida de acceso o fallo del watchdog. Los buckets conservan su deuda hasta
la expiración por inactividad; el sweep también libera cuentas cuando no llega tráfico.
El broker simple sigue siendo local; **no es un broker distribuido**. Solo las garantías
T02/T04 en PostgreSQL y el lock/estado/ownership T03 son compartidas entre procesos.
Varias instancias requerirían broker y presupuesto compartidos antes de afirmar un límite
global. Se conserva el watchdog de validación T03 con cierre en plazo de 5 s.

## Verificación y repetición

Arneses versionados: `InputBudgetsTest`, `TransportInputFilterTest`,
`WhiteboardConnectionLimitsTest`, `TransportLimitsDbTest` y regresiones T01–T06.
Comprueban bordes exactos/primer exceso, profundidad/strings/colecciones, claves/valores,
longitud efectiva sin Content-Length, multipart conservado, esquema, finitud,
identidades duplicadas, ausencia de escritura y broadcast, cuenta compartida, conexiones,
suscripciones repetidas, unsubscribe, revocación y limpieza. Las fixtures antiguas con
texto vacío/estado `[1]` se sustituyen por objetos ficticios válidos sin debilitar la
prueba de autoría/visibilidad original.

Ensayo acotado reproducible:
`TransportLimitsDbTest#noisyAccountTwoSessionsDoesNotBlockPeerOrTeacherAndCanRecover`.
PostgreSQL 18 local, schema aleatorio desechable; **4 clientes STOMP** (dos conexiones
del alumno A, alumno B y docente), **8 SEND: 7 aceptados y 1 rechazado**, incluyendo
espera de recarga del bucket y continuidad de los otros actores. Configuración de ensayo
draw burst 4 / recarga 1/s; resultados/duración impresos por el test. No es carga sostenida,
benchmark, certificación de capacidad, medición de latencia P95 ni cierre de T10.

Comando de build backend: Maven 3.9 / JDK 17, `package`, perfil `test`, propiedades
`spring.datasource.url=jdbc:postgresql://127.0.0.1:PUERTO/chemicallab_t01_test`, usuario
y contraseña **ficticios** de una base desechable. Los guardas de los arneses rechazan
otras bases. T01 también exige contraseña no vacía en sus pruebas de arranque prod:
configurar el arnés correctamente, sin debilitar el guarda. No copiar parámetros de
una base escolar. Para frontend: dependencias del lock sin cambios, `ng test --watch=false`,
build production/colegio; activar las dos pruebas T03 con `T03_LIVE_FIXTURE` emitida
por `WhiteboardBrowserFixture` en loopback 18087.

Resultados finales sobre la implementación entregada:

| Verificación | Resultado |
|---|---|
| Backend `package`, JDK 17 / Maven 3.9, PostgreSQL local desechable | **545 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS**, JAR reempaquetado; 7 min 05 s |
| Bordes/HTTP/STOMP/registro nuevos | 5 InputBudgets + 2 TransportInputFilter + 1 WhiteboardConnectionLimits + 5 TransportLimitsDb, aprobadas dentro de las 545; las 2 reproducciones se integran en servicios |
| Ensayo de cuenta ruidosa con 4 clientes | **3015 ms**, 8 SEND, 7 aceptados / 1 rechazado, continuidad de alumno B/docente y recuperación del alumno A |
| Angular con `T03_LIVE_FIXTURE` real | **154 pruebas, 0 fallos, 0 omitidas**, 11 archivos; incluye 2 pruebas optativas STOMP/PostgreSQL T03 y 18 pruebas nuevas de transporte/recuperación |
| Builds Angular production / colegio | Ambos aprobados sobre configuración original; avisos previos SCSS/budgets/polyfills, sin errores |
| Navegador Selenium / Edge 155.0.4283.45, dos alumnos y docente | Fuente 401 rechazada con banner claro; gesto posterior válido visible; otro alumno continúa; docente/alumno reciben solo objetos aceptados; pendientes regresan a cero; recarga mantiene los objetos aceptados |
| Preservación | Auditoría/documentos anteriores, HEAD/rama institucional y hashes de los 16 archivos e2e-selenium comprobados sin cambios |

El recorrido de navegador usa las cuentas ficticias del arnés T03 y dispara los eventos
mediante el componente real en build development; observa el rechazo STOMP real,
reconciliación, DOM visible y recarga. No es una campaña de interacción manual completa.
Se revisaron screenshots locales del banner y lienzo recuperado. La configuración
temporal de URL del arnés se restauró antes de publicar. Logs, screenshots, fichero de
fixture y credenciales ficticias no se versionan. Se corrigieron intentos fallidos del
arnés/arranque antes de contar los resultados aprobados.

La PR frontend coordinada usa la misma rama y documenta su recuperación en
`docs/seguridad/02-07a-limites-entradas-transporte.md`; las PRs se enlazan entre sí
en sus descripciones para revisión conjunta. No hay merge ni despliegue.

## T07-B / T10 pendientes

T07-B: paginación, agregados SQL, cuotas de almacenamiento y retención de telemetría,
trazas, lienzos y ownership/tombstones. El número de tombstones históricos no se limita
con el tope de objetos visibles; su consulta/crecimiento persistente permanece pendiente.
No se modifica ni se elimina historial para ocultar ese riesgo. T10: carga sostenida y
campaña de seguridad acordada, clientes lentos/colas saturadas, latencias y dimensionamiento
productivo con infraestructura/proxy reales. Timeout de conexión HTTP no equivale a
un deadline absoluto de procesamiento ni al timeout de un proxy de producción.

Fuentes primarias para los límites del stack:
[Spring STOMP performance](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/configuration-performance.html),
[WebSocketTransportRegistration](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/socket/config/annotation/WebSocketTransportRegistration.html),
[Jackson StreamReadConstraints](https://www.javadoc.io/static/com.fasterxml.jackson.core/jackson-core/2.21.5/com/fasterxml/jackson/core/StreamReadConstraints.html).
El comportamiento efectivo se verifica además sobre las dependencias resueltas del proyecto.
