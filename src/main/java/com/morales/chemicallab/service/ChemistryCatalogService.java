package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AcidNonMetalResponse;
import com.morales.chemicallab.dto.BinaryAnionResponse;
import com.morales.chemicallab.dto.CentralElementResponse;
import com.morales.chemicallab.dto.MetalResponse;
import com.morales.chemicallab.dto.OxoanionResponse;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catálogo del motor químico y fuente de verdad del backend.
 *
 * Centraliza los metales (con sus valencias), los aniones monoatómicos para
 * sales binarias, los no metales que forman hidrácido y los oxoaniones (grupos
 * oxácidos). El frontend obtiene estas listas por endpoint y no necesita
 * mantener catálogos químicos propios.
 *
 * Las listas son fijas y de nivel escolar, pensadas para ampliarse agregando una
 * entrada más. No incluyen gases nobles, oxígeno (que forma óxidos) ni hidrógeno
 * (que forma hidruros) como aniones, porque corresponden a otros compuestos.
 *
 * La nomenclatura tradicional/sistemática/Stock no se resuelve aquí: solo se
 * expone el nombre básico del anión o del grupo.
 */
@Service
public class ChemistryCatalogService {

    /**
     * Metales soportados con sus valencias positivas permitidas. El backend es
     * la fuente de verdad: la valencia que llegue en una petición debe estar en
     * esta lista.
     */
    private static final List<MetalResponse> METALS = List.of(
            new MetalResponse("Li", "litio", List.of(1)),
            new MetalResponse("Na", "sodio", List.of(1)),
            new MetalResponse("K", "potasio", List.of(1)),
            new MetalResponse("Rb", "rubidio", List.of(1)),
            new MetalResponse("Cs", "cesio", List.of(1)),
            new MetalResponse("Be", "berilio", List.of(2)),
            new MetalResponse("Mg", "magnesio", List.of(2)),
            new MetalResponse("Ca", "calcio", List.of(2)),
            new MetalResponse("Sr", "estroncio", List.of(2)),
            new MetalResponse("Ba", "bario", List.of(2)),
            new MetalResponse("Al", "aluminio", List.of(3)),
            new MetalResponse("Zn", "zinc", List.of(2)),
            new MetalResponse("Ag", "plata", List.of(1)),
            new MetalResponse("Fe", "hierro", List.of(2, 3)),
            new MetalResponse("Cu", "cobre", List.of(1, 2)),
            new MetalResponse("Hg", "mercurio", List.of(1, 2)),
            new MetalResponse("Sn", "estaño", List.of(2, 4)),
            new MetalResponse("Pb", "plomo", List.of(2, 4)),
            new MetalResponse("Ni", "níquel", List.of(2, 3)),
            new MetalResponse("Co", "cobalto", List.of(2, 3)),
            new MetalResponse("Cr", "cromo", List.of(2, 3, 6)),
            new MetalResponse("Mn", "manganeso", List.of(2, 4, 7)),
            new MetalResponse("Au", "oro", List.of(1, 3)),
            new MetalResponse("Pt", "platino", List.of(2, 4))
    );

    /**
     * Aniones monoatómicos para sales binarias (no metal con carga negativa).
     * La carga se expresa como valor absoluto, igual que en el motor.
     */
    private static final List<BinaryAnionResponse> BINARY_ANIONS = List.of(
            new BinaryAnionResponse("F", "fluoruro", 1),
            new BinaryAnionResponse("Cl", "cloruro", 1),
            new BinaryAnionResponse("Br", "bromuro", 1),
            new BinaryAnionResponse("I", "yoduro", 1),
            new BinaryAnionResponse("S", "sulfuro", 2),
            new BinaryAnionResponse("Se", "seleniuro", 2),
            new BinaryAnionResponse("Te", "telururo", 2),
            new BinaryAnionResponse("N", "nitruro", 3),
            new BinaryAnionResponse("P", "fosfuro", 3),
            new BinaryAnionResponse("C", "carburo", 4)
    );

    /**
     * No metales que forman ácido hidrácido al combinarse con hidrógeno:
     * halógenos (-1) y anfígenos (-2). Es un subconjunto de los aniones
     * binarios; nitruro, fosfuro y carburo no forman hidrácido habitual.
     */
    private static final List<AcidNonMetalResponse> ACID_NON_METALS = List.of(
            new AcidNonMetalResponse("F", "fluoruro", 1),
            new AcidNonMetalResponse("Cl", "cloruro", 1),
            new AcidNonMetalResponse("Br", "bromuro", 1),
            new AcidNonMetalResponse("I", "yoduro", 1),
            new AcidNonMetalResponse("S", "sulfuro", 2),
            new AcidNonMetalResponse("Se", "seleniuro", 2),
            new AcidNonMetalResponse("Te", "telururo", 2)
    );

