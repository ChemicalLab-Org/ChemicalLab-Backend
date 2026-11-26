# Medición energética y de capacidad en Windows

La unidad funcional es una respuesta correcta a la ficha docente cerrada con 20 participantes y captura persistida. La referencia es el JAR de `5bf578953d623e3f5fc1f75af4ffaa44d272d885` y el cambio el JAR de `cd28e7832554c75ece2903fadfe1c2dca8b2d5cd`. Los medidores y la documentación posteriores no entran en esos binarios.

## Instrumento y frontera

Primero ejecutar `python tools/windows-emi.py` en el equipo de prueba. El lector enumera la interfaz EMI instalada con PnP y abre los cuatro IOCTL de consulta con acceso de lectura. Verifica nombres, unidades, tiempos y un incremento de energía del paquete. Un acceso denegado desde un sandbox se debe resolver únicamente con una ejecución de lectura autorizada. El código no modifica controladores ni permisos.

El Intel Core i5-10300H expuso un paquete RAPL, los núcleos PP0, la gráfica integrada PP1 y DRAM. Los incrementos son energía que reporta la interfaz del dominio correspondiente, a partir de su contador acumulado. Se mantienen los dominios por separado porque el paquete puede incluir subdominios. La RTX 2060 es un dispositivo distinto y el alcance de este ensayo no incluye su energía, la pantalla, el disco, el cargador ni la toma de corriente del equipo. La resolución nominal del contador tampoco establece la exactitud de un medidor eléctrico externo.

Un incremento de un picovatio-hora equivale a 3,6 × 10⁻⁹ J. Se conserva el incremento bruto. Para explorar el costo atribuible a la carga se resta la energía de reposo ajustada al tiempo de la ventana, usando las marcas del propio contador. Esa resta es una estimación de la parte incremental y puede variar con carga ajena, frecuencia y temperatura. CPU, consultas, memoria y latencia se conservan como indicadores de recursos. El ensayo no produce un valor de carbono ni una intensidad energética de toda la aplicación. [Interfaz EMI de Microsoft](https://learn.microsoft.com/windows-hardware/drivers/powermeter/energy-meter-interface) · [Estructuras y unidades](https://learn.microsoft.com/windows/win32/api/emi/) · [Dominios RAPL en la implementación de Chromium](https://chromium.googlesource.com/chromium/src/%2B/refs/tags/145.0.7632.154/components/power_metrics/energy_metrics_provider_win.cc).

## Preparación y comandos

Construir los dos JAR según [la guía de referencia](perfilado-pizarra.md). Crear una base de ensayo con `run-runtime-journey.ps1 -Boards` en los puertos 55529 y 18529. Para preparar únicamente la pizarra cerrada, ejecutar las instrucciones del medidor original hasta antes de su bloque de ventanas. En una terminal distinta, desde el backend revisado y con `$fixture` apuntando a ese directorio desechable:

```powershell
$source = Get-Content ./tools/measure-whiteboard-detail.mjs -Raw
$boundary = $source.IndexOf('const cases = [', [StringComparison]::Ordinal)
if ($boundary -lt 0) { throw 'Preparation boundary not found' }
$source.Substring(0, $boundary) | Set-Content "$fixture/prepare.mjs" -Encoding utf8NoBOM
node "$fixture/prepare.mjs" $fixture "$pg/psql.exe" 55529 "$fixture/unused.json"
if ($LASTEXITCODE -ne 0) { throw 'Preparation failed' }
New-Item -ItemType File -Path "$fixture/prepared","$fixture/browser/stop"
```

Esperar a que el lanzador anterior termine y la base quede detenida. Desde este backend, con Python 3, los JAR y los binarios locales preparados:

```powershell
python tools/measure-local-capacity.py $fixture $referenceJar $changeJar $jdk $pg $output --energy --pool 8
python tools/summarize-local-capacity.py $output "$output/resumen.json"
```

El corredor se limita a loopback y al clúster preparado. Agrega tres co-docentes ficticios para repartir la carga sin cambiar cuotas, autentica, coteja respuestas e imprime solo el avance agregado. Se recorre A1, B1, A2, B2 con JVM nuevas. Cada fase calienta las mismas lecturas. El punto común de comparación usa cuatro docentes y 72 lecturas en seis segundos, precedidas por seis segundos de reposo con servidor iniciado. B1 y B2 incluyen también escalones de uno y dos docentes. C repite los tres escalones con un máximo de heap de 256 MiB en lugar de 512 MiB. Las JVM usan G1, inicio de 128 MiB y un recuento de dos procesadores activos para sus políticas internas. Este parámetro de la JVM no restringe por sí mismo el tiempo de CPU del proceso.

La cadencia es de tres peticiones por segundo por docente. El corredor detiene ese escalón ante error de respuesta o latencia mayor de 750 ms, y detiene la secuencia ante p95 mayor de 350 ms, respuesta distinta o incompleta o RSS JVM mayor de 750 MiB al muestrear. El cliente aplica un timeout de dos segundos. Las muestras de memoria y CPU bordean el bloque de trabajo y se toman fuera de la ventana EMI. El protocolo local no dimensiona la capacidad productiva ni cubre escrituras, picos, usuarios distribuidos, red de colegios o una sesión completa.

En la ejecución inicial con pool de cuatro, el escalón de cuatro docentes terminó por timeout antes de escribir una fila completa. Se conservó la incidencia por separado. En B2 con pool de ocho, el escalón de cuatro completó sus 72 lecturas. El escalón de dos se abortó con dos timeouts de las 36 lecturas planificadas. Sus 34 peticiones restantes no se emitieron. B3 ejecutó el nivel de dos y el nivel de uno en una fase nueva. C conservó sus propios escalones. El resumen registra la ventana fallida de B2 y reserva la comparación energética para ventanas completas. El corredor no reintenta peticiones dentro de una ventana ni suma las de B3 a la fallida.
