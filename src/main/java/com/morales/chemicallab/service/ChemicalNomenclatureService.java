package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AcidNonMetalResponse;
import com.morales.chemicallab.dto.BinaryAnionResponse;
import com.morales.chemicallab.dto.MetalResponse;
import com.morales.chemicallab.dto.NomenclatureResponse;
import com.morales.chemicallab.dto.OxoanionResponse;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resuelve la nomenclatura tradicional, de Stock y sistemática de cada compuesto
 * que forma el {@link ChemicalEngineService}.
 *
 * Se mantiene separado del motor de fórmulas para que cada servicio tenga una
 * responsabilidad clara: el motor cruza cargas y arma la fórmula; este servicio
 * traduce esa combinación a nombres. Usa el {@link ChemistryCatalogService} como
 * fuente de verdad para decidir, por ejemplo, si un metal tiene más de una
 * valencia (y por tanto necesita número de oxidación en Stock).
 *
 * <p><b>Alcance (MVP educativo):</b> los oxácidos y oxisales usan, para Stock y
 * sistemática, una forma derivada y documentada (prefijo de oxígenos + «oxo» +
 * raíz + «ato», con el número de oxidación en romanos). Cuando no hay una raíz
 * tradicional -oso/-ico definida para una valencia, se cae en una forma con
 * número de oxidación y se deja constancia en {@code notes}. Nunca se devuelven
 * campos vacíos ni {@code null}.
 */
@Service
public class ChemicalNomenclatureService {

    private final ChemistryCatalogService catalog;

    public ChemicalNomenclatureService(ChemistryCatalogService catalog) {
        this.catalog = catalog;
    }

    // ===== Tablas de apoyo =====

    /** Prefijos multiplicadores sistemáticos por cantidad (índice = cantidad). */
    private static final String[] PREFIXES = {
            "", "mono", "di", "tri", "tetra", "penta", "hexa", "hepta", "octa", "nona", "deca"
    };

