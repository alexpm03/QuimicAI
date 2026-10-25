package com.iaimagenes.backend.entrenamiento;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.openscience.cdk.exception.InvalidSmilesException;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;

/**
 * Lee datos/compuestos.csv, pide a PubChem el SMILES y la fórmula de cada compuesto
 * y descarga una imagen suya para usarla como examen con un estilo de dibujo distinto al nuestro.
 *
 * Entrada:  datos/compuestos.csv           (clave;nombre;pubchem;iupac_es)   iupac_es es opcional
 * Salidas:  datos/compuestos_pubchem.csv   (clave;nombre;cid;formula;smiles;iupac_en;iupac_es)
 *           pruebas/pubchem/<clave>.png
 */
public class DescargarCompuestos {

    private static final String PUBCHEM = "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/";

    // PubChem pide no pasar de 5 peticiones por segundo; hacemos 2 por compuesto con margen
    private static final long PAUSA_MS = 400;

    record Fila(String clave, String nombre, String pubchem, String iupacEs) {}

    public static void main(String[] args) throws Exception {
        Path raiz = Path.of("..");
        Path entrada = raiz.resolve("datos/compuestos.csv");
        Path salida = raiz.resolve("datos/compuestos_pubchem.csv");
        Path carpetaImagenes = raiz.resolve("pruebas/pubchem");
        Files.createDirectories(carpetaImagenes);

        HttpClient http = HttpClient.newHttpClient();
        SmilesParser parser = new SmilesParser(SilentChemObjectBuilder.getInstance());

        List<String> resultado = new ArrayList<>();
        resultado.add("clave;nombre;cid;formula;smiles;iupac_en;iupac_es");
        List<String> fallos = new ArrayList<>();

        for (Fila fila : leer(entrada)) {
            try {
                String nombreUrl = URLEncoder.encode(fila.pubchem(), StandardCharsets.UTF_8).replace("+", "%20");
                String csv = pedirTexto(http,
                        PUBCHEM + "name/" + nombreUrl + "/property/SMILES,MolecularFormula,IUPACName/CSV");

                // Respuesta: cabecera + una fila por CID; nos quedamos con la primera
                // "CID","SMILES","MolecularFormula","IUPACName"
                // 2519,"CN1C=NC2=...","C8H10N4O2","1,3,7-trimethylpurine-2,6-dione"
                // Ojo: el nombre IUPAC lleva comas dentro de las comillas, no se puede cortar por "," sin más
                List<String> campos = camposCsv(csv.lines().skip(1).findFirst().orElseThrow());
                String cid = campos.get(0);
                String smiles = campos.get(1);
                String formula = campos.get(2);
                String iupacEn = campos.size() > 3 ? campos.get(3) : "";

                // Comprobamos que CDK entiende el SMILES antes de darlo por bueno
                parser.parseSmiles(smiles);
                Thread.sleep(PAUSA_MS);

                byte[] png = pedirBytes(http, PUBCHEM + "cid/" + cid + "/PNG?image_size=large");
                Files.write(carpetaImagenes.resolve(fila.clave() + ".png"), png);
                Thread.sleep(PAUSA_MS);

                resultado.add(String.join(";", fila.clave(), fila.nombre(), cid, formula, smiles, iupacEn, fila.iupacEs()));
                System.out.printf("OK     %-15s CID %-9s %-11s %s%n", fila.clave(), cid, formula, iupacEn);
            } catch (InvalidSmilesException e) {
                fallos.add(fila.clave() + ": CDK no entiende su SMILES (" + e.getMessage() + ")");
            } catch (Exception e) {
                fallos.add(fila.clave() + ": " + e.getMessage());
            }
        }

        Files.write(salida, resultado, StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Guardados " + (resultado.size() - 1) + " compuestos en " + salida.toAbsolutePath().normalize());
        System.out.println("Imágenes de prueba en " + carpetaImagenes.toAbsolutePath().normalize());
        if (!fallos.isEmpty()) {
            System.out.println();
            System.out.println("No se han podido descargar " + fallos.size() + ":");
            fallos.forEach(f -> System.out.println("  - " + f));
        }
    }

    private static List<Fila> leer(Path csv) throws Exception {
        return Files.readAllLines(csv, StandardCharsets.UTF_8).stream()
                .skip(1)
                .filter(linea -> !linea.isBlank())
                // -1 conserva la última columna aunque esté vacía
                .map(linea -> linea.split(";", -1))
                .map(c -> new Fila(c[0].trim(), c[1].trim(), c[2].trim(), c.length > 3 ? c[3].trim() : ""))
                .toList();
    }

    /** Separa una línea CSV por comas, respetando las comas que van dentro de "comillas". */
    private static List<String> camposCsv(String linea) {
        List<String> campos = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean dentroDeComillas = false;
        for (char c : linea.toCharArray()) {
            if (c == '"') {
                dentroDeComillas = !dentroDeComillas;
            } else if (c == ',' && !dentroDeComillas) {
                campos.add(actual.toString().trim());
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        campos.add(actual.toString().trim());
        return campos;
    }

    private static String pedirTexto(HttpClient http, String url) throws Exception {
        HttpResponse<String> respuesta = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        comprobar(respuesta.statusCode(), url);
        return respuesta.body();
    }

    private static byte[] pedirBytes(HttpClient http, String url) throws Exception {
        HttpResponse<byte[]> respuesta = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        comprobar(respuesta.statusCode(), url);
        return respuesta.body();
    }

    private static void comprobar(int estado, String url) {
        if (estado == 404) {
            throw new IllegalStateException("PubChem no conoce ese nombre (404)");
        }
        if (estado != 200) {
            throw new IllegalStateException("PubChem respondió " + estado + " a " + url);
        }
    }
}
