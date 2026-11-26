# Pizarras institucionales en la aplicación normal

`board_runtime` v1 se instala sobre `evaluation_runtime` v1 y `content_runtime` v1. El JAR normal usa la sesión T02 y el mismo selector Angular de institución y clase que las evaluaciones. Cada pizarra nativa fija su institución, clase, autor y membresía de origen. La participación registra el episodio de matrícula que autorizó la incorporación.

## Instalación, ampliación y restauración

- En una instalación nueva, prepara Hibernate y aplica T07-B y T08. Aplica `db/runtime/evaluation-v1.sql`, `content-v1.sql` y `board-v1.sql` por separado, en ese orden. Arranca el JAR con `ddl-auto=validate`.
- Para ampliar J+K, detén el JAR anterior y cualquier escritor, conserva un respaldo completo y aplica exclusivamente `db/runtime/board-v1.sql`. Arranca el JAR de pizarras. El SQL toma locks sobre las tres tablas de pizarra y rechaza la instalación íntegra si encuentra una pizarra, participante, identidad histórica o fixture certificado. Conserva la fuente y deja cualquier importación sujeta al mapa por agregado y las aprobaciones F08.
- La instalación vincula el motor persistente T03 a la nueva autoridad mediante FKs y guardas de contexto, comando y auditoría. El JAR adoptado bloquea las rutas HTTP legacy de pizarras y el CONNECT sin ámbito de pizarras. El SQL rechaza también las escrituras de un binario anterior. Mantén detenido ese binario mientras la base esté adoptada, ya que sus lectores desconocen el contrato. Para volver a J+K restaura el respaldo previo al corte.
- Respalda y restaura en conjunto J, K, el esquema de pizarras, las tres tablas del motor T03 y T08. Arranca el JAR correspondiente una vez restaurado el conjunto. En el corredor local, `browser/restart` reinicia sobre la misma base y `browser/recover` hace un dump y arranca sobre su restauración desechable.

## Facultades y datos

Un responsable o co-docente vigente puede crear y gestionar las pizarras de sus clases. Su docencia se verifica en cada operación. La creación fija el autor original y abre la sesión. El cierre es terminal y exige una imagen T06 válida y la revisión del lienzo que el docente capturó. Si se confirmó otro dibujo durante la captura, el servidor devuelve conflicto y el docente recupera el estado antes de repetir.

El alumno con matrícula vigente descubre pizarras activas o pausadas de su clase. La incorporación explícita habilita la observación en vivo y vincula el historial y la captura al episodio vigente. El permiso de dibujo combina el estado activo, la regla global y la excepción individual. La baja o el fin del episodio retira acceso y excluye al participante de los listados y conteos actuales. La admisión histórica queda conservada. La incorporación de un episodio posterior necesita su propia política de readmisión. El rol `ADMIN_INSTITUCION` por sí solo no concede docencia.

La API usa `/api/institutions/{institución}/classes/{clase}/whiteboards/v1/{teacher|student}`. Las consultas usan páginas de metadata, con cargas separadas para estado y captura. Las escrituras HTTP llevan `Idempotency-Key` UUID. La misma clave de una escritura confirmada exige el mismo actor, ámbito, acción y huella de la solicitud. Una incorporación que ya existe devuelve el permiso actual sin escribir otra fila. Los objetos conservan su autor, incluso si un docente los edita o los elimina. Los tombstones mantienen las identidades reservadas. El alumnado puede actuar sobre sus propios objetos y los docentes conservan las facultades del motor T03.

El negocio, los deltas de filas y bytes, el recibo y el SUCCESS T08 confirman en una transacción. Las capturas cobran sus bytes reales una vez más sus metadatos, junto con la proyección, autores, tombstones y recibos. Se conserva la cuota T07-B del motor. El fallo de auditoría o cuota revierte también estado y revisión. Los errores de transporte conservan el registro acotado de rechazos T08. Las lecturas y agregados administrativos globales legacy omiten las pizarras adoptadas. El operador autorizado conserva las filas de auditoría en PostgreSQL.

## Colaboración y alcance del broker

El CONNECT de SockJS/STOMP en `/ws` lleva el token T02 y `board-scope` con valor `institución/clase/pizarra/teacher|student`. La conexión queda fijada a esa pizarra. La suscripción usa `/topic/institutions/{institución}/classes/{clase}/whiteboards/v1/{pizarra}` y la cola privada `/user/queue/whiteboard-errors`. SEND usa el mismo ámbito bajo `/app` con `/draw` o `/ready`. El segundo confirma el alta de las dos suscripciones antes de cargar el estado. Los eventos de participantes se limitan al docente y, para el permiso individual, al alumno afectado.

