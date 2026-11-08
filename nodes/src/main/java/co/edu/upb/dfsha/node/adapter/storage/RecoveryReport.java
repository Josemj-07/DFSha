package co.edu.upb.dfsha.node.adapter.storage;

/** Resultado de revisar el almacén al arrancar. */
public record RecoveryReport(int partesBorradas, int bloquesValidos, int enCuarentena, int metasHuerfanas) {
}
