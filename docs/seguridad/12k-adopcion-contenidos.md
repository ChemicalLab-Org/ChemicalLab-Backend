# Contenidos institucionales en la aplicación normal

El contrato `content_runtime` v1 usa las cuentas T02 y las instituciones, períodos, clases, membresías, roles, docencia, episodios, coordinación y cuotas de `evaluation_runtime` v1. Las rutas versionadas funcionan en el JAR normal con Angular, perfil `dev` y PostgreSQL local desechable. La aplicación reconoce la instalación al reiniciar.

## Instalación y recuperación

- En una instalación nueva, prepara las tablas base con Hibernate, aplica los prerrequisitos T07-B y T08 y luego `db/runtime/evaluation-v1.sql`. Aplica `db/runtime/content-v1.sql` en su propia transacción y arranca con `ddl-auto=validate`. El runner automatiza la preparación desechable.
- En una instalación J poblada, detén escritores, conserva un respaldo y aplica únicamente `db/runtime/content-v1.sql`. El SQL exige el contrato J, conserva sus filas y saldos y crea un esquema propio. Reinicia con el JAR que entiende ambos contratos. Las rutas de ensayos conservan sus guardas.
- El instalador rechaza tablas base de contenidos pobladas y los fixtures certificados. Ante ese rechazo la fuente conserva sus filas y bytes. Una importación futura necesita el mapa por agregado y las aprobaciones F08 de plataforma e institución. Queda fuera de este recorrido.
- Restaura un respaldo completo que incluya las relaciones de J, ambos esquemas, T08 y sus recibos. Reinicia con el binario correspondiente. El runner puede detener el JAR, generar un dump, restaurarlo a otra base del clúster ficticio y continuar por el mismo origen HTTP. La extensión usa los mismos locks de institución del procedimiento J para mantenimiento activo.

## Facultades e historia

Cada raíz tiene autoría y clase de origen inmutables. Un docente con docencia vigente puede crear en esa clase. La autoría da edición y publicación desde la clase de origen mientras siga vigente su docencia. Un grant institucional de biblioteca da exclusivamente la facultad indicada a un DOCENTE vigente. `ASSIGN` exige docencia efectiva en la clase destino, además de la autoría o el grant. El selector incluye las clases con matrícula o docencia y la clase de origen de un contenido con delegación vigente de edición o publicación. Un administrador institucional otorga o revoca grants mediante el procedimiento local de mantenimiento de J y `content_runtime.faculties`.

La asignación guarda la publicación exacta y admite en una sola operación las matrículas vigentes de la clase. El límite es de 1 a 500 destinatarios. Una matrícula posterior o un episodio nuevo requiere una decisión de admisión posterior, fuera de este recorrido. Una baja, suspensión o revocación efectiva retira el acceso sin borrar la asignación. Una revisión sucesora copia el texto, enlaces y referencia al archivo. Asignar esa revisión crea otra entrega y conserva la anterior.

El borrador admite explicación, resumen, pasos, puntos clave, ejemplos, actividad, diez enlaces HTTP(S) y un archivo. El servidor valida enlaces sin acceder a la red. Para publicar se exige material o contenido. El archivo nativo pasa por el validador T06 y conserva el resultado validado en PostgreSQL. Los PNG/JPEG se normalizan y admiten vista previa; los PDF/PPT/PPTX salen como adjuntos. El archivo de una publicación es inmutable y puede ser compartido por su borrador sucesor. Reemplazar o retirar un archivo de borrador libera sus bytes si ninguna revisión lo referencia. Un archivo rechazado conserva la referencia, los bytes y la versión anteriores. Toda consulta de archivo cruza la revisión exacta con la institución y con la autorización docente o la entrega del alumno. El mismo control cubre el detalle, los enlaces y la cantidad de materiales.

