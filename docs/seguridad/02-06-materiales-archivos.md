# T06 — Validación y entrega de materiales y capturas (SEC-10)

Etapa 2 del plan original. Ensayos locales con datos ficticios, 11 de octubre de 2026,
America/Lima. No incluye T07, campaña T10, instituciones, invitaciones, privacidad,
cloud, merge, despliegue, migraciones productivas ni acceso a bases escolares.

## Estado inicial y reproducción

Se verificaron ambos repositorios, ramas, worktrees y remotos, y se consultó `develop`:
backend `3f41fa3bdcae4eb9f8538cc646ea9010173a21c6`, frontend
`9b77b8ad32883e716a287385cd8ad7eb22c0fa9b`. Backend principal limpio en develop;
frontend principal en `feature/validaciones-datos-institucionales`, HEAD
`e88589635a8269c50ee0f53fccf157f0d0985034`, con 16 archivos previos de e2e-selenium.
No se encontraron AGENTS.md aplicables. Se crearon worktrees locales aislados
`.codex-work/t06-backend` y `t06-frontend`, ambos en `security/t06-materiales-archivos`.
No se copiaron cambios ajenos, ni se alteraron auditoría, documentos T01–T05 o la rama institucional.

Antes de conectar el validador se ejecutó
`ConceptMaterialServiceTest#sec10TextoDisfrazadoNoDebePublicarse`: texto inocuo
`Texto ficticio inocuo SEC-10`, MIME application/pdf, nombre ficticio.pdf.
La aserción de rechazo falló porque el servicio lo guardaba: 1 prueba, 1 fallo,
0 errores/omisiones. Evidencia local `t06-reproduction.log`, no versionada.
La misma prueba pasa con T06. No se enviaron exploits ni malware.

## Inventario y permisos

| Operación | Ruta | Autorización conservada |
|---|---|---|
| Subida/reemplazo del único archivo | POST `/api/concepts/teacher/{conceptId}/materials/file`, parte `file`, título opcional | DOCENTE propietario |
| Alta de enlace | POST `/api/concepts/teacher/{conceptId}/materials/link` | DOCENTE propietario; http/https, validación previa conservada |
| Retiro de archivo/enlace | DELETE `/api/concepts/teacher/{conceptId}/materials/{materialId}` | DOCENTE propietario; pareja de IDs |
| Descarga y fuente de vista previa | GET `/api/concepts/{conceptId}/materials/{materialId}/download` | Docente propietario; estudiante con contenido publicado y asignación activa a grado/sección; administrador en lectura |
| Publicación y asignación | Rutas existentes de ConceptContentController, sin nuevas rutas binarias | Reglas previas de propietario/rol/publicación |
| Subida de captura y cierre terminal | POST `/api/whiteboards/teacher/{id}/close`, parte `snapshot` | DOCENTE propietario, ACTIVE/PAUSED; captura íntegra antes de CLOSED |
| Descarga/vista de captura docente | GET `/api/whiteboards/teacher/{id}/snapshot` | DOCENTE propietario |
| Descarga/vista de captura alumno | GET `/api/whiteboards/student/{id}/snapshot` | ESTUDIANTE del grado/sección; sesión CLOSED |

No hay ruta binaria administrativa de capturas, reemplazo de captura cerrada ni endpoint
de vista previa independiente. Los enlaces siguen siendo enlaces externos, no cargas
binarias ni recursos que el servidor descargue. Pizarra JSON, join y STOMP conservan T03.
Las capturas históricas cerradas del grupo mantienen su contrato anterior; no se exige
una participación nueva para leer el historial. La autorización de colaboración en vivo
sigue exigiendo participación/grupo y permiso efectivo según T03.

Todas las rutas mantienen el filtro T02: sin sesión válida/cuenta activa se devuelve 401;
la contraseña temporal restringe negocio con 403. Ninguna ruta pasa a pública ni recibe
credenciales en la URL. Un request que ya pasó el filtro conserva el contrato concurrente T02.

Angular docente usa ConceptMaterialsService para subir/reemplazar/descargar; ConceptsComponent
usa descarga autenticada para alumno y su visor. TeacherWhiteboardService y
StudentWhiteboardService alimentan los visores de captura docente/registro alumno mediante
Blob. Los identificadores se obtienen del contenido/sesión, sin URL pública al almacenamiento.

## Política de formato y contenido

MIME y extensión coherentes siguen siendo una comprobación inicial de subida; no son
prueba del formato. La firma selecciona el parser, pero por sí sola tampoco permite aprobar.
Un proceso aislado valida el archivo entero y devuelve MIME, extensión canónica y bytes
validados. El servicio guarda esos metadatos y el tamaño real del resultado.

