# Prácticas de software verde de ChemicalLab

Adoptadas el 26 de noviembre de 2026 para el tramo previo al despliegue. La persona que opera ChemicalLab mantiene el inventario, revisa cada cambio que afecte recursos y conserva resultados por versión. Las decisiones históricas se documentan solo cuando existe evidencia fechada.

## Inventario y control del ciclo de vida

| Fase | Impactos que se consideran | Decisión y revisión |
| --- | --- | --- |
| Desarrollo y validación | CPU, electricidad del equipo, descarga de dependencias, duplicación de clústeres temporales y artefactos de compilación. | Reutilizar builds cuando el SHA coincide. Preferir pruebas dirigidas con datos ficticios. Registrar ventanas comparables para cambios de recursos y detener procesos desechables al terminar. |
| Operación escolar | JVM, consultas y disco de PostgreSQL, transferencia de adjuntos, conexiones de pizarra, pantalla y cómputo de los clientes. También pesan alta, mantenimiento y reemplazo de equipos. | Limitar trabajo por operación. Medir por clase y por operación de ficha. Incluir servidores y dispositivos en la futura evaluación de huella, distinguiendo el límite de cada sensor. |
| Conservación y recuperación | Almacenamiento principal y de copias, lectura periódica de respaldo y recursos de restauración. | Conservar la integridad y el alcance de datos académicos y de auditoría. Inventariar el volumen y las copias justificadas por el plan de continuidad. Reutilizar referencias de adjuntos y verificar restauraciones con huellas. |
| Retirada | Transferencia o exportación autorizada, tratamiento de copias y destino de equipos o medios físicos. | Inventariar datos, permisos y copias antes de retirar una instalación. Verificar la devolución de registros, aplicar el plazo aprobado y registrar el cierre. Decidir la disposición de equipos propios con canales autorizados. |
| Reutilización | Compra evitada de equipos, uso de navegadores soportados y conservación de herramientas y material educativo reutilizable. | Mantener rutas esenciales ligeras y accesibles, sin exigir GPU dedicada para una página de lectura. Tras verificar compatibilidad y seguridad, preferir reutilización de equipos y materiales. Antes de sustituir un equipo, probar el recorrido prioritario en su navegador soportado. |

La unidad local de servicio es una lectura correcta de una ficha de pizarra con un conjunto fijo de participantes. Los impactos de desarrollo, transporte, conservación y fin de vida quedan identificados cualitativamente. El contador local tiene como frontera sus dominios de hardware.

## Reglas que se aplican al revisar código

1. Acotar consultas, páginas y cachés por ámbito. Una lista usa una proyección definida y evita N+1 y binarios. Comparar número de consultas y respuesta al cambiar lecturas. Conservar auditoría, roles y revocación.
2. Separar metadatos y descarga. Evitar bajar un archivo para mostrar una ficha y permitir usar el objeto ya cargado durante su vista previa. Verificar las huellas de los archivos y retirar referencias al cambiar de ámbito.
3. Cargar pantallas informativas bajo demanda. La ayuda estática debe abrirse sin conexiones API, credenciales, almacenamiento o telemetría. Comprobar el chunk y el tráfico en una navegación real.
4. Atar observadores, temporizadores y conexiones al ciclo de vida. Probar que una vista destruida deje de recibir sus eventos. Mantener la reconciliación, revocación, latidos y temporizadores que sostienen una sesión o un intento vigentes.
5. Validar primero con tamaños representativos y pruebas focalizadas. Registrar mediana, p95, errores, consultas, bytes y frontera de energía. Antes de ampliar recursos, comprobar compatibilidad de respuesta y margen. La electricidad necesita una fuente válida en sus propias unidades.

La revisión aplica los puntos 3 y 4 en la ayuda y la barra lateral. El navegador comprueba carga diferida, ausencia de peticiones API y persistencia, y navegación con teclado. Las pruebas dirigidas cubren el intento activo y la cancelación de la suscripción del menú. Los puntos 1 y 2 mantienen el contrato de la PR #89. [Carga de rutas de Angular](https://angular.dev/guide/routing/define-routes) · [Cierre de observadores de Angular](https://angular.dev/api/core/rxjs-interop/takeUntilDestroyed).

