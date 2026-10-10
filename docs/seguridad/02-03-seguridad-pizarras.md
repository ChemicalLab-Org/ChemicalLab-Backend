# T03 — Seguridad de pizarras

Estado: T03 implementada y verificada localmente, lista para revisión; sin merge.
Alcance exclusivo: SEC-06, SEC-15 y vigencia WebSocket pendiente de SEC-03–SEC-05.
Verificaciones locales: 8–10 de octubre de 2026, datos ficticios.
Base backend: `3c4e48a19deb0db69ef310fd8c960303835dc6a4`.
Base frontend: `baf39da6afd91281edc37307d6beb986ae1d7385`.
PR frontend dependiente: [ChemicalLab-Frontend #79](https://github.com/ChemicalLab-Org/ChemicalLab-Frontend/pull/79).

## Matriz y diseño previo a la implementación

| Acción | Docente propietario | Alumno del grado/sección, unido mediante join | Administrador/ajeno |
|---|---|---|---|
| Observar ACTIVE/PAUSED | Sí | Sí, incluso sin interacción | No |
| Dibujar ACTIVE | Todos los objetos | Solo objetos propios y con permiso efectivo | No |
| Dibujar PAUSED/CLOSED | No | No | No |
| Presence ACTIVE/PAUSED | Latido válido sin participante alumno | Actualiza su propia presencia | No |
| Limpiar lienzo ACTIVE | Sí | No | No |
| Cola de errores | Solo conexión originadora | Solo conexión originadora | Sin acceso a otras conexiones |
| Supervisión REST administrativa | Contratos existentes | Contratos existentes | Se conserva; no habilita realtime |

Se conserva exactamente el modelo actual de grado/sección; no se ofrece aislamiento
institucional. Cada SEND/SUBSCRIBE requiere CONNECT autenticado y comprobaciones
actuales en PostgreSQL. Se deniegan destinos y comandos no enumerados, publicaciones
directas al broker y suscripciones internas, ajenas o con comodines.

T02 sigue siendo la única política de vigencia de cuenta/sesión. Se añade revalidación
del principal sin guardar el JWT. La entrega a cada destinatario vuelve a comprobar
sesión y acceso. Un registro local de sockets permite el cierre físico y limpieza;
una vigilancia periódica consulta PostgreSQL aunque el cliente permanezca pasivo.
El cierre no depende de eventos locales de revocación. Los errores de validación
bloquean tráfico; el límite de cierre y la evidencia se detallan más abajo.

La autoría se guarda por pizarra, tipo e ID, con tombstones tras borrado. Las escrituras
se serializan por pizarra mediante bloqueo PostgreSQL; estado y autoría se confirman
en la misma transacción. La difusión ocurre después del commit. El docente puede
gestionar objetos ajenos sin apropiarse de su autoría. El borrador libre permanece
como trazo de borrado; no concede permisos para borrar otros objetos por ID.

El estado JSON pasa a ser una proyección mantenida por eventos verificados del
servidor. El antiguo PUT de snapshot no reemplazará datos verificados. Los objetos
anteriores sin autoría demostrable se conservan visibles y quedan gestionables solo
por el docente, sin atribuirlos a alumnos por nombres o prefijos.

No se promete colaboración distribuida entre brokers simples independientes ni
cancelación de mensajes que ya pasaron el último punto de validación.

## Contrato STOMP y vigencia

El endpoint SockJS sigue siendo `/ws`. Se exige `CONNECT` con `Authorization: Bearer …`
validado por `AccountSessionService.authenticate`; el token se retira de las cabeceras
del mensaje y no se conserva en el registro de conexiones. Una contraseña temporal
no permite abrir la pizarra. El principal procede de T02, nunca del cuerpo o de
cabeceras de actor enviadas por Angular.

La lista permitida es exacta, con ID decimal positivo sin ceros iniciales:

| Comando | Destino | Comprobación |
|---|---|---|
| SUBSCRIBE | `/topic/whiteboards/{id}` | Sesión T02 vigente y observación de esa pizarra |
| SUBSCRIBE | `/user/queue/whiteboard-errors` | Sesión vigente, ID de suscripción propio |
| SEND | `/app/whiteboards/{id}/draw` | Sesión/observación actuales; luego permiso de dibujo y autoría |
| SEND | `/app/whiteboards/{id}/presence` | Sesión/observación actuales |
| UNSUBSCRIBE | Sin destino | Solo un ID registrado en esa conexión |
| Heartbeat | Sin destino | Conexión autenticada y sesión vigente |
| DISCONNECT | Sin destino | Permitido para cierre y limpieza, incluido el generado por Spring |

Todo otro comando/destino se deniega: no hay SEND a `/topic`, `/queue` o `/user`,
suscripciones a colas internas o ajenas, comodines, transacciones STOMP ni destinos
desconocidos. El administrador mantiene sus contratos REST existentes; no obtiene
acceso al topic. La cola privada usa `@SendToUser(broadcast=false)` y la entrega
comprueba el ID de conexión y su suscripción, incluso con dos logins del mismo usuario.

`presence` del docente propietario es un latido válido sin crear un participante
alumno. El alumno actualiza únicamente su presencia registrada. No implica permiso
de dibujo. PAUSED mantiene observación/presencia; CLOSED retira acceso realtime.

Puntos de validación:

1. Entrada al canal: cada SEND/SUBSCRIBE vuelve a consultar T02 y el ámbito actual.
2. Ejecución del handler de draw/presence: se repite la validación tras la espera en
   cola. El servicio de dibujo comprueba estado, grupo, participación, permiso efectivo
   y autoría bajo bloqueo de la pizarra.
3. Salida: `ExecutorChannelInterceptor.beforeHandle` valida cada MESSAGE por
   destinatario, después de encolarlo y antes de pasarlo al transporte. Se consulta
   PostgreSQL de nuevo; una conexión pasiva no puede recibir un nuevo marcador tras
   una revocación que ya era visible al efectuar esas lecturas. Una comprobación final
   del registro impide entregar si el watchdog cerró durante la lectura.
4. Vigilancia independiente aunque no haya mensajes: consulta T02 y las pizarras
   usadas por cada conexión. Se conserva el conjunto de destinos hasta desconectar,
   incluso si se desuscribe; perder cualquiera de esos ámbitos cierra la conexión.

`AccountSessionService.revalidate` reutiliza el mismo `validate` persistente de T02
(cuenta activa, expiración, revocación individual y versión de credenciales), con
transacción nueva para evitar entidades cacheadas. Comprueba también identidad, rol
y contraseña temporal. `WhiteboardRealtimeAccess` lee grupo/participación/propietario
y estado actuales en una transacción nueva. No hay una caché de permisos ni una
segunda política de revocación.

### Plazo de cierre y límites temporales

Hay cuatro validadores, cola acotada de 256 tareas, consulta periódica por conexión
cada 1 s y watchdog independiente cada 250 ms. El registro vence a los 5 s desde el
**inicio** de la última validación completa satisfactoria, no desde su finalización.
Las lecturas tienen timeout transaccional de 2 s. Una cola saturada o una validación
fallida cierran de forma conservadora; la espera de un pool/driver no renueva el plazo.
Sin CONNECT también vence el registro.

El plazo operativo máximo para ordenar el cierre de una conexión inactiva es
**5,25 s** desde la última validación satisfactoria (o desde la revocación si fue
posterior), con el watchdog y la JVM ejecutándose normalmente. Con BD disponible se
detecta normalmente en el siguiente sondeo. Antes de llamar a `socket.close` se
elimina el registro: desde entonces no se autoriza tráfico nuevo aunque el cierre
físico tarde. No se promete un plazo de tiempo real bajo pausa de JVM, suspensión
del equipo, transporte atascado o partición de red: el cliente puede observar el
cierre más tarde. Las pruebas locales comprueban cierre físico por expiración antes
de expiración + 5,25 s, y con tabla de sesiones bloqueada antes de 5,5 s (tolerancia
de medición de 250 ms), sin recibir el marcador protegido.

Desconectar elimina suscripciones/destinos y cancela/purga la tarea pendiente. Una
consulta JDBC ya en curso puede terminar bajo sus propios límites; no se interrumpe
el validador que está enviando su propio cierre SockJS. No queda un temporizador
individual vivo por conexión. Al cerrar la aplicación se apagan ambos ejecutores.

Una revocación puede confirmarse después de la última lectura de autorización de un
mensaje. Ese mensaje ya en tránsito no se puede retirar; tampoco se cancela una
operación de dibujo que ya cruzó la validación y está en curso. El control no es una
transacción distribuida entre cuenta, broker y socket. El bloqueo de tráfico nuevo
y el cierre físico son garantías distintas.

| Cierre | Significado frontend |
|---|---|
| 4001 `SESSION_INVALID` | Detener reconexión; limpiar la sesión solo si sigue siendo el token de ese intento; ir a login |
| 4003 `BOARD_ACCESS_LOST` / `PROTOCOL_REJECTED` | Detener conexión a pizarra y avisar; conservar sesión de plataforma |
| 4503 `VALIDATION_UNAVAILABLE` | Sin validación no hay tráfico; tratar como fallo transitorio y reintentar |

Un rechazo ordinario de dibujo devuelve a la conexión originadora
`{code: "DRAW_REJECTED", resync: true, error: "…"}`. No revoca la sesión ni desconecta.
Si llega un ERROR STOMP sin código útil, Angular verifica `/api/auth/me`: un 401
detiene la sesión correspondiente; una indisponibilidad no la borra. La prueba de
navegador usó los códigos SockJS sobre WebSocket; no se certifican todos los proxies.

## Autoría, estado y esquema aditivo

`whiteboard_object_ownership` conserva una identidad única
`(board_id, object_kind, object_id)`, autor `owner_user_id` nullable y marca `deleted`.
Los tipos son TEXT, SHAPE y STROKE. La misma cadena de ID en otro tipo/pizarra es otra
identidad. Un ID nuevo adopta el usuario del principal; un ID existente conserva su
autor, incluso si lo modifica el docente. Upsert, borrado y restauración consultan
esta tabla. No se deduce autoría de prefijos, nombres visibles, historial de Angular
ni campos `ownerUserId`/`actorRole` del cliente.

Los borrados y CLEAR conservan las filas como tombstones: otro alumno no puede
recrear un ID borrado. El alumno puede deshacer/rehacer sus objetos; el docente
propietario gestiona todos y es el único que puede limpiar el lienzo. ERASE sigue
siendo un trazo de borrado libre, con su propia autoría; no autoriza TEXT_DELETE,
SHAPE_DELETE o STROKE_DELETE ajenos. Angular no ofrece selección/borrado de objetos
ajenos por identidad; el servidor es quien impone la regla.

Cada mutación toma `PESSIMISTIC_WRITE` sobre la fila de pizarra y refresca su estado.
Se actualizan autoría, proyección JSON y `whiteboard_sessions.state_revision` en la
misma transacción. Los cambios de control docente toman el mismo bloqueo. La
restricción única de identidad protege también frente a escritores concurrentes
que comparten PostgreSQL. Un rechazo revierte incluso una importación legacy
provisional. La difusión se registra en `afterCommit`; rollback no anuncia nada.

El archivo [`sql/03-whiteboard-ownership.sql`](sql/03-whiteboard-ownership.sql) describe
los cambios aditivos: columna bigint no nula con valor inicial 0 y nueva tabla con
claves foráneas/índice único. No borra ni reescribe pizarras/históricos. En el entorno
desechable se creó mediante el mecanismo Hibernate `ddl-auto=update` ya utilizado
por el proyecto. El SQL queda para el procedimiento de migración autorizado por el
operador; **no se aplicó a la base escolar**. No se deben truncar tombstones mientras
se conserven las pizarras. Cada evento reescribe la proyección JSON (límite 2 MB) y
consulta sus identidades; falta medir capacidad antes de aumentar la carga.

### Datos anteriores a T03

Los objetos de snapshots anteriores se conservan visibles. La respuesta GET de
revisión 0 elimina la autoría declarada por el cliente; la primera mutación válida
importa sus identidades bajo el bloqueo, con autor NULL. Solo el docente puede
modificarlos/borrarlos/restaurarlos. Los trazos sin ID reciben uno `legacy-UUID`
al importar, sin atribuirlos a un alumno. JSON histórico no interpretable se conserva
sin cambios y sus modificaciones fallan de forma conservadora; su reparación manual
queda fuera de T03. Las capturas finales históricas se conservan.

El antiguo PUT `/api/whiteboards/teacher/{id}/state` ya no sustituye la proyección:
solo admite un no-op idéntico al JSON persistido; todo reemplazo devuelve 400. Los
clientes T03 dejaron de guardar snapshots arbitrarios. La captura final de imagen
sigue su contrato existente y no establece autoría de objetos. Ambas PRs deben
revisarse juntas por este cambio de contrato.

## Reconciliación Angular

Los servicios de docente/alumno comparten la implementación del transporte, con
instancias independientes. `beforeConnect` lee el token vigente en cada intento.
Los eventos incluyen `ownerUserId` y `revision`, y GET state incluye la revisión.
Al conectar/reconectar, recibir un rechazo o detectar un salto de revisión, se
recupera el estado persistido y se ignoran respuestas HTTP antiguas y eventos ya
incluidos en él. Se cancelan herramientas activas y se sustituyen los cambios
optimistas; un rechazo limpia además el historial local de deshacer/rehacer.
Si falla la carga se bloquea el dibujo y se muestra un aviso para reintentar.
Perder permiso de dibujo no impide observar; perder acceso a la pizarra detiene
esa conexión sin cerrar toda la plataforma.

El broker sigue siendo simple y local. Compartir PostgreSQL hace visibles las
revocaciones y serializa estado/autoría, pero **no distribuye eventos** entre brokers
independientes. Un fallo entre commit y difusión puede dejar un cambio persistido
sin notificación; no hay outbox ni entrega exactamente una vez. La reconexión/recarga
y la detección de saltos recuperan el estado, pero no sustituyen un broker compartido.

## Verificación reproducible

Solo PostgreSQL local desechable: `127.0.0.1:55472/chemicallab_t01_test`, usuario de
prueba `t02_test`, contraseña ficticia `fictitious`. El test T03 rechaza otras URLs,
crea un schema aleatorio `t03_<uuid>`, arranca servidores en loopback y lo elimina
al finalizar. JWT/clave aleatorios en memoria; cuentas `t03fixture*` ficticias.
Dos docentes, grupos 3/A y 4/B, dos alumnos unidos a una pizarra, otro alumno ajeno,
y casos adicionales de alumno no unido y administrador.

Desde el backend (Maven 3.9.15, Java 17; `-o` solo si ya están cacheadas las dependencias):

```powershell
mvn -o clean package '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55472/chemicallab_t01_test' '-Dspring.datasource.username=t02_test' '-Dspring.datasource.password=fictitious'
```

Solo transporte T03:

```powershell
mvn -o test '-Dtest=WhiteboardSecurityTransportDbTest' '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55472/chemicallab_t01_test' '-Dspring.datasource.username=t02_test' '-Dspring.datasource.password=fictitious'
```

El cliente de prueba usa `java.net.http.WebSocket` contra
`/ws/000/{uuid}/websocket`, con framing SockJS real y frames STOMP. Observa MESSAGE
y el cierre `c[...]`; no es una invocación aislada del interceptor. Las consultas
SQL comprueban estado y autoría tras los rechazos. Los tests unitarios complementan
esta evidencia, no la sustituyen.

Desde el frontend, sin cambiar el lockfile:

```powershell
npm ci --ignore-scripts --legacy-peer-deps
npm test -- --watch=false
npm run build
```

Para la comprobación visual se usó `WhiteboardBrowserFixture` (opt-in, excluida del
patrón normal `*Test`), pasando las mismas propiedades JDBC y
`-Dt03.fixture-file=<ruta-absoluta.json>` / `-Dt03.stop-file=<ruta-absoluta.stop>`.
Arranca en 18087 y escribe datos de acceso ficticios; crear el archivo stop termina
y elimina el schema (máximo 30 min). Angular se inició en 127.0.0.1:4200 apuntando
temporalmente a ese backend. Se restauró `environment.ts` después. El puerto 8080
estaba ocupado y no se tocó ese servicio. Los archivos de fixture/logs/captura
quedaron fuera de los repositorios.

### Evidencia ejecutada

| Verificación | Resultado |
|---|---|
| Reproducción sobre base T02 aislada, antes de cambios | 2 fallos esperados: receptor recibió marcador SEC-06 por SEND directo al topic; TEXT_DELETE de alumno borró texto del docente (SEC-15) |
| Suite completa backend, ejecución final del 10 de octubre | 456 pruebas, 0 fallos, 0 errores, 0 omitidas; `clean package` aprobado en 3 min 03 s |
| Transporte T03 incluido en esa suite | 18 pruebas con PostgreSQL y servidor real |
| Regresión T01/T02 | Incluida en la suite completa; arranque seguro, persistencia y sesiones |
| Frontend | 67 pruebas en 7 archivos aprobadas; incluye 16 de transporte y 6 de reconciliación docente/alumno |
| Compilación Angular producción | Aprobada; permanecen avisos de presupuesto SCSS existentes |
| Navegador real, 9 de octubre | Login docente/alumno, join, textos colaborativos, protección de texto ajeno, deshacer/rehacer propio, pausa/reanudar, interacción bloqueada con observación, recarga de ambos textos y cierre por docente conservando sesión de plataforma |

Los 18 tests de transporte cubren comandos/destinos prohibidos y sin CONNECT,
CONNECT ausente/malformado/temporal/revocado, propietario/join/grupo/admin,
errores privados entre dos logins, observación sin interacción, overrides y pausa,
operaciones propias/ajenas y colisiones de texto/forma/trazo, borrador libre/CLEAR,
autoría legacy y PUT, identidad falsificada y alcance por tipo/pizarra, logout
individual/global, expiración, cambio de contraseña, reset/desactivación/reactivación,
pérdida de grupo/participación/cierre, revocación HTTP desde segunda instancia,
reinicio completo, carreras de creación/modificación, rollback e indisponibilidad de
validación con limpieza del registro. Se verifica que clientes pasivos revocados
no reciban marcadores nuevos.

Precisión del arnés: logout y cambio de contraseña se ejecutan por HTTP; reset y
desactivación se confirman mediante los setters persistentes de T02 bajo su bloqueo
de cuenta, no mediante sus pantallas. La regresión T02 conserva sus pruebas HTTP de
esos flujos. El rollback se fuerza desde el servicio dentro de una transacción real
y se observa su ausencia por transporte. Las carreras de objetos usan dos conexiones
y dos hilos; la segunda instancia se prueba explícitamente para revocación, no
como un ensayo de colaboración distribuida.

### Intentos fallidos y correcciones

- Primer arnés de SEC-15 omitía flags booleanos del texto y fallaba al deserializar:
  no se usó como evidencia. La repetición con payload válido reprodujo ambos hallazgos.
- Tras el bloqueo de SEC-06, el arnés intentaba el segundo ataque en el socket ya
  cerrado; se separaron las conexiones y se verificaron ambos rechazos.
- Una ejecución ampliada detectó cierre 1001 en vez de 4001 por interrumpir al
  validador durante su propio cierre SockJS. Se corrigió la cancelación y la repetición
  verificó el código y plazo de expiración.
- El primer arranque visual tenía puerto incorrecto/ocupado; se corrigió la opción
  de fixture y se usó 18087. Una fixture agotada durante una pausa no se contó como
  comprobación de navegador. La prueba visual descrita arriba sí se completó.
- Vitest advierte sobre hoisting después de la transformación Angular aunque los
  mocks están en el nivel superior del archivo. También avisa sobre `polyfills.ts`.
  Las 67 pruebas pasan; no se actualizó el toolchain ni las dependencias generales.
- La primera repetición del 10 de octubre no pudo conectar a PostgreSQL tras la
  pausa (422 tests contabilizados, 20 errores de infraestructura, sin llegar a
  ejecutar los casos de transporte). Se reinició exclusivamente el cluster
  desechable local y se repitió la suite; ese intento no acredita funcionalidad.
- Al ampliar la carrera con dos modificaciones legítimas, el arnés esperaba un
  orden fijo entre conexiones distintas y descartaba el segundo marcador si
  llegaba primero. La ejecución dio 455 aprobadas y 1 fallo de esa aserción. Se
  corrigió para exigir ambos marcadores en cualquier orden y los dos objetos
  persistidos con sus coordenadas modificadas; se repitió el caso y la suite.

### Omisiones y pendientes

No se probaron transportes SockJS XHR alternativos, proxies/TLS de producción,
suspensión de JVM/red, carga prolongada ni capacidad con muchas conexiones/objetos.
No hay despliegue manual, migración de la base escolar, merge ni rotación real. La carga
de validación por mensaje y la reescritura de JSON requieren una prueba de capacidad
antes de escalar. Las advertencias/dependencias generales pertenecen a otras etapas.

SEC-06 y SEC-15 quedan corregidos en esta rama con reproducción y regresión local;
los pendientes WebSocket de SEC-03–SEC-05 tienen la evidencia indicada. Esto no
declara cerrada toda la auditoría ni acredita producción. Se conserva la auditoría
original, el frontend previo y su `e2e-selenium/`. T03 queda pendiente de revisión
de las dos PRs dependientes y del procedimiento de integración autorizado.

Referencias de implementación: [interceptores Spring](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/interceptors.html)
y [orden de mensajes Spring](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/ordered-messages.html).
