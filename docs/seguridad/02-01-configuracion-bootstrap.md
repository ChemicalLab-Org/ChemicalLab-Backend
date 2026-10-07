# T01 — Configuración productiva y bootstrap seguro

Implementación verificada el 7 de octubre de 2026 (America/Lima). Alcance: SEC-01 y
SEC-02. SEC-03–SEC-06 siguen abiertos para T02/T03; no se desplegó ni se rotaron
secretos reales ni se accedió a la base del colegio.

## Evidencia previa y estado inicial

Se leyó `Documentos/Seguridad/01-auditoria-seguridad.md` y se contrastaron SEC-01,
SEC-02 y T01 con el código. Se confirmó el JWT público y las credenciales locales
como fallbacks comunes, y `AdminSeeder` sin perfil/opción creando administrador y
demos al iniciar. El constructor JWT anterior comprobaba decodificación y longitud,
pero no distinguía el secreto de desarrollo en producción.

Backend inicial: `a81df31fb3c49d8f0e3f3d8f49dd6e3505265a15`, rama
`feature/validaciones-datos-institucionales`, árbol limpio. No se encontraron instrucciones
`AGENTS.md` aplicables. El frontend conserva su `e2e-selenium/` previo sin seguimiento;
no se modificó. Taller/Documentos no son repositorios Git reconocidos; la nota local se
refleja en este archivo versionado del backend. No fue necesario modificar otro repositorio.

El informe original se conserva sin editar. SHA-256:
`1EFF5D59F7A114DFEE91CB557A0405C2F7247E43CD2926D2542172C0255E499B`.

## Cambios

- Perfiles mutuamente excluyentes `dev`, `test`, `prod`; sin selección se aplica `prod`.
  Los comandos productivos y Docker seleccionan producción. Un archivo externo con
  nombre de perfil requiere también selección explícita en el comando.
- JWT en producción externo, no vacío, Base64 canónico y mínimo 32 bytes; rechazo de
  la clave pública de desarrollo por material decodificado. Errores sin valores de
  secretos ni causas de decodificación. Solo dev/test tienen fallback público.
- URL, usuario y contraseña DB externos obligatorios en producción. Los fallbacks
  locales quedaron únicamente en `dev`; `test` requiere conexión externa desechable.
- Validación mediante `SecureEnvironmentPostProcessor`, tras cargar configuración y
  antes de beans, DB o migraciones; el constructor JWT repite su validación.
- `DemoAccountSeeder` separado: solo dev/test y `APP_DEMO_ENABLED=true`; producción
  no registra el runner y también hay guard en ejecución. No crea administradores,
  no actualiza cuentas/perfiles existentes ni vuelve a activar cuentas retiradas.
- `AdminBootstrap`: opción explícita, usuario y contraseña externos sin defaults,
  correo opcional validado, BCrypt y `temporaryPassword=true`. Transacción y bloqueo
  asesor PostgreSQL serializan bootstraps simultáneos. Cualquier administrador existente
  (también inactivo) evita crear otro; identidad ocupada falla sin sobrescribirla.
- Guías README, DEPLOY y colegio actualizadas; [procedimiento completo](../configuracion-segura.md)
  para bootstrap de un uso, inventario de demos, rotación JWT y pruebas aisladas.

## Verificación

