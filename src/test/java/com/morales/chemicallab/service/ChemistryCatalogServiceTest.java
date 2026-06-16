package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.BinaryAnionResponse;
import com.morales.chemicallab.dto.CentralElementResponse;
import com.morales.chemicallab.dto.MetalResponse;
import com.morales.chemicallab.dto.OxoanionResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del catálogo del motor químico (fuente de verdad). Confirman que se
 * exponen metales, aniones, no metales de ácido y oxoaniones con datos válidos,
 * que las valencias son positivas y que el filtro por elemento central funciona.
 */
class ChemistryCatalogServiceTest {

    private final ChemistryCatalogService service = new ChemistryCatalogService();

    @Test
    void exponeMetalesConValenciasPositivas() {
        var metals = service.metals();
        assertThat(metals).extracting(MetalResponse::symbol).contains("Na", "Fe", "Al", "Cu");
        assertThat(metals).allSatisfy(metal -> {
            assertThat(metal.symbol()).isNotBlank();
            assertThat(metal.name()).isNotBlank();
            assertThat(metal.valences()).isNotEmpty().allMatch(v -> v > 0);
        });
        // El hierro trabaja con +2 y +3.
        assertThat(service.findMetal("Fe")).isPresent()
                .get().extracting(MetalResponse::valences).isEqualTo(java.util.List.of(2, 3));
    }

    @Test
    void exponeAnionesMonoatomicosConDatosValidos() {
        var anions = service.binaryAnions();
        assertThat(anions).extracting(BinaryAnionResponse::symbol)
                .contains("Cl", "S", "Te", "N", "P", "C");
        assertThat(anions).allSatisfy(anion -> {
            assertThat(anion.symbol()).isNotBlank();
            assertThat(anion.name()).isNotBlank();
            assertThat(anion.charge()).isPositive();
        });
    }

    @Test
    void exponeNoMetalesDeAcidoSoloHalogenosYAnfigenos() {
        assertThat(service.acidNonMetals()).extracting("symbol")
                .containsExactly("F", "Cl", "Br", "I", "S", "Se", "Te");
    }

    @Test
    void exponeOxoanionesConElementoCentral() {
        var oxoanions = service.oxoanions();
        assertThat(oxoanions).extracting(OxoanionResponse::formula)
                .contains("SO4", "NO3", "CO3", "PO4", "ClO3", "MnO4", "Cr2O7");
        assertThat(oxoanions).allSatisfy(group -> {
            assertThat(group.key()).isNotBlank();
            assertThat(group.name()).isNotBlank();
            assertThat(group.formula()).isNotBlank();
            assertThat(group.charge()).isPositive();
            assertThat(group.centralElement()).isNotBlank();
        });
    }

    @Test
    void filtraOxoanionesPorElementoCentral() {
        assertThat(service.oxoanions("S")).extracting(OxoanionResponse::key)
                .containsExactly("sulfato", "sulfito");
    }

    @Test
    void exponeElementosCentralesSinRepetir() {
        assertThat(service.oxoanionCentralElements()).extracting(CentralElementResponse::symbol)
                .containsExactly("S", "N", "C", "P", "Cl", "Br", "I", "Mn", "Cr");
    }
}
