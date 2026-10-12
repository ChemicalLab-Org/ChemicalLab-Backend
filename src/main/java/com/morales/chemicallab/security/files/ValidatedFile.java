package com.morales.chemicallab.security.files;

/** Metadata and bytes returned only after the complete format policy succeeds. */
public record ValidatedFile(byte[] data, String contentType, String fileName) {
    public boolean image() { return contentType.equals("image/png") || contentType.equals("image/jpeg"); }
}
