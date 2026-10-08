# T02 — Autenticación central de cuenta y sesión

## Diseño previo a la implementación (2026-10-07)

Se conserva el modelo de ADMINISTRADOR, DOCENTE y ESTUDIANTE. Cada login crea una
sesión PostgreSQL con UUID aleatorio, cuenta, versión de credenciales, creación,
expiración y revocación. El JWT firmado contiene el UUID y la versión; no se guarda
el JWT ni la contraseña en la tabla de sesiones. Los JWT antiguos se rechazan.

La cuenta mantiene un contador de credenciales. Cambiar contraseña, estado,
identidad de login o privilegios incrementa el contador: ninguna sesión anterior
vuelve a funcionar al reactivar la cuenta. Logout revoca solo el UUID autenticado;
logout global incrementa el contador. El cambio propio incrementa el contador y
crea una sesión nueva en la misma transacción. Reset no entrega una sesión.

Login y todas las escrituras de cuenta adquieren el mismo bloqueo PostgreSQL de
fila, refrescando el estado después de obtenerlo. El cambio propio revalida la
sesión tras bloquear para impedir renovaciones desde una sesión ya revocada.

Un servicio central verifica firma, expiración, sesión persistida, versión y cuenta
activa. REST aplica además una lista exacta de método/ruta para cuentas temporales:
GET /api/auth/me, PATCH /api/auth/change-temporary-password, POST /api/auth/logout,
POST /api/auth/logout-all. Lo demás devuelve 403 PASSWORD_CHANGE_REQUIRED.
Las sesiones inválidas devuelven 401. Se conservan rol y propiedad actuales.
Los nuevos CONNECT STOMP usan la misma validación y rechazan cuentas temporales.
La autorización de destinos y el cierre de conexiones establecidas siguen en T03.

Bases verificadas y actualizadas desde `origin/develop`: backend
`00907333c04d88ea5cb039eac638c5c6ebea714b` (T01 integrada), frontend
`4964165486396050b6240d93f2934a6dbe579c42`. Trabajo en ramas separadas
`security/t02-autenticacion-sesiones`, preservando el checkout previo del frontend
y su carpeta `e2e-selenium/` sin seguimiento.

## Implementación y política

`AccountSessionService.authenticate` es la entrada común de REST y CONNECT: verifica
firma y expiración con `JwtService`, y consulta sesión y cuenta juntas, sin caché de
revocación. Contrasta `jti` (UUID), `cv`, `userId`, `sub` y expiración con PostgreSQL.
Las autoridades provienen de la cuenta actual. El principal contiene identidad de
cuenta y sesión; logout no admite identificadores del cliente. Una base no disponible
no permite autenticar: no existe fallback a JWT sin consulta de sesión.

`UserAccount.credentialsVersion` cambia al modificar contraseña, estado activo,
contraseña temporal, username o rol. Las ediciones de grado/sección/docente responsable
que cambian el acceso del estudiante también invalidan sesiones. Las ediciones
cosméticas no lo hacen. El rol sigue siendo de solo lectura en las APIs actuales;
no se añadió una ruta de cambio de rol. Su setter mantiene la misma invalidación.
Los futuros escritores de cuenta deben usar la transacción y `sessions.lock(user)`
antes de examinar o cambiar credenciales; un SQL externo que omita el contador no
forma parte del contrato y no debe usarse para cambios de seguridad.

| Evento | Efecto persistido |
|---|---|
| Login de cuenta activa | Nueva fila y JWT con UUID único, incluso si ya existen sesiones |
| Cuenta inactiva | Login y JWT anterior rechazados con 401 |
| Logout | `revoked_at` solo en la sesión autenticada |
| Cerrar todas | Incremento del contador de la cuenta, incluida la sesión que llama |
| Cambio propio, temporal o normal | Nuevo hash y contador; sesión nueva emitida en la misma transacción |
| Cambio propio rechazado por contraseña actual incorrecta | Sin cambios en hash, bandera temporal, contador ni filas de sesión; ambas sesiones previas siguen vigentes |
| Reset administrativo o heredado | Nuevo hash temporal y contador; ninguna sesión nueva |
| Desactivar/reactivar | Cambia contador; reactivar nunca recupera tokens anteriores |
| Cambiar privilegios/ámbito | Cambia contador, mantiene las reglas de rol y propiedad |

