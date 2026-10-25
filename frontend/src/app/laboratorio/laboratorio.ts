import { Component, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';

import { Arquitectura, Capa, LaboratorioService } from './laboratorio.service';

/** Capas seguidas que pertenecen al mismo bloque convolucional (bloque 0 = capas sueltas). */
interface Grupo {
  bloque: number;
  capas: Capa[];
}

/** Dibujo de la forma de un tensor: pila de cuadrados (mapas) o barra (fila de números). */
interface Glifo {
  tipo: 'mapas' | 'fila';
  lado: number;
  capas: number[];
  ancho: number;
}

@Component({
  selector: 'app-laboratorio',
  imports: [NgTemplateOutlet],
  templateUrl: './laboratorio.html',
  styleUrl: './laboratorio.scss',
})
export class Laboratorio {
  private readonly laboratorio = inject(LaboratorioService);

  protected readonly arquitectura = signal<Arquitectura | null>(null);
  protected readonly error = signal('');

  protected readonly grupos = computed<Grupo[]>(() => {
    const grupos: Grupo[] = [];
    for (const capa of this.arquitectura()?.capas ?? []) {
      const ultimo = grupos.at(-1);
      if (ultimo && capa.bloque > 0 && ultimo.bloque === capa.bloque) {
        ultimo.capas.push(capa);
      } else {
        grupos.push({ bloque: capa.bloque, capas: [capa] });
      }
    }
    return grupos;
  });

  constructor() {
    this.laboratorio.arquitectura().subscribe({
      next: (arquitectura) => this.arquitectura.set(arquitectura),
      error: (error: HttpErrorResponse) => this.error.set(this.explicarError(error)),
    });
  }

  protected forma(salida: number[]): string {
    return salida.join(' × ');
  }

  protected numero(valor: number): string {
    return valor.toLocaleString('es-ES');
  }

  /** "524.416 pesos · 41 %" solo cuando la capa pesa algo apreciable dentro del total. */
  protected parteDelTotal(pesos: number): string {
    const total = this.arquitectura()?.pesosTotales ?? 0;
    if (!total || pesos / total < 0.01) {
      return '';
    }
    return `${Math.round((pesos / total) * 100)} % del total`;
  }

  protected glifo(salida: number[]): Glifo {
    if (salida.length === 3) {
      const [canales, alto] = salida;
      // Cuadrado proporcional al tamaño de la imagen; más cuadrados apilados = más canales (filtros)
      const apilados = Math.max(1, Math.min(5, Math.round(Math.log2(canales)) - 2));
      return { tipo: 'mapas', lado: 12 + alto * 0.42, capas: Array.from({ length: apilados }, (_, i) => i), ancho: 0 };
    }
    const valores = salida[0] ?? 1;
    return { tipo: 'fila', lado: 0, capas: [], ancho: 12 + Math.log2(valores) * 6 };
  }

  private explicarError(error: HttpErrorResponse): string {
    if (error.status === 0 || error.status >= 502) {
      return 'No hay conexión con el servidor. Arranca el backend con mvnw.cmd spring-boot:run y recarga la página.';
    }
    return `El servidor no ha podido describir la red (error ${error.status}). Revisa la terminal del backend: puede que el modelo guardado no coincida con la arquitectura del código.`;
  }
}
