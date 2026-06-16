package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AcidRequest;
import com.morales.chemicallab.dto.AcidType;
import com.morales.chemicallab.dto.CompoundResponse;
import com.morales.chemicallab.dto.ElementCompoundRequest;
import com.morales.chemicallab.dto.NomenclatureResponse;
import com.morales.chemicallab.dto.OxisaltRequest;
import com.morales.chemicallab.dto.SaltRequest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de las tres nomenclaturas (tradicional, Stock y sistemática) que el
 * motor devuelve para cada tipo de compuesto. Verifican los nombres esperados en
 * los casos principales y que ningún campo quede nulo o vacío.
 */
class ChemicalNomenclatureServiceTest {

    private final ChemistryCatalogService catalog = new ChemistryCatalogService();
    private final ChemicalEngineService service =
            new ChemicalEngineService(catalog, new ChemicalNomenclatureService(catalog));

    private NomenclatureResponse oxide(String symbol, String name, int valence) {
        return service.generateOxide(new ElementCompoundRequest(symbol, name, valence)).nomenclature();
    }

    private NomenclatureResponse hydroxide(String symbol, String name, int valence) {
        return service.generateHydroxide(new ElementCompoundRequest(symbol, name, valence)).nomenclature();
    }

    private NomenclatureResponse hydracid(String nonMetal) {
        return service.generateAcid(new AcidRequest(AcidType.HYDRACID, nonMetal, null)).nomenclature();
    }

    private NomenclatureResponse oxoacid(String oxoanionKey) {
        return service.generateAcid(new AcidRequest(AcidType.OXOACID, null, oxoanionKey)).nomenclature();
    }

    private NomenclatureResponse salt(String metal, int valence, String nonMetal) {
        return service.generateSalt(new SaltRequest(metal, valence, nonMetal)).nomenclature();
    }

    private NomenclatureResponse oxisalt(String metal, int valence, String oxoanionKey) {
        return service.generateOxisalt(new OxisaltRequest(metal, valence, oxoanionKey)).nomenclature();
    }

    private void assertNoBlanks(NomenclatureResponse n) {
        assertThat(n).isNotNull();
        assertThat(n.traditional()).isNotBlank();
        assertThat(n.stock()).isNotBlank();
        assertThat(n.systematic()).isNotBlank();
        assertThat(n.notes()).isNotNull();
    }

    // ===== Óxidos =====

    @Test
    void nombraOxidoDeHierroII() {
        NomenclatureResponse n = oxide("Fe", "hierro", 2);
        assertThat(n.traditional()).isEqualTo("óxido ferroso");
        assertThat(n.stock()).isEqualTo("óxido de hierro (II)");
        assertThat(n.systematic()).isEqualTo("monóxido de hierro");
        assertNoBlanks(n);
    }

    @Test
    void nombraOxidoDeHierroIII() {
        NomenclatureResponse n = oxide("Fe", "hierro", 3);
        assertThat(n.traditional()).isEqualTo("óxido férrico");
        assertThat(n.stock()).isEqualTo("óxido de hierro (III)");
        assertThat(n.systematic()).isEqualTo("trióxido de dihierro");
    }

    @Test
    void nombraOxidoDeSodio() {
        NomenclatureResponse n = oxide("Na", "sodio", 1);
        assertThat(n.traditional()).isEqualTo("óxido de sodio");
        assertThat(n.stock()).isEqualTo("óxido de sodio");
        assertThat(n.systematic()).isEqualTo("monóxido de disodio");
    }

    @Test
    void nombraOxidoDeAluminio() {
        NomenclatureResponse n = oxide("Al", "aluminio", 3);
        assertThat(n.stock()).isEqualTo("óxido de aluminio");
        assertThat(n.systematic()).isEqualTo("trióxido de dialuminio");
    }

    // ===== Hidróxidos =====

    @Test
    void nombraHidroxidoDeHierroII() {
        NomenclatureResponse n = hydroxide("Fe", "hierro", 2);
        assertThat(n.traditional()).isEqualTo("hidróxido ferroso");
        assertThat(n.stock()).isEqualTo("hidróxido de hierro (II)");
        assertThat(n.systematic()).isEqualTo("dihidróxido de hierro");
    }

    @Test
    void nombraHidroxidoDeHierroIII() {
        NomenclatureResponse n = hydroxide("Fe", "hierro", 3);
        assertThat(n.traditional()).isEqualTo("hidróxido férrico");
        assertThat(n.stock()).isEqualTo("hidróxido de hierro (III)");
        assertThat(n.systematic()).isEqualTo("trihidróxido de hierro");
    }

    @Test
    void nombraHidroxidoDeCalcio() {
        NomenclatureResponse n = hydroxide("Ca", "calcio", 2);
        assertThat(n.traditional()).isEqualTo("hidróxido de calcio");
        assertThat(n.systematic()).isEqualTo("dihidróxido de calcio");
    }

    // ===== Hidrácidos =====

    @Test
    void nombraAcidoClorhidrico() {
        NomenclatureResponse n = hydracid("Cl");
        assertThat(n.traditional()).isEqualTo("ácido clorhídrico");
        assertThat(n.stock()).isEqualTo("cloruro de hidrógeno");
        assertThat(n.systematic()).isEqualTo("cloruro de hidrógeno");
    }

    @Test
    void nombraAcidoSulfhidrico() {
        NomenclatureResponse n = hydracid("S");
        assertThat(n.traditional()).isEqualTo("ácido sulfhídrico");
        assertThat(n.stock()).isEqualTo("sulfuro de hidrógeno");
        assertThat(n.systematic()).isEqualTo("sulfuro de dihidrógeno");
    }

