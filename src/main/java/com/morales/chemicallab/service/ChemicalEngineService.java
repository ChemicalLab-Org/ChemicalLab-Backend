package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AcidNonMetalResponse;
import com.morales.chemicallab.dto.AcidRequest;
import com.morales.chemicallab.dto.BinaryAnionResponse;
import com.morales.chemicallab.dto.CompoundResponse;
import com.morales.chemicallab.dto.ElementCompoundRequest;
import com.morales.chemicallab.dto.MetalResponse;
import com.morales.chemicallab.dto.OxisaltRequest;
import com.morales.chemicallab.dto.OxoanionResponse;
import com.morales.chemicallab.dto.SaltRequest;

import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Motor químico del MVP. Forma óxidos, hidróxidos, ácidos (hidrácidos y
 * oxácidos), sales binarias y oxisales cruzando cargas por máximo común divisor.
 *
 * El {@link ChemistryCatalogService} es la fuente de verdad: el motor valida las
 * referencias recibidas (metal, valencia, no metal, oxoanión) contra el catálogo
 * y deriva de allí los nombres y cargas. Si una combinación es químicamente
 * incoherente o no está soportada, lanza {@link IllegalArgumentException} con un
 * mensaje claro que el frontend muestra al estudiante.
 *
 * No resuelve nomenclatura tradicional/sistemática/Stock: usa nombres base.
 */
@Service
public class ChemicalEngineService {

    /** Gases nobles: no forman compuestos en el alcance escolar del MVP. */
    private static final Set<String> NOBLE_GASES = Set.of("He", "Ne", "Ar", "Kr", "Xe", "Rn");

    private final ChemistryCatalogService catalog;

    public ChemicalEngineService(ChemistryCatalogService catalog) {
        this.catalog = catalog;
    }

    // ===== Óxidos =====

    public CompoundResponse generateOxide(ElementCompoundRequest request) {
        validateElementRequest(request);

        if ("O".equals(request.elementSymbol())) {
            throw new IllegalArgumentException(
                    "El oxígeno no puede ser el elemento principal de un óxido.");
        }
        if (NOBLE_GASES.contains(request.elementSymbol())) {
            throw new IllegalArgumentException(
                    "Los gases nobles no forman compuestos en este nivel.");
        }

        String formula = buildFormula(request.elementSymbol(), request.valence(), false, "O", 2, false);

        String explanation = "El óxido se forma combinando el elemento "
                + request.elementName()
                + " con oxígeno. El oxígeno trabaja con valencia 2 y el elemento seleccionado trabaja con valencia "
                + request.valence()
                + ".";

        return new CompoundResponse(true, "Óxido", formula, "óxido de " + request.elementName(), explanation);
    }

    // ===== Hidróxidos =====

    public CompoundResponse generateHydroxide(ElementCompoundRequest request) {
        validateElementRequest(request);

        MetalResponse metal = catalog.findMetal(request.elementSymbol())
                .orElseThrow(() -> new IllegalArgumentException(
                        "El hidróxido requiere un metal del catálogo. El elemento «"
                                + request.elementSymbol() + "» no es un metal válido."));
        validateValence(metal, request.valence());

        String formula = buildFormula(metal.symbol(), request.valence(), false, "OH", 1, true);

        String explanation = "El hidróxido se forma combinando el metal "
                + metal.name()
                + " con el grupo hidroxilo OH. El grupo OH trabaja con carga -1 y el metal trabaja con valencia +"
                + request.valence()
                + ".";

        return new CompoundResponse(true, "Hidróxido", formula, "hidróxido de " + metal.name(), explanation);
    }

    // ===== Ácidos =====

    public CompoundResponse generateAcid(AcidRequest request) {
        if (request.acidType() == null) {
            throw new IllegalArgumentException("El tipo de ácido es obligatorio (HYDRACID u OXOACID).");
        }

        return switch (request.acidType()) {
            case HYDRACID -> generateHydracid(request);
            case OXOACID -> generateOxoacid(request);
        };
    }

