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

## Auditoría de catálogos y alcance (validación integral)

El núcleo químico se validó de forma integral en la Sesión 19.2. Esta es la
referencia de lo **soportado** y lo **excluido**, a nivel escolar/MVP (no cubre
toda la química avanzada).

### Catálogos soportados

- **Metales (24)** con sus valencias: Li⁺¹, Na⁺¹, K⁺¹, Rb⁺¹, Cs⁺¹, Be⁺², Mg⁺²,
  Ca⁺², Sr⁺², Ba⁺², Al⁺³, Zn⁺², Ag⁺¹, Fe⁺²ʼ⁺³, Cu⁺¹ʼ⁺², Hg⁺¹ʼ⁺², Sn⁺²ʼ⁺⁴,
  Pb⁺²ʼ⁺⁴, Ni⁺²ʼ⁺³, Co⁺²ʼ⁺³, Cr⁺²ʼ⁺³ʼ⁺⁶, Mn⁺²ʼ⁺⁴ʼ⁺⁷, Au⁺¹ʼ⁺³, Pt⁺²ʼ⁺⁴.
- **Aniones de sal binaria (10):** fluoruro F⁻¹, cloruro Cl⁻¹, bromuro Br⁻¹,
  yoduro I⁻¹, sulfuro S⁻², seleniuro Se⁻², telururo Te⁻², nitruro N⁻³,
  fosfuro P⁻³, carburo C⁻⁴.
- **No metales de hidrácido (7):** F, Cl, Br, I (−1) y S, Se, Te (−2).
- **Oxoaniones (16):** sulfato (SO₄⁻²), sulfito (SO₃⁻²), nitrato (NO₃⁻¹),
  nitrito (NO₂⁻¹), carbonato (CO₃⁻²), fosfato (PO₄⁻³), fosfito (PO₃⁻³),
  hipoclorito (ClO⁻¹), clorito (ClO₂⁻¹), clorato (ClO₃⁻¹), perclorato (ClO₄⁻¹),
  bromato (BrO₃⁻¹), yodato (IO₃⁻¹), permanganato (MnO₄⁻¹), cromato (CrO₄⁻²),
  dicromato (Cr₂O₇⁻²).

### Elementos excluidos y razón

- **Gases nobles** (He, Ne, Ar, Kr, Xe, Rn): no forman compuestos en el alcance.
- **Oxígeno como anión de sal binaria:** con un metal forma un óxido, no una sal.
- **Hidrógeno como anión de sal binaria:** fuera del alcance escolar.
- **N, P, C como hidrácidos:** se mantienen como aniones binarios (nitruro,
  fosfuro, carburo) pero no como hidrácidos, según el criterio escolar.

### Reglas de validación verificadas

El motor lanza `IllegalArgumentException` (HTTP 400) ante: gas noble, oxígeno o
hidrógeno como anión de sal binaria, valencia no permitida para un metal, no
metal que no forma hidrácido, oxoanión inexistente y elemento fuera del catálogo.
La fórmula cruza cargas, simplifica por MCD, omite el subíndice 1 y usa
paréntesis solo en grupos poliatómicos con subíndice mayor a 1.

### Matriz de fórmulas validadas (tests)

| Tipo       | Casos cubiertos en `ChemicalEngineServiceTest`                          |
| ---------- | ----------------------------------------------------------------------- |
| Óxidos     | Na₂O, CaO, Al₂O₃, FeO, Fe₂O₃, P₂O₃, P₂O₅, SO₂, SO₃                       |
| Hidróxidos | NaOH, Ca(OH)₂, Al(OH)₃, Fe(OH)₂, Fe(OH)₃, Sn(OH)₂, Sn(OH)₄              |
| Hidrácidos | HF, HCl, HBr, HI, H₂S, H₂Se, H₂Te                                       |
| Oxácidos   | H₂SO₄, H₂SO₃, HNO₃, HNO₂, H₂CO₃, H₃PO₄, H₃PO₃, HClO₃, HClO, HClO₄       |
| Sales      | NaCl, CaCl₂, Al₂S₃, FeCl₂, FeCl₃, CuBr, CuBr₂, SnCl₂, SnCl₄, Sn₃N₄, ZnS |
| Oxisales   | Na₂SO₄, CaSO₄, Al₂(SO₄)₃, NaNO₃, Ca(NO₃)₂, FePO₄, Mg₃(PO₄)₂, K₂CO₃, CaCO₃, CuSO₄, Sn(SO₄)₂ |

