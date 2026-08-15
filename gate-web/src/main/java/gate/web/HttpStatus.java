package gate.web;

import gate.domain.error.GateErrorCode;

/**
 * Maps {@link GateErrorCode} to HTTP status codes (执行文档-后端-web §4.4).
 *
 * <table>
 *   <tr><td>OK</td><td>200</td></tr>
 *   <tr><td>REJECT_FINDINGS</td><td>422</td></tr>
 *   <tr><td>REJECT_TOCTOU</td><td>409</td></tr>
 *   <tr><td>REJECT_PRECONDITION</td><td>422</td></tr>
 *   <tr><td>REJECT_NEEDS_HUMAN</td><td>422</td></tr>
 *   <tr><td>GATE_ERROR_ENGINE</td><td>502</td></tr>
 *   <tr><td>GATE_ERROR_IO</td><td>503</td></tr>
 *   <tr><td>GATE_ERROR_CONFIG</td><td>500</td></tr>
 *   <tr><td>USAGE</td><td>400</td></tr>
 *   <tr><td>INTERNAL</td><td>500</td></tr>
 * </table>
 */
final class HttpStatus {

    private HttpStatus() {
    }

    static int forGateError(GateErrorCode code) {
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
