package com.iaimagenes.backend.entrenamiento;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monta un examen más grande con imágenes reales de cada compuesto, en pruebas/examen/<clave>/:
 *
 *   pubchem_grande.png   PubChem a 300 x 300
 *   pubchem_pequena.png  PubChem a 100 x 100 (poca resolución)
 *   wiki_es.png          imagen principal del artículo de Wikipedia en español
 *   wiki_en.png          imagen principal del artículo de Wikipedia en inglés (si es otra distinta)
 *
 * La imagen principal de Wikipedia casi siempre es la fórmula estructural, pero a veces es una foto
 * o un modelo 3D: conviene revisar la carpeta y borrar las que no sean un dibujo 2D.
 */
public class AmpliarExamen {

    private static final String PUBCHEM = "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/";
    private static final String USER_AGENT = "iaImagenes/1.0 (proyecto educativo de reconocimiento de moleculas)";
    // Entre descargas: PubChem pide no pasar de 5 por segundo y Wikipedia es más estricta todavía
    private static final long PAUSA_MS = 1500;

    record Compuesto(String clave, String nombre, String cid, String pubchem) {}

    public static void main(String[] args) throws Exception {
        Path carpetaExamen = Path.of("../pruebas/examen");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        List<Compuesto> compuestos = leer();

        // Wikipedia limita las peticiones: pedimos los 30 artículos de golpe, una sola vez por idioma
        Map<String, String> miniaturasEs = miniaturasWikipedia(http, "es", compuestos.stream().map(Compuesto::nombre).toList());
        Map<String, String> miniaturasEn = miniaturasWikipedia(http, "en", compuestos.stream().map(Compuesto::pubchem).toList());

        // Las que se revisaron a mano y no son dibujos 2D (fotos, modelos 3D): no se vuelven a descargar
        Path descartadas = carpetaExamen.resolve("descartadas.txt");
        Set<String> descartes = Files.exists(descartadas)
                ? new HashSet<>(Files.readAllLines(descartadas, StandardCharsets.UTF_8))
                : Set.of();

        int total = 0;
        for (Compuesto c : compuestos) {
            Path carpeta = carpetaExamen.resolve(c.clave());
            Files.createDirectories(carpeta);
            StringBuilder resumen = new StringBuilder();

            total += guardar(http, PUBCHEM + c.cid() + "/PNG?image_size=large", carpeta.resolve("pubchem_grande.png"), resumen, "pubchem 300");
            total += guardar(http, PUBCHEM + c.cid() + "/PNG?image_size=small", carpeta.resolve("pubchem_pequena.png"), resumen, "pubchem 100");

            // Si las dos Wikipedias usan el mismo archivo, solo lo guardamos una vez
            Set<String> yaGuardadas = new HashSet<>();
            String urlEs = miniaturasEs.get(c.nombre());
            if (urlEs != null && yaGuardadas.add(archivoBase(urlEs)) && !descartes.contains(c.clave() + "/wiki_es")) {
                total += guardar(http, urlEs, carpeta.resolve("wiki_es.png"), resumen, "wiki es");
            }
            String urlEn = miniaturasEn.get(c.pubchem());
            if (urlEn != null && yaGuardadas.add(archivoBase(urlEn)) && !descartes.contains(c.clave() + "/wiki_en")) {
                total += guardar(http, urlEn, carpeta.resolve("wiki_en.png"), resumen, "wiki en");
            }
            System.out.printf("%-15s %s%n", c.clave(), resumen);
        }
        System.out.println();
        System.out.println(total + " imágenes en " + carpetaExamen.toAbsolutePath().normalize());
        System.out.println("Revisa las de Wikipedia: borra las que sean fotos o modelos 3D en vez de dibujos 2D.");
    }