## Nomenclaturas

Desde la Sesión 19 cada respuesta incluye un objeto `nomenclature` con tres
sistemas de nombres, resueltos por `ChemicalNomenclatureService`. El motor de
fórmulas y el de nombres están separados: el primero cruza cargas, el segundo
traduce la combinación a nombres usando el catálogo como fuente de verdad (por
ejemplo, para saber si un metal tiene más de una valencia).

### Criterio único del MVP

El proyecto usa un criterio **uniforme** para óxidos, hidróxidos, sales y
oxisales (los óxidos no metálicos se nombran como anhídridos, ver más abajo):

- **Tradicional:**
  - Metal con **varias valencias** → raíz -oso/-ico según la valencia
    (`cloruro ferroso` / `cloruro férrico`).
  - Metal de **valencia única** → adjetivo del metal cuando existe
    (`óxido cálcico`, `hidróxido sódico`, `cloruro cálcico`, `sulfato sódico`).
    Si no hay adjetivo, fallback «base de elemento» documentado en `notes`.
  - No metal en óxido → nombre de **anhídrido** (`anhídrido sulfúrico`).
- **Stock:** forma «base de elemento»; se añade el número de oxidación en romanos
  cuando el elemento tiene **valencia variable** (`óxido de hierro (II)`,
  `cloruro de hierro (III)`) o cuando es un no metal (`óxido de fósforo (III)`).
  Con valencia única no lleva romano (`óxido de calcio`).
- **Sistemática:** prefijos estequiométricos `mono-, di-, tri-, tetra-…` sobre los
  subíndices reales de la fórmula. El componente **electronegativo** (oxígeno, OH,
  no metal) conserva `mono-` (`monóxido de calcio`, `monohidróxido de sodio`,
  `monocloruro de sodio`); el **metal** omite `mono-` (`de sodio`, no «de
  monosodio»).

### Óxidos: metálicos vs. anhídridos

El número de oxidación del elemento se toma de la valencia con la que se combina
(el oxígeno trabaja con -2). La nomenclatura tradicional distingue:

- **Óxidos metálicos** (el elemento está en el catálogo de metales):
  - Valencia única → adjetivo del metal: `óxido cálcico`, `óxido sódico`,
    `óxido alumínico`.
  - Varias valencias → raíz -oso/-ico: `óxido ferroso` (Fe +2), `óxido férrico`
    (Fe +3), `óxido cuproso` (Cu +1), `óxido cúprico` (Cu +2).
  - Stock: sin romano para valencia única (`óxido de calcio`); con romano para
    varias valencias (`óxido de hierro (II)`).
- **Óxidos no metálicos / anhídridos** (el elemento es no metal):
  - Tradicional como anhídrido: `anhídrido sulfuroso` (S +4), `anhídrido
    sulfúrico` (S +6), `anhídrido fosforoso` (P +3), `anhídrido fosfórico` (P +5),
    `anhídrido perclórico` (Cl +7)…
  - Stock siempre con romano: `óxido de fósforo (III)`, `óxido de azufre (IV)`.
  - Sistemática igual que los metálicos, por proporción de átomos: `trióxido de
    difósforo`, `dióxido de azufre`.
  - Si la valencia no está en el catálogo de anhídridos se usa el fallback
    `anhídrido de <elemento>` y se documenta en `notes`.

### Utilidades implementadas

- Números romanos: 1→I … 10→X.
- Prefijos sistemáticos: 1→mono … 10→deca (con elisión de vocal ante «óxido»:
  `monóxido`, `tetróxido`).
- Prefijos multiplicativos para grupos repetidos: 2→bis, 3→tris, 4→tetrakis.
- Raíces tradicionales -oso/-ico de los metales con varias valencias (hierro,
  cobre, estaño, plomo, mercurio, oro, cobalto, níquel, cromo, manganeso, platino).
- Adjetivos tradicionales de metales de valencia única (sódico, cálcico,
  alumínico, magnésico…), comunes a óxidos, hidróxidos, sales y oxisales.
