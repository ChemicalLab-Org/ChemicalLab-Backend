package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AcidRequest;
import com.morales.chemicallab.dto.AcidType;
import com.morales.chemicallab.dto.ElementCompoundRequest;
import com.morales.chemicallab.dto.NomenclatureResponse;
import com.morales.chemicallab.dto.OxisaltRequest;
import com.morales.chemicallab.dto.SaltRequest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matriz de validación de las tres nomenclaturas (tradicional, Stock y
 * sistemática) para cada tipo de compuesto del MVP. Cada prueba corresponde a un
 * caso de la matriz acordada en la auditoría de la Sesión 19; también se verifica
 * que ningún campo quede nulo o vacío.
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

    private void assertNames(NomenclatureResponse n, String traditional, String stock, String systematic) {
        assertThat(n).isNotNull();
        assertThat(n.traditional()).isEqualTo(traditional);
        assertThat(n.stock()).isEqualTo(stock);
        assertThat(n.systematic()).isEqualTo(systematic);
        assertThat(n.notes()).isNotNull();
    }

    // ===== Óxidos metálicos =====

    @Test
    void caso01_oxidoDeCalcio() {
        assertNames(oxide("Ca", "calcio", 2),
                "óxido cálcico", "óxido de calcio", "monóxido de calcio");
    }

    @Test
    void caso02_oxidoDeSodio() {
        assertNames(oxide("Na", "sodio", 1),
                "óxido sódico", "óxido de sodio", "monóxido de disodio");
    }

    @Test
    void caso03_oxidoDeAluminio() {
        assertNames(oxide("Al", "aluminio", 3),
                "óxido alumínico", "óxido de aluminio", "trióxido de dialuminio");
    }

    @Test
    void caso04_oxidoFerroso() {
        assertNames(oxide("Fe", "hierro", 2),
                "óxido ferroso", "óxido de hierro (II)", "monóxido de hierro");
    }

    @Test
    void caso05_oxidoFerrico() {
        assertNames(oxide("Fe", "hierro", 3),
                "óxido férrico", "óxido de hierro (III)", "trióxido de dihierro");
    }

    // ===== Óxidos no metálicos (anhídridos) =====

    @Test
    void caso06_anhidridoFosforoso() {
        assertNames(oxide("P", "fósforo", 3),
                "anhídrido fosforoso", "óxido de fósforo (III)", "trióxido de difósforo");
    }

    @Test
    void caso07_anhidridoFosforico() {
        assertNames(oxide("P", "fósforo", 5),
                "anhídrido fosfórico", "óxido de fósforo (V)", "pentóxido de difósforo");
    }

    @Test
    void caso08_anhidridoSulfuroso() {
        assertNames(oxide("S", "azufre", 4),
                "anhídrido sulfuroso", "óxido de azufre (IV)", "dióxido de azufre");
    }

    @Test
    void caso09_anhidridoSulfurico() {
        assertNames(oxide("S", "azufre", 6),
                "anhídrido sulfúrico", "óxido de azufre (VI)", "trióxido de azufre");
    }

    // ===== Hidróxidos =====

    @Test
    void caso10_hidroxidoCalcico() {
        assertNames(hydroxide("Ca", "calcio", 2),
                "hidróxido cálcico", "hidróxido de calcio", "dihidróxido de calcio");
    }

    @Test
    void caso11_hidroxidoSodico() {
        assertNames(hydroxide("Na", "sodio", 1),
                "hidróxido sódico", "hidróxido de sodio", "monohidróxido de sodio");
    }

    @Test
    void caso12_hidroxidoFerroso() {
        assertNames(hydroxide("Fe", "hierro", 2),
                "hidróxido ferroso", "hidróxido de hierro (II)", "dihidróxido de hierro");
    }

    @Test
    void caso13_hidroxidoFerrico() {
        assertNames(hydroxide("Fe", "hierro", 3),
                "hidróxido férrico", "hidróxido de hierro (III)", "trihidróxido de hierro");
    }

    // ===== Ácidos hidrácidos =====

    @Test
    void caso14_acidoClorhidrico() {
        assertNames(hydracid("Cl"),
                "ácido clorhídrico", "cloruro de hidrógeno", "cloruro de hidrógeno");
    }

    @Test
    void caso15_acidoBromhidrico() {
        assertNames(hydracid("Br"),
                "ácido bromhídrico", "bromuro de hidrógeno", "bromuro de hidrógeno");
    }

    @Test
    void caso16_acidoSulfhidrico() {
        assertNames(hydracid("S"),
                "ácido sulfhídrico", "sulfuro de hidrógeno", "sulfuro de dihidrógeno");
    }

    // ===== Oxácidos =====

    @Test
    void caso17_acidoSulfurico() {
        assertNames(oxoacid("sulfato"),
                "ácido sulfúrico", "ácido tetraoxosulfúrico (VI)", "tetraoxosulfato (VI) de dihidrógeno");
    }

    @Test
    void caso18_acidoSulfuroso() {
        assertNames(oxoacid("sulfito"),
                "ácido sulfuroso", "ácido trioxosulfúrico (IV)", "trioxosulfato (IV) de dihidrógeno");
    }

    @Test
    void caso19_acidoNitrico() {
        assertNames(oxoacid("nitrato"),
                "ácido nítrico", "ácido trioxonítrico (V)", "trioxonitrato (V) de hidrógeno");
    }

    @Test
    void caso20_acidoNitroso() {
        assertNames(oxoacid("nitrito"),
                "ácido nitroso", "ácido dioxonítrico (III)", "dioxonitrato (III) de hidrógeno");
    }

    @Test
    void caso21_acidoFosforico() {
        assertNames(oxoacid("fosfato"),
                "ácido fosfórico", "ácido tetraoxofosfórico (V)", "tetraoxofosfato (V) de trihidrógeno");
    }

    // ===== Sales binarias =====

    @Test
    void caso22_cloruroSodico() {
        assertNames(salt("Na", 1, "Cl"),
                "cloruro sódico", "cloruro de sodio", "monocloruro de sodio");
    }

    @Test
    void caso23_cloruroCalcico() {
        assertNames(salt("Ca", 2, "Cl"),
                "cloruro cálcico", "cloruro de calcio", "dicloruro de calcio");
    }

    @Test
    void caso24_cloruroFerroso() {
        assertNames(salt("Fe", 2, "Cl"),
                "cloruro ferroso", "cloruro de hierro (II)", "dicloruro de hierro");
    }

    @Test
    void caso25_cloruroFerrico() {
        assertNames(salt("Fe", 3, "Cl"),
                "cloruro férrico", "cloruro de hierro (III)", "tricloruro de hierro");
    }

    @Test
    void caso26_sulfuroAluminico() {
        assertNames(salt("Al", 3, "S"),
                "sulfuro alumínico", "sulfuro de aluminio", "trisulfuro de dialuminio");
    }

    // ===== Oxisales =====

    @Test
    void caso27_sulfatoSodico() {
        assertNames(oxisalt("Na", 1, "sulfato"),
                "sulfato sódico", "sulfato de sodio", "tetraoxosulfato (VI) de disodio");
    }

    @Test
    void caso28_nitratoCalcico() {
        assertNames(oxisalt("Ca", 2, "nitrato"),
                "nitrato cálcico", "nitrato de calcio", "bis(trioxonitrato (V)) de calcio");
    }

    @Test
    void caso29_fosfatoFerrico() {
        assertNames(oxisalt("Fe", 3, "fosfato"),
                "fosfato férrico", "fosfato de hierro (III)", "tetraoxofosfato (V) de hierro (III)");
    }

    @Test
    void caso30_fosfatoMagnesico() {
        assertNames(oxisalt("Mg", 2, "fosfato"),
                "fosfato magnésico", "fosfato de magnesio", "bis(tetraoxofosfato (V)) de trimagnesio");
    }

    // ===== Estaño: criterio escolar estañoso (+2) / estáñico (+4) =====

    @Test
    void caso31_oxidoEstanoso() {
        assertNames(oxide("Sn", "estaño", 2),
                "óxido estañoso", "óxido de estaño (II)", "monóxido de estaño");
    }

    @Test
    void caso32_oxidoEstanico() {
        assertNames(oxide("Sn", "estaño", 4),
                "óxido estáñico", "óxido de estaño (IV)", "dióxido de estaño");
    }

    @Test
    void caso33_hidroxidoEstanoso() {
        assertNames(hydroxide("Sn", "estaño", 2),
                "hidróxido estañoso", "hidróxido de estaño (II)", "dihidróxido de estaño");
    }

    @Test
    void caso34_hidroxidoEstanico() {
        assertNames(hydroxide("Sn", "estaño", 4),
                "hidróxido estáñico", "hidróxido de estaño (IV)", "tetrahidróxido de estaño");
    }

    @Test
    void caso35_cloruroEstanoso() {
        assertNames(salt("Sn", 2, "Cl"),
                "cloruro estañoso", "cloruro de estaño (II)", "dicloruro de estaño");
    }

    @Test
    void caso36_cloruroEstanico() {
        assertNames(salt("Sn", 4, "Cl"),
                "cloruro estáñico", "cloruro de estaño (IV)", "tetracloruro de estaño");
    }

    @Test
    void caso37_nitruroEstanico() {
        assertNames(salt("Sn", 4, "N"),
                "nitruro estáñico", "nitruro de estaño (IV)", "tetranitruro de triestaño");
    }
}
