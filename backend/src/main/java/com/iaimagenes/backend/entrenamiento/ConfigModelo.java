package com.iaimagenes.backend.entrenamiento;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Qué red hay guardada en una carpeta de modelo y a qué tamaño trabaja. Se guarda en modelo.properties
 * junto a los pesos, para que el backend, el examen y el laboratorio sepan cómo cargarla.
 *
 *   propia    -> la red diseñada capa a capa en este proyecto, entrenada desde cero, a 64 x 64
 *   resnet18  -> ResNet18 preentrenada con ImageNet, ajustada a nuestros compuestos, a 128 x 128
 */
public record ConfigModelo(String arquitectura, int tamano) {

    public static final ConfigModelo PROPIA = new ConfigModelo("propia", 64);
    public static final ConfigModelo RESNET18 = new ConfigModelo("resnet18", 128);

    private static final String ARCHIVO = "modelo.properties";

    /** Las redes preentrenadas con ImageNet esperan las imágenes normalizadas como en ImageNet. */
    public boolean preentrenada() {
        return !arquitectura.equals(PROPIA.arquitectura());
    }

    /** Cada tamaño tiene su dataset: ../dataset (64) o ../dataset_128. */
    public Path carpetaDataset() {
        return Path.of(tamano == PROPIA.tamano() ? "../dataset" : "../dataset_" + tamano);
    }

    /** Si la carpeta no tiene modelo.properties (modelos anteriores a este archivo), es la red propia. */
    public static ConfigModelo leer(Path carpetaModelo) throws IOException {
        Path archivo = carpetaModelo.resolve(ARCHIVO);
        if (!Files.exists(archivo)) {
            return PROPIA;
        }
        Properties p = new Properties();
        try (Reader lector = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            p.load(lector);
        }
        return new ConfigModelo(p.getProperty("arquitectura", PROPIA.arquitectura()),
                Integer.parseInt(p.getProperty("tamano", String.valueOf(PROPIA.tamano()))));
    }

    public void guardar(Path carpetaModelo) throws IOException {
        Properties p = new Properties();
        p.setProperty("arquitectura", arquitectura);
        p.setProperty("tamano", String.valueOf(tamano));
        try (Writer escritor = Files.newBufferedWriter(carpetaModelo.resolve(ARCHIVO), StandardCharsets.UTF_8)) {
            p.store(escritor, "Arquitectura y tamano de entrada del modelo guardado en esta carpeta");
        }
    }
}
