# Configuración por entorno y bootstrap seguro (T01)

Esta entrega corrige la configuración compartida y el aprovisionamiento automático
de SEC-01/SEC-02. No implementa sesiones revocables, restricciones centrales de
contraseña temporal ni autorización STOMP (SEC-03–SEC-06; T02/T03).

Actualización T02: esta descripción delimita la entrega original T01. La versión
actual incorpora [sesiones persistentes y restricciones centrales REST/CONNECT](seguridad/02-02-autenticacion-sesiones.md).
Las garantías de configuración y bootstrap de este documento se mantienen; la
autorización de destinos y revocación de conexiones STOMP existentes siguen en T03.

## Perfiles y arranque

Seleccione exactamente uno: `dev`, `test` o `prod`. Los perfiles combinados o desconocidos
se rechazan antes de crear beans o conectar a la base. Sin selección, el perfil por
defecto es `prod`. Un archivo externo llamado `application-test.properties` no selecciona
por sí mismo el perfil: indíquelo en el comando. El Dockerfile selecciona `prod`.

| Perfil | JWT | PostgreSQL | Demos por defecto |
|---|---|---|---|
| `dev` | Fallback público de desarrollo, sobrescribible | Fallback local `lab_quimico_db`, `postgres` / `admin` | Deshabilitadas |
| `test` | Fallback público de pruebas, sobrescribible | URL, usuario y contraseña externos para una base desechable | Deshabilitadas |
| `prod` (también sin perfil) | Externo obligatorio, fallback público rechazado | URL, usuario y contraseña externos obligatorios, sin fallback local | Bloqueadas siempre |

Desarrollo local, con Java 17+ y una base PostgreSQL de desarrollo:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

En Unix: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`.
Para un JAR local: `java -jar target/chemical-lab-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev`.
Los comandos productivos deben seleccionar `prod`, nunca `dev` para evitar un error
de configuración. El guard funciona también con `spring.config.additional-location`.

## Variables externas

| Variable | Uso |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev`, `test` o `prod` |
| `SPRING_DATASOURCE_URL` | URL JDBC, p. ej. `jdbc:postgresql://HOST:5432/BASE`; credenciales separadas |
| `SPRING_DATASOURCE_USERNAME` | Usuario PostgreSQL externo |
| `SPRING_DATASOURCE_PASSWORD` | Contraseña PostgreSQL externa no vacía |
| `APP_JWT_SECRET` | Base64 estándar canónico de al menos 32 bytes generados con un RNG criptográfico |
| `APP_JWT_EXPIRATION_MS` | Duración JWT; se conserva el valor actual de 86400000 ms |
| `APP_CORS_ALLOWED_ORIGINS` | Orígenes autorizados del frontend, separados por comas |
| `APP_DEMO_ENABLED` | `true` para demos exclusivamente en `dev`/`test`; valor predeterminado `false` |
| `TEACHER_INITIAL_PASSWORD`, `STUDENT_INITIAL_PASSWORD` | Opcionales solo para demos; fallbacks públicos exclusivos del entorno no productivo |
| `APP_BOOTSTRAP_ADMIN_ENABLED` | `true` habilita el bootstrap; predeterminado `false` |
| `APP_BOOTSTRAP_ADMIN_USERNAME` | Obligatorio al habilitarlo; 4–50 letras/números sin espacios |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | Obligatoria al habilitarlo; mínimo 12 caracteres, máximo 72 bytes UTF-8 por BCrypt |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | Opcional; si se define debe ser un correo válido |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Se conserva `update`. Nunca usar `create`/`create-drop` contra datos que deban conservarse |
| `SPRING_JPA_SHOW_SQL` | Predeterminado `false` |

`ADMIN_INITIAL_PASSWORD` ya no aprovisiona administradores. Retírela de instrucciones
y configuraciones antiguas. Las opciones booleanas admiten solamente `true`/`false`.
También puede usar las propiedades equivalentes `app.bootstrap.admin.*` en un archivo
externo protegido, nunca versionado. La validación informa el nombre de la variable y
el formato requerido sin mostrar secretos ni contraseñas.

