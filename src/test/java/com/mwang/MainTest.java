package com.mwang;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @Test
    void numberIsTrue() {
        int x = 4;
        assertThat(x).isEqualTo(4);
    }

}