- Nombres de anhídridos por no metal y valencia (carbono, azufre, nitrógeno,
  fósforo, cloro, bromo, yodo, selenio, telurio).
- Raíz «-ico» del oxácido por elemento central para el Stock de oxácidos
  (sulfúrico, nítrico, fosfórico, carbónico, clórico, brómico, yódico…).

### Alcance por tipo de compuesto

| Tipo            | Tradicional                  | Stock                          | Sistemática                              |
| --------------- | ---------------------------- | ------------------------------ | ---------------------------------------- |
| Óxidos          | `óxido ferroso`              | `óxido de hierro (II)`         | `monóxido de hierro`                     |
| Hidróxidos      | `hidróxido férrico`          | `hidróxido de hierro (III)`    | `trihidróxido de hierro`                 |
| Hidrácidos      | `ácido clorhídrico`          | `cloruro de hidrógeno`         | `cloruro de hidrógeno` / `sulfuro de dihidrógeno` |
| Oxácidos        | `ácido sulfúrico`            | `ácido tetraoxosulfúrico (VI)` | `tetraoxosulfato (VI) de dihidrógeno`    |
| Sales binarias  | `cloruro férrico`            | `cloruro de hierro (III)`      | `tricloruro de hierro`                   |
| Oxisales        | `fosfato férrico`            | `fosfato de hierro (III)`      | `tetraoxofosfato (V) de hierro (III)`    |

### Matriz de casos validados

Estos casos están cubiertos por `ChemicalNomenclatureServiceTest` (uno por fila):

| Fórmula      | Tradicional            | Stock                          | Sistemática                              |
| ------------ | ---------------------- | ------------------------------ | ---------------------------------------- |
| CaO          | óxido cálcico          | óxido de calcio                | monóxido de calcio                       |
| Na₂O         | óxido sódico           | óxido de sodio                 | monóxido de disodio                      |
| Al₂O₃        | óxido alumínico        | óxido de aluminio              | trióxido de dialuminio                   |
| FeO          | óxido ferroso          | óxido de hierro (II)           | monóxido de hierro                       |
| Fe₂O₃        | óxido férrico          | óxido de hierro (III)          | trióxido de dihierro                     |
| P₂O₃         | anhídrido fosforoso    | óxido de fósforo (III)         | trióxido de difósforo                    |
| P₂O₅         | anhídrido fosfórico    | óxido de fósforo (V)           | pentóxido de difósforo                   |
| SO₂          | anhídrido sulfuroso    | óxido de azufre (IV)           | dióxido de azufre                        |
| SO₃          | anhídrido sulfúrico    | óxido de azufre (VI)           | trióxido de azufre                       |
| Ca(OH)₂      | hidróxido cálcico      | hidróxido de calcio            | dihidróxido de calcio                    |
| NaOH         | hidróxido sódico       | hidróxido de sodio             | monohidróxido de sodio                   |
| Fe(OH)₂      | hidróxido ferroso      | hidróxido de hierro (II)       | dihidróxido de hierro                    |
| Fe(OH)₃      | hidróxido férrico      | hidróxido de hierro (III)      | trihidróxido de hierro                   |
| HCl          | ácido clorhídrico      | cloruro de hidrógeno           | cloruro de hidrógeno                     |
| HBr          | ácido bromhídrico      | bromuro de hidrógeno           | bromuro de hidrógeno                     |
| H₂S          | ácido sulfhídrico      | sulfuro de hidrógeno           | sulfuro de dihidrógeno                   |
| H₂SO₄        | ácido sulfúrico        | ácido tetraoxosulfúrico (VI)   | tetraoxosulfato (VI) de dihidrógeno      |
| H₂SO₃        | ácido sulfuroso        | ácido trioxosulfúrico (IV)     | trioxosulfato (IV) de dihidrógeno        |
| HNO₃         | ácido nítrico          | ácido trioxonítrico (V)        | trioxonitrato (V) de hidrógeno           |
| HNO₂         | ácido nitroso          | ácido dioxonítrico (III)       | dioxonitrato (III) de hidrógeno          |
| H₃PO₄        | ácido fosfórico        | ácido tetraoxofosfórico (V)    | tetraoxofosfato (V) de trihidrógeno      |
| NaCl         | cloruro sódico         | cloruro de sodio               | monocloruro de sodio                     |
| CaCl₂        | cloruro cálcico        | cloruro de calcio              | dicloruro de calcio                      |
| FeCl₂        | cloruro ferroso        | cloruro de hierro (II)         | dicloruro de hierro                      |
| FeCl₃        | cloruro férrico        | cloruro de hierro (III)        | tricloruro de hierro                     |
| Al₂S₃        | sulfuro alumínico      | sulfuro de aluminio            | trisulfuro de dialuminio                 |
| Na₂SO₄       | sulfato sódico         | sulfato de sodio               | tetraoxosulfato (VI) de disodio          |
| Ca(NO₃)₂     | nitrato cálcico        | nitrato de calcio              | bis(trioxonitrato (V)) de calcio         |
| FePO₄        | fosfato férrico        | fosfato de hierro (III)        | tetraoxofosfato (V) de hierro (III)      |
| Mg₃(PO₄)₂    | fosfato magnésico      | fosfato de magnesio            | bis(tetraoxofosfato (V)) de trimagnesio  |
| SnO          | óxido estañoso         | óxido de estaño (II)           | monóxido de estaño                       |
| SnO₂         | óxido estáñico         | óxido de estaño (IV)           | dióxido de estaño                        |
| Sn(OH)₂      | hidróxido estañoso     | hidróxido de estaño (II)       | dihidróxido de estaño                    |
| Sn(OH)₄      | hidróxido estáñico     | hidróxido de estaño (IV)       | tetrahidróxido de estaño                 |
| SnCl₂        | cloruro estañoso       | cloruro de estaño (II)         | dicloruro de estaño                      |
| SnCl₄        | cloruro estáñico       | cloruro de estaño (IV)         | tetracloruro de estaño                   |
| Sn₃N₄        | nitruro estáñico       | nitruro de estaño (IV)         | tetranitruro de triestaño                |
| Sn(SO₄)₂     | sulfato estáñico       | sulfato de estaño (IV)         | bis(tetraoxosulfato (VI)) de estaño      |

