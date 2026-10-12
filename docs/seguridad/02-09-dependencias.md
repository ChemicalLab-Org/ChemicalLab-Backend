# T09 — Dependencias / VER-01 — etapa 2

Fecha del análisis: **2026-10-12, America/Lima**. Base backend: `b31b637dd25bc48abe964ee5e1d92f3144463b69`. Base frontend coordinada: `b9d6b97877ef64c4cdc90c3e7f432dc0aeb457a5`. Trabajo en checkouts aislados, rama `security/t09-dependencias`, PR hacia `develop`. **VER-01 queda parcialmente corregido y pendiente de cobertura operativa**, no cerrado: siguen riesgos de un servidor auxiliar Jetty y falta la imagen real. No se acepta ningún riesgo alto en nombre del usuario.

Se leyeron la auditoría VER-01/T09, las notas T01–T08 y el cierre T08. No se modificó código de producción fuera del POM. Se conserva autenticación/JJWT, migración BCrypt–Argon2, sesiones, STOMP/revocación/reconciliación, evaluaciones, validadores hijos, consultas/cuotas, retención desactivada y auditoría transaccional. No hay merge, despliegue, DDL real, acceso a bases escolares, rotaciones ni T10.

## Inventario: declaración, resolución, artefacto y observación

El POM declara versiones o delega en el parent/BOM; el árbol resuelto fija coordenadas y ámbitos Maven; el JAR confirma qué bibliotecas entraron en `BOOT-INF/lib`. Estos datos describen **artefactos locales de esta revisión**, no el despliegue real. Los inventarios están en [t09/maven-inventory.csv](t09/maven-inventory.csv) y los SBOM CycloneDX en [t09/](t09/).

| Familia | Resolución base | Resolución final | Decisión |
|---|---|---|---|
| Spring Boot parent/starter | 4.0.6 | 4.0.8 | Parche de la misma línea; conserva BOM |
| Spring Framework / Security | 7.0.7 / 7.0.5 | 7.0.9 / 7.0.7 | Gestionados por Boot; no versiones individuales |
| Spring Data JPA | 4.0.6 | 4.0.7 | Gestionado por Spring Data BOM 2025.1.7 |
| Tomcat embebido | 11.0.21 | 11.0.26 | Override de familia: Boot 4.0.8 aún gestiona 11.0.24 |
| Jackson 3 / Jackson 2 | 3.1.2 / 2.21.2 | 3.1.7 / 2.21.7 | Propiedades de ambos BOM; Boot gestiona 3.1.5 / 2.21.5 |
| PostgreSQL JDBC | 42.7.10 | 42.7.14 | Boot 4.0.8 gestiona 42.7.13, afectado por avisos de octubre |
| PDFBox / POI | 3.0.8 / 5.5.1 | iguales | Ya corregidos; no cambio mayor innecesario |
| Bouncy Castle / JJWT | 1.86 / 0.12.6 | iguales | Sin hallazgo en esta revisión; límites abajo |
| Logback / Log4j2 | 1.5.32 / 2.25.3 | 1.5.38 / 2.25.5 | Parche BOM; auditoría revalidada |

CycloneDX Maven 2.9.3 produjo 173 componentes tanto antes como después, **incluyendo test**. No equivale a 173 bibliotecas desplegadas. El JAR base y final contienen 120 bibliotecas identificadas, con SHA-256 por JAR interno. Boot repackage inyecta `spring-boot-jarmode-tools`, ausente del árbol de aplicación: inicialmente quedó sin coordenada, después se verificó identidad byte a byte con las publicaciones Maven 4.0.6/4.0.8. [packaging-inputs-final.cdx.json](t09/packaging-inputs-final.cdx.json) conserva esa procedencia; SCA final reconoce los 120. Lombok y DevTools están ausentes del JAR ejecutable; las bibliotecas de pruebas y los plugins tampoco pertenecen a su runtime. No se identificó un WAR externo ni un servidor Jetty de aplicación.

El inventario adicional de plugins contiene nueve roots efectivos del lifecycle/realm, 204 componentes antes y 152 después. `dependency:resolve-plugins` ofrece la pertenencia al realm de cada plugin, **no un árbol transitorio jerárquico completo**; las aristas del adaptador significan «resuelto dentro de este realm». Plugins invocados ad hoc para inventario (CycloneDX/dependency/help), Maven CLI y herramientas locales se registran aparte; no se afirma cobertura integral del equipo o del caché Maven.

