# Revisión para integrar las suscripciones en modo de pruebas

El módulo queda deshabilitado por defecto. Los modos de prueba se rechazan en el perfil `prod`. Integrar el código deja los cobros reales deshabilitados.

## Vencimiento y retorno

El corte comercial excluye el instante exacto de vencimiento. Un intento abierto conserva su fecha y plazo académico. El corte impide iniciar otro intento. La recuperación, el guardado y el envío existentes siguen la autorización y el plazo académico original, y la entrega de la corrección sigue T05. En el retorno de checkout, el servidor agrega el identificador del colegio a una URL configurada. El navegador usa esa referencia para consultar el estado del colegio por HTTP y conserva la sesión en la misma pestaña. El estado de pago no se deriva de los parámetros de retorno.

El escenario por HTTP se ejecuta con el runner de [la guía](suscripciones-institucionales.md), en una base nueva con `-Administration -Subscriptions`. El escenario prepara entregas académicas reales por HTTP. Cambia la resolución de `statement_timestamp()` solamente en la base desechable. Así compara un microsegundo antes, el instante exacto y un microsegundo después sin depender de la latencia de la red. Los plazos académicos siguen el reloj real.

```powershell
node tools/verify-subscription-attempt-expiry.mjs "<directorio-nuevo>" "<bin>/psql.exe" 55542
```

Para la revisión de Edge, usa otro directorio nuevo y los puertos de la misma guía. Compila el frontend con `npm run build:colegio` para usar la API relativa. Ejecuta en el frontend el siguiente comando. El runner levanta Angular en el puerto loopback 4200. Debe coincidir con la URL de retorno configurada por el runner del backend.

```powershell
node tools/verify-institutional-journey.mjs "<directorio-nuevo>/browser" "<msedge.exe>" "dist/chemical-lab-frontend/browser" subscriptions "<bin>/psql.exe" 55542 18094
```

El verificador comercial `tools/verify-subscriptions-runtime.mjs` usa un tercer directorio nuevo. Comprueba la espera ante múltiples resultados para la referencia, una creación remota con respuesta perdida, y la cancelación solicitada mientras otro worker reconcilia la suscripción. Un webhook solo resuelve un alta incierta cuando la búsqueda confirma una coincidencia exacta. La actualización final del worker conserva una cancelación registrada después de que tomó el trabajo.

## Compatibilidad con la documentación de Mercado Pago Perú

| Contrato | Confirmación documental y comprobación pendiente |
| --- | --- |
| Credenciales e identidad | Hay credenciales de prueba y cuentas de comprador y vendedor. La documentación consultada no garantiza que una combinación para Suscripciones devuelva un vendedor MPE con etiqueta `test_user` en `/users/me`, ni que los IDs resultantes coincidan en todos los recursos. El adaptador mantiene esas comprobaciones y requiere una prueba externa de la combinación antes de activarse. [Credenciales](https://www.mercadopago.com.pe/developers/es/docs/subscriptions/additional-content/your-integrations/credentials) · [Cuentas de prueba](https://www.mercadopago.com.pe/developers/es/docs/checkout-pro-orders/resources/your-integrations/test/accounts) |
| Contratación y recuperación | La documentación admite `POST /preapproval` pendiente con URL de retorno, moneda e importe. La referencia documenta `collector_id`, `application_id`, `payer_id`, `last_modified` e `init_point`. La referencia de Perú muestra una URL de ejemplo argentina. Falta confirmar el host/ruta emitidos para la aplicación peruana de prueba. La búsqueda admite `q`, cuya coincidencia no equivale a idempotencia. El servicio coteja la referencia exacta y retiene reservas con cero o múltiples coincidencias. [Checkout pendiente](https://www.mercadopago.com.pe/developers/es/docs/subscriptions/integration-configuration/subscription-no-associated-plan/pending-payments) · [Crear suscripción](https://www.mercadopago.com.pe/developers/es/reference/online-payments/subscriptions/create-preapproval/post) · [Buscar suscripciones](https://www.mercadopago.com.pe/developers/en/reference/online-payments/subscriptions/search-preapproval/get) |
| Factura y pago | La factura documenta `preapproval_id`, `external_reference`, `currency_id`, `transaction_amount`, `debit_date`, `last_modified` y `payment.id`. El pago canónico se requiere además para verificar identidad, importe, entorno y acreditación, reversión o disputa. Falta contrastar las respuestas de ambos recursos para un mismo cobro recurrente de prueba. [Factura](https://www.mercadopago.com.pe/developers/es/reference/online-payments/subscriptions/get-authorized-payment/get) · [Pago](https://www.mercadopago.com.pe/developers/es/reference/online-payments/checkout-api-payments/get-payment/get) |
| Eventos y cancelación | Los tópicos documentados son `subscription_preapproval`, `subscription_authorized_payment` y `payment`. La firma HMAC incluye `data.id`, `x-request-id` y `ts`. El adaptador también exige identidad esperada y `live_mode=false` en eventos y pagos. Debe observarse si esos campos, la firma y los cambios por devoluciones o disputas llegan con la forma esperada en el entorno de prueba. La guía documenta `PUT /preapproval/{id}` con `status=canceled`. [Webhooks](https://www.mercadopago.com.pe/developers/es/docs/checkout-pro-orders/resources/notifications/webhooks) · [Cancelar](https://www.mercadopago.com.pe/developers/es/docs/subscriptions/subscription-management) |

## Decisiones antes de producción

- **Pago tardío.** El ensayo atribuye el pago al ciclo de la fecha de débito y conserva su vencimiento. Un cobro aprobado después del fin del ciclo deja contabilidad sin vigencia nueva. Acordar si se devuelve, se acredita al ciclo siguiente o se revisa manualmente, y quién avisa al colegio.
- **Devolución parcial.** El ensayo marca como revertida toda la factura si registra un importe devuelto positivo. Acordar cuándo corresponde conservar acceso, ajustar crédito o suspender y qué puede decidir el operador. Una devolución parcial no implica por sí sola un nuevo vencimiento acordado.
- **Contracargo.** El ensayo retira la vigencia de la factura revertida aunque conserve evidencia y otras facturas válidas. Acordar si una disputa abierta pone el acceso en revisión, cuándo se confirma el retiro, cómo restituirlo si prospera una impugnación, quién notifica y cuándo escalar.
- **Reservas inciertas y cancelación.** Definir quién puede investigar y resolver una reserva retenida, cómo probar que no existe una segunda recurrencia, cómo actuar con una orden ya iniciada y cómo tratar cancelaciones cerca del cobro. La versión de pruebas evita reintentar ciegamente un alta o iniciar el cobro de una reserva ambigua.
- **Activación.** Requiere conformidad con las condiciones definitivas, procedimiento operativo de incidencias y validación de checkout, factura, Webhooks y cancelación con Mercado Pago Perú. Durante esta revisión no se prepararon la cuenta, las credenciales ni un destino público.
