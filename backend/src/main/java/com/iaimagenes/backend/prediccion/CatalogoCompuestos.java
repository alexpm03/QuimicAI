package com.iaimagenes.backend.prediccion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Nombre y fórmula de cada compuesto, leídos al arrancar de datos/compuestos_pubchem.csv
 * (el archivo que crea DescargarCompuestos). Traduce la clave interna que da la red
 * ("acido_acetico") a lo que ve el estudiante ("Ácido acético", "C2H4O2").
 */
@Service
public class CatalogoCompuestos {

    /** iupac: el nombre sistemático en español; si no se ha escrito, el oficial en inglés de PubChem. */
    public record Compuesto(String clave, String nombre, String formula, String iupac) {}

    private final List<Compuesto> compuestos;
    private final Map<String, Compuesto> porClave;

    public CatalogoCompuestos(@Value("${datos.compuestos}") String ruta) throws IOException {
        compuestos = Files.readAllLines(Path.of(ruta), StandardCharsets.UTF_8).stream()
                .skip(1)
                .filter(linea -> !linea.isBlank())
                // -1 conserva las columnas vacías del final
                .map(linea -> linea.split(";", -1))
                // clave;nombre;cid;formula;smiles;iupac_en;iupac_es
                .map(c -> new Compuesto(c[0].trim(), c[1].trim(), c[3].trim(), iupac(c)))
                .toList();
        porClave = compuestos.stream().collect(Collectors.toMap(Compuesto::clave, Function.identity()));
    }

    private static String iupac(String[] columnas) {
        String espanol = columnas.length > 6 ? columnas[6].trim() : "";
        String ingles = columnas.length > 5 ? columnas[5].trim() : "";
        return espanol.isEmpty() ? ingles : espanol;
    }

    public List<Compuesto> todos() {
        return compuestos;
    }

    /** Si la clave no está en el CSV (no debería pasar), se devuelve tal cual y sin fórmula. */
    public Compuesto buscar(String clave) {
        return porClave.getOrDefault(clave, new Compuesto(clave, clave, "", ""));
    }
}
