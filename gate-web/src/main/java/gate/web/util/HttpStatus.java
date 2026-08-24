package gate.web.util;

import gate.domain.error.GateErrorCode;

/**
 * Maps {@link GateErrorCode} to HTTP status codes (执行文档-后端-web §4.4).
 */
public final class HttpStatus {

    private HttpStatus() {
    }

    public static int forGateError(GateErrorCode code) {
        return switch (code) {
            case OK -> 200;
            case REJECT_FINDINGS, REJECT_PRECONDITION, REJECT_NEEDS_HUMAN -> 422;
            case REJECT_TOCTOU -> 409;
            case GATE_ERROR_ENGINE -> 502;
            case GATE_ERROR_IO -> 503;
            case GATE_ERROR_CONFIG -> 500;
            case USAGE -> 400;
            case INTERNAL -> 500;
        };
    }
}