Todas las rutas de escritura existentes en `AdminService` y `UserManagementService`
se revisaron, incluidas las heredadas `/api/users/teachers/...`, la edición de alumnos
que contiene `active`, y las dos familias generales de activación/desactivación.
Las escrituras refrescan la cuenta bajo `PESSIMISTIC_WRITE`. Login comprueba el hash
solo después de ese bloqueo. Si login confirma primero, un reset posterior invalida
la sesión recién emitida. Si reset/desactivación confirma primero, el login que estaba
esperando lee las nuevas credenciales/estado y rechaza las antiguas. El cambio propio
y logout revalidan sesión y versión después del bloqueo.

El límite es la validación inicial de cada solicitud: una operación REST que ya pasó
ese punto puede terminar mientras se confirma una revocación concurrente. No se
promete cancelar solicitudes ya en ejecución. Las mutaciones propias de sesión y
contraseña sí vuelven a comprobar vigencia bajo bloqueo.

## Contratos API

Se conserva `Authorization: Bearer <token>` y no se introducen cookies ni refresh tokens.

| Método y ruta | Respuesta y política |
|---|---|
| `POST /api/auth/login` | 200 con `AuthResponse` y token nuevo; cuenta inactiva o credenciales inválidas: 401 |
| `GET /api/auth/me` | 200; en modo temporal solo identidad/rol/estado, con email y nombres nulos |
| `PATCH /api/auth/change-temporary-password` | Acepta `currentPassword`, `newPassword`, `confirmPassword`; 200 con `message`, `temporaryPassword: false`, **`token` nuevo y `tokenType: Bearer`**. También sirve para cambio propio no temporal |
| Cambio propio con sesión válida y `currentPassword` incorrecta | **400** con `status: 400`, `code: CURRENT_PASSWORD_INVALID`, `message: La contraseña actual es incorrecta.`; permite corregir el formulario sin cerrar la sesión |
| `POST /api/auth/logout` | Cuerpo vacío o `{}`; 204. Solo revoca la sesión obtenida del contexto |
| `POST /api/auth/logout-all` | Cuerpo vacío o `{}`; 204. Revoca todas las sesiones del usuario del contexto |
| Cualquier ruta protegida con sesión ausente/inválida/expirada/revocada | 401 |
| Sesión temporal válida fuera de los cuatro pares método/ruta permitidos | 403 con `code: PASSWORD_CHANGE_REQUIRED` |

No se permite todo `/api/auth/**`. Métodos distintos, rutas desconocidas, materiales,
uploads, descargas y capturas quedan igualmente restringidos. Health, login y los
handshakes públicos conservan sus reglas para solicitudes sin Bearer; un Bearer
presentado se valida. Los resets heredados conservan `message` y `temporaryPassword`;
los campos de token del DTO compartido son nulos. El reset administrativo conserva
su respuesta de contraseña temporal entregada una sola vez, sin JWT.

El cambio propio primero revalida la sesión bajo bloqueo y después comprueba la
contraseña actual. Solo una discrepancia de contraseña con sesión vigente lanza
`CurrentPasswordInvalidException`, que no es una excepción de autenticación. No se
modificó el mapeo global de `BadCredentialsException` ni `DisabledException`: siguen
devolviendo 401. Una sesión revocada, expirada, inválida o una cuenta desactivada
devuelve 401 también en este endpoint, aunque el formulario tenga una contraseña
incorrecta. Los restantes errores de validación conservan su contrato 400 existente.

## Esquema, caducidad y actualización de una base existente

Cambios **aditivos**:

- `user_accounts.credentials_version bigint NOT NULL DEFAULT 0`.
- `account_sessions`: UUID PK, FK `user_id`, versión, `created_at`, `expires_at`,
  `revoked_at` nullable; índices por usuario y expiración.
- No se eliminan cuentas, perfiles, relaciones ni historial académico.

Se conserva `ddl-auto=update`: Hibernate añade columna/tabla/índices al iniciar una
versión T02. La prueba parte de una tabla de usuarios anterior a T02 ya poblada y
verifica el valor inicial y conservación de la cuenta. Para instalaciones que aplican
DDL por un procedimiento externo, se incluye
[`sql/02-account-sessions.sql`](sql/02-account-sessions.sql), que puede aplicarse antes
del arranque con `ddl-auto=validate`. El operador debe verificar esquema seleccionado,
copia/restauración y permisos en su ventana autorizada; esta tarea solo ejercitó
PostgreSQL ficticio y no ejecutó DDL sobre el despliegue.

