package co.edu.upb.dfsha.server.namespace.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class PathNormalizerTest {
  @Test
  void canonizar() {
    assertEquals("/home/a/c", PathNormalizer.normalizar("/home/a/b/../c"));
  }

  @Test
  void rechazarEscape() {
    assertThrows(IllegalArgumentException.class, () -> PathNormalizer.normalizar("/../../a"));
  }

  @Test
  void rechazarControlYVacios() {
    for (String path : new String[] {"/home//a", "/home/a/", "/home/\n", "relativa"})
      assertThrows(IllegalArgumentException.class, () -> PathNormalizer.normalizar(path));
  }
}
