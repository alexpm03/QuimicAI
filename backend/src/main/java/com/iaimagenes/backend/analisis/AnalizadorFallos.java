package com.iaimagenes.backend.analisis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.openscience.cdk.fingerprint.CircularFingerprinter;
import org.openscience.cdk.fingerprint.IBitFingerprint;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.similarity.Tanimoto;
import org.openscience.cdk.smiles.SmilesParser;

/**
 * Módulo 1: clasifica los fallos del examen en los 4 tipos que hemos visto que se repiten,
 * para saber qué problema atacar antes de tocar la red o el dataset.
 *
 *   PEQUENA    la imagen es la versión pequeña de PubChem (líneas rotas, letras pixeladas)
 *   WIKIPEDIA  la imagen es de Wikipedia (otro estilo de dibujo)
 *   PARECIDA   ha dicho un compuesto casi igual al correcto (dopamina por adrenalina...)
 *   CAJON      ha dicho una clase "cajón de sastre": la que recibe fallos de muchos compuestos distintos
 *
 * Un mismo fallo puede tener varios tipos (una imagen pequeña confundida con una molécula parecida).
 */
public class AnalizadorFallos {

    public enum Tipo {
        PEQUENA("Imagen pequeña"),
        WIKIPEDIA("Estilo Wikipedia"),
        PARECIDA("Molécula casi igual"),
        CAJON("Cajón de sastre");

        public final String nombre;

        Tipo(String nombre) {
            this.nombre = nombre;
        }
    }

    /** Lo que respondió la red para una imagen del examen. */
    public record Resultado(String esperado, String fuente, String predicho, double confianza) {
        public boolean acierto() {
            return esperado.equals(predicho);
        }
    }

    public record Fallo(Resultado resultado, Set<Tipo> tipos, double parecido) {}

    public record Informe(List<Resultado> resultados, List<Fallo> fallos, Map<String, Set<String>> cajones) {

        public int aciertos() {
            return resultados.size() - fallos.size();
        }

        public int conTipo(Tipo tipo) {
            return (int) fallos.stream().filter(f -> f.tipos().contains(tipo)).count();
        }

        public int sinExplicar() {
            return (int) fallos.stream().filter(f -> f.tipos().isEmpty()).count();
        }

        /** Fallos en los que la red estaba casi segura: los más peligrosos en el portal. */
        public int fallosSeguros() {
            return (int) fallos.stream().filter(f -> f.resultado().confianza() >= CONFIANZA_ALTA).count();
        }
    }

    // Parecido químico (Tanimoto de huellas ECFP4, de 0 a 1) a partir del cual dos compuestos cuentan
    // como "casi iguales". Con nuestros 30: dopamina/adrenalina 0,31, fenol/tolueno 0,38,
    // alanina/ácido láctico 0,56; en cambio cafeína o colesterol no pasan de 0,2 con ninguno.
    static final double PARECIDO_MINIMO = 0.25;

    // Una clase es cajón de sastre si en un mismo examen la eligen por error para al menos 3 compuestos distintos
    static final int ORIGENES_CAJON = 3;

    static final double CONFIANZA_ALTA = 0.9;

    private final Map<String, IBitFingerprint> huellas = new HashMap<>();

    /** Lee datos/compuestos_pubchem.csv (clave;nombre;cid;formula;smiles;...) y calcula la huella de cada compuesto. */
    public AnalizadorFallos(Path compuestosCsv) throws Exception {
        SmilesParser parser = new SmilesParser(SilentChemObjectBuilder.getInstance());
        CircularFingerprinter huella = new CircularFingerprinter(CircularFingerprinter.CLASS_ECFP4);
        List<String> lineas = Files.readAllLines(compuestosCsv, StandardCharsets.UTF_8);
        for (String linea : lineas.subList(1, lineas.size())) {
            if (linea.isBlank()) {
                continue;
            }
            String[] campos = linea.split(";");
            huellas.put(campos[0].trim(), huella.getBitFingerprint(parser.parseSmiles(campos[4].trim())));
        }
    }

    /** Parecido químico entre dos compuestos, de 0 (nada en común) a 1 (iguales). */
    public double parecido(String a, String b) {
        IBitFingerprint huellaA = huellas.get(a);
        IBitFingerprint huellaB = huellas.get(b);
        return huellaA == null || huellaB == null ? 0 : Tanimoto.calculate(huellaA, huellaB);
    }