La caducidad usa `APP_JWT_EXPIRATION_MS`, 86400000 ms (24 horas) por defecto y mínimo
1000 ms. Se almacena y firma con precisión de segundos, sin renovación por actividad.
Sesiones revocadas o con versión antigua no se aceptan aunque expiren en el futuro.
`SessionCleanup` elimina únicamente filas de sesión ya vencidas cada hora, con demora
inicial de una hora (`app.sessions.cleanup-ms`, valor positivo). La validación rechaza
vencidas inmediatamente, sin esperar al job. La eliminación es idempotente entre
instancias y usa el índice de expiración. Un fallo del job no restaura sesiones.

No se guardan JWT completos ni contraseñas en sesiones o nuevos logs. Todas las
instancias deben compartir PostgreSQL, clave de firma y relojes sincronizados. No se
modificaron perfiles productivos, guardas de secretos, bootstrap ni demos de T01.

## Frontend y orden de actualización

Se mantiene `sessionStorage`. El cambio propio guarda el token de reemplazo, cierra
conexiones anteriores del cliente y libera el guard temporal para los tres roles.
La pantalla de cambio también acepta contraseñas propias no temporales. La barra
común de seguridad ofrece cambio, logout y cierre global, incluida la pantalla temporal.

Ante 400 `CURRENT_PASSWORD_INVALID`, el formulario muestra «La contraseña actual es
incorrecta. Corrígela e inténtalo de nuevo.», habilita el reintento y conserva token,
usuario y estado temporal. No cierra conexiones ni navega al login. Un segundo intento
correcto reemplaza el token y libera el estado temporal. El interceptor no contiene
excepciones para los 401 del endpoint de cambio: sigue limpiando y navegando al login
cuando la sesión deja de ser válida durante ese flujo.

Logout captura el Bearer actual, limpia inmediatamente estado local/examen/conexiones
y solicita la revocación. Un 204 muestra confirmación; fallo HTTP/red o timeout de ocho
segundos muestra que **no se confirmó la revocación remota**. Cerrar una pestaña retira
el almacenamiento del navegador, pero no revoca la fila del servidor. Los guards y
el interceptor limpian localmente ante 401 sin llamar a logout. El 403 específico
activa modo temporal y navega una vez; otros 403 conservan la sesión. Respuestas tardías
de tokens reemplazados no eliminan ni restauran una sesión nueva.

Las versiones T02 de frontend/backend dependen entre sí:

1. Preparar y verificar ambos artefactos y el esquema aditivo en un entorno aislado.
2. En una ventana coordinada, actualizar **todas** las instancias del backend T02;
   evitar mezclar instancias antiguas, que aceptarían tokens sin el control de sesiones.
3. Publicar el frontend T02 inmediatamente después y exigir recarga/login nuevo.
4. No hacer rollback al backend anterior: volvería a aceptar JWT sin revocación.

Frontend anterior + backend T02 conserva un token revocado después del cambio propio
hasta recibir 401. Frontend T02 + backend anterior no recibe reemplazo y limpia sesión;
logout remoto no existe y se muestra como no confirmado. Por ello no es una combinación
operativa soportada. No se realizó esta actualización ni rotación real.

## Verificación reproducible

Entorno: Java 17 compatible, Maven 3.9.15, PostgreSQL 18.4 desechable en
`127.0.0.1:55472`, base `chemicallab_t01_test` (nombre compartido con el arnés de T01),
usuario ficticio `t02_test`; Node 24.13.0, npm 11.6.2. La suite T02 crea un esquema
aleatorio `t02_*`, arranca aplicaciones completas con puertos HTTP aleatorios ligados
a loopback y lo elimina al finalizar. Rechaza URLs que no sean loopback con puerto
explícito y ese nombre de base antes de conectar. Las suites anteriores pueden escribir
en `public`: ejecutar solo sobre una instancia desechable. No reutilizar la base escolar.

Desde el backend, con la instancia ficticia ya iniciada:

```powershell
.\mvnw.cmd -o clean package "-Dspring.profiles.active=test" `
  "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55472/chemicallab_t01_test" `
  "-Dspring.datasource.username=t02_test" "-Dspring.datasource.password=fictitious"
```

Para repetir solo T02, sustituir `clean package` por `test -Dtest=AccountSessionsDbTest`.
`-o` presupone dependencias ya descargadas; omitirlo en un entorno nuevo. En esta máquina
se invocó el `mvn.cmd` 3.9.15 cacheado por el wrapper, con esos mismos argumentos, porque
el wrapper dentro del sandbox no completaba. Java/Node/PostgreSQL requirieron ejecución
local fuera de las restricciones de procesos del sandbox.

Desde el frontend:

```powershell
npm.cmd ci --ignore-scripts --legacy-peer-deps
npm.cmd test -- --watch=false
npm.cmd run build
```

