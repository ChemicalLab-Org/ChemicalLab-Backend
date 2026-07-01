# Despliegue interno del colegio

Esta guia prepara ChemicalLab para funcionar dentro de la red interna del colegio,
manteniendo disponible el despliegue publico actual en Vercel/Render como respaldo.

No se deben versionar secretos, contrasenas reales, backups, archivos `.jar`, carpetas
`dist`, carpetas `target`, logs ni dumps de base de datos. Los valores sensibles se
configuran directamente en la PC del colegio.

## Arquitectura prevista

- Nginx escucha en el puerto 80 de la PC de la profesora.
- Angular compilado se sirve desde `C:\ChemicalLab\frontend`.
- Spring Boot se ejecuta localmente en `127.0.0.1:8080`.
- PostgreSQL se ejecuta localmente en `localhost:5432`.
- Nginx envia las rutas `/api/` hacia Spring Boot.
- Nginx envia `/ws` hacia Spring Boot para la pizarra en vivo.
- Las computadoras del laboratorio solo acceden desde navegador.

URLs previstas:

- Inicial: `http://192.168.18.147`
- Posterior: `http://chemicallab`

Bases previstas:

- Pruebas: `chemicallab_test`
- Final: `chemicallab`

## Build del backend

Desde el repositorio del backend:

```powershell
cd C:\Users\carlo\OneDrive\Escritorio\Taller\ChemicalLab-Backend
.\mvnw.cmd clean package -DskipTests
```

El artefacto queda en:

```text
target\chemical-lab-backend-0.0.1-SNAPSHOT.jar
```

Para desplegarlo en la PC del colegio, copiarlo como:

```powershell
New-Item -ItemType Directory -Force C:\ChemicalLab\backend
Copy-Item .\target\chemical-lab-backend-0.0.1-SNAPSHOT.jar C:\ChemicalLab\backend\chemical-lab-backend.jar -Force
```

## Build del frontend para colegio

El frontend tiene un build especifico para red interna. Usa rutas relativas para REST
(`/api`) y calcula el WebSocket con el host actual (`/ws/websocket`).

Desde el repositorio del frontend:

```powershell
cd C:\Users\carlo\OneDrive\Escritorio\Taller\ChemicalLab-Frontend
npm ci
npm run build:colegio
```

Copiar el contenido generado:

```powershell
New-Item -ItemType Directory -Force C:\ChemicalLab\frontend
Copy-Item .\dist\chemical-lab-frontend\browser\* C:\ChemicalLab\frontend -Recurse -Force
```

El comando `npm run build` conserva la configuracion publica existente para Vercel/Render.

## Configuracion externa del backend

Se recomienda arrancar el backend con `spring.config.additional-location` para conservar
los valores versionados por defecto y sumar la configuracion local no versionada:

```powershell
cd C:\ChemicalLab\backend
java -jar .\chemical-lab-backend.jar --spring.config.additional-location=file:C:/ChemicalLab/config/application-test.properties
```

Para despliegue final:

```powershell
cd C:\ChemicalLab\backend
java -jar .\chemical-lab-backend.jar --spring.config.additional-location=file:C:/ChemicalLab/config/application-prod.properties
```

### `C:\ChemicalLab\config\application-test.properties`

```properties
server.address=127.0.0.1
server.port=8080

spring.datasource.url=jdbc:postgresql://localhost:5432/chemicallab_test
spring.datasource.username=postgres
spring.datasource.password=<POSTGRES_PASSWORD>

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false

spring.jackson.time-zone=America/Lima

spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=12MB

app.jwt.secret=<JWT_SECRET_BASE64_MIN_32_BYTES>
app.jwt.expiration-ms=86400000

app.cors.allowed-origins=http://localhost,http://127.0.0.1,http://192.168.18.147,http://chemicallab
```

### `C:\ChemicalLab\config\application-prod.properties`

```properties
server.address=127.0.0.1
server.port=8080

spring.datasource.url=jdbc:postgresql://localhost:5432/chemicallab
spring.datasource.username=postgres
spring.datasource.password=<POSTGRES_PASSWORD>

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false

spring.jackson.time-zone=America/Lima

spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=12MB

app.jwt.secret=<JWT_SECRET_BASE64_MIN_32_BYTES>
app.jwt.expiration-ms=86400000

app.cors.allowed-origins=http://localhost,http://127.0.0.1,http://192.168.18.147,http://chemicallab
```

Las claves iniciales del administrador, docente demo y estudiante demo no deben ponerse en
el repositorio. Definirlas como variables de entorno de Windows antes de iniciar el backend:

```powershell
$env:ADMIN_INITIAL_PASSWORD="<ADMIN_INITIAL_PASSWORD>"
$env:TEACHER_INITIAL_PASSWORD="<TEACHER_INITIAL_PASSWORD>"
$env:STUDENT_INITIAL_PASSWORD="<STUDENT_INITIAL_PASSWORD>"
```

## Configuracion Nginx recomendada

El backend define el endpoint STOMP/SockJS en `/ws`. El frontend del colegio se conecta al
transporte WebSocket directo `/ws/websocket`, por lo que `location /ws` es suficiente: cubre
`/ws`, `/ws/` y `/ws/websocket`.

```nginx
server {
    listen       80;
    server_name  localhost 192.168.18.147 chemicallab;

    root   C:/ChemicalLab/frontend;
    index  index.html;

    client_max_body_size 12M;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

No agregar barra final en `proxy_pass` de `/api/`; el backend espera rutas con prefijo
`/api`.

## Pruebas recomendadas

Probar backend local:

```powershell
Invoke-WebRequest http://127.0.0.1:8080/api/health
```

Probar a traves de Nginx en la PC servidor:

```powershell
Invoke-WebRequest http://192.168.18.147
Invoke-WebRequest http://192.168.18.147/api/health
```

Luego probar desde una PC del laboratorio:

- Abrir `http://192.168.18.147`.
- Iniciar sesion.
- Probar contenidos, evaluaciones, materiales y pizarra en vivo.

Para usar `http://chemicallab`, configurar DNS interno o entradas `hosts` en las PCs del
laboratorio apuntando `chemicallab` a `192.168.18.147`.

## Recomendaciones de base de datos

- Usar `chemicallab_test` para pruebas y capacitacion inicial.
- Usar `chemicallab` para el despliegue final.
- No probar con la base final si se van a crear usuarios, evaluaciones, intentos, logs,
  metricas o materiales temporales.
- Realizar backups fuera del repositorio, por ejemplo en `E:\ChemicalLab_Backups`.
- Mantener `spring.jpa.hibernate.ddl-auto=update` para el colegio. No usar `create` en la
  base final.
