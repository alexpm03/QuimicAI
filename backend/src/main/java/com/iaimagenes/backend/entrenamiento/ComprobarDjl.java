package com.iaimagenes.backend.entrenamiento;

import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;

public class ComprobarDjl {

    public static void main(String[] args) {
        Engine engine = Engine.getInstance();
        System.out.println("Motor: " + engine.getEngineName() + " " + engine.getVersion());

        try (NDManager manager = engine.newBaseManager()) {
            NDArray unos = manager.ones(new Shape(2, 3));
            System.out.println(unos.mul(2));
        }
    }
}
