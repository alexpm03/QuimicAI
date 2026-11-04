package com.iaimagenes.backend.analisis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.iaimagenes.backend.analisis.AnalizadorFallos.Informe;
import com.iaimagenes.backend.analisis.AnalizadorFallos.Tipo;

/**
 * Guarda cada examen en historial/examenes.csv, una fila por examen, para comparar versiones
 * sin depender de lo que recordemos. La etiqueta agrupa repeticiones del mismo experimento
 * (por ejemplo, entrenar 3 veces la misma red): así se ve cuánto varía el resultado por puro azar.
 */
public final class Historial {

    static final List<String> FUENTES = List.of("pubchem_grande", "pubchem_pequena", "wiki_en", "wiki_es");

    private static final String CABECERA = "fecha;etiqueta;modelo;red;aciertos;total;"
            + String.join(";", FUENTES) + ";pequena;wikipedia;parecida;cajon;sin_explicar;fallos_seguros";

    private Historial() {}

    public static void anotar(Path csv, String etiqueta, String modelo, String red, Informe informe) throws Exception {
        if (!Files.exists(csv)) {
            Files.createDirectories(csv.getParent());
            Files.writeString(csv, CABECERA + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        List<String> campos = new ArrayList<>();
        campos.add(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        campos.add(etiqueta);
        campos.add(modelo);
        campos.add(red);
        campos.add(String.valueOf(informe.aciertos()));
        campos.add(String.valueOf(informe.resultados().size()));
        for (String fuente : FUENTES) {
            campos.add(String.valueOf(informe.resultados().stream()
                    .filter(r -> r.fuente().equals(fuente) && r.acierto()).count()));
        }
        Map<Tipo, Integer> recuento = AnalizadorFallos.recuento(informe);
        for (Tipo tipo : Tipo.values()) {
            campos.add(String.valueOf(recuento.get(tipo)));
        }
        campos.add(String.valueOf(informe.sinExplicar()));
        campos.add(String.valueOf(informe.fallosSeguros()));
        Files.writeString(csv, String.join(";", campos) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    /**
     * Si hay varios exámenes con la misma etiqueta, muestra cuánto varían: si la diferencia entre
     * repeticiones es de 4 aciertos, un cambio que gane 3 no demuestra nada.
     */
    public static void resumirRepeticiones(Path csv, String etiqueta) throws Exception {
        // Examinar dos veces el mismo modelo da siempre lo mismo: no es una repetición.
        // De cada carpeta de modelo nos quedamos con su último examen.
        Map<String, String[]> porModelo = new LinkedHashMap<>();
        Files.readAllLines(csv, StandardCharsets.UTF_8).stream()
                .skip(1)
                .map(linea -> linea.split(";"))
                .filter(f -> f.length > 5 && f[1].equals(etiqueta))
                .forEach(f -> {
                    porModelo.remove(f[2]);
                    porModelo.put(f[2], f);
                });
        List<String[]> filas = new ArrayList<>(porModelo.values());
        if (filas.size() < 2) {
            return;
        }
        List<Integer> aciertos = filas.stream().map(f -> Integer.parseInt(f[4])).toList();
        int minimo = aciertos.stream().min(Integer::compare).orElseThrow();
        int maximo = aciertos.stream().max(Integer::compare).orElseThrow();
        double media = aciertos.stream().mapToInt(Integer::intValue).average().orElseThrow();

        System.out.println();
        System.out.println("Repeticiones de \"" + etiqueta + "\" (" + filas.size() + " modelos distintos): " + aciertos);
        System.out.printf("  Media %.1f, de %d a %d: el azar mueve el resultado %d aciertos.%n",
                media, minimo, maximo, maximo - minimo);
        System.out.println("  Por tipo de fallo en cada repetición (pequeña / wikipedia / parecida / cajón / sin explicar):");
        int primerTipo = 6 + FUENTES.size();
        for (String[] f : filas) {
            System.out.printf("    %-20s %s aciertos:  %s / %s / %s / %s / %s%n", f[2], f[4],
                    f[primerTipo], f[primerTipo + 1], f[primerTipo + 2], f[primerTipo + 3], f[primerTipo + 4]);
        }
    }
}