Cada escritura exige UUID, revalida al actor y coordina por institución, UUID y raíz. Para repetir se verifica el actor, el ámbito, la facultad vigente y la huella de la solicitud normalizada. La versión impide editar sobre otro cambio. PostgreSQL confirma negocio, bytes, historia, cupo y recibo junto al SUCCESS de T08. Las filas binarias contabilizan longitud real y metadatos. Una referencia a un archivo existente conserva el mismo cargo de bytes. Las identidades, entregas y recibos confirmados se conservan.

En una base adoptada, el backend bloquea `/api/concepts/**`, incluyendo la descarga directa y los conteos de materiales, y las lecturas globales de supervisión académica. El resumen y la actividad administrativa generales omiten los contenidos del módulo anterior. PostgreSQL sella sus seis tablas base contra escrituras y truncado. En una instalación anterior, los recorridos legacy siguen sus reglas propias.

## Recorrido manual en Angular

Desde el backend y con Maven, Java 17 y PostgreSQL 18 locales:

```powershell
mvn -o -DskipTests package
$run = 'C:/ruta/escribible/contenidos-fresh'
./tools/run-runtime-journey.ps1 -Content -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -Java 'C:/Program Files/Java/jdk-17/bin/java.exe' -Jar target/chemical-lab-backend-0.0.1-SNAPSHOT.jar -RunDirectory $run
```

En el frontend usa `npm run build -- --configuration colegio`. Sirve Angular y reenvía `/api` al origen de `browser/ready.json` con un proxy de mismo origen. El runner de navegador reutiliza ese proxy con Edge local. El archivo `ready.json` contiene solo cuentas ficticias.

1. Inicia sesión como `teacherA`. En **Contenidos conceptuales** selecciona Fictitious A y la clase CHEM. Crea un borrador, completa título, categoría y explicación y agrega un enlace. Guarda el borrador y sube un PNG. Intenta reemplazarlo por texto con extensión PNG. Comprueba que el archivo anterior sigue disponible. Publica la revisión y asígnala a la clase.
2. Como `teacherB`, crea y asigna otro contenido en Fictitious B. Como `student`, abre **Conceptos químicos**, selecciona A y CHEM y consulta el primer contenido. Abre la vista previa y descarga el PNG. Cambia a B y CHEM. Comprueba que solo queda el contenido de B y que la vista previa anterior desaparece.
3. Como `teacherA`, crea una revisión siguiente y cambia texto y archivo. Publica y asigna esa revisión. El alumno puede seguir consultando la revisión anterior con sus enlaces y archivo originales.

```powershell
# Desde el frontend
node tools/verify-institutional-journey.mjs "$run/browser" 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' dist/chemical-lab-frontend/browser contents
# Desde el backend, después del navegador
node tools/verify-content-runtime.mjs $run 'C:/Program Files/PostgreSQL/18/bin/psql.exe' 55472 fresh
New-Item -ItemType File "$run/browser/stop"
```

Para la ampliación usa otro directorio y otros puertos, omite `-Content` y pasa `-PredecessorJar` con el JAR J verificado. Ejecuta el runner Angular de evaluaciones con los mismos argumentos, omitiendo `contents`. Escribe una nota cerrada en la instalación previa. Luego ejecuta `verify-content-runtime.mjs` con el puerto PostgreSQL de esa corrida y el modo `upgrade`. Este modo dispara la instalación con escritores detenidos y compara las huellas antes de continuar con contenidos.

Las listas usan cursor y devuelven metadata sin bytes. El detalle se carga al abrir un contenido y el archivo al pedir vista previa o descarga. La vista previa y su descarga comparten un Blob mientras la vista permanece abierta. Cambiar el contexto destruye la vista anterior, cancela solicitudes pendientes y revoca su URL. El runner CDP mide la transferencia reutilizada y la línea base con una descarga separada después de cerrar la vista previa. Los resultados son solicitudes, bytes y tiempos locales.

Pizarras, invitaciones, pagos, administración institucional, privacidad, migración real y despliegue permanecen fuera de alcance. La etapa 4 continúa abierta.
