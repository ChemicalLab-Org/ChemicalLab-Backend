package com.morales.chemicallab.dto;

/**
 * Tipo de ácido que sabe formar el motor químico en el MVP.
 *
 * <ul>
 *   <li>{@code HYDRACID}: hidrácido, hidrógeno + un no metal (HCl, H2S).</li>
 *   <li>{@code OXOACID}: oxácido, hidrógeno + un oxoanión (H2SO4, HNO3).</li>
 * </ul>
 */
public enum AcidType {
    HYDRACID,
    OXOACID
}