`npm ci` estándar detectó una inconsistencia previa de resolución peer del lockfile
(`@emnapi/wasi-threads`); la instalación con `--legacy-peer-deps` funcionó sin modificar
`package.json` ni `package-lock.json`. No se actualizan dependencias en T02.

### Resultados

Verificación final completada el **8 de octubre de 2026 (America/Lima)**:

| Comprobación ejecutada | Resultado |
|---|---|
| Backend `clean package`, suite completa | **438 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS** |
| Dentro de ella, `AccountSessionsDbTest` | **18 pruebas PostgreSQL/HTTP aprobadas**, incluida la matriz de 118 rutas y seis casos de contraseña actual incorrecta |
| Dentro de ella, `GlobalExceptionHandlerTest` | **12 pruebas aprobadas**; resolución MVC de errores de formulario, autenticación, validación, 404, 413 y 500 |
| Regresión de T01 | Incluida: configuración segura, bootstrap, persistencia y rotación JWT |
| Frontend Angular/Vitest | **45 pruebas aprobadas**, cinco archivos; 0 fallidas |
| Frontend producción `npm run build` | Aprobada; avisos previos de presupuesto inicial/SCSS |
| Diff de los dos repositorios | `git diff --check` sin errores; solo código/documentación/pruebas T02 |

En la verificación inicial de T02, después de hacer determinista el cambio de un carácter de firma en el arnés, se
repitió `AccountSessionsDbTest#signatureExpirationLegacyClaimsAndConnectUseCentralValidation`:
una prueba aprobada, cero fallos/errores/omisiones (`t02-signature-final.log`). No cambia
el código de producción. Aquella ejecución focalizada reemplazó el XML de esa clase en
Surefire; su conteo completo de 424 corresponde al log histórico de `clean package`.
La ejecución completa de esta corrección reemplazó todos los XML y acredita las 438
pruebas de la tabla, incluida nuevamente la prueba de firma/expiración/CONNECT.

Los XML de Surefire (`target/surefire-reports`) permiten repetir y contar resultados.
Los logs de ejecución se conservaron localmente fuera de los repositorios:
`t02-password-backend-green.log`, `t02-password-frontend-green.log`,
`t02-password-frontend-build.log`, dentro del directorio de trabajo aislado. Los logs
`t02-backend-verified.log`, `t02-frontend-tests-final.log` y
`t02-frontend-build-final.log` conservan la verificación inicial.
No se publican logs, bases, binarios, tokens, credenciales locales ni archivos temporales.
El aviso de las pruebas Angular sobre `polyfills.ts` y los presupuestos de componentes
preexistentes no impidieron compilación. El instalador npm informó avisos de seguridad
del árbol existente; su evaluación/actualización no se acredita como realizada en T02
(corresponde a T09).

Cobertura específica de `AccountSessionsDbTest`:

- Inventario automático de **118 operaciones REST de negocio**; 1062 combinaciones
  de denegación: tres roles temporales (403) y tres roles inactivos, con bandera
  temporal tanto falsa como verdadera (401). Incluye archivos, uploads y capturas.
- Accesos legítimos de los tres roles a química, conceptos, evaluaciones, pizarras,
  materiales y métricas; permisos administrativos, capturas docente/alumno y rechazo
  del docente ajeno. Las suites previas mantienen la cobertura de propiedad detallada.
- Dos sesiones independientes, logout individual/global y reemplazo de ambas tras
  cambio propio para A/D/E. El cuerpo del logout no puede elegir otra cuenta/sesión.
- Contraseña actual incorrecta para A/D/E, normal y temporal: 400 con código explícito;
  comparación de la fila completa de cuenta y de todas sus sesiones antes/después,
  sin cambios de hash, bandera, versión ni timestamps. Ambas sesiones siguen sirviendo
  `/api/auth/me`. Un segundo intento correcto con el mismo token emite uno nuevo,
  cambia el hash, retira la bandera temporal y revoca las dos sesiones anteriores.
  Revocar realmente la sesión sustituta mediante logout y reutilizarla en el endpoint
  de cambio devuelve 401. También se verifica ese endpoint con firma alterada,
  expiración, JWT legado y cuenta desactivada.
- Reset actual y heredado, desactivación por todas sus familias y edición `active`,
  reactivación sin recuperación de tokens futuros y cambio de rol/ámbito.
- Firma alterada, expiración, JWT anterior sin datos nuevos, y CONNECT aceptado/rechazado
  por el interceptor real con repositorios PostgreSQL (sin mocks de persistencia).
