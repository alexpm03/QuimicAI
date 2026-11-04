package com.iaimagenes.backend.lector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;

import com.iaimagenes.backend.prediccion.Prediccion;
import com.iaimagenes.backend.prediccion.Prediccion.Probabilidad;

/**
 * Paso 4 del lector: junta lo que dice la red principal con las letras leídas.
 *
 * Cada candidato de la red se multiplica por CASTIGO_POR_LETRA una vez por cada O o N que le sobre o le
 * falte respecto a lo leído, y luego se reparten de nuevo los porcentajes para que sumen 100 %.
 *
 *   letras exactas          x 1
 *   una letra de diferencia x 0,05
 *   dos letras              x 0,0025 ...
 *
 * Por qué 0,05: el lector cuenta bien las O y N en 85 de 88 imágenes, así que se equivoca en torno a
 * 1 de cada 20 veces; cuando las letras no encajan, lo más probable (unas 20 veces más) es que el
 * candidato sea falso y no que el lector haya fallado. No es una eliminación: si el lector se equivoca
 * en una letra y la red está muy segura del compuesto correcto, todavía puede ganar.
 * (Con 0,3 se quedaba corto: la red seguía imponiendo errores dichos al 97 %.)
 */
public final class CombinadorLetras {

    static final double CASTIGO_POR_LETRA = 0.05;

    private final Map<String, int[]> letrasPorCompuesto;

    public CombinadorLetras(Path compuestosCsv) throws Exception {
        letrasPorCompuesto = letrasPorCompuesto(compuestosCsv);
    }

    public Prediccion reordenar(Prediccion red, LectorLetras.Resultado leidas) {
        List<Probabilidad> ajustadas = new ArrayList<>();
        double suma = 0;
        for (Probabilidad p : red.ranking()) {
            int[] esperadas = letrasPorCompuesto.getOrDefault(p.compuesto(), new int[2]);
            int diferencia = Math.abs(esperadas[0] - leidas.oxigenos()) + Math.abs(esperadas[1] - leidas.nitrogenos());
            double ajustada = p.probabilidad() * Math.pow(CASTIGO_POR_LETRA, diferencia);
            ajustadas.add(new Probabilidad(p.compuesto(), ajustada));
            suma += ajustada;
        }
        double total = suma;
        List<Probabilidad> ranking = ajustadas.stream()
                .map(p -> new Probabilidad(p.compuesto(), p.probabilidad() / total))
                .sorted((a, b) -> Double.compare(b.probabilidad(), a.probabilidad()))
                .toList();
        return new Prediccion(ranking.get(0).compuesto(), ranking.get(0).probabilidad(), ranking);
    }

    /** Cuántos átomos de O y de N tiene cada compuesto ({O, N}), a partir de su SMILES. */
    public static Map<String, int[]> letrasPorCompuesto(Path csv) throws Exception {
        SmilesParser parser = new SmilesParser(SilentChemObjectBuilder.getInstance());
        Map<String, int[]> cuentas = new HashMap<>();
        List<String> lineas = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (String linea : lineas.subList(1, lineas.size())) {
            if (linea.isBlank()) {
                continue;
            }
            String[] campos = linea.split(";");
            IAtomContainer molecula = parser.parseSmiles(campos[4].trim());
            int[] on = new int[2];
            for (IAtom atomo : molecula.atoms()) {
                if (atomo.getSymbol().equals("O")) {
                    on[0]++;
                } else if (atomo.getSymbol().equals("N")) {
                    on[1]++;
                }
            }
            cuentas.put(campos[0].trim(), on);
        }
        return cuentas;
    }
}
