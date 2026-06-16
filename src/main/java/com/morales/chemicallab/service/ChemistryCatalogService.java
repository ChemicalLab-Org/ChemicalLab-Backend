package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.BinaryAnionResponse;
import com.morales.chemicallab.dto.OxoanionResponse;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Catálogo del motor químico: aniones monoatómicos y oxoaniones válidos para
 * formar sales binarias y oxisales en el MVP educativo.
 *
 * Es la fuente de datos del backend. Las listas son fijas y de nivel escolar,
 * pensadas para ampliarse fácilmente agregando una entrada más. No incluye
 * gases nobles, oxígeno (que forma óxidos) ni hidrógeno (que forma hidruros),
 * porque corresponden a otros tipos de compuesto.
 *
 * La nomenclatura tradicional/sistemática/Stock no se resuelve aquí: solo se
 * expone el nombre básico del anión o del grupo.
 */
@Service
public class ChemistryCatalogService {

    /**
     * Aniones monoatómicos para sales binarias (no metal con carga negativa).
     * La carga se expresa como valor absoluto, igual que en {@code SaltRequest}.
     */
    private static final List<BinaryAnionResponse> BINARY_ANIONS = List.of(
            new BinaryAnionResponse("F", "fluoruro", 1),
            new BinaryAnionResponse("Cl", "cloruro", 1),
            new BinaryAnionResponse("Br", "bromuro", 1),
            new BinaryAnionResponse("I", "yoduro", 1),
            new BinaryAnionResponse("S", "sulfuro", 2),
            new BinaryAnionResponse("Se", "seleniuro", 2),
            new BinaryAnionResponse("N", "nitruro", 3),
            new BinaryAnionResponse("P", "fosfuro", 3),
            new BinaryAnionResponse("C", "carburo", 4)
    );

    /**
     * Oxoaniones (grupos oxácidos) para oxisales. La carga se expresa como
     * valor absoluto, igual que en {@code OxisaltRequest}.
     */
    private static final List<OxoanionResponse> OXOANIONS = List.of(
            new OxoanionResponse("sulfato", "sulfato", "SO4", 2),
            new OxoanionResponse("sulfito", "sulfito", "SO3", 2),
            new OxoanionResponse("nitrato", "nitrato", "NO3", 1),
            new OxoanionResponse("nitrito", "nitrito", "NO2", 1),
            new OxoanionResponse("carbonato", "carbonato", "CO3", 2),
            new OxoanionResponse("fosfato", "fosfato", "PO4", 3),
            new OxoanionResponse("fosfito", "fosfito", "PO3", 3),
            new OxoanionResponse("clorato", "clorato", "ClO3", 1),
            new OxoanionResponse("clorito", "clorito", "ClO2", 1),
            new OxoanionResponse("hipoclorito", "hipoclorito", "ClO", 1),
            new OxoanionResponse("perclorato", "perclorato", "ClO4", 1),
            new OxoanionResponse("bromato", "bromato", "BrO3", 1),
            new OxoanionResponse("yodato", "yodato", "IO3", 1),
            new OxoanionResponse("permanganato", "permanganato", "MnO4", 1),
            new OxoanionResponse("cromato", "cromato", "CrO4", 2),
            new OxoanionResponse("dicromato", "dicromato", "Cr2O7", 2)
    );

    /** Devuelve el catálogo de aniones monoatómicos para sales binarias. */
    public List<BinaryAnionResponse> binaryAnions() {
        return BINARY_ANIONS;
    }

    /** Devuelve el catálogo de oxoaniones para oxisales. */
    public List<OxoanionResponse> oxoanions() {
        return OXOANIONS;
    }
}
