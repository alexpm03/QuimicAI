package com.iaimagenes.backend.entrenamiento;

import java.util.ArrayList;
import java.util.List;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.nn.Block;
import ai.djl.nn.Blocks;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.convolutional.Conv2d;
import ai.djl.nn.core.Linear;
import ai.djl.nn.norm.BatchNorm;
import ai.djl.nn.norm.Dropout;
import ai.djl.nn.pooling.Pool;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;

/** Construye la red (vacía o con su base preentrenada) que corresponde a una ConfigModelo. */
public final class Arquitecturas {

    // Los modelos del zoo de los que sacamos la base de ResNet. Hay que tenerlos referenciados mientras
    // se use la red: si Java los recoge como basura, se cierran y liberan la memoria de PyTorch de la base
    // ("PyTorch model handle has been released!" a mitad del entrenamiento).
    private static final List<ZooModel<NDList, NDList>> BASES_EN_USO = new ArrayList<>();

    private Arquitecturas() {}

    public static Block crear(ConfigModelo config, int numClases) throws Exception {
        return config.preentrenada() ? resnet18(numClases) : propia(numClases);
    }

    /**
     * Nuestra red, diseñada capa a capa y entrenada desde cero. 64 -> 32 -> 16 -> 8 píxeles.
     * (Probamos con 96 px y un cuarto bloque: acertaba más en el dataset sintético pero menos con
     * imágenes reales, 47 frente a 54 de 93, porque aprendía detalles del estilo de dibujo de CDK.)
     */
    public static SequentialBlock propia(int numClases) {
        return new SequentialBlock()
                .add(capaConvolucional(16))
                .add(capaConvolucional(32))
                .add(capaConvolucional(64))
                .add(Blocks.batchFlattenBlock())
                .add(Linear.builder().setUnits(256).build())
                .add(Activation.reluBlock())
                .add(Dropout.builder().optRate(0.2f).build())
                .add(Linear.builder().setUnits(numClases).build());
    }

    /**
     * Transfer learning: ResNet18 entrenada con ImageNet (1,2 millones de fotos) sin su última capa,
     * más una capa nueva con una salida por compuesto. DJL la descarga la primera vez (~45 MB).
     * La base sale como 512 x 1 x 1 por imagen; la aplanamos a 512 números antes de la capa nueva.
     */
    public static SequentialBlock resnet18(int numClases) throws Exception {
        Criteria<NDList, NDList> criterio = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelUrls("djl://ai.djl.pytorch/resnet18_embedding")
                .optEngine("PyTorch")
                // Sin esto la base vendría congelada y no se podría ajustar al entrenar
                .optOption("trainParam", "true")
                .build();
        ZooModel<NDList, NDList> zoo = criterio.loadModel();
        synchronized (BASES_EN_USO) {
            BASES_EN_USO.add(zoo);
        }
        Block base = zoo.getBlock();

        return new SequentialBlock()
                .add(base)
                .add(Blocks.batchFlattenBlock())
                .add(Linear.builder().setUnits(numClases).build());
    }

    private static SequentialBlock capaConvolucional(int filtros) {
        return new SequentialBlock()
                .add(Conv2d.builder()
                        .setKernelShape(new Shape(3, 3))
                        .optPadding(new Shape(1, 1))
                        .setFilters(filtros)
                        .build())
                .add(BatchNorm.builder().build())
                .add(Activation.reluBlock())
                .add(Pool.maxPool2dBlock(new Shape(2, 2), new Shape(2, 2)));
    }
}