    public Informe analizar(List<Resultado> resultados) {
        // Primero, qué compuestos ha "absorbido" cada clase al equivocarse
        Map<String, Set<String>> absorbidos = new TreeMap<>();
        for (Resultado r : resultados) {
            if (!r.acierto()) {
                absorbidos.computeIfAbsent(r.predicho(), p -> new TreeSet<>()).add(r.esperado());
            }
        }
        Map<String, Set<String>> cajones = new TreeMap<>();
        absorbidos.forEach((clase, origenes) -> {
            if (origenes.size() >= ORIGENES_CAJON) {
                cajones.put(clase, origenes);
            }
        });

        List<Fallo> fallos = new ArrayList<>();
        for (Resultado r : resultados) {
            if (r.acierto()) {
                continue;
            }
            Set<Tipo> tipos = EnumSet.noneOf(Tipo.class);
            if (r.fuente().equals("pubchem_pequena")) {
                tipos.add(Tipo.PEQUENA);
            }
            if (r.fuente().startsWith("wiki")) {
                tipos.add(Tipo.WIKIPEDIA);
            }
            double parecido = parecido(r.esperado(), r.predicho());
            if (parecido >= PARECIDO_MINIMO) {
                tipos.add(Tipo.PARECIDA);
            }
            if (cajones.containsKey(r.predicho())) {
                tipos.add(Tipo.CAJON);
            }
            fallos.add(new Fallo(r, tipos, parecido));
        }
        return new Informe(resultados, fallos, cajones);
    }

    /** Escribe el informe en la consola, en el mismo estilo que el resto del examen. */
    public static void imprimir(Informe informe) {
        List<Fallo> fallos = informe.fallos();
        System.out.println();
        System.out.println("Tipos de fallo (" + fallos.size() + " fallos; uno puede tener varios tipos):");
        for (Tipo tipo : Tipo.values()) {
            System.out.printf("  %-22s %3d%n", tipo.nombre, informe.conTipo(tipo));
        }
        System.out.printf("  %-22s %3d%n", "Sin explicar", informe.sinExplicar());
        System.out.printf("  %-22s %3d  (fallos con confianza de %d %% o más)%n",
                "Fallos muy seguros", informe.fallosSeguros(), Math.round(CONFIANZA_ALTA * 100));

        if (!informe.cajones().isEmpty()) {
            System.out.println();
            System.out.println("Cajones de sastre (clase elegida por error -> compuestos que se ha tragado):");
            informe.cajones().forEach((clase, origenes) ->
                    System.out.printf("  %-15s <- %s%n", clase, String.join(", ", origenes)));
        }

        Map<String, Integer> pares = new TreeMap<>();
        Map<String, Double> parecidos = new HashMap<>();
        for (Fallo f : fallos) {
            if (f.tipos().contains(Tipo.PARECIDA)) {
                String par = f.resultado().esperado() + " -> " + f.resultado().predicho();
                pares.merge(par, 1, Integer::sum);
                parecidos.put(par, f.parecido());
            }
        }
        if (!pares.isEmpty()) {
            System.out.println();
            System.out.println("Confusiones entre moléculas casi iguales (veces, parecido químico):");
            pares.forEach((par, veces) -> System.out.printf("  %-32s %d  (%.2f)%n", par, veces, parecidos.get(par)));
        }

        List<Fallo> sinExplicar = fallos.stream().filter(f -> f.tipos().isEmpty()).toList();
        if (!sinExplicar.isEmpty()) {
            System.out.println();
            System.out.println("Sin explicar (imagen grande de PubChem, molécula distinta, clase normal):");
            for (Fallo f : sinExplicar) {
                Resultado r = f.resultado();
                System.out.printf("  %-15s -> %-15s %5.1f %%%n", r.esperado(), r.predicho(), r.confianza() * 100);
            }
        }
    }

    /** Recuento por tipo, en el orden de {@link Tipo}: lo que se guarda en el historial. */
    public static Map<Tipo, Integer> recuento(Informe informe) {
        Map<Tipo, Integer> recuento = new EnumMap<>(Tipo.class);
        for (Tipo tipo : Tipo.values()) {
            recuento.put(tipo, informe.conTipo(tipo));
        }
        return recuento;
    }
}
