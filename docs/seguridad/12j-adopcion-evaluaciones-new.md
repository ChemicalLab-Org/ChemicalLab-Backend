# Adopción local del contrato institucional NEW

La aplicación usa el contrato persistente `evaluation_runtime` v1 cuando está instalado. Los mismos endpoints versionados de Angular y las mismas reglas de contenido, intentos y calificación de F sirven la aplicación normal. El ensayo se hace con un JAR Spring Boot, perfil `dev` y PostgreSQL ficticio en loopback. Las propiedades `app.evaluations.institutional.*` de los fixtures conservan sus valores normales desactivados.

## Dependencias retiradas del recorrido normal

| Dependencia histórica | Sustitución en el contrato normal |
| --- | --- |
| Perfil único `test`, base `chemicallab_t01_test`, schema `t03_*`, `t11b5_fixture()` | Instalación transaccional y explícita de `db/runtime/evaluation-v1.sql`. El JAR reconoce su versión al servir la primera petición. |
| Mantenedores B1/B2/B6/B8 y controles `t12f_*`, `t12g_*`, `t12h_*` | Relaciones persistentes de institución, período, clase, membresía, rol, docencia y episodio. Las operaciones académicas entran por las rutas normales del JAR. |
| Cabezas, manifest y censo B10 para derivar un contexto preparado | Las raíces nativas se crean con procedencia inmutable. FKs compuestas conservan institución, clase, cuenta, revisión, entrega, destinatario y episodio exactos. |
| Locks de tablas de todo el fixture | Lock compartido de contexto por institución, UUID de comando y lock de la raíz para escrituras y detalle coherente. Las bajas y cambios de permiso toman el lock exclusivo de la institución afectada. |
| Recibos que prueban delta de un censo y medición global | UUID, actor, clase, raíz y SHA-256 de la solicitud JSON canónica. Resultado, auditoría T08 y contadores de filas/bytes lógicos se confirman con la operación. |

A–H mantienen sus guardas y estructuras. La implementación comparte los validadores G/H, la política académica y el motor F con esos ensayos.

## Origen, F08 y corte

- **NEW nativo.** La creación y cada admisión nacen en el ámbito autorizado en la misma transacción. Las relaciones tipadas y la auditoría conservan su procedencia. La entrega inicial puede abrirse desde que inicia su ventana. F08 mantiene su función de conciliación y corrección de contextos existentes. El contrato nativo carece de aprobaciones ficticias generadas por publicación o asignación.
- **Historia.** El instalador se detiene ante un fixture certificado o una base con raíces, asignaciones o intentos LEGACY. Esos datos y su motor permanecen en su instalación anterior. La importación de raíces históricas requiere una fotografía y mapa por agregado, aprobaciones de una cuenta de plataforma y otra de la institución destino, revisión de vigencia y un corte que conserve consumos, activos, revisiones y cierre T05. Esta entrega carece de un importador de históricos. Las aprobaciones y evidencias B10 existentes conservan su alcance original.
- **Rutas.** La base adoptada bloquea por HTTP las rutas globales antiguas de evaluaciones y las rutas de lectura/escritura institucional de ensayo. Las tablas legacy de negocio quedan selladas contra escrituras. Las instalaciones que carecen del nuevo contrato conservan su comportamiento anterior. La instalación requiere detener escritores de esa base y reiniciar el binario coordinado. Después de servir datos nativos, una recuperación usa un binario que entienda este contrato y una copia que conserve su ledger.
- **Mantenimiento de relaciones.** La carga inicial se ejecuta una vez mediante SQL explícito. Las altas y cambios administrativos quedan en el ámbito del operador autorizado. Para cambios con el servidor activo, la transacción de mantenimiento adquiere primero `pg_advisory_xact_lock(evaluation_runtime.institution_lock(id))` y opera sobre una institución. Los triggers coordinan cambios sobre sus filas. Los episodios se terminan y se conservan. El grant de biblioteca tiene una facultad independiente y registra la membresía del otorgante, que debe tener administración vigente en el colegio al otorgar. El contrato carece de un panel de administración.

Contadores y recibos cubren los agregados nativos. El checksum acompaña la FK a la publicación inmutable. La cuota cuenta los bytes de la representación JSON de las filas en UTC, incluidos los recibos. T08 conserva sus tablas y presupuesto técnico independientes. El cierre global toma en cuenta todas las ventanas y notas de la raíz, incluidas entregas sin destinatarios. F09 mantiene `(raíz, cuenta, número)` y un solo intento activo de la cuenta por raíz.

## Reproducir con un JAR normal

Use Windows, Java 17, Maven con caché local, PostgreSQL 18 y Node.js con las dependencias Angular del lockfile. El script de preparación usa un directorio nuevo y dos puertos de loopback. En una base vacía la preparación deja que Hibernate cree las tablas base y que el guard existente se detenga ante el prerrequisito T07-B/T08. Aplica después esos SQL históricos mediante `psql`. Instala el nuevo contrato y carga dos instituciones ficticias y tres cuentas. El servidor del recorrido arranca con `ddl-auto=validate`.

```powershell
mvn -o -DskipTests package
$run = 'C:/ruta/escribible/adopcion-local'
./tools/run-runtime-journey.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -Java 'C:/Program Files/Java/jdk-17/bin/java.exe' -Jar target/chemical-lab-backend-0.0.1-SNAPSHOT.jar -RunDirectory $run
```

Desde el frontend, compile la variante colegio y use el mismo runner Chromium del recorrido anterior. La UI está en `/evaluaciones-institucionales`. Para usarla manualmente, sirva la compilación con un proxy de mismo origen a la dirección de `browser/ready.json`. Las cuentas de ese archivo son exclusivamente ficticias.

```powershell
npm run build -- --configuration colegio
node tools/verify-institutional-journey.mjs "$run/browser" 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
```

Desde el backend, después del navegador, ejecute el caso de contrato. Hace comprobaciones HTTP, un reinicio del JAR normal, mantenimiento local de prueba y un fallo controlado de T08. Las operaciones del recorrido preceden a la instrumentación de fallos. Al terminar, cree el marcador `stop` para detener el JAR y el clúster.

```powershell
python tools/verify-runtime-contract.py $run --psql 'C:/Program Files/PostgreSQL/18/bin/psql.exe'
python tools/summarize-institutional-journey.py $run
New-Item -ItemType File "$run/browser/stop"
```

Para comparar, guarde el resumen anterior como `baseline.json` en el directorio de su corrida y haga lo mismo con el actual. Ejecute `tools/compare-runtime-journey.py <corrida-fixture> <corrida-normal>`. Empareja solicitudes completadas por método, patrón de ruta y cantidad común, separa el intervalo de F08 y conserva los totales y recursos medidos. La ventana SQL excluye el arranque, la preparación y las comprobaciones focalizadas posteriores. La CPU/heap/RSS corresponden a los procesos que mide el runner. Carecemos de medición eléctrica.

Materiales, pizarras, pagos, privacidad, despliegue y adopción de las otras superficies siguen fuera de este bloque. La etapa 4 sigue abierta.
