# Motor químico

Documentación del motor de formación de compuestos del MVP educativo. Cubre los
endpoints, los catálogos y el contrato de las peticiones tras la Sesión 18.

> **Alcance:** el motor devuelve la **fórmula correcta**, el **tipo de
> compuesto**, una **explicación básica** y, desde la Sesión 19, las **tres
> nomenclaturas** del compuesto (tradicional, Stock y sistemática). Ver la
> sección [Nomenclaturas](#nomenclaturas).

Todos los endpoints cuelgan de `/api/chemistry` y requieren autenticación JWT
(cualquier rol autenticado). El backend es la **fuente de verdad**: valida las
referencias contra el catálogo y deriva nombres, cargas y fórmulas; el frontend
no mantiene catálogos químicos propios.

## Fuente de verdad y contrato

A partir de la Sesión 18, las peticiones de sal binaria, oxisal y ácido envían
**solo referencias** al catálogo (símbolos y claves). El backend deriva el resto.

| DTO              | Campos del contrato                              |
| ---------------- | ------------------------------------------------ |
| `SaltRequest`    | `metalSymbol`, `metalValence`, `nonMetalSymbol`  |
| `OxisaltRequest` | `metalSymbol`, `metalValence`, `oxoanionKey`     |
| `AcidRequest`    | `acidType` (`HYDRACID`/`OXOACID`), `nonMetalSymbol` (hidrácido) **o** `oxoanionKey` (oxácido) |
| `ElementCompoundRequest` (óxidos/hidróxidos) | `elementSymbol`, `elementName`, `valence` |

Respuesta común (`CompoundResponse`):

```json
{
  "valid": true,
  "compoundType": "Sal binaria",
  "formula": "NaCl",
  "name": "cloruro de sodio",
  "explanation": "...",
  "nomenclature": {
    "traditional": "cloruro de sodio",
    "stock": "cloruro de sodio",
    "systematic": "cloruro de sodio",
    "notes": ""
  }
}
```

El campo `name` se mantiene como nombre base de referencia (compatibilidad con
clientes previos); las tres nomenclaturas viven en `nomenclature`.

En caso de combinación inválida se devuelve **HTTP 400** con
`{ "valid": false, "error": "...", "message": "..." }` y un mensaje claro
(p. ej. valencia no permitida, gas noble, oxígeno como anión de sal binaria,
oxoanión inexistente).

## Endpoints de formación

| Método | Ruta                       | Compuesto             |
| ------ | -------------------------- | --------------------- |
| POST   | `/api/chemistry/oxides`    | Óxido                 |
| POST   | `/api/chemistry/hydroxides`| Hidróxido             |
| POST   | `/api/chemistry/acids`     | Ácido (hidrácido/oxácido) |
| POST   | `/api/chemistry/salts`     | Sal binaria           |
| POST   | `/api/chemistry/oxisalts`  | Oxisal                |

## Endpoints de catálogo

| Método | Ruta                                                  | Devuelve                                  |
| ------ | ----------------------------------------------------- | ----------------------------------------- |
| GET    | `/api/chemistry/catalog/metals`                       | Metales con sus valencias                 |
| GET    | `/api/chemistry/catalog/binary-nonmetals`             | No metales para sales binarias            |
| GET    | `/api/chemistry/catalog/acid-nonmetals`               | No metales que forman hidrácido           |
| GET    | `/api/chemistry/catalog/oxoanion-central-elements`    | Elementos centrales de los oxoaniones     |
| GET    | `/api/chemistry/catalog/oxoanions`                    | Oxoaniones (todos)                        |
| GET    | `/api/chemistry/catalog/oxoanions?centralElement=S`   | Oxoaniones filtrados por elemento central |

## Ejemplos de petición

### Sal binaria — `NaCl`

```http
POST /api/chemistry/salts
{ "metalSymbol": "Na", "metalValence": 1, "nonMetalSymbol": "Cl" }
```

### Oxisal — `Al2(SO4)3`

```http
POST /api/chemistry/oxisalts
{ "metalSymbol": "Al", "metalValence": 3, "oxoanionKey": "sulfato" }
```

### Ácido hidrácido — `HCl`

```http
POST /api/chemistry/acids
{ "acidType": "HYDRACID", "nonMetalSymbol": "Cl" }
```

### Oxácido — `H2SO4`

```http
POST /api/chemistry/acids
{ "acidType": "OXOACID", "oxoanionKey": "sulfato" }
```

### Óxido / hidróxido — `Na2O` / `NaOH`

```http
POST /api/chemistry/oxides
{ "elementSymbol": "Na", "elementName": "sodio", "valence": 1 }
```

## Reglas de fórmula

- Cruce de cargas simplificado por máximo común divisor.
- Se omite el subíndice 1 (`NaCl`, no `Na1Cl1`).
- Paréntesis solo cuando un grupo poliatómico lleva subíndice mayor a 1
  (`Al2(SO4)3`, pero `CaSO4`).
- Se rechazan gases nobles, oxígeno e hidrógeno como anión de sal binaria, y las
  valencias no registradas para cada metal.

## Nomenclaturas

Desde la Sesión 19 cada respuesta incluye un objeto `nomenclature` con tres
sistemas de nombres, resueltos por `ChemicalNomenclatureService`. El motor de
fórmulas y el de nombres están separados: el primero cruza cargas, el segundo
traduce la combinación a nombres usando el catálogo como fuente de verdad (por
ejemplo, para saber si un metal tiene más de una valencia).

### Criterios generales

- **Tradicional:** para metales con **una sola valencia** se usa la forma
  «base de elemento» (p. ej. `óxido de sodio`). Para metales con **más de una
  valencia** se usan las raíces -oso/-ico (p. ej. `óxido ferroso` / `óxido
  férrico`). Criterio adoptado y consistente en todo el motor.
- **Stock:** se añade el número de oxidación en romanos **solo** cuando el metal
  tiene más de una valencia (`óxido de hierro (II)`); con valencia única coincide
  con la forma «de elemento».
- **Sistemática:** prefijos multiplicadores `mono-, di-, tri-, tetra-…` sobre los
  subíndices reales de la fórmula (`trióxido de dihierro`).

### Utilidades implementadas

- Números romanos: 1→I … 10→X.
- Prefijos sistemáticos: 1→mono … 10→deca (con elisión de vocal ante «óxido»:
  `monóxido`, `tetróxido`).
- Prefijos multiplicativos para grupos repetidos: 2→bis, 3→tris, 4→tetrakis.
- Raíces tradicionales -oso/-ico de los metales con varias valencias (hierro,
  cobre, estaño, plomo, mercurio, oro, cobalto, níquel, cromo, manganeso, platino).

### Alcance por tipo de compuesto

| Tipo            | Tradicional                  | Stock                          | Sistemática                              |
| --------------- | ---------------------------- | ------------------------------ | ---------------------------------------- |
| Óxidos          | `óxido ferroso`              | `óxido de hierro (II)`         | `monóxido de hierro`                     |
| Hidróxidos      | `hidróxido férrico`          | `hidróxido de hierro (III)`    | `trihidróxido de hierro`                 |
| Hidrácidos      | `ácido clorhídrico`          | `cloruro de hidrógeno`         | `cloruro de hidrógeno` / `sulfuro de dihidrógeno` |
| Oxácidos        | `ácido sulfúrico`            | `ácido tetraoxosulfúrico (VI)` | `tetraoxosulfato (VI) de dihidrógeno`    |
| Sales binarias  | `cloruro férrico`            | `cloruro de hierro (III)`      | `tricloruro de hierro`                   |
| Oxisales        | `fosfato férrico`            | `fosfato de hierro (III)`      | `tetraoxofosfato (V) de hierro (III)`    |

### Casos con forma simplificada y documentada

- **Oxácidos y oxisales — Stock/sistemática:** la regla completa de la
  nomenclatura sistemática de oxoácidos es avanzada para el nivel del MVP. Se
  implementa una forma **derivada y consistente**: prefijo de oxígenos + `oxo` +
  raíz + `ato`, con el número de oxidación del átomo central en romanos
  (calculado por balance de cargas). Para Stock del oxácido se antepone `ácido` y
  se usa el adjetivo tradicional con el prefijo de oxígenos
  (`ácido tetraoxosulfúrico (VI)`). En oxisales con el grupo repetido se usan los
  prefijos multiplicativos `bis(…)`, `tris(…)` (`bis(trioxonitrato) de calcio`).
- **Valencias sin raíz -oso/-ico** (p. ej. cromo +6, manganeso +7): no se inventa
  un nombre tradicional; se devuelve la forma con número de oxidación y se deja
  constancia en `notes`.

### Garantías

Los campos `traditional`, `stock` y `systematic` **nunca** quedan vacíos ni
`null`. `notes` siempre es una cadena (vacía si no aplica) y aclara cuándo se usó
una forma simplificada. Es una implementación a **nivel MVP educativo**.