El servidor revalida sesión y ámbito en la recepción, en el hilo de entrega y en el watchdog de conexiones pasivas. El controlador vuelve a validar la escritura en su transacción bajo los locks T02, de institución y de pizarra. La revocación local usa el código 4003 para el ámbito afectado y la pérdida de sesión global usa 4001. Una entrega ya autorizada que se encuentre en curso puede terminar mientras se confirma la revocación. El watchdog conserva sus plazos T03 y el transporte conserva los límites y latidos configurados. La UI no envía presencia periódica en el recorrido institucional.

El broker simple funciona en una instancia, en memoria. La emisión ocurre después del commit, puede perderse tras un commit o una desconexión y no constituye un historial durable del transporte. El cliente usa revisiones, descarta duplicados y recupera la proyección confirmada después de suscribirse de nuevo. Repetir el mismo UUID de dibujo confirmado conserva una sola revisión aplicada. En esa repetición la aplicación recupera por HTTP si perdió la confirmación. Los gestos sin confirmar se descartan al salir de una pizarra y la navegación no los reenvía a otra. La autorización de entrega se comprueba para cada destinatario.

## Recorrido manual local

En el checkout backend, con Java 17, Maven offline y PostgreSQL local:

```powershell
mvn -o -DskipTests package
$run = 'C:/ruta/escribible/pizarras-fresh'
./tools/run-runtime-journey.ps1 -Boards -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -Java 'C:/Program Files/Java/jdk-17/bin/java.exe' -Jar target/chemical-lab-backend-0.0.1-SNAPSHOT.jar -RunDirectory $run
```

Desde el checkout Angular, en otro proceso, usa el origen de `browser/ready.json`:

```powershell
$ready = Get-Content "$run/browser/ready.json" -Raw | ConvertFrom-Json
@{ '/api/**' = @{ target = $ready.origin; secure = $false; changeOrigin = $true }
   '/ws/**'  = @{ target = $ready.origin; secure = $false; changeOrigin = $true; ws = $true } } |
  ConvertTo-Json -Depth 4 | Set-Content "$run/angular-proxy.json" -Encoding utf8NoBOM
npm run ng -- serve --build-target chemical-lab-frontend:build:colegio --host 127.0.0.1 --proxy-config "$run/angular-proxy.json"
```

1. Inicia sesión como `teacherA`. Abre **Pizarras** desde el menú y selecciona Fictitious A y CHEM. Usa **Crear pizarra** y **Crear y abrir**. Haz lo mismo como `teacherB` en Fictitious B.
2. En una sesión Angular separada usa `student`. Abre la pizarra de A. Elige una herramienta de dibujo en el docente y comprueba el resultado en el alumno. Activa la interacción global en el docente. Dibuja con el alumno y comprueba el trazo en el docente. Cambia el permiso individual y comprueba que el alumno pasa a modo de visualización.
3. Cambia al colegio B en el selector, sin salir de la página de pizarras. Abre la pizarra de B. Cambia otra vez a A y comprueba la recuperación sin duplicados. Interrumpe temporalmente la conexión de esa pestaña y vuelve a conectarla. Pausa y reanuda la sesión desde el docente.
4. Finaliza en el docente y genera la captura. Abre el historial o **Ver captura final** como alumno. Descarga el mismo archivo desde la vista previa. Cambia de institución o clase para retirar la vista previa y su URL.
5. Crea `$run/browser/stop` para detener el JAR y el PostgreSQL desechable.

Para repetir con Edge y medir solicitudes, frames, bytes y uso de recursos local, compila Angular con `npm run build -- --configuration colegio` y ejecuta:

```powershell
node tools/verify-institutional-journey.mjs "$run/browser" 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' dist/chemical-lab-frontend/browser boards
# Desde el backend y, para aislar las cuentas, después del navegador
node tools/verify-board-runtime.mjs $run 'C:/Program Files/PostgreSQL/18/bin/psql.exe' 55472
```

Para la ampliación usa otro directorio y otros puertos. Añade `-PredecessorJar` con el JAR J+K de la base local. Antes del corte, ejecuta el recorrido Angular de evaluaciones y el de contenidos para cerrar al menos una nota y publicar un material con bytes. Luego, desde el backend, ejecuta `node tools/verify-board-upgrade.mjs $run 'C:/Program Files/PostgreSQL/18/bin' <puerto>`. El runner detiene escritores, aplica L y compara huellas antes de operar con las pizarras.

Invitaciones, traslados, pagos, acuerdos de privacidad, migración real y despliegue quedan fuera del recorrido. La etapa 4 sigue abierta.
