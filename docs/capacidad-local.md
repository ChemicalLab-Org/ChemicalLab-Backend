# Capacidad local de ChemicalLab

El ensayo usa una instancia Java y PostgreSQL 18 en Windows, sobre loopback. Se mantienen los contratos de producción instalados en una base desechable, con nombres ficticios y acuerdos y cobros deshabilitados. Los resultados caracterizan esos recorridos y esa máquina. Las 200 plazas del plan son cuentas registradas.

## Cómo reproducir las ventanas

Compilar Java 17 con Maven en un checkout aislado por SHA. Preparar el dossier cerrado con 20 participantes, según [la receta de la medición anterior](medicion-local-emi.md#preparación-y-comandos). Detener esa instancia. Para cada ejecución, usar una copia nueva de su `pgdata`, con el marcador `prepared` y sin `postmaster.pid`. Los puertos deben estar libres. Con Python 3.11+, PowerShell 7 y Node 24, ejecutar desde el backend:

```powershell
$jdk = 'C:/Program Files/Java/jdk-17'
$pg = 'C:/Program Files/PostgreSQL/18/bin'
python -B tools/diagnose-local-capacity.py $fixture $jar $jdk $pg $salida `
  --label $sha --pool 4 --actors 1 2 4 --rounds 18
python -B tools/diagnose-local-capacity.py $otraCopia $jar $jdk $pg $otraSalida `
  --label $sha --pool 8 --actors 4 2 1 --rounds 18
python -B tools/diagnose-local-capacity.py $copiaMixta $jar $jdk $pg $salidaMixta `
  --label $sha --pool 4 --journey tools/measure-mixed-local.mjs --students 30 --rounds 30
```

La ficha usa barreras para lanzar a la vez 1, 2 o 4 docentes distintos, con 18 tandas por escalón y tres lecturas por segundo y docente. Se calienta antes de medir y se compara la huella del cuerpo. Una respuesta incorrecta, un vencimiento o una operación de más de 750 ms detienen la secuencia. El temporizador del cliente es de 2 s en las pruebas. La parada del cliente permite observar durante unos segundos más el trabajo del servidor.

La carga mixta crea una pizarra y un contenido publicado y asignado en cada institución. Abre los canales STOMP, comprueba el acceso cruzado denegado y prepara un intento institucional por institución. Calienta una tanda antes de medir. Cada tanda lanza seis solicitudes HTTP y dos dibujos STOMP. Lee ficha, estado y contenidos de ambos ámbitos, guarda una respuesta por institución y espera cada revisión de todos los suscriptores elegibles. Las parejas de operaciones se programan a 0, 300, 600 y 900 ms dentro de cada tanda de 2 s. A mitad de la ventana, un alumno cierra sesión. La siguiente petición de ese alumno debe recibir HTTP 401, su canal debe cerrarse y la otra institución debe seguir recibiendo eventos. Se cotejan las revisiones, los recibos y las auditorías duraderas. Una operación de más de 750 ms detiene el escalón aunque termine correctamente. La preparación, el calentamiento y la verificación quedan fuera del conteo de la ventana. El 401 de sesión revocada cuenta como éxito cuando cumple su contrato.

El corredor registra intentos nuevos, sin reenvíos ni reanudación automática. Cada ejecución conserva el SHA-256 del JAR y las solicitudes previstas, enviadas, correctas, fallidas, agotadas y sin enviar. Los dibujos se cuentan como una operación de origen y se identifican sus receptores. Los intentos detenidos se conservan por separado de otras ejecuciones.

## Instrumentación y lectura

- `pool.csv` consulta JMX local cada 100 ms aproximadamente. Registra conexiones activas, ociosas y totales, hilos que esperan conexión, hilos Java, CPU acumulada y heap usado. Los atributos se leen por separado y una muestra puede cruzar un cambio de estado. `pool-stacks.log` registra las pilas que esperan Hikari al observar presión. El agente se adjunta con la identidad local del proceso.
- `postgres-samples.jsonl` registra estado, evento de espera, bloqueadores y deadlocks de PostgreSQL. `postgres.log` conserva trazas de las consultas del clúster de ensayo. Se distinguen ejecuciones de `parse` y `bind`, y se excluyen las consultas del observador al atribuir SQL a las conexiones JDBC. La instrumentación añade trabajo al clúster.
- `processes.jsonl` muestrea CPU y memoria residente de Java y de los backends JDBC cada 500 ms. El RSS sumado de PostgreSQL puede contar páginas compartidas varias veces. El heap máximo de Java es un ajuste de la JVM. El RSS incluye memoria residente fuera del heap. `gc.log` registra las pausas con sus timestamps.
- Los perfiles versionados carecen de un tamaño explícito de pool. El JAR incluye HikariCP 7.0.2 con máximo predeterminado de diez conexiones. El ensayo fija mínimo y máximo por corrida, usa heap inicial de 128 MiB, G1 y dos procesadores activos para las políticas de la JVM. El parámetro de procesadores activos no fija una cuota de CPU del proceso. Las opciones de instrumentación del ensayo son locales.

## Fallos que discrimina el ensayo

Con cuatro docentes y un pool de cuatro, cada transacción exterior podía llegar a la revalidación con su conexión todavía activa. `REQUIRES_NEW` solicitaba una segunda conexión. Hikari registró cuatro activas, cero ociosas y cuatro hilos esperando. Las cuatro sesiones PostgreSQL estaban `idle in transaction` en `ClientRead`, sin bloqueadores ni deadlocks. La revalidación de las rutas institucionales normales usa la transacción `READ COMMITTED` que bloquea primero cuenta y después sesión. Las dos comprobaciones leen campos persistentes y evalúan el nombre exacto y la expiración después de obtener el resultado. Si la operación ya tiene los bloqueos, el cierre de sesión espera y la operación todavía puede confirmar. Si la revocación confirma primero, la operación encuentra la sesión inválida al obtener los bloqueos. El transporte sigue validando en su transacción corta independiente.

En pizarra, un alumno que ya se había suscrito podía quedar esperando dibujos después de que se incorporara otro alumno. Con el orden de publicación de Spring activo, el descarte tardío de un aviso reservado al docente podía impedir que se ejecutara la continuación de la cola. El filtro de destinatarios actúa al intentar encolar el mensaje. El hilo de entrega comprueba de nuevo sesión, ámbito, destino, suscripción y audiencia sobre la conexión registrada. La continuación de Spring se ejecuta incluso si se descarta un mensaje después de encolarlo. Así se conserva la entrega de las revisiones posteriores en orden.

La ventana histórica B2 de dos actores, con dos vencimientos de cliente y 34 solicitudes sin enviar, permanece como incidencia sin causa demostrada. La repetición local completó las 36. El timeout HTTP, el límite de adquisición JDBC y los locks de negocio son relojes distintos. La aplicación conserva sus límites.

## Pendientes para el alojamiento

Quedan por medir los picos de inicio de clase, la latencia de red de los colegios, la duración real de clase y la carga con datos de mayor cardinalidad. Hay que contrastar conexiones por réplica con el presupuesto de PostgreSQL, operación del broker y revocación al cruzar instancias, CPU, RSS total y recolección sostenida, respaldos, recuperación y telemetría bajo carga. La frontera histórica EMI/RAPL está documentada por dominio. CPU, SQL y RSS son medidas de recursos y tienen sus propias unidades.
