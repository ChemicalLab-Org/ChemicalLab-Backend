# Administración institucional y superficies transversales

La ampliación `administration_runtime` v1 usa las instituciones, pertenencias, períodos, clases, episodios y facultades vigentes de J+K+L. El JAR normal y Angular usan la sesión T02. Las rutas administrativas toman al actor de la sesión y comprueban el ámbito antes de cualquier consulta o escritura.

## Instalar sobre J+K+L

1. Detén los escritores y guarda un respaldo completo de PostgreSQL. Comprueba J+K+L y T08. Aplica exclusivamente `src/main/resources/db/runtime/administration-v1.sql` con `psql -X -v ON_ERROR_STOP=1`. El archivo abre y confirma su propia transacción.
2. En la instalación inicial, usa una vez el bootstrap T02 de administrador con sus parámetros `APP_BOOTSTRAP_ADMIN_*`. Si ya existe una cuenta global autorizada para ejercer la administración de plataforma, identifica su ID por el procedimiento controlado del operador. Con el escritor detenido, registra su ID una sola vez en `administration_runtime.platform_authority(account_id)`. La tabla admite una fila y el JAR exige además que la cuenta global siga activa y sea `ADMINISTRADOR`. Desactiva el bootstrap y retira sus credenciales tras provisionar la cuenta.
3. Arranca el JAR con `ddl-auto=validate`, retención desactivada y Angular apuntando al mismo origen para API y SockJS. Una instalación J+K+L que aún carece de la ampliación bloquea las rutas globales anteriores y mantiene la administración institucional deshabilitada.
4. Para recuperar, restaura el respaldo completo, incluidas las tablas del motor T03, T08 y los cuatro esquemas de ejecución. Usa el JAR que corresponda al respaldo. Mantén el escritor anterior detenido después de activar la ampliación. El respaldo anterior al corte sirve para reponer el estado previo dentro de la ventana de instalación, antes de aceptar escrituras nuevas.

## Recorrido local

En los checkouts de entrega, prepara el JAR con Java 17 y Maven offline. Prepara Angular con las dependencias del lockfile y compila `npm run build -- --configuration colegio`.

```powershell
mvn -o -DskipTests package
$run = 'C:/ruta/escribible/admin-fresh'
./tools/run-runtime-journey.ps1 -Administration -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -Java 'C:/Program Files/Java/jdk-17/bin/java.exe' -Jar target/chemical-lab-backend-0.0.1-SNAPSHOT.jar -RunDirectory $run -DatabasePort 55738 -HttpPort 18211
```