| Formato | Validación y restricciones | Almacenamiento y entrega |
|---|---|---|
| PNG | Envelope completo, CRC de todos los chunks, IHDR/IEND, dimensiones previas a raster; codec JDK ImageIO; rechazo de APNG/corrupción/avisos de recuperación | Nuevo raster ARGB y nueva codificación PNG, sin metadatos; inline, solo img en Angular |
| JPEG/JPG | Firma y cierre EOI, codec JDK ImageIO, dimensiones antes de read, rechazo de avisos de recuperación | Nuevo raster RGB y nueva codificación JPEG; elimina EXIF/perfiles/comentarios; extensión .jpg, inline/ img |
| PDF | PDFBox 3.0.8, parser no lenient, EOF, xrefs/objetos y streams recorridos, páginas y tokens de contenido; límites de expansión | Bytes originales, attachment. Se rechaza todo cifrado, incluso contraseña de usuario vacía; acciones (incluidos enlaces), JS, formularios/AcroForm/XFA, archivos adjuntos, objetos externos y multimedia/3D |
| PPT | POI HSLF/POIFS 5.5.1; sectores completos, streams de presentación/Current User, framing y árbol de records, diapositivas y raster embebido | Bytes originales, attachment. Sin cifrado, macros, almacenes desconocidos, OLE/controles, objetos externos, multimedia, acciones/interactividad. Marcador VBAInfo sin macros ni referencia es permitido |
| PPTX | POI XSLF/OPC 5.5.1, presentación OOXML y diapositivas con schema; preflight central/local ZIP y CRC, XML StAX sin DTD/entidades/resolver externo | Bytes originales, attachment. Sin cifrado/macro-enabled/VBA, OLE/ActiveX/embeddings, enlaces/recursos externos, multimedia/acciones. Solo partes XML/rels y PNG/JPEG en media/thumbnail; fuentes embebidas, SVG/EMF/WMF y otros binarios se rechazan |

Los documentos se admiten como documentos estáticos dentro de esta política, no se rasterizan
ni reescriben. Se recorren también objetos PDF no alcanzables por el catálogo para detectar
contenido prohibido. No se extraen rutas del ZIP ni se evalúan scripts, acciones, fórmulas,
recursos externos, renderers Office o estilos XML. XML tiene límites de profundidad/elementos/atributos.
Se rechazará un documento que exceda presupuesto, aunque otro visor tolere/recupere el archivo.
Los JPEG de miniatura también deben estar completos; la plantilla de prueba POI tenía bytes
después del EOI y se sustituyó por una miniatura JPEG real, sin relajar la política.

Esto es **validación estructural y de política**, no análisis antimalware, verificación
de firmas digitales, conformidad PDF/A, certificación de todos los documentos Office,
ni CDR de documentos. No detecta malware desconocido, contenido engañoso, esteganografía
o vulnerabilidades futuras de lectores. Abrir un adjunto en un lector externo requiere
mantener ese lector y el sistema operativo actualizados.

## Límites y aislamiento de procesamiento

| Recurso | Límite |
|---|---|
| Material / captura, bytes efectivos de entrada y salida | 10 MiB / 5 MiB; el borde exacto es permitido |
| Multipart previo | Configuración existente: archivo 10 MiB y request 12 MiB |
| Raster | 8192 por eje y 16.000.000 píxeles por imagen; producto long, previo a decodificar |
| Raster en una presentación | 32.000.000 píxeles acumulados en media/miniaturas comprobadas |
| ZIP PPTX | 512 entradas; 16 MiB por entrada, 40 MiB total expandido, expansión máxima 100:1 por entrada; tamaños reales al leer |
| PDF | 300 páginas, 100.000 nodos/objetos y profundidad 64; streams 16 MiB cada uno / 40 MiB total; 500.000 tokens por página |
| PPT/PPTX | 300 diapositivas; 2000 shapes por slide; PPT 100.000 records y profundidad 64 |
| XML | Profundidad 64, 100.000 elementos por parte, 64 atributos por elemento, 8192 caracteres por valor de atributo |
| PNG | 4096 chunks, sin datos después de IEND |
| Parser JVM | Heap 192 MiB, metaspace 96 MiB, stack 512 KiB; plazo 15 segundos, kill forzoso y espera hasta 2 segundos |
| Admisión | 2 workers por proceso API, sin cola; tercero recibe 503 y puede reintentar |