| Comprobación | Tipo | Resultado |
|---|---|---|
| Claves ausentes/vacías/malformadas/cortas/de desarrollo, perfiles mezclados, DB sin fallbacks y bootstrap inválido | `SecureStartupTest`: SpringApplication y configuración reales; sin repositorios ni DB | 19 casos pasan; fallos ocurren antes del primer bean |
| Demos opt-in, veto productivo, bootstrap deshabilitado, credenciales/collisiones, preservación y orden del bloqueo | `AccountProvisioningTest`: repositorios/JdbcTemplate/encoder simulados | 17 casos pasan |
| Firma y validación con claves anteriores/nuevas | `JwtRotationTest`: JJWT real, claves ficticias aleatorias | 1 caso pasa; token anterior rechazado por la nueva clave |
| Producción vacía/poblada sin demos aun con opción activa; bootstrap, administrador inactivo, contraseñas, perfiles e historial tras reinicios; demos dev/test opt-in, deshabilitación y cuenta retirada no recreada | `StartupPersistenceDbTest`: aplicación completa, HTTP loopback en puerto aleatorio, JPA/PostgreSQL y runners reales | 2 escenarios pasan, con 11 arranques completos |
| Pruebas existentes pertinentes más casos nuevos sin DB | Ejecución focalizada | 72 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Suite completa del backend, incluidas integraciones DB existentes y T01 | PostgreSQL desechable 18.4 | 412 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS |
| Empaquetado de JAR Spring Boot | `mvnw.cmd package -DskipTests`, después de la suite | BUILD SUCCESS |

Los casos de persistencia usan un clúster nuevo en `.codex-work/t01-security/pgdata`,
ligado solo a `127.0.0.1:55471`, base `chemicallab_t01_test`, usuario ficticio `t01_test`.
Cada escenario T01 crea y elimina su propio esquema aleatorio. Las pruebas existentes
usan el esquema público de esa base ficticia. No hubo conexión al puerto/base del colegio.
El primer intento de pruebas dentro del sandbox quedó bloqueado al instrumentar Mockito;
las ejecuciones concluyentes se realizaron fuera del sandbox con el mismo código y DB aislada.

Comando concluyente de suite (credenciales exclusivamente ficticias de esa instancia):

```powershell
.\mvnw.cmd test "-Dspring.profiles.active=test" `
  "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55471/chemicallab_t01_test" `
  "-Dspring.datasource.username=t01_test" `
  "-Dspring.datasource.password=<CONTRASENA_FICTICIA>"
```

Resultados/logs locales: `.codex-work/t01-security/focused-tests-elevated.log`,
`backend-suite.log`, `package.log`; reportes XML en `ChemicalLab-Backend/target/surefire-reports`.
Estos auxiliares y datos no se incluyen en commits.

## Operación posterior y límites

Antes de aplicar en un entorno real: inventariar demos y sus vínculos/historial,
respaldar y comprobar recuperación según el procedimiento operativo; configurar `prod`
y DB/JWT externos; planificar una rotación coordinada por versión del gestor de secretos.
No eliminar/desactivar cuentas a ciegas. Los JWT firmados con la clave anterior dejan
de validar en las instancias rotadas y requieren nuevo login; no se modifican cuentas
ni historial. Cerrar/reconectar operativamente conexiones STOMP ya establecidas.

Para una instalación nueva, bootstrap una sola vez y retirar la opción y credenciales
después. Para una instalación existente, dejarlo deshabilitado. No se usa como recuperación
de administradores inactivos. El cambio temporal existente se conserva; no se acredita su
restricción central en la API hasta T02. Tampoco se acreditan revocación individual de
sesiones, continuidad/autorización WebSocket, instituciones o invitaciones.

Se conserva `ddl-auto=update` y las migraciones actuales: no se evaluó una migración
productiva, la infraestructura o backups reales. Docker se revisó estáticamente; la
imagen no se construyó ni desplegó. El bloqueo asesor se ejecutó realmente, pero no hubo
una prueba de concurrencia multiinstancia. Estas limitaciones no bloquean la entrega de
código aislada de T01; su efectividad en producción requiere aplicar y verificar la
configuración en el despliegue autorizado posterior.

Rama de entrega: `security/t01-configuracion-bootstrap`. La PR se dirige a `develop`,
cuya revisión inicial y árbol coinciden con los archivos de la base usada para T01;
no se incluyen cambios funcionales de tareas anteriores. No se realiza merge.

Referencia de implementación del hook de arranque:
[EnvironmentPostProcessor de Spring Boot](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/EnvironmentPostProcessor.html).
