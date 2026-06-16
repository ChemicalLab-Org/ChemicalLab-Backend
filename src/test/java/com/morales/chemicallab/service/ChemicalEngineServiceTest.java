package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.CompoundResponse;
import com.morales.chemicallab.dto.OxisaltRequest;
import com.morales.chemicallab.dto.SaltRequest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del motor químico para sales binarias y oxisales.
 *
 * Verifican que la fórmula se arma cruzando valencia del metal y carga del
 * anión/oxoanión, simplificando por máximo común divisor, omitiendo el
 * subíndice 1 y usando paréntesis solo cuando el grupo poliatómico lleva
 * subíndice mayor a 1.
 */
class ChemicalEngineServiceTest {

    private final ChemicalEngineService service = new ChemicalEngineService();

    private SaltRequest salt(String metalSymbol, int metalValence, String anionSymbol,
                             String anionName, int anionCharge) {
        return new SaltRequest(metalSymbol, metalSymbol.toLowerCase(), metalValence,
                anionSymbol, anionName, anionCharge);
    }

    private OxisaltRequest oxisalt(String metalSymbol, int metalValence, String groupFormula,
                                   String groupName, int groupCharge) {
        return new OxisaltRequest(metalSymbol, metalSymbol.toLowerCase(), metalValence,
                groupFormula, groupName, groupCharge);
    }

    // ===== Sales binarias =====

    @Test
    void formaClorurodeSodioComoNaCl() {
        CompoundResponse response = service.generateSalt(salt("Na", 1, "Cl", "cloruro", 1));
        assertThat(response.valid()).isTrue();
        assertThat(response.compoundType()).isEqualTo("Sal binaria");
        assertThat(response.formula()).isEqualTo("NaCl");
    }

    @Test
    void formaClorurodeCalcioComoCaCl2() {
        CompoundResponse response = service.generateSalt(salt("Ca", 2, "Cl", "cloruro", 1));
        assertThat(response.formula()).isEqualTo("CaCl2");
    }

    @Test
    void formaSulfurodeAluminioComoAl2S3() {
        CompoundResponse response = service.generateSalt(salt("Al", 3, "S", "sulfuro", 2));
        assertThat(response.formula()).isEqualTo("Al2S3");
    }

    @Test
    void formaClorurosDeHierroSegunSuValencia() {
        assertThat(service.generateSalt(salt("Fe", 2, "Cl", "cloruro", 1)).formula())
                .isEqualTo("FeCl2");
        assertThat(service.generateSalt(salt("Fe", 3, "Cl", "cloruro", 1)).formula())
                .isEqualTo("FeCl3");
    }

    @Test
    void simplificaSulfurodeZincComoZnS() {
        CompoundResponse response = service.generateSalt(salt("Zn", 2, "S", "sulfuro", 2));
        assertThat(response.formula()).isEqualTo("ZnS");
    }

    // ===== Oxisales =====

    @Test
    void formaSulfatodeSodioComoNa2SO4() {
        CompoundResponse response = service.generateOxisalt(oxisalt("Na", 1, "SO4", "sulfato", 2));
        assertThat(response.valid()).isTrue();
        assertThat(response.compoundType()).isEqualTo("Oxisal");
        assertThat(response.formula()).isEqualTo("Na2SO4");
    }

    @Test
    void simplificaSulfatodeCalcioComoCaSO4() {
        CompoundResponse response = service.generateOxisalt(oxisalt("Ca", 2, "SO4", "sulfato", 2));
        assertThat(response.formula()).isEqualTo("CaSO4");
    }

    @Test
    void usaParentesisEnSulfatodeAluminioAl2SO43() {
        CompoundResponse response = service.generateOxisalt(oxisalt("Al", 3, "SO4", "sulfato", 2));
        assertThat(response.formula()).isEqualTo("Al2(SO4)3");
    }

    @Test
    void formaNitratodeSodioComoNaNO3() {
        CompoundResponse response = service.generateOxisalt(oxisalt("Na", 1, "NO3", "nitrato", 1));
        assertThat(response.formula()).isEqualTo("NaNO3");
    }

    @Test
    void usaParentesisEnNitratodeCalcioCaNO32() {
        CompoundResponse response = service.generateOxisalt(oxisalt("Ca", 2, "NO3", "nitrato", 1));
        assertThat(response.formula()).isEqualTo("Ca(NO3)2");
    }

    @Test
    void simplificaFosfatodeHierroComoFePO4() {
        CompoundResponse response = service.generateOxisalt(oxisalt("Fe", 3, "PO4", "fosfato", 3));
        assertThat(response.formula()).isEqualTo("FePO4");
    }

    @Test
    void usaParentesisEnFosfatodeMagnesioMg3PO42() {
        CompoundResponse response = service.generateOxisalt(oxisalt("Mg", 2, "PO4", "fosfato", 3));
        assertThat(response.formula()).isEqualTo("Mg3(PO4)2");
    }
}