    private CompoundResponse generateHydracid(AcidRequest request) {
        if (request.nonMetalSymbol() == null || request.nonMetalSymbol().isBlank()) {
            throw new IllegalArgumentException("El símbolo del no metal es obligatorio para un hidrácido.");
        }

        AcidNonMetalResponse nonMetal = catalog.findAcidNonMetal(request.nonMetalSymbol())
                .orElseThrow(() -> new IllegalArgumentException(
                        "El no metal «" + request.nonMetalSymbol()
                                + "» no forma ácido hidrácido en el catálogo."));

        String formula = buildFormula("H", 1, false, nonMetal.symbol(), nonMetal.charge(), false);

        String explanation = "El ácido hidrácido se forma combinando hidrógeno con el no metal "
                + nonMetal.name()
                + ". El hidrógeno trabaja con carga +1 y el no metal con carga -"
                + nonMetal.charge()
                + ".";

        return new CompoundResponse(true, "Ácido", formula, "hidrácido (" + nonMetal.name() + ")", explanation);
    }

    private CompoundResponse generateOxoacid(AcidRequest request) {
        if (request.oxoanionKey() == null || request.oxoanionKey().isBlank()) {
            throw new IllegalArgumentException("La clave del oxoanión es obligatoria para un oxácido.");
        }

        OxoanionResponse group = catalog.findOxoanion(request.oxoanionKey())
                .orElseThrow(() -> new IllegalArgumentException(
                        "El grupo oxácido «" + request.oxoanionKey() + "» no existe en el catálogo."));

        String formula = buildFormula("H", 1, false, group.formula(), group.charge(), true);

        String explanation = "El oxácido se forma combinando hidrógeno con el grupo "
                + group.name() + " (" + group.formula()
                + "). El hidrógeno trabaja con carga +1 y el grupo con carga -"
                + group.charge() + ".";

        return new CompoundResponse(true, "Ácido", formula, "oxácido (" + group.name() + ")", explanation);
    }

    // ===== Sales binarias =====

    public CompoundResponse generateSalt(SaltRequest request) {
        if (request.metalSymbol() == null || request.metalSymbol().isBlank()) {
            throw new IllegalArgumentException("El símbolo del metal es obligatorio.");
        }
        if (request.metalValence() == null || request.metalValence() <= 0) {
            throw new IllegalArgumentException("La valencia del metal debe ser mayor a cero.");
        }
        if (request.nonMetalSymbol() == null || request.nonMetalSymbol().isBlank()) {
            throw new IllegalArgumentException("El símbolo del no metal es obligatorio.");
        }

        MetalResponse metal = catalog.findMetal(request.metalSymbol())
                .orElseThrow(() -> new IllegalArgumentException(
                        "El metal «" + request.metalSymbol() + "» no está en el catálogo."));
        validateValence(metal, request.metalValence());

        BinaryAnionResponse anion = findSaltNonMetalOrThrow(request.nonMetalSymbol());

        String formula = buildFormula(metal.symbol(), request.metalValence(), false,
                anion.symbol(), anion.charge(), false);

        String name = anion.name() + " de " + metal.name();

        String explanation = "La sal binaria se forma combinando el metal "
                + metal.name() + " con el anión " + anion.name()
                + ". El metal trabaja con valencia +" + request.metalValence()
                + " y el anión con carga -" + anion.charge()
                + ". El sistema cruza las cargas para equilibrar la fórmula.";

        return new CompoundResponse(true, "Sal binaria", formula, name, explanation);
    }