    /** Lee clave, nombre, CID y nombre de PubChem de los dos CSV de datos/. */
    private static List<Compuesto> leer() throws Exception {
        List<String[]> pubchem = Files.readAllLines(Path.of("../datos/compuestos_pubchem.csv"), StandardCharsets.UTF_8)
                .stream().skip(1).filter(l -> !l.isBlank()).map(l -> l.split(";", -1)).toList();
        List<String[]> lista = Files.readAllLines(Path.of("../datos/compuestos.csv"), StandardCharsets.UTF_8)
                .stream().skip(1).filter(l -> !l.isBlank()).map(l -> l.split(";", -1)).toList();

        return pubchem.stream().map(p -> {
            String nombreIngles = lista.stream().filter(l -> l[0].trim().equals(p[0].trim()))
                    .map(l -> l[2].trim()).findFirst().orElse(p[1].trim());
            return new Compuesto(p[0].trim(), p[1].trim(), p[2].trim(), nombreIngles);
        }).toList();
    }

    /**
     * Pide a Wikipedia, en una sola petición, la miniatura (en PNG) de la imagen principal de cada artículo.
     * Devuelve título pedido -> URL de la miniatura; los artículos sin imagen no aparecen.
     */
    private static Map<String, String> miniaturasWikipedia(HttpClient http, String idioma, List<String> titulos)
            throws Exception {
        String url = "https://" + idioma + ".wikipedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&redirects=1&prop=pageimages&piprop=thumbnail&pithumbsize=500&pilimit=50&titles="
                + URLEncoder.encode(String.join("|", titulos), StandardCharsets.UTF_8);
        String cuerpo = http.send(peticion(url), HttpResponse.BodyHandlers.ofString()).body();
        if (!cuerpo.startsWith("{")) {
            System.out.println("Wikipedia (" + idioma + ") no ha respondido bien, probablemente por exceso de peticiones."
                    + " Espera unos minutos y vuelve a ejecutarlo.");
            return Map.of();
        }

        JsonNode consulta = JsonMapper.builder().build().readTree(cuerpo).path("query");

        // Wikipedia corrige mayúsculas ("sucrose" -> "Sucrose") y sigue redirecciones ("acetaminophen" -> "Paracetamol")
        Map<String, String> cambios = new HashMap<>();
        for (String tipo : List.of("normalized", "redirects")) {
            for (JsonNode cambio : consulta.path(tipo)) {
                cambios.put(cambio.path("from").asString(), cambio.path("to").asString());
            }
        }
        Map<String, String> miniaturaPorPagina = new HashMap<>();
        for (JsonNode pagina : consulta.path("pages")) {
            String fuente = pagina.path("thumbnail").path("source").asString("");
            if (!fuente.isEmpty()) {
                miniaturaPorPagina.put(pagina.path("title").asString(), fuente);
            }
        }

        Map<String, String> resultado = new HashMap<>();
        for (String titulo : titulos) {
            String pagina = titulo;
            while (cambios.containsKey(pagina)) {
                pagina = cambios.get(pagina);
            }
            if (miniaturaPorPagina.containsKey(pagina)) {
                resultado.put(titulo, miniaturaPorPagina.get(pagina));
            }
        }
        return resultado;
    }

    private static int guardar(HttpClient http, String url, Path destino, StringBuilder resumen, String etiqueta) {
        if (Files.exists(destino)) {
            // Ya descargada en una ejecución anterior: así repetir el programa no vuelve a pedir lo mismo
            resumen.append(etiqueta).append(" ✔  ");
            return 1;
        }
        try {
            HttpResponse<byte[]> respuesta = http.send(peticion(url), HttpResponse.BodyHandlers.ofByteArray());
            Thread.sleep(PAUSA_MS);
            if (respuesta.statusCode() != 200) {
                resumen.append("[").append(etiqueta).append(": error ").append(respuesta.statusCode()).append("] ");
                return 0;
            }
            Files.write(destino, respuesta.body());
            resumen.append(etiqueta).append(" ✔  ");
            return 1;
        } catch (Exception e) {
            resumen.append("[").append(etiqueta).append(": ").append(e.getMessage()).append("] ");
            return 0;
        }
    }

    /** ".../thumb/a/a1/Koffein_-_Caffeine.svg/500px-..." -> "Koffein_-_Caffeine.svg" */
    private static String archivoBase(String url) {
        String sinParametros = url.split("\\?")[0];
        String[] partes = sinParametros.split("/");
        return partes.length >= 2 ? partes[partes.length - 2] : sinParametros;
    }

    private static HttpRequest peticion(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).GET().build();
    }
}
