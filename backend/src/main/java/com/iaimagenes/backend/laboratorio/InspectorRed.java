package com.iaimagenes.backend.laboratorio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.iaimagenes.backend.prediccion.ClasificadorService;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.ParameterStore;
import ai.djl.nn.Block;
import ai.djl.nn.LambdaBlock;
import ai.djl.nn.Parameter;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.convolutional.Convolution;
import ai.djl.nn.core.Linear;
import ai.djl.nn.norm.BatchNorm;
import ai.djl.nn.norm.Dropout;

/**
 * Recorre la red que tiene cargada el backend y la describe capa a capa:
 * qué tipo de capa es, qué forma tiene lo que sale de ella y cuántos pesos aprende.
 * Todo se lee del modelo real, así que si cambia crearRed() el laboratorio lo refleja solo.
 */
@Service
public class InspectorRed {

    /** Una capa tal y como la ve el laboratorio. salida = forma sin el tamaño de lote, p. ej. [16, 48, 48]. */
    public record Capa(int bloque, String tipo, String nombre, String detalle, List<Long> salida, long pesos,
            String explicacion) {}

    /** red: "propia" o "resnet18", según modelo.properties. */
    public record Arquitectura(String red, List<Long> entrada, int clases, long pesosTotales, String archivo,
            List<Capa> capas) {}

    private final ClasificadorService clasificador;

    public InspectorRed(ClasificadorService clasificador) {
        this.clasificador = clasificador;
    }

    public Arquitectura describir() throws Exception {
        int tamano = clasificador.config().tamano();
        Shape entrada = new Shape(1, 3, tamano, tamano);
        List<Capa> capas = new ArrayList<>();
        Shape actual = entrada;
        int bloque = 0;

        // La red es una secuencia: bloques convolucionales (que a su vez son secuencias) y capas sueltas
        for (Block hijo : clasificador.red().getChildren().values()) {
            if (hijo instanceof SequentialBlock secuencia) {
                bloque++;
                for (Block capa : secuencia.getChildren().values()) {
                    actual = anadir(capas, bloque, capa, actual);
                }
            } else {
                actual = anadir(capas, 0, hijo, actual);
            }
        }

        long total = capas.stream().mapToLong(Capa::pesos).sum();
        return new Arquitectura(clasificador.config().arquitectura(), sinLote(entrada), clasificador.clases().size(),
                total, clasificador.archivoPesos(), capas);
    }

    private static Shape anadir(List<Capa> capas, int bloque, Block capa, Shape entrada) {
        Shape salida = formaDeSalida(capa, entrada);
        capas.add(describirCapa(bloque, capa, salida));
        return salida;
    }

    /**
     * Las capas de DJL saben calcular su forma de salida; la base de ResNet (un bloque TorchScript)
     * no, así que en ese caso pasamos una imagen vacía por ella y medimos lo que sale.
     */
    private static Shape formaDeSalida(Block capa, Shape entrada) {
        try {
            return capa.getOutputShapes(new Shape[] {entrada})[0];
        } catch (RuntimeException e) {
            try (NDManager manager = NDManager.newBaseManager()) {
                NDList salida = capa.forward(new ParameterStore(manager, false), new NDList(manager.zeros(entrada)), false);
                return salida.singletonOrThrow().getShape();
            }
        }
    }

    private static Capa describirCapa(int bloque, Block capa, Shape salida) {
        long pesos = pesosQueAprende(capa);
        List<Long> forma = sinLote(salida);

        if (capa.getClass().getSimpleName().equals("PtSymbolBlock")) {
            return new Capa(bloque, "preentrenada", "ResNet18 preentrenada", "17 convoluciones + 1 agrupación, de ImageNet",
                    forma, pesos,
                    "La red de Microsoft entrenada con 1,2 millones de fotos, sin su última capa. Llega ya sabiendo "
                            + "detectar bordes, curvas, círculos y texturas, y resume cada imagen en " + forma.get(0)
                            + " números. Al entrenar con nuestros dibujos se ajusta con suavidad.");
        }

        if (capa instanceof Convolution conv) {
            Shape k = conv.getKernelShape();
            return new Capa(bloque, "convolucion", "Convolución",
                    conv.getFilters() + " filtros de " + k.get(0) + "×" + k.get(1), forma, pesos,
                    "Pasa " + conv.getFilters() + " plantillas pequeñas por toda la imagen. Cada una aprende a "
                            + "reconocer un patrón (una línea, un ángulo, una letra) y produce un mapa de dónde lo ha visto.");
        }
        if (capa instanceof BatchNorm) {
            return new Capa(bloque, "normalizacion", "Normalización", "BatchNorm", forma, pesos,
                    "Reajusta los valores para que tengan una escala parecida. Es el «ecualizador»: evita que las "
                            + "neuronas se apaguen o se disparen y deja que la red aprenda más deprisa.");
        }
        if (capa instanceof Dropout) {
            return new Capa(bloque, "dropout", "Dropout", "solo al entrenar", forma, pesos,
                    "Durante el entrenamiento apaga neuronas al azar para que la red no dependa de un solo detalle. "
                            + "Al predecir no hace nada.");
        }
        if (capa instanceof Linear) {
            boolean ultima = forma.size() == 1 && forma.get(0) < 200;
            return new Capa(bloque, "densa", ultima ? "Salida" : "Densa",
                    forma.get(0) + (ultima ? " notas, una por compuesto" : " neuronas"), forma, pesos,
                    ultima
                            ? "Da una nota a cada compuesto. Después, softmax convierte esas notas en probabilidades."
                            : "Cada neurona mira todos los valores anteriores a la vez y combina las pistas encontradas "
                                    + "por las convoluciones. Es donde más pesos hay.");
        }
        String nombre = capa instanceof LambdaBlock lambda ? lambda.getName().toLowerCase() : capa.getClass().getSimpleName();
        if (nombre.contains("relu")) {
            return new Capa(bloque, "activacion", "ReLU", "sin pesos", forma, pesos,
                    "Pone a cero los valores negativos: «si no has visto nada, cállate». Sin esto, la red solo "
                            + "podría aprender relaciones muy simples.");
        }
        if (nombre.contains("pool")) {
            return new Capa(bloque, "pool", "MaxPool", "2×2, se queda con el máximo", forma, pesos,
                    "Reduce la imagen a la mitad quedándose con el valor más alto de cada cuadradito de 2×2. "
                            + "Resume y ahorra cálculo.");
        }
        if (nombre.contains("flatten")) {
            return new Capa(bloque, "aplanar", "Aplanar", "de mapas a una fila", forma, pesos,
                    "Pone todos los mapas en una sola fila de números para que las capas densas puedan leerlos.");
        }
        return new Capa(bloque, "otra", nombre, "", forma, pesos, "");
    }

    /** Solo cuenta los pesos que se ajustan al entrenar (las medias internas de BatchNorm no). */
    private static long pesosQueAprende(Block capa) {
        long total = 0;
        for (Parameter parametro : capa.getParameters().values()) {
            if (parametro.requiresGradient() && parametro.isInitialized()) {
                total += parametro.getArray().size();
            }
        }
        return total;
    }

    private static List<Long> sinLote(Shape forma) {
        return Arrays.stream(forma.getShape()).skip(1).boxed().toList();
    }
}