## Herramientas y compatibilidad

Observado localmente: Windows, Oracle JDK **17.0.12**, Maven **3.9.15**, wrapper **3.3.4**, Node **24.13.0**, npm **11.6.2**, Python **3.14.3**, PostgreSQL **18** desechable y OSV Scanner **2.6.0**. Los ejecutables no se atribuyen a producción. Se usó el Maven 3.9.15 ya descargado por el wrapper; no se cambió su distribución. El PostgreSQL se inició sólo en loopback y un puerto dedicado, con datos y usuarios ficticios.

[Spring Boot 4.0.8](https://docs.spring.io/spring-boot/4.0/system-requirements.html) requiere Java 17+, Maven 3.6.3+ y conserva Tomcat 11/Servlet 6.1. No se migró a Boot 4.1, Java 21 ni Jackson 4. Los [plugins Compiler](https://maven.apache.org/plugins/maven-compiler-plugin/plugin-info.html) y [Jar](https://maven.apache.org/plugins/maven-jar-plugin/plugin-info.html) conservan compatibilidad con este Maven/JDK. En frontend, [Angular 21.2](https://angular.dev/reference/versions) admite Node 24 y TypeScript 5.9; compatibilidad no significa que cualquier parche antiguo sea seguro.

OSV Scanner se descargó del [release oficial 2.6.0](https://github.com/google/osv-scanner/releases/tag/v2.6.0); SHA-256 `e0ed7644118b717b028c249ee9d3515024e55e8510747ca08906eb96765354d6`, comprobado contra `osv-scanner_SHA256SUMS`. Se consultó OSV en línea, sin suppressions ni configuración de ignore. `--all-packages` permite conservar también paquetes sin avisos. npm audit usa el servicio del registry npm, explicado en la nota frontend.

## Avisos, exposición y decisiones

La matriz [maven-advisories.csv](t09/maven-advisories.csv) conserva los 75 pares componente/aviso de la base (57 identificadores distintos), con alias CVE, rangos, fixes, severidad/CVSS, fuente, versión final, ruta resuelta y evidencia o incertidumbre de alcance. [plugins-advisories.csv](t09/plugins-advisories.csv) cubre la unión antes/final (73 filas, incluidas versiones nuevas todavía afectadas). **Una coincidencia de versión no es una prueba de explotación**; «corregido» significa que la versión final ya no coincide con el aviso en el feed consultado. Las precondiciones no demostradas permanecen explícitas, sin borrar filas.

[tomcat-official.csv](t09/tomcat-official.csv) agrega 38 avisos oficiales del intervalo 11.0.22–11.0.26; evita que retrasos de OSV oculten fixes HTTP/WebSocket. Incluye severidad del proveedor, rango y versión mínima corregida. HTTP/SockJS/WebSocket se usan realmente. AJP, WebDAV, ejemplos Tomcat, CLIENT_CERT/Realm, FFM/OpenSSL, RewriteValve, HTTP/2/TLS/proxy no se certificaron en el despliegue. CVE-2026-86350 afecta 11.0.22–25, **no** la base 11.0.21; se evita introducirlo al pasar por el Tomcat 11.0.24 gestionado por Boot. [Fuente Tomcat](https://tomcat.apache.org/security-11.html).

| Aviso / fuente primaria | Rango y fix de la línea usada | Exposición/evidencia | Decisión |
|---|---|---|---|
| [CVE-2026-41707](https://spring.io/security/cve-2026-41707/) | Security 7.0.0–6; fix 7.0.7; HIGH | DPoP/OAuth no resuelto ni configurado; autenticación propia JJWT | BOM actualizado; reabrir análisis si se incorpora DPoP |
| [CVE-2026-47841](https://spring.io/security/cve-2026-47841/) | Security 7.0.0–6; fix 7.0.7; HIGH | No WebAuthn ni almacén HTTP distribuido en el árbol inspeccionado | BOM actualizado |
| [CVE-2026-47842](https://spring.io/security/cve-2026-47842/) | Security 7.0.0–6; fix 7.0.7; MEDIUM | PasswordService usa BCrypt/Argon2; no AesBytesEncryptor hallado | No recifrado/rotación; BOM actualizado |
| [CVE-2026-47877](https://spring.io/security/cve-2026-47877/) | Security 7.0.0–6; fix 7.0.7; HIGH | No Authorization Server/página OAuth de consentimiento | BOM actualizado |
| [CVE-2026-47834](https://spring.io/security/cve-2026-47834/) | Data JPA 4.0.0–6; fix 4.0.7; MEDIUM | Paginación/orden permitido por la aplicación; no Sort arbitrario hacia native query observado | BOM actualizado; T07B revalidado |
| [CVE-2026-107315 / GHSA-f64h-wr5q-3qf3](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-f64h-wr5q-3qf3) | JDBC 42.7.4–13; fix 42.7.14; MODERATE 5.3 | Binary writer con longitud mayor que bytes; JPA usa JDBC, invocación concreta vulnerable no demostrada | Parche y prueba que fuerza buffer reuse: 200.000 bytes suministrados / 300.000 declarados, padding 100.000 ceros |
| [CVE-2026-107314 / GHSA-rhp9-mr79-r74h](https://jdbc.postgresql.org/security/) | JDBC 42.7.11–13; fix 42.7.14; MEDIUM | requireAuth con exclusión de todos los métodos; base .10 fuera del rango, configuración externa desconocida | Evita regresión de .13; prueba falla cerrada SQLState 08004 |
| [CVE-2026-107313](https://jdbc.postgresql.org/security/) | JDBC 42.7.4–5; fix .6 | GSS específico; fuera de versión inicial/final | No se declara aplicable a .10 |
| [CVE-2026-54291](https://jdbc.postgresql.org/security/) | JDBC 42.7.4–11; fix .12; HIGH en OSV | TLS/channelBinding de despliegue no observado | .14 corregido; pendiente verificación TLS real |

Los avisos JDBC de octubre 107314/107315 **no aparecían en OSV final**, aunque su versión intermedia 42.7.13 era afectada. Su contraste oficial motivó .14 y dos pruebas adicionales; no se tomó «cero OSV» como cierre. Los avisos Jackson de límites de tokens, entradas malformadas y deserialización están en la matriz; ambas familias se coordinan por BOM. El frontend y JWT alcanzan Jackson HTTP; no se encontró `default typing`, `JsonView` o `JsonTypeInfo` propio, pero se parcheó toda la familia. [Releases Jackson 3.1](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1), [2.21](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21).

### Plugins: correcciones y excepción pendiente

Compiler 3.14.1→3.16.0 elimina Commons IO 2.11.0 afectado por [CVE-2024-47554](https://commons.apache.org/proper/commons-io/security.html). Jar→3.5.1, Resources→3.5.0, Install/Deploy→3.2.0 y Site→3.22.0 sustituyen transitivas antiguas (Compress/IO, dom4j, Guava, Maven Core, Velocity, BeanUtils y jsoup), documentadas por aviso en la matriz. Site antes era el default Maven 3.12.1; una propiedad de Boot no lo gestionaba, por eso se fija explícitamente.

El BOM de aplicación **no gobierna los realms de plugins**. Overrides exclusivamente dentro de plugins:

- Plexus Utils 3.6.1 en Resources/Site/Boot y 4.0.3 en Clean; conserva la línea mayor de cada realm y corrige [GHSA-6fmv-xxpf-w3cw / CVE-2025-67030](https://github.com/advisories/GHSA-6fmv-xxpf-w3cw).
- Site: BeanUtils 1.11.0 y jsoup 1.23.2, fixes de los avisos conservados en la matriz. Se verificó `mvn site -DgenerateReports=false`, generación estática local, sin abrir servidor ni publicar.
- Boot plugin: Jackson Core/Databind 3.1.7, Commons Lang 3.18.0 y HttpClient 5.6.3 / HttpCore 5.4.3. Estas copias de build no heredan el BOM de runtime. [Seguridad HttpComponents](https://hc.apache.org/security.html). Repackage completo revalidado; Docker image/buildpack no ejecutados.

**Persisten cuatro avisos OSV, dos HIGH y dos MODERATE**, todos en Jetty 9.4.58.v20250814 del realm de Site:

| Aviso | Componente / condición | Corrección del proveedor y pendiente |
|---|---|---|
| [GHSA-355h-qmc2-wpwf / CVE-2026-2332](https://github.com/jetty/jetty.project/security/advisories/GHSA-355h-qmc2-wpwf) HIGH | jetty-http; framing HTTP frente a proxy | 9.4.60 o línea 12 soportada; no ejecutado `site:run` |
| [GHSA-qh8g-58pp-2wxh / CVE-2024-6763](https://github.com/advisories/GHSA-qh8g-58pp-2wxh) MODERATE | jetty-http; interpretación de autoridad URI | Fix 12.0.12; requiere migración compatible del plugin/servidor |
| [GHSA-2fvj-hgj9-j2gr / CVE-2026-10050](https://github.com/jetty/jetty.project/security/advisories/GHSA-2fvj-hgj9-j2gr) HIGH | jetty-security; Digest y caracteres no Latin-1; uso no demostrado | 9.4.63 / 12.0.36; no configurar Digest de Jetty sin resolver |
| [GHSA-7p3p-8qv8-m2vh / CVE-2026-6790](https://jetty.org/security.html) MODERATE | jetty-server; discrepancia Host/authority | 9.4.61 / 12.0.35; servidor auxiliar pendiente |

Además el contraste oficial identifica [CVE-2026-19203 / GHSA-xc35-c22g-239h](https://github.com/jetty/jetty.project/security/advisories/GHSA-xc35-c22g-239h), HIGH, jetty-http 9.4.0–63, fix 9.4.64: requests fragmentados/proxy si se habilita `site:run`; tampoco fue informado por este escaneo. [CVE-2026-12611](https://github.com/jetty/jetty.project/security/advisories/GHSA-gpg9-4257-cfm3), HIGH, http2-common 9.4.36–63, fix .64, no coincide con el realm observado porque **no contiene módulo HTTP/2**; [CVE-2026-5795](https://jetty.org/security.html) exige también esa superficie, no acreditada. No son avisos de Tomcat ni del JAR de aplicación.

Jetty 9 está [fuera de soporte público](https://webtide.com/end-of-life/); los parches 9.4.60+ son de disponibilidad restringida y no están en Maven Central consultado. Un intento de resolver 9.4.63.v20260825 falló y fue retirado; no quedó override ficticio. Cambiar sólo estos JAR a Jetty 12 no preserva APIs del plugin Site. **Pendiente concreto:** no habilitar/publicar `site:run` mientras se decide una alternativa mantenida o actualización del plugin compatible; revisar en cada cambio de Site o antes de usar ese goal. La compilación ordinaria `clean package` no inicia Jetty. No se eliminó el plugin ni se silenció el feed para obtener cero.

### Parsers, ZIP/XML, criptografía y JWT sin actualización

[PDFBox 3.0.8](https://pdfbox.apache.org/security.html) ya queda fuera de <=3.0.7 en CVE-2026-33929 (ejemplo específico). Su política no cubre toda denegación de servicio por PDF malformado: se conservan dos slots, JVM hija `-Xmx192m`, metaspace 96m, stack 512k y timeout 15s. [POI 5.5.1](https://poi.apache.org/security.html), XMLBeans/Compress resueltos y Bouncy Castle 1.86 no aportaron avisos OSV aplicables; el inventario identifica versiones exactas. Esto no garantiza inocuidad de entradas ZIP/XML ni demuestra aislamiento a nivel OS. [Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/). [JJWT](https://github.com/jwtk/jjwt/security/advisories) no publicaba avisos coincidentes; se conservan 0.12.6 y la validación propia T02. Se mantienen rechazos de cifrado, contenido activo, objetos embebidos y archivos truncados.

## Reproducción y límites de herramientas

Desde un checkout limpio de la rama final, con Java 17 parcheado, Maven del wrapper y PostgreSQL **desechable** en loopback:

```powershell
# Cada comando es independiente: no reutilizar -DoutputFile entre tree y resolve-plugins.
./mvnw.cmd help:effective-pom -Doutput=evidence/effective-pom.xml
./mvnw.cmd dependency:tree -DoutputType=json -DoutputFile=evidence/tree.json
./mvnw.cmd dependency:resolve-plugins -DoutputFile=evidence/plugins.txt
./mvnw.cmd org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -DincludeTestScope=true -DoutputDirectory=evidence -DoutputName=maven.cdx
python docs/seguridad/t09/inventory.py plugins evidence/plugins.txt evidence/plugins.cdx.json
./mvnw.cmd clean package '-Dspring.profiles.active=test' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:<PUERTO>/chemicallab_t01_test' '-Dspring.datasource.username=<USUARIO_FICTICIO>' '-Dspring.datasource.password=<CLAVE_FICTICIA>'
# Repackage agrega jarmode-tools; verificar su hash contra la publicación Maven del patch usado.
./mvnw.cmd dependency:copy -Dartifact=org.springframework.boot:spring-boot-jarmode-tools:4.0.8 -DoutputDirectory=evidence/jarmode-verified
python docs/seguridad/t09/inventory.py jar target/chemical-lab-backend-0.0.1-SNAPSHOT.jar evidence/packaged.cdx.json evidence/maven.cdx.json docs/seguridad/t09/packaging-inputs-final.cdx.json
osv-scanner scan source -L evidence/maven.cdx.json --format json --all-packages --output-file evidence/osv-maven.json
osv-scanner scan source -L evidence/plugins.cdx.json --format json --all-packages --output-file evidence/osv-plugins.json
osv-scanner scan source -L evidence/packaged.cdx.json --format json --all-packages --output-file evidence/osv-packaged.json
java '-Dloader.path=target/test-classes' '-Dloader.main=com.morales.chemicallab.security.files.PackagedFormatsProbe' -cp target/chemical-lab-backend-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Crear antes `evidence/`. Para el comparativo repetir inventario sobre la base exacta en otro checkout. El adaptador usa sólo Python estándar, sin red o ejecución de aplicación/DB. Para JAR toma metadatos Maven propios, reconcilia las publicaciones sin `pom.properties` con el SBOM resuelto y hace explícita cualquier identidad no resuelta; no inventa grupos. El nombre `.cdx.json` es importante: OSV rechazó un nombre inicial `.json` sin ese sufijo, y se repitió correctamente. Exit 1 de OSV significa findings, no fallo de actualización; exit 0 no prueba cobertura universal.

Los SBOM de revisión retienen coordenadas, hashes, scopes y aristas; omiten descripciones voluminosas/metadata redundante, conservando formato estándar. Validación contra JSON Schema oficial CycloneDX 1.6 y referencias internas. `SHA256SUMS` identifica los artefactos versionados; `artifact-hashes.json` identifica el POM/JAR local analizado. POM efectivo, reportes OSV íntegros, logs de Maven/DB, caches y binarios quedan localmente en `.codex-work/t09-evidence/`, sin versionar rutas personales, credenciales o datos de fixture.

## Resultado antes/después y validación

| Análisis | Base | Final | Alcance |
|---|---:|---:|---|
| OSV dependencias Maven, avisos únicos / pares | 57 / 75 | 0 / 0 | 173 componentes, incluye tests; no imagen |
| OSV JAR realmente empaquetado, avisos / pares | 57 / 75 | 0 / 0 | 120 componentes identificados, incluido jarmode-tools |
| OSV plugins, avisos únicos / pares | 61 / 69 | 4 / 4 | 204→152 componentes; dos HIGH Jetty pendientes |
| OSV lock npm, avisos únicos / pares | 111 / 124 | 0 / 0 | Detalle en nota frontend; no sumar a Maven |
| npm audit, paquetes afectados | 37 | 0 | Incluye desarrollo; definición distinta de OSV |

SHA de implementación backend final: **`cdee3c5caa5b2fa15ec6892055e19d2e7f8d992b`**. Resultado final exacto y hashes de artefactos: [validation.json](t09/validation.json). Los commits posteriores de documentación sólo agregan evidencias/adaptadores de inventario; no se atribuye una ejecución a un SHA posterior con código de aplicación distinto.

Suite final sobre `cdee3c5`: **576 pruebas, 0 fallos, 0 errores, 0 omitidas**; `clean package` terminó correctamente el 2026-10-12 a las 07:33:45 -05:00. El JAR generado pasó después los **10 checks de formatos mediante JVMs hijas**, y su SCA reconoce 120 componentes sin avisos en el feed consultado. Los esquemas de prueba se limpiaron y se detuvo exclusivamente el clúster PostgreSQL desechable T09.

Primera suite (POM intermedio): 574 pruebas, un fallo de `AuditReliabilityDbTest`, porque el segundo contexto tardaba lo suficiente para cambiar de minuto y reabrir legítimamente el presupuesto. Se movió la precarga del test después del arranque y se evita iniciar la ráfaga en los últimos 15 segundos; control SQL de producción intacto. Ejecución con propiedades DB incorrectas falló y **no cuenta como validación**. Suites completas intermedias `ee4e3c9` y `39266df`: cada una 576/0/0/0; no se suman. Se amplió la regresión JDBC de padding para que fuerce reutilización del buffer (un valor pequeño podía pasar también en driver afectado). Los controles negativos con 42.7.10 y 42.7.13 fallaron en los dos casos previstos, sin errores ni omitidas; la suite final con .14 los pasa. El caso requireAuth de .10 demuestra comportamiento previo sin ese requisito, no se atribuye CVE-2026-107314 a una versión fuera de su rango. Las dos pruebas JDBC usan tabla temporal y guardia estricta de URL local, sin bases reales.

Se validan autenticación/hash/sesiones, transporte STOMP y autorización/revocación, reconciliación, evaluaciones/ventanas, cinco formatos y límites de procesos, consultas/cuotas/retención apagada y auditoría/rollback. `PackagedFormatsProbe` exige el comando hijo `java -jar ... --file-validation-worker` y comprueba válido+truncado para PDF/PPT/PPTX/PNG/JPEG; no basta ejecutar parsers en el classpath de tests. La prueba frontend integrada tiene fixture PostgreSQL efímera, indicada en la nota del otro repositorio. No se ejecutan navegadores Selenium originales ni se usan sus datos.

## Pendientes operativos y cobertura no verificada

- **Imagen:** daemon Docker no accesible (`dockerDesktopLinuxEngine` ausente). Dockerfile observado usa tags mutables `eclipse-temurin:17-jdk` y `17-jre`, no digest. No se construyó, descargó ni escaneó imagen, OS/libc/OpenSSL/JRE de despliegue. Antes de cerrar VER-01: identificar digest de base y final, generar SBOM de imagen, SCA OS/JRE y revalidar hijos dentro de ella. No pin ficticio de digest.
- **Java:** 17.0.12 local es antiguo frente al [CPU de julio de 2026](https://www.oracle.com/security-alerts/cpujul2026.html). Pendiente usar Java 17 del proveedor mantenido con fixes vigentes y repetir build/runtime/validadores; no se actualizó software productivo. No se realizó atribución exhaustiva CVE del JDK local.
- **Node:** 24.13.0 local admite Angular pero precede fixes [junio](https://nodejs.org/en/blog/vulnerability/june-2026-security-releases) y [julio de 2026](https://nodejs.org/en/blog/vulnerability/july-2026-security-releases) (24.18.1). Pendiente builder CI/local parcheado, revisar también releases posteriores; Node no se incluye en el bundle estático. Nota frontend amplía exposición.
- **DB/TLS:** PostgreSQL 18 usado sólo como fixture local; versión/configuración del servidor real, certificados, `sslmode=verify-full`, SCRAM/channelBinding y GSS desconocidos. La prueba con trust ficticio no certifica TLS ni corrige software DB real.
- **Feeds:** OSV y npm respondieron; no hubo scanner fallback offline ni suppressions. Hay retrasos comprobados respecto a avisos JDBC/Jetty oficiales. Reintentos de un advisory Angular y del URL específico Plexus devolvieron timeout/404; se usaron datos npm/OSV y fuente reconocida, sin considerarlo verificación plena del texto primario. No se ejecutó NVD/Dependency-Check ni análisis dinámico de cada PoC. El SBOM JAR no cubre clases del JDK; stats del frontend no cubren recursos cargados remotamente.
- **Servidor Site:** resolver Jetty mantenido antes de `site:run`; los riesgos altos son pendientes de decisión, no aceptados ni cerrados. Revisar en cada cambio de dependencia/imagen y antes de integración operativa.

Preservación: [preservation.json](t09/preservation.json) confirma los 22 hashes del snapshot T08, incluidos los **16 archivos e2e-selenium** originales; las 12 notas backend versionadas previas y 5 frontend coinciden con sus bases (normalización CRLF de Git). Frontend original permanece en `feature/validaciones-datos-institucionales`, HEAD `e88589635a8269c50ee0f53fccf157f0d0985034`. Las PRs coordinadas quedan para revisión; consultar enlaces de entrega, sin integración automática.
