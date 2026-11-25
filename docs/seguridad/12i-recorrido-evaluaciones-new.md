# Recorrido institucional de evaluaciones NEW

El recorrido local usa HTTP y el motor de intentos persistentes F. El catálogo descubre ámbitos y filas que el servidor vuelve a comprobar en cada comando. Los endpoints G publican revisiones inmutables, H vincula matrículas, F08 aprueba el contexto y F ejecuta la entrega. El ledger F09 sigue compartido por cuenta y raíz en todas sus publicaciones y clases. La corrección sigue sujeta a la política de cierre T05 de toda la raíz.

La ejecución con dos instituciones ficticias usa las guardas existentes de A–H. Requiere el perfil único `test`, una base PostgreSQL desechable y una cadena certificada hasta H. El arnés instala y siembra ese entorno. El frontend consume las rutas versionadas de G/H/F. Las rutas LEGACY y la simulación B8 conservan sus clientes y sus motores. La transición a operación institucional fuera del entorno ficticio requiere otro contrato persistente, coordinación con los escritores y los requisitos operativos de T11-D.

## Catálogo

`GET /api/institutions/evaluation-catalog/NEW/v1/classes` devuelve pares institución/clase con los modos disponibles según la matrícula o asignación docente vigente. El ámbito para las otras consultas está en `/api/institutions/{institutionId}/classes/{classId}/evaluation-catalog/NEW/v1`.

| Sufijo | Proyección |
| --- | --- |
| `/own-deliveries` | Título, IDs para abrir F, ventana y disponibilidad de intentos. Incluye el acceso a intentos previos de un episodio que sigue vigente. |
| `/own-deliveries/{deliveryId}/attempts` | Historial propio exacto, para recuperar o consultar por F. |
| `/revisions` | Resumen de autoría y permisos independientes para editar, publicar o asignar. |
| `/enrollments` | Episodios vigentes de la clase para asignar bajo H. |
| `/reviewable-attempts` | Intentos enviados revisables por el docente asignado. F sigue resolviendo la revisión, la nota y el cierre. |

Todas son lecturas con `Cache-Control: no-store`. Cada página usa `after` (0 inicial) y `limit` (20 por defecto, máximo 50). `nextAfter` identifica la última fila visible de la página cuando se encontró otra fila autorizada. Los IDs son selectores y cada petición recalcula la facultad. El cursor corresponde al ámbito elegido. Las páginas carecen de totales y se calculan de nuevo sobre el estado vigente. El examen de candidatos se limita a 200 por petición. Ante un exceso que impide establecer la página autorizada se devuelve 503 sin cursor parcial. La guarda de escrituras, la coordinación y la medición histórica de B10 se mantienen.

## Ejecución local en Windows