El API hace lectura efectiva de como máximo límite+1, aunque el proveedor MultipartFile
mienta sobre getSize. El worker recibe un archivo temporal de nombre generado, sin
credenciales/configuración del entorno; stdout/stderr se descartan. Solo escribe su
resultado local acotado. El padre comprueba también el tamaño y tipo del resultado.
El bloqueo/plazo es del proceso, no de un Future cuyo parser pueda continuar consumiendo
CPU después de timeout. Los slots se liberan en finally, también por interrupción/fallo.

Los temporales se retiran al terminar; si el sistema operativo impide borrarlos se registra
su borrado al salir de la JVM. No constituyen almacenamiento publicado. Un cierre abrupto
del host puede dejar temporales, por lo que el operador debe limpiar el directorio temporal
de su usuario de servicio después de reinicios y supervisar espacio/permisos.
No existe aislamiento OS de red/syscalls/usuario distinto: el subprocess contiene
memoria/tiempo, no es una sandbox contra ejecución de una vulnerabilidad del parser.
Los límites JVM no son un límite RSS: sumar API, dos heaps/metaspaces, buffers y memoria
nativa al presupuesto del contenedor. No se midió carga sostenida ni capacidad productiva.

## Reemplazo, históricos y entrega

La validación completa precede a consultar/eliminar el archivo anterior o mutar CLOSED.
Una carga rechazada no inserta filas ni bytes, no elimina el material anterior ni publica
control de cierre. Los cambios de reemplazo permanecen en la transacción existente;
se probó rollback por fallo posterior con PostgreSQL real.

**Históricos:** se revalidan todos los bytes en cada descarga autorizada, sin confiar en
MIME/nombre/tamaño previos y sin un marcador de confianza por metadatos. Si pasan,
documentos salen como adjuntos e imágenes se normalizan en memoria para esa respuesta.
El MIME/extension de descarga se deriva del resultado. No se escribe esa normalización
en la base, no se borran ni se migran datos masivamente. Un histórico falso/corrupto o
fuera de política queda retenido y su descarga responde 400. El docente puede reemplazar
un material; una captura CLOSED sigue terminal y su eventual recuperación es operativa,
no una ruta nueva T06. Los listados conservan metadata previa: no certifican el archivo
y podrían mostrar una opción de imagen cuyo download resulte rechazado.

Las respuestas comparten Content-Type real, Content-Length real, Content-Disposition
UTF-8 con nombre saneado/canónico, `X-Content-Type-Options: nosniff`,
`Cache-Control: no-store, private`, `Content-Security-Policy: sandbox; default-src 'none';
frame-ancestors 'none'`, `X-Frame-Options: DENY`. Solo imágenes son inline; PDF/PPT/PPTX
attachment, independientemente de flags de metadata.

El Blob/objectURL no hereda esas cabeceras. Angular elimina los iframes PDF y
bypassSecurityTrustResourceUrl: solo un PNG/JPEG retornado por la API entra en img,
también en pantalla completa. Una respuesta de documento nunca se promueve a visor por
previewAvailable obsoleto. Capturas comprueban el tipo del Blob antes de crear objectURL.
Nombres de descarga se sanean también en cliente y su extensión se deriva del MIME
de la respuesta, no del nombre histórico. Se revoca el objectURL al cerrar/cambiar/destruir;
una respuesta de preview llegada después del cierre no crea una URL nueva. El documento
se descarga mediante petición Bearer del interceptor y anchor download; no hay token en URL.

## Validación ejecutada y compatibilidad

La matriz reproduce y prueba los cinco formatos, texto/firma/MIME/extensión falsos,
vacíos/corrupción/truncamiento, 10/5 MiB exactos y exceso, dimensiones/píxeles y desbordamiento,
CRC/APNG, cifrado PDF/PPT, macros PPT y OLE, acciones/adjuntos/forms PDF, expansión PDF/ZIP,
DTD/XXE y relaciones externas. Incluye ZIP al borde de 16/40 MiB y 512 entradas,
procesos atascados/interrumpidos/saturación y rollback/reemplazo real.

FileDeliveryHttpDbTest recorre por HTTP real/JPA roles, propiedad, IDs cruzados,
publicación/asignación, históricos sin escritura, cabeceras, captura rechazada/aceptada,
cuenta inactiva, logout/revocación y contraseña temporal. Los arneses T02–T05 se reutilizan
sin cambiar sus reglas. Las fixtures antiguas de ConceptMaterialServiceTest,
WhiteboardSessionServiceTest y AccountSessionsDbTest se sustituyen por formatos reales.