    /** Números romanos por valor (índice = valor). */
    private static final String[] ROMAN = {
            "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    /**
     * Raíces tradicionales -oso/-ico de los metales con más de una valencia,
     * indexadas por símbolo y valencia. Si una valencia no está aquí, se usa una
     * forma con número de oxidación y se documenta en las notas.
     */
    private static final Map<String, Map<Integer, String>> METAL_TRAD_ROOTS = Map.ofEntries(
            Map.entry("Fe", Map.of(2, "ferroso", 3, "férrico")),
            Map.entry("Cu", Map.of(1, "cuproso", 2, "cúprico")),
            Map.entry("Hg", Map.of(1, "mercurioso", 2, "mercúrico")),
            Map.entry("Sn", Map.of(2, "estañoso", 4, "estáñico")),
            Map.entry("Pb", Map.of(2, "plumboso", 4, "plúmbico")),
            Map.entry("Au", Map.of(1, "auroso", 3, "áurico")),
            Map.entry("Co", Map.of(2, "cobaltoso", 3, "cobáltico")),
            Map.entry("Ni", Map.of(2, "niqueloso", 3, "niquélico")),
            Map.entry("Cr", Map.of(2, "cromoso", 3, "crómico")),
            Map.entry("Mn", Map.of(2, "manganoso", 4, "mangánico")),
            Map.entry("Pt", Map.of(2, "platinoso", 4, "platínico"))
    );

    /** Nombre tradicional del ácido hidrácido por símbolo del no metal. */
    private static final Map<String, String> HYDRACID_TRADITIONAL = Map.of(
            "F", "ácido fluorhídrico",
            "Cl", "ácido clorhídrico",
            "Br", "ácido bromhídrico",
            "I", "ácido yodhídrico",
            "S", "ácido sulfhídrico",
            "Se", "ácido selenhídrico",
            "Te", "ácido telurhídrico"
    );

    /** Adjetivo tradicional del oxácido por clave de oxoanión (p. ej. «sulfúrico»). */
    private static final Map<String, String> OXOACID_ADJECTIVE = Map.ofEntries(
            Map.entry("sulfato", "sulfúrico"),
            Map.entry("sulfito", "sulfuroso"),
            Map.entry("nitrato", "nítrico"),
            Map.entry("nitrito", "nitroso"),
            Map.entry("carbonato", "carbónico"),
            Map.entry("fosfato", "fosfórico"),
            Map.entry("fosfito", "fosforoso"),
            Map.entry("hipoclorito", "hipocloroso"),
            Map.entry("clorito", "cloroso"),
            Map.entry("clorato", "clórico"),
            Map.entry("perclorato", "perclórico"),
            Map.entry("bromato", "brómico"),
            Map.entry("yodato", "yódico"),
            Map.entry("permanganato", "permangánico"),
            Map.entry("cromato", "crómico"),
            Map.entry("dicromato", "dicrómico")
    );

    /**
     * Adjetivo tradicional del metal de valencia única, común a óxidos,
     * hidróxidos, sales y oxisales (p. ej. «cálcico» → «óxido cálcico»,
     * «hidróxido cálcico», «cloruro cálcico»). Los metales con varias valencias
     * usan las raíces -oso/-ico de {@link #METAL_TRAD_ROOTS}.
     */
    private static final Map<String, String> METAL_ADJECTIVE = Map.ofEntries(
            Map.entry("Li", "lítico"),
            Map.entry("Na", "sódico"),
            Map.entry("K", "potásico"),
            Map.entry("Rb", "rubídico"),
            Map.entry("Cs", "césico"),
            Map.entry("Be", "berílico"),
            Map.entry("Mg", "magnésico"),
            Map.entry("Ca", "cálcico"),
            Map.entry("Sr", "estróncico"),
            Map.entry("Ba", "bárico"),
            Map.entry("Al", "alumínico"),
            Map.entry("Zn", "cíncico"),
            Map.entry("Ag", "argéntico")
    );

    /**
     * Nombre tradicional del anhídrido (óxido no metálico) por símbolo del no
     * metal y valencia. Los no metales con varias valencias usan los prefijos
     * hipo-/per- y sufijos -oso/-ico clásicos.
     */
    private static final Map<String, Map<Integer, String>> ANHYDRIDE_NAMES = Map.ofEntries(
            Map.entry("C", Map.of(2, "anhídrido carbonoso", 4, "anhídrido carbónico")),
            Map.entry("S", Map.of(4, "anhídrido sulfuroso", 6, "anhídrido sulfúrico")),
            Map.entry("Se", Map.of(4, "anhídrido selenioso", 6, "anhídrido selénico")),
            Map.entry("Te", Map.of(4, "anhídrido teluroso", 6, "anhídrido telúrico")),
            Map.entry("N", Map.of(3, "anhídrido nitroso", 5, "anhídrido nítrico")),
            Map.entry("P", Map.of(3, "anhídrido fosforoso", 5, "anhídrido fosfórico")),
            Map.entry("Cl", Map.of(1, "anhídrido hipocloroso", 3, "anhídrido cloroso",
                    5, "anhídrido clórico", 7, "anhídrido perclórico")),
            Map.entry("Br", Map.of(1, "anhídrido hipobromoso", 3, "anhídrido bromoso",
                    5, "anhídrido brómico", 7, "anhídrido perbrómico")),
            Map.entry("I", Map.of(1, "anhídrido hipoyodoso", 3, "anhídrido yodoso",
                    5, "anhídrido yódico", 7, "anhídrido peryódico"))
    );

    /** Raíz sistemática del elemento central para los nombres «...ato». */
    private static final Map<String, String> CENTRAL_STEM = Map.of(
            "S", "sulf",
            "N", "nitr",
            "C", "carbon",
            "P", "fosf",
            "Cl", "clor",
            "Br", "brom",
            "I", "yod",
            "Mn", "mangan",
            "Cr", "crom"
    );

    /**
     * Raíz «-ico» del oxácido por elemento central, usada en la nomenclatura de
     * Stock de oxácidos (p. ej. «ácido trioxosulfúrico (IV)»). Es independiente
     * del número de oxígenos: el estado de oxidación se indica con el romano.
     */
    private static final Map<String, String> STOCK_ACID_ROOT = Map.of(
            "S", "sulfúrico",
            "N", "nítrico",
            "C", "carbónico",
            "P", "fosfórico",
            "Cl", "clórico",
            "Br", "brómico",
            "I", "yódico",
            "Mn", "mangánico",
            "Cr", "crómico"
    );

    // ===== Óxidos =====

    /**
     * Nomenclatura de un óxido (elemento + oxígeno).
     *
     * <p>Distingue dos casos según el elemento principal:
     * <ul>
     *   <li><b>Óxido metálico</b> (el elemento está en el catálogo de metales):
     *       la tradicional usa el adjetivo del metal (valencia única, p. ej.
     *       «óxido cálcico») o la raíz -oso/-ico (varias valencias, «óxido
     *       ferroso»/«óxido férrico»).</li>
     *   <li><b>Anhídrido</b> (el elemento es no metal): la tradicional usa el
     *       nombre de anhídrido (p. ej. «anhídrido fosforoso»).</li>
     * </ul>
     *
     * <p>El número de oxidación de Stock se toma de la valencia con la que el
     * elemento se combina (el oxígeno trabaja con -2): se muestra en romanos para
     * metales con varias valencias y siempre para los no metales.
     */
    public NomenclatureResponse forOxide(String symbol, String elementName, int valence) {
        int[] subs = crossSubscripts(valence, 2); // {subíndice del elemento, subíndice del oxígeno}
        String systematic = oxidePrefixed(subs[1]) + " de " + sysPrefixOmitMono(subs[0]) + elementName;

        Optional<MetalResponse> metal = catalog.findMetal(symbol);
        if (metal.isEmpty()) {
            return anhydrideNomenclature(symbol, elementName, valence, systematic);
        }
        return metalBased("óxido", metal.get(), valence, systematic);
    }

    /** Nomenclatura de un anhídrido (óxido no metálico). Stock siempre con romano. */
    private NomenclatureResponse anhydrideNomenclature(String symbol, String elementName,
                                                       int valence, String systematic) {
        Map<Integer, String> byValence = ANHYDRIDE_NAMES.get(symbol);
        String anhydride = byValence == null ? null : byValence.get(valence);
        String traditional;
        String notes = "";
        if (anhydride != null) {
            traditional = anhydride;
        } else {
            traditional = "anhídrido de " + elementName;
            notes = "Sin nombre de anhídrido definido para la valencia " + valence
                    + "; se usa «anhídrido de " + elementName + "».";
        }
        String stock = "óxido de " + elementName + " (" + roman(valence) + ")";
        return new NomenclatureResponse(traditional, stock, systematic, notes);
    }

    // ===== Hidróxidos =====

    /** Nomenclatura de un hidróxido (metal + grupo OH⁻). */
    public NomenclatureResponse forHydroxide(MetalResponse metal, int valence) {
        // OH trabaja con carga 1: el subíndice del grupo coincide con la valencia.
        // La sistemática siempre lleva prefijo en el hidróxido (monohidróxido…).
        String systematic = sysPrefixAlways(valence) + "hidróxido de " + metal.name();
        return metalBased("hidróxido", metal, valence, systematic);
    }

    // ===== Sales binarias =====

    /** Nomenclatura de una sal binaria (metal + no metal). */
    public NomenclatureResponse forBinarySalt(MetalResponse metal, int valence, BinaryAnionResponse anion) {
        int[] subs = crossSubscripts(valence, anion.charge()); // {subíndice metal, subíndice anión}
        // El no metal siempre lleva prefijo (monocloruro…); el metal omite «mono».
        String systematic = sysPrefixAlways(subs[1]) + anion.name()
                + " de " + sysPrefixOmitMono(subs[0]) + metal.name();
        return metalBased(anion.name(), metal, valence, systematic);
    }

    // ===== Ácidos hidrácidos =====

    /** Nomenclatura de un ácido hidrácido (hidrógeno + no metal). */
    public NomenclatureResponse forHydracid(AcidNonMetalResponse nonMetal) {
        String traditional = HYDRACID_TRADITIONAL.get(nonMetal.symbol());
        String notes = "";
        if (traditional == null) {
            traditional = nonMetal.name() + " de hidrógeno";
            notes = "Sin nombre tradicional definido para este no metal; se usa la forma de Stock.";
        }
        String stock = nonMetal.name() + " de hidrógeno";
        // El subíndice del hidrógeno coincide con la carga del no metal.
        String systematic = nonMetal.name() + " de " + sysPrefixOmitMono(nonMetal.charge()) + "hidrógeno";
        return new NomenclatureResponse(traditional, stock, systematic, notes);
    }

    // ===== Oxácidos =====

    /**
     * Nomenclatura de un oxácido (hidrógeno + oxoanión). El nombre tradicional usa
     * el adjetivo clásico (p. ej. «ácido sulfúrico»). Stock y sistemática usan la
     * forma derivada y documentada con prefijo de oxígenos, «oxo», raíz «...ato» y
     * número de oxidación en romanos.
     */
    public NomenclatureResponse forOxoacid(OxoanionResponse group) {
        int hydrogen = group.charge();
        int oxygens = oxygenCount(group.formula());
        int centralCount = centralCount(group.formula(), group.centralElement());
        int oxidation = (2 * oxygens - hydrogen) / centralCount;

        String stem = CENTRAL_STEM.getOrDefault(group.centralElement(), group.centralElement().toLowerCase());
        String anionSystematic = PREFIXES[clampPrefix(oxygens)] + "oxo"
                + (centralCount > 1 ? PREFIXES[clampPrefix(centralCount)] : "") + stem + "ato";

        String adjective = OXOACID_ADJECTIVE.get(group.key());
        String traditional;
        String notes;
        if (adjective != null) {
            traditional = "ácido " + adjective;
            notes = "Stock y sistemática del oxácido en forma derivada (prefijo de oxígenos + «oxo» + «ato»).";
        } else {
            traditional = "ácido de " + group.name();
            notes = "Sin adjetivo tradicional definido; se documenta la forma derivada del oxácido.";
        }

        // Stock «de ácidos»: ácido + (oxígenos)oxo + raíz «-ico» del elemento + (n.º oxidación).
        // La raíz «-ico» es fija por elemento central; el estado de oxidación lo da el romano.
        String stockRoot = STOCK_ACID_ROOT.getOrDefault(group.centralElement(), stem + "ico");
        String stock = "ácido " + PREFIXES[clampPrefix(oxygens)] + "oxo" + stockRoot + " (" + roman(oxidation) + ")";

        String systematic = anionSystematic + " (" + roman(oxidation) + ") de "
                + sysPrefixOmitMono(hydrogen) + "hidrógeno";

        return new NomenclatureResponse(traditional, stock, systematic, notes);
    }

    // ===== Oxisales =====

    /**
     * Nomenclatura de una oxisal (metal + oxoanión). Tradicional y Stock usan el
     * nombre del oxoanión («sulfato de…»); la sistemática usa el nombre derivado
     * del oxoanión con prefijo multiplicador (bis-, tris-…) cuando el grupo se
     * repite, en forma simplificada y documentada.
     */
    public NomenclatureResponse forOxisalt(MetalResponse metal, int valence, OxoanionResponse group) {
        int[] subs = crossSubscripts(valence, group.charge()); // {subíndice metal, subíndice grupo}
        int metalSub = subs[0];
        int groupSub = subs[1];

        int oxygens = oxygenCount(group.formula());
        int centralCount = centralCount(group.formula(), group.centralElement());
        int oxidation = (2 * oxygens - group.charge()) / centralCount;
        String stem = CENTRAL_STEM.getOrDefault(group.centralElement(), group.centralElement().toLowerCase());
        String anionSystematic = PREFIXES[clampPrefix(oxygens)] + "oxo"
                + (centralCount > 1 ? PREFIXES[clampPrefix(centralCount)] : "") + stem + "ato";

        // El estado de oxidación del grupo se conserva dentro del paréntesis aunque
        // el grupo se multiplique: «bis(trioxonitrato (V)) de calcio».
        String anionWithOxidation = anionSystematic + " (" + roman(oxidation) + ")";
        String groupPart = groupSub == 1
                ? anionWithOxidation
                : multiplicative(groupSub) + "(" + anionWithOxidation + ")";

        // El número de oxidación del metal solo se añade cuando el grupo no lleva
        // prefijo multiplicador (groupSub == 1); con «bis», «tris»… la estequiometría
        // ya determina el estado de oxidación y el romano sería redundante.
        boolean multivalent = metal.valences().size() > 1;
        String metalPart = (metalSub > 1 ? PREFIXES[clampPrefix(metalSub)] : "") + metal.name();
        if (multivalent && groupSub == 1) {
            metalPart = metalPart + " (" + roman(valence) + ")";
        }
        String systematic = groupPart + " de " + metalPart;

        return metalBased(group.name(), metal, valence, systematic);
    }

    // ===== Núcleo común para compuestos metal + (anión/grupo) =====

    /**
     * Arma la nomenclatura de un compuesto cuyo catión es un metal y cuyo nombre
     * base es {@code baseNoun} («óxido», «hidróxido», «cloruro», «sulfato»…).
     *
     * <p>Criterio único del MVP para la nomenclatura tradicional:
     * <ul>
     *   <li>Metal con <b>varias valencias</b>: raíz -oso/-ico según la valencia
     *       (p. ej. «cloruro ferroso»/«cloruro férrico»). Si no hay raíz definida,
     *       se cae en la forma con número de oxidación y se documenta.</li>
     *   <li>Metal de <b>valencia única</b>: adjetivo del metal cuando existe
     *       («cloruro sódico», «hidróxido cálcico»); si no, fallback «base de
     *       elemento» documentado en {@code notes}.</li>
     * </ul>
     * El Stock usa «base de elemento» y añade el romano solo si el metal tiene
     * varias valencias. La sistemática se calcula fuera y se recibe ya resuelta.
     */
    private NomenclatureResponse metalBased(String baseNoun, MetalResponse metal,
                                            int valence, String systematic) {
        String name = metal.name();
        String traditional;
        String stock;
        String notes = "";

        if (metal.valences().size() > 1) {
            String root = rootFor(metal.symbol(), valence);
            if (root != null) {
                traditional = baseNoun + " " + root;
            } else {
                traditional = baseNoun + " de " + name + " (" + roman(valence) + ")";
                notes = "Sin raíz tradicional -oso/-ico para la valencia " + valence
                        + "; se usa la forma con número de oxidación.";
            }
            stock = baseNoun + " de " + name + " (" + roman(valence) + ")";
        } else {
            String adjective = METAL_ADJECTIVE.get(metal.symbol());
            if (adjective != null) {
                traditional = baseNoun + " " + adjective;
            } else {
                traditional = baseNoun + " de " + name;
                notes = "Sin adjetivo tradicional definido para este metal; se usa «" + baseNoun
                        + " de " + name + "».";
            }
            stock = baseNoun + " de " + name;
        }

        return new NomenclatureResponse(traditional, stock, systematic, notes);
    }

    // ===== Utilidades =====

    /** Raíz tradicional -oso/-ico de un metal para una valencia, o {@code null}. */
    private String rootFor(String symbol, int valence) {
        Map<Integer, String> roots = METAL_TRAD_ROOTS.get(symbol);
        return roots == null ? null : roots.get(valence);
    }

    /**
     * Subíndices cruzados (simplificados por máximo común divisor) para un catión
     * y un anión. Devuelve {@code {subíndice del catión, subíndice del anión}}.
     */
    private int[] crossSubscripts(int cationCharge, int anionCharge) {
        int g = gcd(cationCharge, anionCharge);
        return new int[]{anionCharge / g, cationCharge / g};
    }

    private int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** Número romano del valor; fuera de tabla devuelve el número en texto. */
    private String roman(int value) {
        return value >= 0 && value < ROMAN.length ? ROMAN[value] : String.valueOf(value);
    }

    /** Prefijo multiplicador sistemático; omite «mono» cuando la cantidad es 1. */
    private String sysPrefixOmitMono(int count) {
        if (count == 1) {
            return "";
        }
        return PREFIXES[clampPrefix(count)];
    }

    /** Prefijo multiplicador sistemático conservando «mono» para la cantidad 1. */
    private String sysPrefixAlways(int count) {
        return PREFIXES[clampPrefix(count)];
    }

    /** Prefijo multiplicativo para grupos repetidos (bis-, tris-, tetrakis-…). */
    private String multiplicative(int count) {
        return switch (count) {
            case 2 -> "bis";
            case 3 -> "tris";
            case 4 -> "tetrakis";
            case 5 -> "pentakis";
            default -> PREFIXES[clampPrefix(count)];
        };
    }

    /**
     * Prefija la palabra «óxido» elidiendo la vocal final del prefijo
     * (mono → monóxido, tetra → tetróxido) y conservando «di»/«tri».
     */
    private String oxidePrefixed(int count) {
        String prefix = PREFIXES[clampPrefix(count)];
        if (prefix.isEmpty()) {
            return "óxido";
        }
        char last = prefix.charAt(prefix.length() - 1);
        if (last == 'a' || last == 'o') {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "óxido";
    }

    /** Mantiene el índice dentro de la tabla de prefijos (defensa ante valores altos). */
    private int clampPrefix(int count) {
        if (count < 0) {
            return 0;
        }
        return Math.min(count, PREFIXES.length - 1);
    }

    private static final Pattern OXYGEN_PATTERN = Pattern.compile("O(\\d*)$");

    /** Cuenta los oxígenos a partir de la fórmula del oxoanión (p. ej. «SO4» → 4). */
    private int oxygenCount(String formula) {
        Matcher matcher = OXYGEN_PATTERN.matcher(formula);
        if (matcher.find()) {
            String digits = matcher.group(1);
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        }
        return 0;
    }

    /** Cuenta los átomos del elemento central (p. ej. «Cr2O7» → 2 para Cr). */
    private int centralCount(String formula, String central) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(central) + "(\\d*)").matcher(formula);
        if (matcher.find()) {
            String digits = matcher.group(1);
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        }
        return 1;
    }
}