Con el corredor en ejecución, prepara el proxy Angular como indica [Pizarras institucionales](12l-adopcion-pizarras.md#recorrido-manual-local), usando el origen de `$run/browser/ready.json`. El corredor provisiona la cuenta ficticia de plataforma mediante el bootstrap y publica solo las credenciales del fixture en ese archivo.

1. Inicia sesión con la cuenta de plataforma y cambia la contraseña temporal. En **Administración institucional**, crea A y B. Usa **Crear primer administrador** para cada una y guarda sus contraseñas temporales. Prueba **Suspender institución** y **Reanudar institución** en B. La suspensión retira HTTP y conexiones de B. La lista de plataforma muestra la institución y su estado.
2. Inicia sesión como cada administrador en perfiles de navegador independientes y cambia su contraseña. Selecciona su institución, crea un período vigente y luego una clase. En **Miembros**, crea cuentas con identificadores nuevos. Concede los roles locales que correspondan. El sistema genera la contraseña temporal y la muestra una vez al administrador que hizo el alta. La persona la cambia con la pantalla T02.
3. Desde **Docencia**, asigna un responsable y, si corresponde, un co-docente con pertenencia y rol DOCENTE vigentes. Usa **Matrículas** para incorporar a un miembro con rol ESTUDIANTE. La vigencia de una nueva relación queda limitada al período, la pertenencia y el rol. Puedes finalizar una relación y cerrar clases o períodos. La baja preserva los episodios y las autorías.
4. Crea un borrador desde **Evaluaciones** o **Contenidos** como docente habilitado. En **Biblioteca**, elige el módulo, el recurso, el miembro y una facultad entre `EDIT_DRAFT`, `PUBLISH` y `ASSIGN`. Revocar una facultad conserva el historial y deja disponibles las demás. Los permisos de docencia y calificación se comprueban de manera independiente.
5. Abre una pizarra con un docente y un alumno matriculado. Desde otra sesión de administración, suspende la pertenencia del alumno. La consulta de A queda denegada y el watchdog cierra su conexión. B y la sesión global T02 conservan su vigencia cuando corresponda. Comprueba las proyecciones en **Resumen**, **Actividad** y **Auditoría**.
6. Para repetir el recorrido de Angular desde una base fresca, ejecuta el corredor en Edge. Para detener el JAR y PostgreSQL, crea `$run/browser/stop`.

```powershell
node tools/verify-institutional-journey.mjs "$run/browser" 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' dist/chemical-lab-frontend/browser administration
```

Para la ampliación usa un directorio y puertos distintos, con `-Administration -PredecessorJar` y el JAR de la base de pizarras. Los modos `evaluations`, `contents` y `boards` del corredor Angular pueblan intentos, notas, contenido, archivos y pizarras antes del corte. Ejecuta `node tools/verify-administration-runtime.mjs $run $psql $port before` desde el checkout backend. Crea `$run/browser/upgrade`, espera `$run/upgraded.pid` y vuelve a ejecutar el mismo corredor sin `before`. En esta segunda ejecución comprueba firmas, rutas, facultades, revocaciones, auditoría, reinicio y restauración de la base desechable.

## Matriz de superficies

| Superficie | Aplicación adoptada | Cobertura o pendiente |
| --- | --- | --- |
| Plataforma, `/api/platform/institutions/v1` | Lista de instituciones, alta, estado y primer administrador | Autoridad de plataforma explícita más T02. Sin datos académicos transversales. |
| Usuarios legacy, `/api/users/**`, `/api/admin/users/**` y pantallas anteriores de docentes, estudiantes y contraseñas | Bloqueados | Nuevo listado de pertenencias por institución, con nombre local y roles. Altas nuevas y cambios de estado o facultades locales. |
| Períodos, clases, matrículas y docencia | Administración institucional | Cierres, bajas y vigencias conservan historia. Una matrícula terminada conserva su identidad. Traslados y readmisiones siguen fuera del piloto. |
| Biblioteca de evaluaciones y contenidos | Listado acotado de recursos y concesión o revocación de cada facultad | Proyección mínima, sin respuestas, archivos ni payloads completos. Los recorridos existentes mantienen sus decisiones de acceso. |
| Supervisión y pizarra global, `/api/admin/summary`, `/api/admin/academic-supervision/**`, `/api/whiteboards/admin/**` y sus pantallas | Bloqueadas | Resumen local de miembros y clases vigentes y conteos de evaluaciones, contenidos y pizarras. Rutas de pizarra HTTP y STOMP conservan su ámbito. |
| Actividad global y auditoría, `/api/admin/activity`, `/api/admin/logs/**` y sus pantallas | Bloqueadas | Actividad localizada desde recibos confirmados de J, K y L. Auditoría local incluye cambios administrativos confirmados y admite filtro por membresía. Los logs técnicos globales, inicios de sesión y rechazos siguen en T08 bajo control de operador. |
| Métricas globales, `/api/usage-metrics/events`, `/api/admin/usage-metrics/**`, `/api/admin/student-usage-records/**` y paneles | Bloqueadas. Angular evita emitir telemetría legacy. | La telemetría histórica permanece almacenada. La ruta carecía de procedencia académica suficiente para atribución institucional. El único emisor backend adicional era el alta de participante en la pizarra anterior. |
| Retención técnica, `/api/admin/technical-retention/**` y servicio de lote | Bloqueada, incluida la simulación | Sin scheduler de retención. T08 conserva sus reglas y presupuesto de rechazos. |
| Estado del sistema, cachés, tareas y exportaciones | Salud de servicio en `/api/health`. Pantalla anterior de estado global bloqueada. | El job existente de sesiones caducadas limpia T02. El watchdog revalida conexiones pasivas. Las sondas de instalación cachean la versión del contrato por proceso. La revisión no encontró una exportación administrativa ni una caché de datos de supervisión. Las descargas académicas autorizadas siguen en J+K+L. |

## Contratos y límites

Las consultas filtran por institución antes de ordenar, paginar o contar. Los listados usan cursores y un límite máximo de 40. La actividad combina hasta cuatro fuentes, cada una con su propio límite y ámbito. El filtro de cuenta compartida usa el ID local de pertenencia, incluso cuando el mismo actor opera en A y B. Se proyectan acciones y referencias de dominio. Las contraseñas, correos, tokens, hashes, metadatos crudos de auditoría y datos de otros colegios quedan fuera de esas respuestas.

Una escritura escolar toma el lock exclusivo de institución que comparten los recorridos académicos. El administrador se comprueba bajo ese lock. El cambio y la evidencia T08 SUCCESS confirman en la misma transacción. El contrato vincula la evidencia administrativa con actor, institución y, cuando aplica, la pertenencia afectada. T02 y las guardas vigentes siguen verificando cada acceso académico. El sistema mantiene el perfil técnico exigido por las claves foráneas de evaluaciones y del motor T03. La relación escolar autorizante permanece en el ámbito académico, con su propia vigencia y procedencia.

La interfaz admite una cuenta nueva por alta. Vincular una cuenta preexistente requiere definir el consentimiento, quién puede pedirlo, cómo se verifica el control de la cuenta y qué evidencia autoriza el vínculo. Sigue pendiente F06/T14. También queda pendiente el tratamiento de una contraseña temporal perdida y de una respuesta de alta perdida después del commit. Las cuentas compartidas que ya existan en J+K+L conservan sus pertenencias. El colegio puede administrar únicamente la suya y la contraseña global sigue bajo T02. El piloto local no acredita cierre productivo, migración histórica, operación externa, privacidad ni recuperación de cuentas.
