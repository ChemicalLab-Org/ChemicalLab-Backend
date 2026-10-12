package com.morales.chemicallab.security.limits;

public class InputRejectedException extends IllegalArgumentException {
    private final int status;
    private final String code;
    public InputRejectedException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static InputRejectedException size() { return new InputRejectedException(413, "INPUT_LIMIT_EXCEEDED", "La entrada supera los límites permitidos. Reduce su tamaño y reintenta."); }
    public static InputRejectedException invalid() { return new InputRejectedException(400, "INPUT_INVALID", "La estructura o los valores de la entrada no son válidos."); }
}