## Evaluación del stack y decisiones de arquitectura

- **Java y Spring Boot.** Mantener el servicio actual conserva transacciones, ámbito de autorización y pruebas reutilizables. La JVM tiene costos de arranque, clases, heap y GC que se contabilizan aparte del trabajo estable. Se comparan dos límites de heap con la misma aplicación y se observa el dominio energético del paquete. Una imagen nativa o una reescritura se justificaría con un problema medido de arranque o memoria y una comparación que conserve semántica y seguridad. [Opciones de Java 17](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html).
- **PostgreSQL.** Mantener almacenamiento e índices transaccionales evita operar una caché distribuida adicional para esta lectura y mantiene la coherencia de revocación. El cambio medible es quitar la hidratación de perfiles y la proyección del bytea. Un caché o almacén adicional obligaría a evaluar invalidación, electricidad y conservación de una copia. [Almacenamiento de valores grandes en PostgreSQL](https://www.postgresql.org/docs/18/storage-toast.html).
- **Angular y navegador.** Una ruta informativa diferida evita que su código viaje en cada carga inicial. La prueba a 375 px con CPU emulada permite detectar problemas de interacción de la ayuda. Una página generada por el backend duplicaría rutas y operación para un recurso estático pequeño. Canvas y STOMP conservan su funcionalidad y límites.
- **Broker e infraestructura.** Conservar una instancia mientras el piloto y su margen lo permitan. Las revalidaciones de sesión abren transacciones independientes. El pool debe dejar conexiones disponibles cuando las transacciones externas siguen ocupadas. El ensayo usa ocho conexiones fijas para cuatro lectores simultáneos. El ajuste se limita al perfil de prueba. [Propagación transaccional de Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

Se considera el impacto energético de los flujos reales y de las opciones de arquitectura. La comparación cuantitativa cubre dos versiones del mismo stack. La evaluación de las otras opciones es cualitativa. El análisis de carbono del servicio pendiente requiere la frontera, la electricidad y la infraestructura del alojamiento seleccionado. [Método SCI](https://github.com/Green-Software-Foundation/sci/blob/main/SPEC.md).

## Avisos y actividad de vistas

La revisión del frontend buscó permisos Web Push, gestores de suscripciones, service workers y solicitudes de notificación. La del backend buscó emisores de correo, notificaciones comerciales y tareas programadas. El alcance revisado carece de avisos opcionales activos. Los estados de formularios y errores son información de uso. STOMP, reconciliación, vencimiento de intentos y revocación son comunicaciones funcionales. El módulo que recibe notificaciones comerciales permanece deshabilitado. No se incorpora un modo de ahorro que interrumpa esos controles.

## Operación por una persona y participación real

El operador actualiza estos controles al revisar cambios de datos, tráfico o cómputo y registra el alcance, SHA, resultados y decisión. Antes del piloto verifica baja, recuperación y capacidad con un clúster ficticio. Durante el piloto revisa mensualmente los reportes voluntarios recibidos por soporte y el peso de los recorridos prioritarios. Consolida en un registro local por versión, sin datos personales, y actúa según frecuencia, impacto y reproducibilidad. El centro mantiene su canal habitual.

Para la futura selección de alojamiento, comparar demanda medida, ubicación, información primaria sobre intensidad y origen eléctrico y tamaño mínimo con margen. Considerar una tarifa verde documentada o un acuerdo de compra de energía solo si se contrata suministro directamente. Si el suministro queda a cargo del proveedor, pedir información del servicio y el período. Las decisiones y evidencias siguen pendientes de esa selección.

Para acreditar participación, confirmar con el colegio su canal de soporte, presentar la ayuda a una persona voluntaria con datos ficticios y guardar fecha, versión y constancia agregada de recepción. Pedir una observación sobre lentitud, descargas repetidas o facilidad de uso. Registrar únicamente módulo, situación, observación y si pudo reproducirse, sin nombres, credenciales, archivos ni contenido de alumnos.