    // ===== Oxácidos =====

    @Test
    void nombraAcidoSulfurico() {
        NomenclatureResponse n = oxoacid("sulfato");
        assertThat(n.traditional()).isEqualTo("ácido sulfúrico");
        assertThat(n.stock()).isEqualTo("ácido tetraoxosulfúrico (VI)");
        assertThat(n.systematic()).isEqualTo("tetraoxosulfato (VI) de dihidrógeno");
    }

    @Test
    void nombraAcidoNitrico() {
        NomenclatureResponse n = oxoacid("nitrato");
        assertThat(n.traditional()).isEqualTo("ácido nítrico");
        assertThat(n.systematic()).isEqualTo("trioxonitrato (V) de hidrógeno");
        assertNoBlanks(n);
    }

    @Test
    void nombraAcidoFosforico() {
        NomenclatureResponse n = oxoacid("fosfato");
        assertThat(n.traditional()).isEqualTo("ácido fosfórico");
        assertThat(n.systematic()).isEqualTo("tetraoxofosfato (V) de trihidrógeno");
        assertNoBlanks(n);
    }

    // ===== Sales binarias =====

    @Test
    void nombraClorurodeSodio() {
        NomenclatureResponse n = salt("Na", 1, "Cl");
        assertThat(n.traditional()).isEqualTo("cloruro de sodio");
        assertThat(n.systematic()).isEqualTo("cloruro de sodio");
    }

    @Test
    void nombraClorurodeCalcio() {
        NomenclatureResponse n = salt("Ca", 2, "Cl");
        assertThat(n.traditional()).isEqualTo("cloruro de calcio");
        assertThat(n.systematic()).isEqualTo("dicloruro de calcio");
    }

    @Test
    void nombraCloruroFerrosoYFerrico() {
        NomenclatureResponse ferroso = salt("Fe", 2, "Cl");
        assertThat(ferroso.traditional()).isEqualTo("cloruro ferroso");
        assertThat(ferroso.stock()).isEqualTo("cloruro de hierro (II)");
        assertThat(ferroso.systematic()).isEqualTo("dicloruro de hierro");

        NomenclatureResponse ferrico = salt("Fe", 3, "Cl");
        assertThat(ferrico.traditional()).isEqualTo("cloruro férrico");
        assertThat(ferrico.stock()).isEqualTo("cloruro de hierro (III)");
        assertThat(ferrico.systematic()).isEqualTo("tricloruro de hierro");
    }

    @Test
    void nombraSulfurodeAluminio() {
        NomenclatureResponse n = salt("Al", 3, "S");
        assertThat(n.traditional()).isEqualTo("sulfuro de aluminio");
        assertThat(n.systematic()).isEqualTo("trisulfuro de dialuminio");
    }

    // ===== Oxisales =====

    @Test
    void nombraSulfatodeSodio() {
        NomenclatureResponse n = oxisalt("Na", 1, "sulfato");
        assertThat(n.traditional()).isEqualTo("sulfato de sodio");
        assertThat(n.systematic()).isEqualTo("tetraoxosulfato (VI) de disodio");
    }

    @Test
    void nombraNitratodeCalcio() {
        NomenclatureResponse n = oxisalt("Ca", 2, "nitrato");
        assertThat(n.traditional()).isEqualTo("nitrato de calcio");
        assertThat(n.systematic()).isEqualTo("bis(trioxonitrato) de calcio");
    }

    @Test
    void nombraFosfatodeHierroIII() {
        NomenclatureResponse n = oxisalt("Fe", 3, "fosfato");
        assertThat(n.traditional()).isEqualTo("fosfato férrico");
        assertThat(n.stock()).isEqualTo("fosfato de hierro (III)");
        assertThat(n.systematic()).isEqualTo("tetraoxofosfato (V) de hierro (III)");
    }

    @Test
    void nombraFosfatodeMagnesio() {
        NomenclatureResponse n = oxisalt("Mg", 2, "fosfato");
        assertThat(n.traditional()).isEqualTo("fosfato de magnesio");
        assertThat(n.systematic()).isEqualTo("bis(tetraoxofosfato) de trimagnesio");
    }

    // ===== Garantía de no vacíos en todos los casos mínimos =====

    @Test
    void ningunCasoMinimoDevuelveCamposVacios() {
        assertNoBlanks(oxide("Fe", "hierro", 2));
        assertNoBlanks(oxide("Fe", "hierro", 3));
        assertNoBlanks(oxide("Na", "sodio", 1));
        assertNoBlanks(oxide("Al", "aluminio", 3));
        assertNoBlanks(hydroxide("Fe", "hierro", 2));
        assertNoBlanks(hydroxide("Fe", "hierro", 3));
        assertNoBlanks(hydroxide("Ca", "calcio", 2));
        assertNoBlanks(hydracid("Cl"));
        assertNoBlanks(hydracid("S"));
        assertNoBlanks(oxoacid("sulfato"));
        assertNoBlanks(oxoacid("nitrato"));
        assertNoBlanks(oxoacid("fosfato"));
        assertNoBlanks(salt("Na", 1, "Cl"));
        assertNoBlanks(salt("Ca", 2, "Cl"));
        assertNoBlanks(salt("Fe", 2, "Cl"));
        assertNoBlanks(salt("Fe", 3, "Cl"));
        assertNoBlanks(salt("Al", 3, "S"));
        assertNoBlanks(oxisalt("Na", 1, "sulfato"));
        assertNoBlanks(oxisalt("Ca", 2, "nitrato"));
        assertNoBlanks(oxisalt("Fe", 3, "fosfato"));
        assertNoBlanks(oxisalt("Mg", 2, "fosfato"));
    }
}
