# Perfilado de la ficha docente de pizarra

En la ficha y el listado de participantes, el backend proyecta los metadatos bajo el mismo bloqueo compartido y consulta las admisiones vigentes y los perfiles en una sola consulta. PostgreSQL devuelve un booleano para indicar si existe captura. La descarga conserva su ruta, validación y contenido. Las consultas siguen evaluando el ámbito y la vigencia en cada solicitud.

## Reproducción local

Usa dos checkouts, uno del commit de referencia `5bf578953d623e3f5fc1f75af4ffaa44d272d885` y otro del cambio. Compila el JAR de cada checkout en el mismo equipo con Java 17 y Maven. Prepara directorios nuevos, puertos libres y PostgreSQL 18 con el corredor existente. Ejecuta las dos variantes en serie. Este ejemplo se ejecuta desde el checkout correspondiente y reutiliza los puertos una vez detenida la primera variante.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\mvnw.cmd -DskipTests package
$pg = 'C:/Program Files/PostgreSQL/18/bin'
$java = "$env:JAVA_HOME/bin/java.exe"
$run = Join-Path $PWD 'target/board-profile'
./tools/run-runtime-journey.ps1 -PostgresBin $pg -Java $java `
  -Jar 'target/chemical-lab-backend-0.0.1-SNAPSHOT.jar' -RunDirectory $run `
  -Boards -DatabasePort 55526 -HttpPort 18526
```

Cuando aparezca `Ready`, ejecuta en otra terminal el mismo medidor de la rama optimizada contra cada variante. Se requiere Node 24 o posterior.

```powershell
node <ruta-del-checkout-optimizado>/tools/measure-whiteboard-detail.mjs <directorio-run> <ruta-psql.exe> 55526 <salida.json>
New-Item -ItemType File -Path '<directorio-run>/browser/stop'
```

El medidor prepara identidades ficticias en la base desechable y usa las rutas normales para crear la pizarra, incorporar a 20 estudiantes, cerrarla y descargar la captura. Comprueba la huella del archivo persistido frente a la descarga. Antes de medir calienta los endpoints. Ejecuta cinco ventanas de ocho lecturas por caso, rota el orden e introduce pausas de recarga del límite REST fuera de las ventanas. Registra tiempos de HTTP, bytes de cuerpo, ejecuciones SQL de primer nivel y CPU y memoria residente de los procesos que puede observar en Windows. El total de bytes lógicos seleccionados para la captura se calcula con el tamaño persistido y los SELECT que proyectan el bytea. No incluye la codificación de PostgreSQL, cabeceras ni una captura de red. Los casos de solo lectura no abren WebSocket.

Para comprobar las mismas rutas desde la aplicación, compila el frontend `07a1d0912e0e58c1a6bca446837c0be6fc08e942` con `npm run build:colegio`. Usa una instancia desechable ya preparada con `-Boards` para el recorrido de Edge. Deja el benchmark y el navegador en ventanas separadas.

```powershell
node tools/verify-institutional-journey.mjs <directorio-run>/browser <ruta-msedge.exe> dist/chemical-lab-frontend/browser boards
```

Para verificar el aislamiento, revocación, cuotas y restauración, ejecuta `node tools/verify-board-runtime.mjs <directorio-run> <ruta-psql.exe> 55526` desde el backend sobre otra instancia desechable. Los indicadores de proceso y las transferencias cuantifican recursos. La medición de energía requiere un instrumento o un modelo calibrado y una frontera declarada. [PostgreSQL 18](https://www.postgresql.org/docs/18/runtime-config-logging.html) · [Win32](https://learn.microsoft.com/windows/win32/api/processthreadsapi/nf-processthreadsapi-getprocesstimes) · [Especificación SCI](https://github.com/Green-Software-Foundation/sci/blob/main/SPEC.md).
