package com.hify.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TextInputTest {
    @Test void rejectsOnlyActualNulWithoutEchoingOrNormalizingText() {
        TextInput.requireNoNul(null,"中文🙂\n\t literal \\u0000");
        for(String text:new String[]{"\0private","private\0","pri\0vate"})
            assertThatThrownBy(()->TextInput.requireNoNul(text)).isInstanceOfSatisfying(BizException.class,
                    e->{assertThat(e.errorCode()).isEqualTo(ErrorCode.PARAM_ERROR);assertThat(e.getMessage()).doesNotContain("private");});
    }
    @Test void checksNestedValuesAndKeysWithoutChangingTheTree() throws Exception {
        var mapper=new ObjectMapper();
        var root=mapper.readTree("{\"rows\":[1,true,null,{\"label\":\"中文\"}]}");
        var before=root.deepCopy();
        TextInput.requireNoNulInJson(root);assertThat(root).isEqualTo(before);
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.at("/rows/3")).put("label","bad\0value");
        assertThatThrownBy(()->TextInput.requireNoNulInJson(root)).isInstanceOf(BizException.class);
        var keys=mapper.createObjectNode().put("bad\0key","ok");
        assertThatThrownBy(()->TextInput.requireNoNulInJson(keys)).isInstanceOf(BizException.class);
    }
}
