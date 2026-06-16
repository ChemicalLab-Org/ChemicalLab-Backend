package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.BinaryAnionResponse;
import com.morales.chemicallab.dto.OxoanionResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del catálogo del motor químico: confirman que se exponen los aniones
 * monoatómicos y oxoaniones esperados y que ninguna entrada queda con datos
 * vacíos o carga no positiva.
 */
class ChemistryCatalogServiceTest {

    private final ChemistryCatalogService service = new ChemistryCatalogService();

    @Test
    void exponeAnionesMonoatomicosConDatosValidos() {
        var anions = service.binaryAnions();
        assertThat(anions).extracting(BinaryAnionResponse::symbol)
                .contains("Cl", "S", "N", "P", "C");
        assertThat(anions).allSatisfy(anion -> {
            assertThat(anion.symbol()).isNotBlank();
            assertThat(anion.name()).isNotBlank();
            assertThat(anion.charge()).isPositive();
        });
    }

    @Test
    void exponeOxoanionesConDatosValidos() {
        var oxoanions = service.oxoanions();
        assertThat(oxoanions).extracting(OxoanionResponse::formula)
                .contains("SO4", "NO3", "CO3", "PO4", "ClO3", "MnO4", "Cr2O7");
        assertThat(oxoanions).allSatisfy(group -> {
            assertThat(group.key()).isNotBlank();
            assertThat(group.name()).isNotBlank();
            assertThat(group.formula()).isNotBlank();
            assertThat(group.charge()).isPositive();
        });
    }
}