Se requiere PostgreSQL 18, Java 17, Maven con dependencias locales, Node.js compatible con el frontend y Chrome o Edge. Desde el backend, inicia la base ficticia y el servidor HTTP en una ruta nueva.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$run = 'C:/ruta/escribible/prueba-t12'
./tools/run-institutional-journey.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -Maven 'C:/ruta/a/mvn.cmd' -RunDirectory $run
```

El arnés escribe `browser/ready.json` con la URL de loopback y las cuentas ficticias. Si se interrumpe la comprobación, crear `browser/stop` permite cerrar el arnés. El script detiene el clúster al terminar y conserva los artefactos de ejecución. El período histórico que reutiliza el arnés termina el 1 de enero de 2027.

Desde el frontend, usa las dependencias del lockfile y genera la variante colegio. El runner publica esa compilación por loopback, bloquea conexiones web externas en Chromium y ejerce las pantallas reales con tres sesiones independientes.

```powershell
npm run build -- --configuration colegio
node tools/verify-institutional-journey.mjs "$run/browser" 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
```

Para la navegación manual se puede servir la compilación o usar el proxy de desarrollo con `/api` apuntando a `ready.json.origin` y conservando el host del navegador. Tras iniciar sesión, abre `/evaluaciones-institucionales`. Selecciona la institución y la clase. Crea un borrador, añade una pregunta abierta, guárdalo, publica y asigna un episodio vigente con inicio y vencimiento. El alumno comparte las dos instituciones. Una entrega recién asignada queda pendiente de F08.

### Conciliación F08 para cada entrega

El runner de navegador se detiene tras crear ambas asignaciones y escribe sus IDs en `browser/assignments.json`. Abre las dos sesiones de aprobación antes de proponer. Así los eventos de autenticación ya forman parte de la fotografía cuando se registra el checksum. Conserva los tokens ficticios en variables de la terminal. Revisa que el mapa coincida con las filas exactas de la entrega y del episodio. Cada aprobador usa su sesión y el checksum revisado. Ejecuta toda la secuencia para una evaluación antes de proponer la siguiente, con el runner en pausa.

```powershell
$fixture = "$run/browser"
$identidades = Get-Content "$fixture/ready.json" -Raw | ConvertFrom-Json
$asignaciones = Get-Content "$fixture/assignments.json" -Raw | ConvertFrom-Json
$plataforma = ./tools/reconcile-institutional-journey.ps1 -FixtureDirectory $fixture -Action authenticate -Approver $identidades.platform
$colegio = ./tools/reconcile-institutional-journey.ps1 -FixtureDirectory $fixture -Action authenticate -Approver $identidades.school
$propuesta = ./tools/reconcile-institutional-journey.ps1 -FixtureDirectory $fixture -Action propose -Evaluation $asignaciones.a.evaluation | ConvertFrom-Json
$propuesta.mapping | ConvertTo-Json -Depth 20
```

Tras revisar el mapa, registra las dos decisiones de forma individual y aplica la propuesta. Para el colegio B, repite desde `propose` usando `$asignaciones.b.evaluation`.

```powershell
./tools/reconcile-institutional-journey.ps1 -FixtureDirectory $fixture -Action approve -Proposal $propuesta.proposal -ApprovalRole PLATFORM -SessionToken $plataforma -Checksum $propuesta.checksum -Evidence FIXTURE-PLATFORM-REVIEW
./tools/reconcile-institutional-journey.ps1 -FixtureDirectory $fixture -Action approve -Proposal $propuesta.proposal -ApprovalRole INSTITUTION -SessionToken $colegio -Checksum $propuesta.checksum -Evidence FIXTURE-SCHOOL-REVIEW
./tools/reconcile-institutional-journey.ps1 -FixtureDirectory $fixture -Action apply -Proposal $propuesta.proposal
```

Tras obtener `APPLIED` para ambas, crea el marcador que autoriza al runner a continuar. El marcador solo coordina el ensayo. El catálogo y F comprueban las cabezas PostgreSQL con independencia del marcador.

```powershell
New-Item -ItemType File "$fixture/reconciled"
```

El runner abre la entrega en A, guarda, cambia a B, vuelve a A, recupera y envía. El docente califica y cierra. El alumno verifica el bloqueo de corrección hasta el vencimiento global. La consulta de corrección comprueba que la nota se expone después del cierre mientras el criterio privado permanece reservado al docente.

### Evidencia y límites

Para repetir el ensayo focalizado de catálogo y rollback T08, ejecuta el script del clúster con un directorio nuevo, `-QuietSql` y `-Selection 'InstitutionalEvaluationCatalogDbTest,NewEvaluationExecutionDbTest#mandatoryAuditFailureRollsBackConsumptionAnswersGradesAndClosure'`.

El runner escribe `browser-metrics.json` con tiempos y bytes observados por Chromium. Tras el recorrido, ejecuta `python tools/summarize-institutional-journey.py <directorio-del-run>` con el registro SQL habilitado. El resumen cuenta sentencias de primer nivel, incluso las de control transaccional. Los tiempos SQL se agregan por comando. Incluyen el efecto de la instrumentación y excluyen las sentencias anidadas de las funciones. Los tiempos del recorrido contienen la revisión F08 y la espera del vencimiento. Esta línea base describe solicitudes, volumen y tiempos del equipo local. El entorno carece de medición eléctrica.

Siguen pendientes las superficies LEGACY transversales, materiales, archivos, pizarras y STOMP, exportaciones, jobs, cachés y consultas de actividad y auditoría. También quedan la operación con responsables y datos reales, privacidad y despliegue. La etapa 4 sigue abierta.
