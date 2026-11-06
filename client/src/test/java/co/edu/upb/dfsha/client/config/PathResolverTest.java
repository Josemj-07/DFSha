package co.edu.upb.dfsha.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PathResolverTest {

    @ParameterizedTest
    @CsvSource({
        "/home/jose, ., /home/jose",
        "/home/jose, .., /home",
        "/home/jose, a/b, /home/jose/a/b",
        "/home/jose, /tmp/x, /tmp/x",
        "/home/jose, ../../.., /",
        "/, ., /",
        "/home/jose, a//b///c, /home/jose/a/b/c",
        "/home/jose, ./a/../b, /home/jose/b",
        "/home/jose, /, /",
    })
    void resuelveRutas(String cwd, String path, String expected) {
        assertEquals(expected, PathResolver.resolver(cwd, path));
    }
}
