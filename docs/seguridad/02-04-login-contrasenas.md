# T04 — Login y contraseñas

Implementación del 11 de octubre de 2026 (America/Lima), para revisión, sin merge
ni despliegue. Alcance: SEC-07, SEC-08, SEC-09; conserva T01–T03. No incluye
instituciones, invitaciones, privacidad ni T05. Bases `origin/develop` comprobadas:
backend `0cc132f1b80603a8d883f9056095d472f7e7575e`, frontend
`49cd664884953a1829b45ab3b7be75bea91c10cf`. Rama en ambos repositorios:
`security/t04-login-contrasenas`, en checkouts aislados.

La auditoría original se leyó sin modificarla (SHA-256
`1eff5d59f7a114dfee91cb557a0405c2f7247e43cd2926d2542172c0255e499b`). Se preservan
la rama frontend `feature/validaciones-datos-institucionales`, sus archivos previos
y `e2e-selenium/`. No se usaron credenciales ni datos escolares.

## Reproducción y contrato público

Antes de modificar producción se ejecutaron tres pruebas sobre la base T03:
la comparación de respuestas de login falló por mensajes distintos, la validación
aceptó seis caracteres y BCrypt lanzó `IllegalArgumentException` al recibir más
de 72 bytes UTF-8. Resultado esperado: dos fallos y un error, conservados en el log
local `t04-baseline.log`. La revisión del código confirmó que no había contadores
ni limitación de intentos. Las pruebas correspondientes pasan con T04.

Para credenciales formalmente válidas, identidad ausente, contraseña incorrecta,
identidad modificada concurrentemente o cuenta inactiva reciben exactamente:

```json
{"status":401,"code":"LOGIN_INVALID","message":"Usuario o contraseña incorrectos."}
```

No se incluyen rol, estado, existencia, motivo interno ni marcas temporales. La
auditoría administrativa restringida conserva códigos internos de motivo y el
identificador intentado, nunca contraseña, hash ni token. No se añaden sleeps.

Cada comprobación ejecuta una verificación Argon2id y otra BCrypt: una utiliza el
hash real y la otra uno ficticio generado al iniciar. En cuentas inexistentes se
usan ambos ficticios; cuentas inactivas también comprueban su hash. El coste BCrypt
ficticio es 10, el usado por el encoder anterior. Otros costes heredados, consultas,
bloqueos de fila, JIT/GC y auditoría todavía pueden variar; no se promete tiempo
constante absoluto. El éxito puede incluir migración y emisión de sesión.

Los accesos por usuario/correo conservan comparación exacta y la precedencia
usuario→correo existente. Se recortan espacios del **identificador**, nunca de la
contraseña. Login admite entradas antiguas cortas; su cota de transporte es 1024
unidades UTF-16, sin exigir la nueva política. Entradas >72 bytes contra BCrypt
se rechazan de forma genérica, ejecutando trabajo ficticio, sin excepción ni
truncamiento. Una contraseña que el encoder BCrypt anterior podía guardar sigue
siendo verificable.

## Política única y compatibilidad

`PasswordPolicy` valida altas heredadas, cambios, resets, bootstrap y generación
temporal; `@NewPassword` usa esa misma política en DTO y el encoder la exige antes
de cualquier escritura nueva, incluidas llamadas internas y demos opt-in.

- De 15 a 128 **puntos de código Unicode**, como `String.codePointCount` / `Array.from`;
  máximo 512 bytes UTF-8. Un emoji suplementario cuenta como uno; una letra más
  acento combinante cuenta como dos. Se rechazan sustitutos UTF-16 aislados.
- Se permiten espacios, frases y Unicode. No hay reglas de mezcla de mayúsculas,
  números o símbolos, ni expiración periódica nueva. No se recorta, normaliza ni
  trunca el secreto. NFC y NFD siguen siendo contraseñas distintas.
- Lista local estática de 10 000 entradas de SecLists (MIT, licencia y huella
  junto al recurso), más frases contextuales/españolas y patrones repetidos de
  hasta cuatro puntos de código. La comparación de bloqueo ignora mayúsculas y
  espacios externos; el hash siempre usa la entrada exacta. No se envía ninguna
  credencial a terceros. Esta lista no cubre todas las contraseñas comprometidas.
- Temporales: 20 caracteres aleatorios con `SecureRandom`, alfabeto de símbolos
  legibles y validación por la misma política. Las contraseñas demo públicas se
  actualizan solo para altas nuevas en dev/test explícitamente habilitados.

Nuevos hashes: **Argon2id v19, m=19456 KiB (19 MiB), t=2, p=1**, sal de 16 bytes
y resultado de 32 bytes, mediante Spring Security y Bouncy Castle 1.86. La columna
existente `password varchar(255)` tiene capacidad suficiente; no hay cambio de tipo.