### Casos con forma simplificada y documentada

- **Oxácidos y oxisales — Stock/sistemática:** la regla completa de la
  nomenclatura sistemática de oxoácidos es avanzada para el nivel del MVP. Se
  implementa una forma **derivada y consistente**: prefijo de oxígenos + `oxo` +
  raíz + `ato`, con el número de oxidación del átomo central en romanos
  (calculado por balance de cargas). Para el Stock del oxácido se antepone `ácido`
  y se usa la raíz «-ico» del elemento central con el prefijo de oxígenos
  (`ácido tetraoxosulfúrico (VI)`, `ácido trioxosulfúrico (IV)`). En oxisales con
  el grupo repetido se usan los prefijos multiplicativos `bis(…)`, `tris(…)`,
  conservando el estado de oxidación dentro del paréntesis
  (`bis(trioxonitrato (V)) de calcio`).
- **Oxisal — número de oxidación del metal en sistemática:** se añade el romano al
  metal solo cuando el grupo **no** lleva prefijo multiplicador (subíndice del
  grupo = 1), porque en ese caso la estequiometría no lo evidencia
  (`tetraoxofosfato (V) de hierro (III)`). Con grupo multiplicado el romano sería
  redundante y se omite (`bis(tetraoxosulfato (VI)) de estaño`).
- **Variante aceptada — adjetivo vs. «de elemento»:** en tradicional se elige
  siempre el **adjetivo clásico** cuando existe (`sulfato sódico`, no «sulfato de
  sodio»). La forma «de elemento» queda reservada para Stock y como fallback
  documentado cuando falta el adjetivo.
- **Valencias sin raíz -oso/-ico** (p. ej. cromo +6, manganeso +7): no se inventa
  un nombre tradicional; se devuelve la forma con número de oxidación y se deja
  constancia en `notes`.

### Garantías

Los campos `traditional`, `stock` y `systematic` **nunca** quedan vacíos ni
`null`. `notes` siempre es una cadena (vacía si no aplica) y aclara cuándo se usó
una forma simplificada. Es una implementación a **nivel MVP educativo**.