- Una segunda instancia comparte la revocación; cierre/reinicio completo del contexto
  conserva una sesión válida y mantiene rechazada la revocada.
- Carrera con transacciones reales: `pg_stat_activity` acredita espera por bloqueo de
  login frente a reset/desactivación; también orden inverso login antes de reset.
- Rollback conserva contraseña y sesión anteriores y descarta la sesión sustituta;
  limpieza de vencidas no elimina usuarios.

Frontend: tests Angular/Vitest con HttpTestingController y DOM jsdom, incluyendo la
pantalla real de cambio para A/D/E, guard temporal, sustitución de token, botones de
cierre global, limpieza de conexiones, errores de logout, 401/403 paralelos, respuestas
tardías, inicio de sesión y validación de arranque. No son pruebas Selenium de red.

### Corrección previa al merge: contraseña actual incorrecta

Se reprodujo el defecto **antes de corregir el código de producción**. La prueba
`incorrectCurrentPasswordPreservesStateAndSessionsThenAllowsRetry` falló en los seis
casos con `expected: 400, but was: 401` (cero errores de ejecución), registrado en
`t02-password-backend-red.log`. Antes se corrigió un getter mal nombrado en el arnés;
ese fallo de compilación no se cuenta como reproducción del defecto.

En frontend, la caracterización del contrato anterior confirmó que un 401 con el
mensaje de contraseña incorrecta borra el token y navega al login. Doce casos nuevos
fallaron por el mensaje genérico que aún no reconocía el código explícito
(`t02-password-frontend-red.log`: 12 fallidas, 33 aprobadas). La caracterización se
conserva como protección contra excepciones por endpoint o texto del error.

Tras la corrección, los mismos seis casos backend pasan dentro de la suite completa.
En frontend se escriben los inputs y se pulsa el botón del componente real, con
AuthService e interceptor reales: seis casos rol × estado para 400 seguido de reintento
correcto y otros seis para 400 seguido de un 401 por revocación durante el reintento.
Se verifica la alerta visible, botón habilitado, token/usuario/estado intactos, ausencia
de redirección y limpieza, sustitución posterior del token y limpieza/navegación ante
401. HttpTestingController controla esas respuestas; la revocación persistida y el
401 real se acreditan en PostgreSQL/HTTP, no se presentan como prueba de navegador
conectado al backend. No se modificó el interceptor.

Al añadir el manejador específico se amplió la regresión MVC de
`GlobalExceptionHandlerTest`: código 400 específico, `BadCredentialsException` y
`DisabledException` todavía 401, validaciones/argumentos 400 sin ese código, recursos
ausentes 404, tamaño de archivo 413 y error inesperado 500. La suite completa y ambas
compilaciones están aprobadas; no queda pendiente funcional de esta corrección.

### Intentos fallidos, omisiones y límites

Los intentos iniciales detectaron una importación ambigua en el arnés Java y una
expectativa de conteo de rutas; se corrigieron. Una prueba detectó que el cambio propio
aún devolvía el contrato antiguo: se corrigió antes de la ejecución T02 aprobada.
Tras reanudar el chat, la instancia desechable estaba detenida: una ejecución completa
registró 413 pruebas, 19 errores de conexión y cero fallos de aserción. Se reinició
exclusivamente ese cluster y se repitió la regresión; ese intento no se acredita como
aprobado. La instalación offline inicial del frontend tampoco completó por caché faltante.

No se ejecutaron pruebas contra el colegio, migraciones productivas, despliegues,
rotaciones, carga ni un navegador externo/Selenium. No se modificó el informe original
`Documentos/Seguridad/01-auditoria-seguridad.md`, ni el trabajo previo del frontend.

## Pendiente de T03

T02 valida nuevos CONNECT. **No** autoriza todavía destinos SEND/SUBSCRIBE, recursos
STOMP ni autoría de objetos; tampoco revalida o cierra conexiones ya establecidas al
expirar, cambiar contraseña, resetear, desactivar o cerrar sesiones desde otro cliente.
El cliente que hace logout cierra sus propias conexiones, pero el servidor no garantiza
el cierre de otras conexiones existentes. La prueba de CONNECT es del interceptor/canal,
no una certificación del transporte SockJS/proxy/broker. SEC-03, SEC-04 y SEC-05 tienen
corrección REST y de nuevos CONNECT; no se declaran completamente cerrados mientras
falte T03. SEC-06/SEC-15 y el endurecimiento de contraseñas/rate limit de T04 permanecen
fuera de esta implementación.
