package com.psicocare.shared;

public class Problem extends RuntimeException {
    public final int status;
    public final String code;

    public Problem(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public static Problem notFound() {
        return new Problem(404, "RESOURCE_NOT_FOUND");
    }

    public static void require(boolean condition, int status, String code) {
        if (!condition) throw new Problem(status, code);
    }
}