BCrypt sin prefijo `{id}` sigue leyéndose (`$2a$`, `$2b$`, `$2y$`). Tras login
correcto se recalcula Argon2id bajo el mismo bloqueo de fila T02, sobre el hash
recién refrescado. `upgradePasswordHash` comprueba el valor esperado y cambia solo
la representación: conserva `credentialsVersion` y todas las sesiones previas.
La migración no impone la nueva política a un secreto heredado. El reset/cambio
real conserva su invalidación; si confirma antes de un login en espera, este lee
el nuevo hash y no puede sobrescribirlo. No se recupera texto plano de hashes.

Fuentes oficiales consultadas el 11 de octubre de 2026:

- [NIST SP 800-63B-4, contraseñas](https://pages.nist.gov/800-63-4/sp800-63b.html):
  mínimo 15 para un único factor, admitir al menos 64, contar puntos de código y
  bloquear contraseñas comunes. Elegimos 128; por requisito explícito del proyecto
  conservamos Unicode exacto en lugar de aplicar normalización recomendada.
- [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html):
  Argon2id y parámetros mínimos usados aquí; BCrypt queda para compatibilidad.
- [Spring Security: password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html):
  función adaptativa y dependencia Bouncy Castle. La calibración sobre hardware
  productivo queda para una ventana autorizada, no se infiere del equipo local.
- [Bouncy Castle Java](https://www.bouncycastle.org/download/bouncy-castle-java/):
  versión estable 1.86 incorporada como dependencia explícita.

## Límites compartidos

PostgreSQL existente evita añadir Redis u otra infraestructura. Todos los
contadores viven en `authentication_limits(bucket varchar(100) PK, attempts int,
expires_at bigint)` con índice de caducidad. El esquema es aditivo vía Hibernate
`ddl-auto=update`; para migración controlada/`validate` se incluye
[`sql/04-authentication-limits.sql`](sql/04-authentication-limits.sql).

| Ámbito | Umbral | Ventana |
|---|---:|---:|
| Global, todas las rutas cubiertas | 20 | 1 segundo |
| Global, todas las rutas cubiertas | 300 | 60 segundos |
| Origen efectivo compartido | 300 | 15 minutos |
| Identificador de login, incluso inexistente | 10 | 15 minutos |
| ID de cuenta resuelto, compartido por usuario y correo | 10 | 15 minutos |
| Cambio propio, ID de cuenta | 5 | 15 minutos |
| Reset, ID de cuenta destino entre rutas admin/docente | 5 | 15 minutos |
| Actor autenticado, suma de rutas cubiertas | 60 | 15 minutos |

Se cuentan **todos los intentos**, incluidos éxitos; el éxito no borra contadores.
Es una decisión conservadora que evita borrar intentos concurrentes o reiniciar
presupuestos alternando credenciales válidas. Ventanas desde el primer intento
admitido; un rechazo no extiende la caducidad del contador saturado. La recuperación
es automática al vencer; no se desactiva la cuenta y no hay bloqueo permanente.
Un ataque sostenido sí puede consumir ventanas sucesivas: el límite de cuenta no
elimina ese riesgo de denegación. Los umbrales son constantes versionadas comunes
a todas las instancias; revisar capacidad/NAT y cambiar coordinadamente si procede.

Cobertura: login, cambio propio normal/temporal, tres rutas de reset existentes y
altas con trabajo criptográfico. El interceptor MVC consume global/origen/actor
antes de deserializar/validar el cuerpo. Los controladores consumen identificador,
cuenta o destino antes de entrar a la transacción del servicio. El actor y origen
siguen limitando resets inválidos/no autorizados; un docente ajeno no consume el
contador del alumno destino. Las llamadas Java internas no son endpoints y deben
mantener la misma fachada HTTP para acceso externo.

Cada consumo usa una transacción independiente, un bloqueo asesor PostgreSQL
`14004001` y `INSERT ... ON CONFLICT`. El bloqueo serializa solo el breve trabajo
de contadores; no comprende hash ni bloqueo de cuentas. Los contadores se confirman
antes de lanzar 429 y sobreviven al rollback del intento. No hay fallback en RAM.
El reloj de producción es `clock_timestamp()` de PostgreSQL, común a instancias.

Las claves tienen prefijo de ámbito + SHA-256 del identificador/IP/ID, sin texto
plano ni credenciales. Son seudónimos susceptibles de adivinación de diccionario,
no anonimización. Global se consume primero y frena creación masiva de claves.
Límite duro de **50 000 filas** bajo el mismo bloqueo; si se alcanza se rechaza sin
insertar. Cada consumo elimina hasta 256 vencidas; cada minuto un job borra hasta
5000 más. Sin tráfico, una tabla completamente vencida se vacía en hasta diez
ejecuciones normales. Si la limpieza falla, no crece más allá de la cota; reparar
almacenamiento/limpieza, sin borrar cuentas ni sesiones. No esperar a la limpieza
para aplicar expiración.

Al exceder: **429 AUTH_RATE_LIMITED**, mensaje genérico, `Retry-After` entero en
segundos y `retryAfterSeconds` en cuerpo. Indica la ventana del primer control
saturado; al reintentar otro ámbito aún puede limitar. No expone cuál se saturó.
Ante indisponibilidad, timeout o capacidad agotada: **503 AUTH_LIMITS_UNAVAILABLE**,
`Retry-After: 30`, sin comprobar contraseñas ni crear/modificar cuentas/sesiones.
Se rechaza el intento; no se ofrece acceso sin límites. `statement_timeout=1500ms`,
`lock_timeout=1000ms`, timeout transaccional 3 s. El tiempo de adquirir una conexión
depende adicionalmente del pool/driver; no se promete respuesta en tres segundos.

## Origen y proxies

`server.forward-headers-strategy=none` es obligatorio y se valida antes de crear
beans. No habilitar `native`, `framework`, filtros de reescritura ni una válvula
externa que altere el peer antes de este control. `APP_AUTH_TRUSTED_PROXIES` es una
lista explícita de IP/CIDR literal, separada por comas, **vacía por defecto**.
No incluye loopback ni rangos privados automáticamente.

Sin proxy confiable se usa `remoteAddr` y se ignoran X-Forwarded-For y Forwarded.
Con peer confiable se recorre XFF de derecha a izquierda retirando solo saltos
confiables y se elige el primer origen no confiable. Forwarded sigue ignorándose.
Solo IP literales (IPv4/IPv6), sin DNS; máximo 20 saltos/2048 caracteres. Una cadena
inválida usa el peer. El proxy debe anexar la IP real o sanear la cabecera y el
firewall debe impedir acceso directo que suplante ese peer. Los rangos concretos
de Render/Nginx no se inventaron ni verificaron en esta tarea.

## Sesiones, frontend y orden operativo

Se conservan `400 CURRENT_PASSWORD_INVALID` y la sesión si la contraseña actual
es incorrecta. Nueva contraseña inválida o 429: sin cambios de hash, cuenta,
versión o sesiones. Cambio correcto: revoca otras sesiones y entrega JWT nuevo;
reset: revoca y obliga al cambio temporal, sin emitir sesión. Una sesión inválida
continúa recibiendo 401 y limpiándose en Angular. Se conservan T03 y sus cierres
de sockets por revocación; migrar solo el formato de hash no provoca cierres.

Angular valida puntos de código/bytes, muestra la política y los mensajes de
rechazo del servidor. El servidor es la autoridad para la lista de contraseñas
comunes. Login y cambio muestran cuenta atrás; altas/resets comunican la espera.
El interceptor limita reintentos de esas rutas durante la espera, reconoce segundos
y fechas HTTP y conserva token/estado. No bloquea logout ni consultas de sesión.
`Retry-After` queda expuesto por CORS; 429 y 503 de límites no causan logout.

Antes de producción (pendiente, sin autorización de ejecución en esta tarea):

1. Preparar backup/restauración y esquema aditivo en entorno autorizado; revisar
   permisos de tabla/bloqueos y conectividad común a **todas** las instancias.
2. Verificar rangos de proxy reales, saneamiento XFF, peer original, firewall y
   CORS; validar un flujo desde alumnos que comparten NAT.
3. Medir latencia/memoria de Argon2id y ocupación del pool bajo concurrencia
   representativa; revisar umbrales y alertas de 429/503/capacidad/limpieza.
4. Actualizar coordinadamente backend/frontend; evitar coexistencia de backend
   antiguo que no pueda leer Argon2id. El rollback debe conservar lectura dual:
   **no volver a BCrypt-only tras el primer hash Argon2id**.
5. Mantener y revisar la lista local mediante artefactos versionados. No existe
   rotación masiva ni reset forzado automático por T04; las cuentas heredadas
   migran al autenticar o al cambiar/restablecer contraseña.

## Evidencia y límites de las pruebas

Entorno local: Java 17, PostgreSQL 18 desechable nuevo en `127.0.0.1:55474`, usuario
ficticio `t04_test`, base ficticia `chemicallab_t01_test` (nombre requerido por las
guardas T01/T02/T03). T04 crea/elimina su esquema aleatorio y abre dos contextos
Spring independientes con dos listeners HTTP y dos pools compartiendo ese esquema.
Son dos instancias en una JVM, no una certificación de dos hosts o de un proxy real.

Pruebas nuevas: `LoginHardeningTest`, `PasswordPolicyTest`, `ClientOriginResolverTest`,
`LoginProtectionDbTest`, y dos casos de arranque que rechazan reescritura automática
de cabeceras. Cubren matriz usuario/correo ausente/activo/inactivo, cuentas temporales,
HTTP 429/503, diez admisiones exactas entre 40 competidores compartidos, carrera de
doce logins entre ambas instancias, expiración con reloj controlado del algoritmo,
recuperación persistida, éxitos sin reinicio de presupuesto, treinta alumnos con
una IP, cabeceras falsas/cadenas confiables/IPv6, límites global/origen/actor/cuenta,
cota de 50 000 filas, limpieza, lista local, Unicode/bytes, hashes BCrypt cortos y
largos, migración sin revocación y reset concurrente que gana el bloqueo.

Los flujos HTTP comprueban rechazos sin mutación y restablecimiento por ambas
familias; la suite T01 comprueba bootstrap/demos y T02/T03 cambio/reset/revocación
HTTP y cierre de clientes STOMP pasivos. Los tests de formularios Angular usan
DOM de componentes reales y HTTP controlado; no equivalen a un navegador completo.

Resultados del 11 de octubre de 2026:

- Backend: `clean package`, **476 pruebas, 0 fallos, 0 errores, 0 omitidas**;
  JAR ejecutable generado. Incluye las regresiones T01/T02/T03. Se adaptaron dos
  fixtures heredadas: una contraseña de rollback cumple la nueva política y las
  pruebas de destinos STOMP reutilizan un login para sus conexiones independientes.
  No se deshabilitó la protección para hacerlas pasar.
- Frontend: **113 pruebas aprobadas, 0 omitidas** con `T03_LIVE_FIXTURE` apuntando
  a otra fixture desechable del backend. Incluye login/cambio/reset con 429,
  cuenta atrás, reintento, conservación/renovación de sesión, Unicode y transporte
  real de pizarra. `npm run build` aprobó producción; permanecen avisos de tamaño
  de bundles/SCSS y del entorno de pruebas (`vi.hoisted`/`vi.mock`, polyfills).
- Concurrencia HTTP: diez rechazos genéricos 401 y dos 429 entre doce logins
  simultáneos alternando usuario/correo entre dos listeners. El test atómico
  admite exactamente diez de cuarenta competidores con dos pools.
- Navegador real sobre localhost y la fixture desechable: login correcto hasta
  panel de estudiante, política visible en cambio, logout y 429 real en login
  con cuenta atrás y botón deshabilitado. El cambio completo y la expiración de
  espera se verificaron en pruebas automatizadas, no esperando 15 minutos en UI.
  El proxy local usa `/api/**` y origen `http://localhost:4200`, ya permitido por
  la fixture; el rechazo CORS inicial en otro puerto no requirió cambios de producto.
- Muestra temporal acotada de ocho rechazos por categoría, milisegundos:

| Categoría | Mínimo | Mediana | Máximo |
|---|---:|---:|---:|
| Identidad ausente | 122,1 | 153,7 | 201,4 |
| Activa, contraseña incorrecta | 125,1 | 148,5 | 176,7 |
| Cuenta inactiva | 130,2 | 146,5 | 174,7 |

Las distribuciones se solapan en esta muestra local; no prueba indistinguibilidad
estadística, tiempo constante ni capacidad productiva.

Reproducción desde el checkout backend, con una instancia PostgreSQL **nueva y
desechable** en loopback y la base ficticia ya creada (PowerShell):

```powershell
.\mvnw.cmd clean package '-Dspring.profiles.active=test' `
  '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55474/chemicallab_t01_test' `
  '-Dspring.datasource.username=t04_test' '-Dspring.datasource.password=fictitious'
```

Las guardas de las suites exigen perfil test, loopback y nombre de base test;
usan esquemas aleatorios y los eliminan al finalizar. Para los dos tests Angular
de transporte real, iniciar `WhiteboardBrowserFixture` con `-Dtest` y las mismas
propiedades de datasource, más `-Dt03.fixture-file=<ruta temporal JSON>` y
`-Dt03.stop-file=<ruta temporal señal>`. En el checkout frontend:

```powershell
$env:T03_LIVE_FIXTURE = Get-Content -LiteralPath '<ruta temporal JSON>' -Raw
npm.cmd test -- --watch=false
npm.cmd run build
```

Crear el archivo señal termina la fixture y elimina su esquema; también termina
automáticamente a los 30 minutos. Los JSON, logs, tokens, directorio de PostgreSQL
y configuraciones locales no forman parte de los commits. No se hicieron pruebas
sobre datos reales, carga sostenida, producción, despliegues o rotaciones.