Resultados finales ejecutados sobre los cambios T06:

| Comprobación | Resultado |
|---|---|
| Backend `mvn clean package`, perfil test, PostgreSQL local desechable | **530 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS**. Incluye regresiones T01–T05 y transporte/colaboración de pizarra T03 |
| Matriz de formatos y presupuestos `FileValidationTest` | 22 pruebas aprobadas, incluidas dentro de las 530 |
| Plazo, interrupción y admisión `FileWorkerLimitsTest` | 2 pruebas aprobadas, incluidas dentro de las 530 |
| Entrega/autorización/rollback HTTP y PostgreSQL `FileDeliveryHttpDbTest` | 6 pruebas aprobadas, incluidas dentro de las 530 |
| JAR ejecutable reempaquetado | `PackagedFileProbe` mediante Boot PropertiesLauncher aprobó PDF y PNG; el worker se ejecutó desde el JAR, sin arrancar Spring ni acceder a base |
| Frontend suite completa con `T03_LIVE_FIXTURE_JSON` | **136 pruebas, 0 fallos, 0 omitidas**, 11 archivos; incluye 7 pruebas nuevas T06 y las 2 optativas STOMP con servidor real |
| Frontend build production / colegio | Ambos aprobados. Avisos SCSS/presupuesto y polyfills existentes; no errores |
| Selenium, Microsoft Edge 155, docente/alumno | PDF real subido; reemplazo falso rechazado conservando el PDF; alumno descargó bytes PDF; PNG reemplazado y visualizado en img normal y pantalla completa con Blob, sin iframe/embed/object ni token en URL |
| Preservación | Auditoría y documentos anteriores sin cambios; HEAD/rama institucional y hashes de los 16 archivos e2e-selenium intactos |

Para repetir backend, usar el Maven wrapper o Maven 3.9 con JDK 17 y `clean package`,
perfil `test`, URL/usuario de una base **desechable** permitida por los guardas existentes.
No usar una base escolar: los arneses crean y retiran sus propios schemas de prueba.
Para frontend, `npm ci --legacy-peer-deps`, `npm test -- --watch=false`,
`npm run build -- --configuration production` y `npm run build -- --configuration colegio`.
Sin el servidor `WhiteboardBrowserFixture` y `T03_LIVE_FIXTURE_JSON`, las dos pruebas
STOMP optativas se omiten; aquí se ejecutaron también con ese servidor.

`FileBrowserFixture` es optativo (`-Dtest=FileBrowserFixture`), con propiedades
`t06.fixture-file` y `t06.stop-file`, puerto HTTP de ensayo y CORS para el origen Angular.
Genera archivos/usuarios ficticios y espera la marca de parada para limpiar su schema.
El recorrido Selenium reutilizó los helpers previos de e2e-selenium sin modificar ninguno
de sus 16 archivos; su script, resultados, screenshots, credenciales de arnés y logs
permanecen locales. Se ajustaron CORS y esperas del arnés hasta completar el recorrido;
un intento fallido del arnés no se cuenta como comprobación aprobada.

La extensión JPG se normaliza a .jpg, tamaño/bytes de imágenes cambian y JPEG puede
tener pérdida por nueva codificación. PDF deja de tener preview integrado. Documentos
con formularios, hipervínculos/acciones, recursos externos, medios o gráficos vectoriales
Office deberán exportarse a un PDF estático dentro de política o una presentación sin
esos elementos. No se modifica el contenido textual/publicación/asignaciones existentes.

## Pendientes operativos y fuentes

Validar permisos de java y temporales y presupuesto real del contenedor antes de desplegar;
configurar y mantener el usuario de servicio, sistema/JDK/parsers y lectores de adjuntos.
Medir latencia de revalidación y concurrencia en la etapa correspondiente. Un servicio
antimalware/cuarentena duradera, sandbox OS o CDR documental serían trabajo adicional
explícito; no se presentan como protección existente. La actualización general de
dependencias sigue siendo T09; npm ci informó avisos del árbol previo y no se ejecutó
una actualización automática ni se certificó su ausencia de CVE.

Fuentes primarias consultadas para seleccionar los parsers y sus límites:
[PDFBox releases](https://pdfbox.apache.org/download.html),
[PDFBox migration](https://pdfbox.apache.org/3.0/migration.html),
[POI releases](https://poi.apache.org/download.html),
[POI guidance for untrusted files](https://poi.apache.org/security.html).
Las garantías descritas se sustentan además en el código y los ensayos locales.
