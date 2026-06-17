package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AcidRequest;
import com.morales.chemicallab.dto.AcidType;
import com.morales.chemicallab.dto.CompoundResponse;
import com.morales.chemicallab.dto.ElementCompoundRequest;
import com.morales.chemicallab.dto.OxisaltRequest;
import com.morales.chemicallab.dto.SaltRequest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas del motor químico. Verifican que la fórmula se arma cruzando valencia
 * y carga, simplificando por máximo común divisor, omitiendo el subíndice 1 y
 * usando paréntesis solo cuando el grupo poliatómico lleva subíndice mayor a 1.
 *
 * También comprueban que el motor valida contra el catálogo y rechaza
 * combinaciones químicamente incoherentes con un mensaje claro.
 */
class ChemicalEngineServiceTest {

    private final ChemistryCatalogService catalog = new ChemistryCatalogService();
    private final ChemicalEngineService service =
            new ChemicalEngineService(catalog, new ChemicalNomenclatureService(catalog));

    private ElementCompoundRequest element(String symbol, String name, int valence) {
        return new ElementCompoundRequest(symbol, name, valence);
    }

    private SaltRequest salt(String metalSymbol, int metalValence, String nonMetalSymbol) {
        return new SaltRequest(metalSymbol, metalValence, nonMetalSymbol);
    }

    private OxisaltRequest oxisalt(String metalSymbol, int metalValence, String oxoanionKey) {
        return new OxisaltRequest(metalSymbol, metalValence, oxoanionKey);
    }

    // ===== Óxidos =====

    @Test
    void formaOxidoDeSodioComoNa2O() {
        assertThat(service.generateOxide(element("Na", "sodio", 1)).formula()).isEqualTo("Na2O");
    }

    @Test
    void formaOxidoDeCalcioComoCaO() {
        assertThat(service.generateOxide(element("Ca", "calcio", 2)).formula()).isEqualTo("CaO");
    }

    @Test
    void formaOxidoDeAluminioComoAl2O3() {
        assertThat(service.generateOxide(element("Al", "aluminio", 3)).formula()).isEqualTo("Al2O3");
    }

    // ===== Hidróxidos =====

    @Test
    void formaHidroxidoDeSodioComoNaOH() {
        assertThat(service.generateHydroxide(element("Na", "sodio", 1)).formula()).isEqualTo("NaOH");
    }

    @Test
    void usaParentesisEnHidroxidoDeCalcioCaOH2() {
        assertThat(service.generateHydroxide(element("Ca", "calcio", 2)).formula()).isEqualTo("Ca(OH)2");
    }

    @Test
    void usaParentesisEnHidroxidoDeAluminioAlOH3() {
        assertThat(service.generateHydroxide(element("Al", "aluminio", 3)).formula()).isEqualTo("Al(OH)3");
    }

    // ===== Ácidos hidrácidos =====

    @Test
    void formaAcidoClorhidricoComoHCl() {
        CompoundResponse response = service.generateAcid(new AcidRequest(AcidType.HYDRACID, "Cl", null));
        assertThat(response.compoundType()).isEqualTo("Ácido");
        assertThat(response.formula()).isEqualTo("HCl");
    }

    @Test
    void formaAcidoBromhidricoComoHBr() {
        assertThat(service.generateAcid(new AcidRequest(AcidType.HYDRACID, "Br", null)).formula())
                .isEqualTo("HBr");
    }

    @Test
    void formaAcidoSulfhidricoComoH2S() {
        assertThat(service.generateAcid(new AcidRequest(AcidType.HYDRACID, "S", null)).formula())
                .isEqualTo("H2S");
    }

    // ===== Oxácidos =====