Producción (cargue previamente las variables reales desde el gestor de secretos):

```powershell
.\mvnw.cmd clean package -DskipTests
java -jar target/chemical-lab-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Ejemplo de archivo externo, con marcadores deliberadamente no utilizables:

```properties
spring.datasource.url=jdbc:postgresql://HOST:5432/BASE
spring.datasource.username=<USUARIO_DB>
spring.datasource.password=<CONTRASENA_DB>
app.jwt.secret=<BASE64_DE_BYTES_ALEATORIOS>
app.cors.allowed-origins=https://FRONTEND
app.demo.enabled=false
app.bootstrap.admin.enabled=false
```

Arranque: `java -jar app.jar --spring.profiles.active=prod --spring.config.additional-location=file:/RUTA/config.properties`.
No coloque credenciales en la URL JDBC o en argumentos visibles del proceso.

## Crear el primer administrador

1. Seleccione el entorno y la base correctos. Prepare un usuario externo y una contraseña
   temporal única mediante un gestor de contraseñas. No reutilice las contraseñas demo.
2. Configure `APP_BOOTSTRAP_ADMIN_USERNAME`, `APP_BOOTSTRAP_ADMIN_PASSWORD` y, si corresponde,
   `APP_BOOTSTRAP_ADMIN_EMAIL` mediante el gestor de secretos o un archivo externo protegido.
3. Active `APP_BOOTSTRAP_ADMIN_ENABLED=true` y arranque con el perfil correspondiente.
   El bootstrap está separado de las demos. Solo crea una cuenta si no existe ningún
   `ADMINISTRADOR`, incluso inactivo. Una identidad ocupada provoca error sin sobrescribirla.
4. Revise el mensaje de creación/omisión. El bootstrap utiliza una transacción y un bloqueo
   asesor transaccional de PostgreSQL para serializar arranques simultáneos de bootstrap.
5. Desactive `APP_BOOTSTRAP_ADMIN_ENABLED=false` y retire las tres variables de credenciales
   del servicio/archivo externo. Reinicie sin bootstrap. En PowerShell puede quitar las
   variables de la sesión con `Remove-Item Env:APP_BOOTSTRAP_ADMIN_PASSWORD` y equivalentes;
   quite también las persistidas en el gestor de despliegue.
6. Inicie sesión y cambie la contraseña temporal mediante el flujo existente. La cuenta
   se crea con `temporaryPassword=true`. T02 restringe centralmente esta sesión a estado
   mínimo, cambio de contraseña y cierre de sesiones; al cambiar entrega un JWT nuevo.

No use este procedimiento para recuperar una cuenta, añadir administradores posteriores
o restablecer contraseñas. Si ya hay un administrador, no se crea otro ni se cambia su
contraseña. Si todos están inactivos, requiere recuperación operativa explícita; el
bootstrap no los reactiva. Mantener la opción habilitada permitiría crear un administrador
si en el futuro se eliminan todos: por eso debe retirarse después del uso.

## Demos, reinicios e inventario previo

Solo en una base ficticia: seleccione `dev`/`test` y active `APP_DEMO_ENABLED=true`.
Se crean `docente` y `EST0001` con perfiles ficticios y contraseña temporal. No se crea
un administrador demo. Las cuentas y perfiles encontrados no se actualizan, no se
reactivan ni se cambia su contraseña. Deshabilitar la opción no elimina ninguna cuenta.
Una cuenta retirada no se vuelve a crear con la opción deshabilitada. Si mantiene demos
habilitadas, una demo ausente sí vuelve a crearse; retire la opción al terminar las pruebas.
En producción el runner demo ni siquiera se registra, aunque `APP_DEMO_ENABLED=true`.

Antes de decidir el retiro de cuentas antiguas, un operador autorizado debe consultar
el inventario (ejemplo de lectura, sin seleccionar hashes):

```sql
SELECT id, username, email, role, active, temporary_password, created_at
FROM user_accounts
WHERE username IN ('admin', 'docente', 'EST0001')
   OR email IN ('admin@chemicallab.local', 'docente@chemicallab.local');
