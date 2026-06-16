# Motor químico

Documentación del motor de formación de compuestos del MVP educativo. Cubre los
endpoints, los catálogos y el contrato de las peticiones tras la Sesión 18.

> **Alcance:** en esta etapa el motor devuelve la **fórmula correcta**, el **tipo
> de compuesto** y una **explicación básica**. La **nomenclatura tradicional,
> sistemática y de Stock queda pendiente para la Sesión 19** y no se implementa
> todavía. Los nombres devueltos son nombres base (p. ej. «cloruro de sodio»), no
> nomenclatura con números de oxidación ni sufijos -oso/-ico.

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
{ "valid": true, "compoundType": "Sal binaria", "formula": "NaCl", "name": "cloruro de sodio", "explanation": "..." }
```

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