    /**
     * Oxoaniones (grupos oxácidos) para oxisales y oxácidos. La carga se expresa
     * como valor absoluto. La clave es estable y sirve de referencia en las
     * peticiones.
     */
    private static final List<OxoanionResponse> OXOANIONS = List.of(
            new OxoanionResponse("sulfato", "sulfato", "SO4", 2, "S"),
            new OxoanionResponse("sulfito", "sulfito", "SO3", 2, "S"),
            new OxoanionResponse("nitrato", "nitrato", "NO3", 1, "N"),
            new OxoanionResponse("nitrito", "nitrito", "NO2", 1, "N"),
            new OxoanionResponse("carbonato", "carbonato", "CO3", 2, "C"),
            new OxoanionResponse("fosfato", "fosfato", "PO4", 3, "P"),
            new OxoanionResponse("fosfito", "fosfito", "PO3", 3, "P"),
            new OxoanionResponse("hipoclorito", "hipoclorito", "ClO", 1, "Cl"),
            new OxoanionResponse("clorito", "clorito", "ClO2", 1, "Cl"),
            new OxoanionResponse("clorato", "clorato", "ClO3", 1, "Cl"),
            new OxoanionResponse("perclorato", "perclorato", "ClO4", 1, "Cl"),
            new OxoanionResponse("bromato", "bromato", "BrO3", 1, "Br"),
            new OxoanionResponse("yodato", "yodato", "IO3", 1, "I"),
            new OxoanionResponse("permanganato", "permanganato", "MnO4", 1, "Mn"),
            new OxoanionResponse("cromato", "cromato", "CrO4", 2, "Cr"),
            new OxoanionResponse("dicromato", "dicromato", "Cr2O7", 2, "Cr")
    );

    /** Nombres en español de los elementos centrales de los oxoaniones. */
    private static final Map<String, String> CENTRAL_ELEMENT_NAMES = Map.of(
            "S", "azufre",
            "N", "nitrógeno",
            "C", "carbono",
            "P", "fósforo",
            "Cl", "cloro",
            "Br", "bromo",
            "I", "yodo",
            "Mn", "manganeso",
            "Cr", "cromo"
    );

    // ===== Listados (endpoints de catálogo) =====

    /** Devuelve el catálogo de metales con sus valencias. */
    public List<MetalResponse> metals() {
        return METALS;
    }

    /** Devuelve el catálogo de aniones monoatómicos para sales binarias. */
    public List<BinaryAnionResponse> binaryAnions() {
        return BINARY_ANIONS;
    }

    /** Devuelve el catálogo de no metales que forman hidrácido. */
    public List<AcidNonMetalResponse> acidNonMetals() {
        return ACID_NON_METALS;
    }

    /** Devuelve el catálogo completo de oxoaniones. */
    public List<OxoanionResponse> oxoanions() {
        return OXOANIONS;
    }

    /** Devuelve los oxoaniones cuyo elemento central coincide con el indicado. */
    public List<OxoanionResponse> oxoanions(String centralElement) {
        return OXOANIONS.stream()
                .filter(group -> group.centralElement().equalsIgnoreCase(centralElement))
                .toList();
    }

    /**
     * Devuelve los elementos centrales presentes en el catálogo de oxoaniones,
     * en su orden de aparición y sin repetir, con su nombre en español.
     */
    public List<CentralElementResponse> oxoanionCentralElements() {
        Map<String, CentralElementResponse> unique = new LinkedHashMap<>();
        for (OxoanionResponse group : OXOANIONS) {
            unique.computeIfAbsent(group.centralElement(), symbol ->
                    new CentralElementResponse(symbol, CENTRAL_ELEMENT_NAMES.getOrDefault(symbol, symbol)));
        }
        return List.copyOf(unique.values());
    }

    // ===== Búsquedas para el motor (validación y derivación de datos) =====

    /** Busca un metal por símbolo (sensible a la forma canónica del símbolo). */
    public Optional<MetalResponse> findMetal(String symbol) {
        return METALS.stream().filter(metal -> metal.symbol().equals(symbol)).findFirst();
    }

    /** Busca un anión binario por símbolo del no metal. */
    public Optional<BinaryAnionResponse> findBinaryAnion(String symbol) {
        return BINARY_ANIONS.stream().filter(anion -> anion.symbol().equals(symbol)).findFirst();
    }

    /** Busca un no metal de ácido hidrácido por símbolo. */
    public Optional<AcidNonMetalResponse> findAcidNonMetal(String symbol) {
        return ACID_NON_METALS.stream().filter(anion -> anion.symbol().equals(symbol)).findFirst();
    }

    /** Busca un oxoanión por su clave. */
    public Optional<OxoanionResponse> findOxoanion(String key) {
        return OXOANIONS.stream().filter(group -> group.key().equals(key)).findFirst();
    }
}