```

Los identificadores son candidatos, no prueba de que la cuenta sea prescindible.
Verifique propietarios, perfiles de docente/alumno, contenidos, evaluaciones, intentos,
pizarras, auditoría y métricas asociados antes de decidir. Documente responsables,
dependencias e historial y prepare una copia/restauración conforme al procedimiento del
entorno. T01 no elimina, desactiva ni modifica cuentas existentes. Desactivar/restablecer
revoca con T02 todas las sesiones anteriores en REST y nuevos CONNECT. El cierre de
conexiones STOMP existentes sigue pendiente de T03.

## Rotación de la clave JWT

Inventaríe primero qué instancias usan la misma clave, mediante un identificador de
versión del gestor de secretos; nunca muestre el valor en logs, informes o PRs.
Genere una clave nueva con un RNG criptográfico en el entorno autorizado:

```powershell
$jwtBytes = [byte[]]::new(64)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
# Guarde el resultado directamente en el gestor de secretos, sin versionarlo.
$env:APP_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
[Array]::Clear($jwtBytes, 0, $jwtBytes.Length)
```

Alternativa: `openssl rand -base64 64` (retire saltos de línea al cargar el valor).
No derive la clave de una frase ni use generadores no criptográficos.
Programe una ventana de rotación y sustituya `APP_JWT_SECRET` en **todas** las instancias.
Reinícielas y confirme el perfil y la versión externa, sin imprimir el secreto.
No existe un período de aceptación de ambas claves: los JWT anteriores fallan por firma
en las nuevas instancias y los usuarios deben iniciar sesión de nuevo. Durante un cambio
parcial pueden existir errores intermitentes y aceptación de tokens antiguos en instancias
sin rotar. Retire la clave anterior y evite un rollback que vuelva a aceptarla.

La rotación no cambia hashes, cuentas, perfiles ni historial. Las conexiones STOMP ya
establecidas requieren cierre/reconexión operativa; su revocación continua será T03.
Estas acciones reales no se ejecutaron como parte de T01.

## Pruebas aisladas

Las pruebas unitarias de provisionamiento usan repositorios simulados. `SecureStartupTest`
arranca SpringApplication real con configuración y post-procesadores, sin DB ni servidor.
`JwtRotationTest` firma y valida JWT reales con dos claves aleatorias ficticias.

`StartupPersistenceDbTest` arranca repetidamente **toda** la aplicación, con puerto HTTP
aleatorio, JPA, transacciones, migraciones y runners reales. Usa esquemas nuevos en un
PostgreSQL desechable y comprueba base vacía/poblada, cuentas, perfiles, contraseña,
indicadores e historial conceptual tras reinicios. Rechaza antes de conectar toda URL
distinta de `jdbc:postgresql://127.0.0.1:PUERTO/chemicallab_t01_test`.

Prepare una instancia PostgreSQL aislada, ligada únicamente a loopback en un puerto
distinto del del colegio. Cree la base ficticia `chemicallab_t01_test`. Para la suite
completa (incluye las pruebas DB existentes), use credenciales de esa instancia:

```powershell
.\mvnw.cmd test "-Dspring.profiles.active=test" `
  "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:PUERTO/chemicallab_t01_test" `
  "-Dspring.datasource.username=<USUARIO_FICTICIO>" `
  "-Dspring.datasource.password=<CONTRASENA_FICTICIA>"
```

Para pruebas sin DB: `-Dtest=SecureStartupTest,AccountProvisioningTest,JwtRotationTest,AuthServiceTest,AdminServiceTest,UserManagementServiceTest,InputValidationTest`.
No ejecute la suite contra la base del colegio. Los esquemas de T01 se eliminan al
finalizar y las pruebas existentes pueden modificar el esquema de su base ficticia.
Detenga la instancia desechable al terminar. La verificación de infraestructura productiva,
backup/restauración, inventario real y rotación real quedan a cargo del despliegue posterior.
