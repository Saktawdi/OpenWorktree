package gate.adapters.engine;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** PrismJson 的报错可诊断性：非数字 token 必须指认字符与位置，而不是裸 NumberFormatException。 */
class PrismJsonTest {

    @Test
    void nonNumberTokenNamesOffendingCharacterAndPosition() {
        // T-127：thinking 模型的 <think> 前缀曾在此走进 parseNumber，裸抛 "For input string: \"\"" 无从定位。
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PrismJson.parse("{\"a\":<}"));
        assertTrue(e.getMessage().contains("expected number but got '<' at 5"), e.getMessage());
    }

    @Test
    void loneDashBeforeEndDoesNotLeakNumberFormatException() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PrismJson.parse("[ - ]"));
        assertTrue(e.getMessage().startsWith("expected number but got"), e.getMessage());
    }
}