    @Test
    void formaAcidoSulfuricoComoH2SO4() {
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "sulfato")).formula())
                .isEqualTo("H2SO4");
    }

    @Test
    void formaAcidoNitricoComoHNO3() {
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "nitrato")).formula())
                .isEqualTo("HNO3");
    }

    @Test
    void formaAcidoCarbonicoComoH2CO3() {
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "carbonato")).formula())
                .isEqualTo("H2CO3");
    }

    @Test
    void formaAcidoFosforicoComoH3PO4() {
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "fosfato")).formula())
                .isEqualTo("H3PO4");
    }

    // ===== Sales binarias =====

    @Test
    void formaClorurodeSodioComoNaCl() {
        CompoundResponse response = service.generateSalt(salt("Na", 1, "Cl"));
        assertThat(response.valid()).isTrue();
        assertThat(response.compoundType()).isEqualTo("Sal binaria");
        assertThat(response.formula()).isEqualTo("NaCl");
    }

    @Test
    void formaClorurodeCalcioComoCaCl2() {
        assertThat(service.generateSalt(salt("Ca", 2, "Cl")).formula()).isEqualTo("CaCl2");
    }

    @Test
    void formaSulfurodeAluminioComoAl2S3() {
        assertThat(service.generateSalt(salt("Al", 3, "S")).formula()).isEqualTo("Al2S3");
    }

    @Test
    void formaClorurosDeHierroSegunSuValencia() {
        assertThat(service.generateSalt(salt("Fe", 2, "Cl")).formula()).isEqualTo("FeCl2");
        assertThat(service.generateSalt(salt("Fe", 3, "Cl")).formula()).isEqualTo("FeCl3");
    }

    @Test
    void simplificaSulfurodeZincComoZnS() {
        assertThat(service.generateSalt(salt("Zn", 2, "S")).formula()).isEqualTo("ZnS");
    }

    // ===== Oxisales =====

    @Test
    void formaSulfatodeSodioComoNa2SO4() {
        CompoundResponse response = service.generateOxisalt(oxisalt("Na", 1, "sulfato"));
        assertThat(response.valid()).isTrue();
        assertThat(response.compoundType()).isEqualTo("Oxisal");
        assertThat(response.formula()).isEqualTo("Na2SO4");
    }

    @Test
    void simplificaSulfatodeCalcioComoCaSO4() {
        assertThat(service.generateOxisalt(oxisalt("Ca", 2, "sulfato")).formula()).isEqualTo("CaSO4");
    }

    @Test
    void usaParentesisEnSulfatodeAluminioAl2SO43() {
        assertThat(service.generateOxisalt(oxisalt("Al", 3, "sulfato")).formula()).isEqualTo("Al2(SO4)3");
    }

    @Test
    void usaParentesisEnNitratodeCalcioCaNO32() {
        assertThat(service.generateOxisalt(oxisalt("Ca", 2, "nitrato")).formula()).isEqualTo("Ca(NO3)2");
    }

    @Test
    void simplificaFosfatodeHierroComoFePO4() {
        assertThat(service.generateOxisalt(oxisalt("Fe", 3, "fosfato")).formula()).isEqualTo("FePO4");
    }

    @Test
    void usaParentesisEnFosfatodeMagnesioMg3PO42() {
        assertThat(service.generateOxisalt(oxisalt("Mg", 2, "fosfato")).formula()).isEqualTo("Mg3(PO4)2");
    }

    // ===== Casos inválidos =====

    @Test
    void rechazaGasNobleComoOxido() {
        assertThatThrownBy(() -> service.generateOxide(element("Ar", "argón", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gases nobles");
    }

    @Test
    void rechazaValenciaNoPermitidaParaUnMetal() {
        // El sodio solo trabaja con valencia +1.
        assertThatThrownBy(() -> service.generateSalt(salt("Na", 2, "Cl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no es válida");
    }

    @Test
    void rechazaOxoanionInexistente() {
        assertThatThrownBy(() -> service.generateOxisalt(oxisalt("Na", 1, "inexistente")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no existe");
    }

    @Test
    void rechazaNoMetalNoPermitidoParaHidracido() {
        // El carbono no forma hidrácido en el catálogo.
        assertThatThrownBy(() -> service.generateAcid(new AcidRequest(AcidType.HYDRACID, "C", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no forma ácido");
    }

    @Test
    void rechazaOxigenoComoAnionDeSalBinaria() {
        assertThatThrownBy(() -> service.generateSalt(salt("Ca", 2, "O")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("óxido");
    }

    @Test
    void rechazaMetalFueraDelCatalogoEnSalBinaria() {
        assertThatThrownBy(() -> service.generateSalt(salt("Xx", 1, "Cl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no está en el catálogo");
    }

    @Test
    void rechazaHidrogenoComoAnionDeSalBinaria() {
        assertThatThrownBy(() -> service.generateSalt(salt("Na", 1, "H")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hidrógeno");
    }

    // ===== Cobertura ampliada de fórmulas (validación integral 19.2) =====

    @Test
    void formaOxidosMetalicosYNoMetalicos() {
        assertThat(service.generateOxide(element("Fe", "hierro", 2)).formula()).isEqualTo("FeO");
        assertThat(service.generateOxide(element("Fe", "hierro", 3)).formula()).isEqualTo("Fe2O3");
        assertThat(service.generateOxide(element("P", "fósforo", 3)).formula()).isEqualTo("P2O3");
        assertThat(service.generateOxide(element("P", "fósforo", 5)).formula()).isEqualTo("P2O5");
        assertThat(service.generateOxide(element("S", "azufre", 4)).formula()).isEqualTo("SO2");
        assertThat(service.generateOxide(element("S", "azufre", 6)).formula()).isEqualTo("SO3");
    }

    @Test
    void formaHidroxidosDeHierroYEstano() {
        assertThat(service.generateHydroxide(element("Fe", "hierro", 2)).formula()).isEqualTo("Fe(OH)2");
        assertThat(service.generateHydroxide(element("Fe", "hierro", 3)).formula()).isEqualTo("Fe(OH)3");
        assertThat(service.generateHydroxide(element("Sn", "estaño", 2)).formula()).isEqualTo("Sn(OH)2");
        assertThat(service.generateHydroxide(element("Sn", "estaño", 4)).formula()).isEqualTo("Sn(OH)4");
    }

    @Test
    void formaHidracidosDeHalogenosYAnfigenos() {
        assertThat(service.generateAcid(new AcidRequest(AcidType.HYDRACID, "F", null)).formula()).isEqualTo("HF");
        assertThat(service.generateAcid(new AcidRequest(AcidType.HYDRACID, "I", null)).formula()).isEqualTo("HI");
        assertThat(service.generateAcid(new AcidRequest(AcidType.HYDRACID, "Se", null)).formula()).isEqualTo("H2Se");
        assertThat(service.generateAcid(new AcidRequest(AcidType.HYDRACID, "Te", null)).formula()).isEqualTo("H2Te");
    }

    @Test
    void formaOxacidosSegunElOxoanion() {
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "sulfito")).formula()).isEqualTo("H2SO3");
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "nitrito")).formula()).isEqualTo("HNO2");
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "fosfito")).formula()).isEqualTo("H3PO3");
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "clorato")).formula()).isEqualTo("HClO3");
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "hipoclorito")).formula()).isEqualTo("HClO");
        assertThat(service.generateAcid(new AcidRequest(AcidType.OXOACID, null, "perclorato")).formula()).isEqualTo("HClO4");
    }

    @Test
    void formaSalesBinariasDeCobreEstanoYZinc() {
        assertThat(service.generateSalt(salt("Cu", 1, "Br")).formula()).isEqualTo("CuBr");
        assertThat(service.generateSalt(salt("Cu", 2, "Br")).formula()).isEqualTo("CuBr2");
        assertThat(service.generateSalt(salt("Sn", 2, "Cl")).formula()).isEqualTo("SnCl2");
        assertThat(service.generateSalt(salt("Sn", 4, "Cl")).formula()).isEqualTo("SnCl4");
        assertThat(service.generateSalt(salt("Sn", 4, "N")).formula()).isEqualTo("Sn3N4");
    }

    @Test
    void formaOxisalesConYSinParentesis() {
        assertThat(service.generateOxisalt(oxisalt("Na", 1, "nitrato")).formula()).isEqualTo("NaNO3");
        assertThat(service.generateOxisalt(oxisalt("K", 1, "carbonato")).formula()).isEqualTo("K2CO3");
        assertThat(service.generateOxisalt(oxisalt("Ca", 2, "carbonato")).formula()).isEqualTo("CaCO3");
        assertThat(service.generateOxisalt(oxisalt("Cu", 2, "sulfato")).formula()).isEqualTo("CuSO4");
        assertThat(service.generateOxisalt(oxisalt("Sn", 4, "sulfato")).formula()).isEqualTo("Sn(SO4)2");
    }
}
