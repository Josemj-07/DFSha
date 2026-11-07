package co.edu.upb.dfsha.server.namespace.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DirectoryIndexTest {
  DirectoryIndex crear() {
    var index = new DirectoryIndex();
    index.poner("/", 1);
    index.poner("/home", 2);
    index.poner("/home/ana", 3);
    index.poner("/home/ana/a", 4);
    index.poner("/home/ana/a/file", 5);
    return index;
  }

  @Test
  void indexarPorInodoPadreYNombre() {
    var index = crear();
    assertEquals(5L, index.entries.get(new DirectoryEntry(4, "file")));
  }

  @Test
  void moverSinReescribirDescendientes() {
    var index = crear();
    var child = new DirectoryEntry(4, "file");
    index.mover("/home/ana/a", "/home/ana/b");
    assertEquals(5L, index.obtener("/home/ana/b/file"));
    assertNull(index.obtener("/home/ana/a/file"));
    assertEquals(5L, index.entries.get(child));
  }

  @Test
  void serializarYReconstruirIndice() {
    var mapper = JsonMapper.builder().build();
    var index = crear();
    var json = mapper.writeValueAsString(index);
    var recovered = mapper.readValue(json, DirectoryIndex.class);
    assertEquals(index.entries, recovered.entries);
    assertEquals(index.listarRutas(), recovered.listarRutas());
  }

  @Test
  void aislarCopia() {
    var index = crear();
    var copy = index.copiar();
    copy.mover("/home/ana/a", "/home/ana/b");
    assertNotNull(index.obtener("/home/ana/a/file"));
  }
}