    /**
     * Busca el no metal de una sal binaria y rechaza con mensaje específico los
     * casos que corresponden a otros tipos de compuesto (oxígeno → óxido,
     * hidrógeno → hidruro) o que no están en el catálogo.
     */
    private BinaryAnionResponse findSaltNonMetalOrThrow(String symbol) {
        if ("O".equals(symbol)) {
            throw new IllegalArgumentException(
                    "El oxígeno no forma sales binarias; con un metal forma un óxido.");
        }
        if ("H".equals(symbol)) {
            throw new IllegalArgumentException(
                    "El hidrógeno no forma sales binarias en este nivel.");
        }
        if (NOBLE_GASES.contains(symbol)) {
            throw new IllegalArgumentException(
                    "Los gases nobles no forman sales binarias.");
        }
        return catalog.findBinaryAnion(symbol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "El no metal «" + symbol + "» no está disponible para sales binarias."));
    }

    // ===== Oxisales =====

    public CompoundResponse generateOxisalt(OxisaltRequest request) {
        if (request.metalSymbol() == null || request.metalSymbol().isBlank()) {
            throw new IllegalArgumentException("El símbolo del metal es obligatorio.");
        }
        if (request.metalValence() == null || request.metalValence() <= 0) {
            throw new IllegalArgumentException("La valencia del metal debe ser mayor a cero.");
        }
        if (request.oxoanionKey() == null || request.oxoanionKey().isBlank()) {
            throw new IllegalArgumentException("La clave del grupo oxácido es obligatoria.");
        }

        MetalResponse metal = catalog.findMetal(request.metalSymbol())
                .orElseThrow(() -> new IllegalArgumentException(
                        "El metal «" + request.metalSymbol() + "» no está en el catálogo."));
        validateValence(metal, request.metalValence());

        OxoanionResponse group = catalog.findOxoanion(request.oxoanionKey())
                .orElseThrow(() -> new IllegalArgumentException(
                        "El grupo oxácido «" + request.oxoanionKey() + "» no existe en el catálogo."));

        String formula = buildFormula(metal.symbol(), request.metalValence(), false,
                group.formula(), group.charge(), true);

        String name = group.name() + " de " + metal.name();

        String explanation = "La oxisal se forma combinando el metal "
                + metal.name() + " con el grupo químico " + group.name()
                + " (" + group.formula() + "). El metal trabaja con valencia +" + request.metalValence()
                + " y el grupo químico con carga -" + group.charge()
                + ". El sistema cruza las cargas para obtener una fórmula eléctricamente neutra.";

        return new CompoundResponse(true, "Oxisal", formula, name, explanation);
    }

    // ===== Utilidad genérica de fórmula =====

    /**
     * Construye una fórmula cruzando la carga del catión y del anión, simplifica
     * por máximo común divisor, omite el subíndice 1 y usa paréntesis solo cuando
     * un grupo poliatómico lleva subíndice mayor a 1.
     */
    private String buildFormula(String cationFormula, int cationCharge, boolean cationIsGroup,
                                String anionFormula, int anionCharge, boolean anionIsGroup) {
        int gcd = gcd(cationCharge, anionCharge);

        int cationSubscript = anionCharge / gcd;
        int anionSubscript = cationCharge / gcd;

        return formatFormulaPart(cationFormula, cationSubscript, cationIsGroup)
                + formatFormulaPart(anionFormula, anionSubscript, anionIsGroup);
    }

    private String formatFormulaPart(String formula, int subscript, boolean isGroup) {
        if (subscript == 1) {
            return formula;
        }
        if (isGroup) {
            return "(" + formula + ")" + subscript;
        }
        return formula + subscript;
    }

    private int gcd(int a, int b) {
        if (b == 0) {
            return a;
        }
        return gcd(b, a % b);
    }

    // ===== Validaciones comunes =====

    private void validateElementRequest(ElementCompoundRequest request) {
        if (request.elementSymbol() == null || request.elementSymbol().isBlank()) {
            throw new IllegalArgumentException("El símbolo del elemento es obligatorio.");
        }
        if (request.elementName() == null || request.elementName().isBlank()) {
            throw new IllegalArgumentException("El nombre del elemento es obligatorio.");
        }
        if (request.valence() == null || request.valence() <= 0) {
            throw new IllegalArgumentException("La valencia debe ser mayor a cero.");
        }
    }

    private void validateValence(MetalResponse metal, int valence) {
        if (!metal.valences().contains(valence)) {
            throw new IllegalArgumentException(
                    "La valencia +" + valence + " no es válida para el metal " + metal.name()
                            + ". Valencias válidas: " + metal.valences() + ".");
        }
    }
}
