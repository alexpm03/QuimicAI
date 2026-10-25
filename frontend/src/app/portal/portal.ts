import { Component, computed, inject, OnDestroy, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { Compuesto, Prediccion, PrediccionService } from '../prediccion.service';
import { formatearPorcentaje, ParteFormula, partesFormula } from '../compuestos';

type Estado = 'espera' | 'analizando' | 'listo' | 'error';

interface Linea {
  tipo: 'info' | 'ok' | 'aviso' | 'error';
  texto: string;
}

const TIPOS_ACEPTADOS = ['image/png', 'image/jpeg', 'image/gif', 'image/bmp'];
const TAMANO_MAXIMO = 10 * 1024 * 1024;
// Por debajo de esta confianza avisamos de que el resultado es dudoso
const CONFIANZA_MINIMA = 0.7;
// Del ranking completo solo enseñamos los más probables
const OPCIONES_VISIBLES = 3;

const TEXTO_ESTADO: Record<Estado, string> = {
  espera: 'en espera',
  analizando: 'analizando',
  listo: 'listo',
  error: 'error',
};

@Component({
  selector: 'app-portal',
  templateUrl: './portal.html',
  styleUrl: './portal.scss',
})
export class Portal implements OnDestroy {
  private readonly prediccion = inject(PrediccionService);

  protected readonly compuestos = signal<Compuesto[]>([]);
  protected readonly estado = signal<Estado>('espera');
  protected readonly arrastrando = signal(false);
  protected readonly vistaPrevia = signal<string | null>(null);
  protected readonly nombreArchivo = signal('');
  protected readonly resultado = signal<Prediccion | null>(null);
  protected readonly lineas = signal<Linea[]>([
    { tipo: 'info', texto: 'Esperando una imagen.' },
  ]);
  protected readonly textoEstado = computed(() => TEXTO_ESTADO[this.estado()]);
  protected readonly masProbables = computed(() => this.resultado()?.ranking.slice(0, OPCIONES_VISIBLES) ?? []);

  constructor() {
    // Si el backend está apagado, la lista se queda vacía y el portal sigue funcionando
    this.prediccion.compuestos().subscribe({
      next: (compuestos) => this.compuestos.set(compuestos),
      error: () => this.compuestos.set([]),
    });
  }

  protected alArrastrarEncima(evento: DragEvent): void {
    evento.preventDefault();
    this.arrastrando.set(true);
  }

  protected alSalirArrastrando(evento: DragEvent): void {
    // dragleave también salta al pasar por encima de un hijo: solo cuenta si salimos de la zona
    const zona = evento.currentTarget as HTMLElement;
    if (!zona.contains(evento.relatedTarget as Node | null)) {
      this.arrastrando.set(false);
    }
  }

  protected alSoltar(evento: DragEvent): void {
    evento.preventDefault();
    this.arrastrando.set(false);
    const archivo = evento.dataTransfer?.files[0];
    if (archivo) {
      this.analizar(archivo);
    }
  }

  protected alElegir(evento: Event): void {
    const selector = evento.target as HTMLInputElement;
    const archivo = selector.files?.[0];
    if (archivo) {
      this.analizar(archivo);
    }
    // Permite volver a elegir el mismo archivo
    selector.value = '';
  }

  protected partes(formula: string): ParteFormula[] {
    return partesFormula(formula);
  }

  protected porcentaje(probabilidad: number): string {
    return formatearPorcentaje(probabilidad);
  }

  protected anchoBarra(probabilidad: number): number {
    // Un mínimo para que hasta las probabilidades diminutas dejen una marca visible
    return Math.max(probabilidad * 100, 0.6);
  }

  ngOnDestroy(): void {
    this.liberarVistaPrevia();
  }

  private analizar(archivo: File): void {
    if (!TIPOS_ACEPTADOS.includes(archivo.type)) {
      this.fallo(`«${archivo.name}» no es una imagen PNG, JPG, GIF o BMP. Prueba con otro archivo.`);
      return;
    }
    if (archivo.size > TAMANO_MAXIMO) {
      const megas = (archivo.size / 1024 / 1024).toLocaleString('es-ES', { maximumFractionDigits: 1 });
      this.fallo(`«${archivo.name}» ocupa ${megas} MB y el máximo son 10 MB. Guárdala más pequeña y vuelve a subirla.`);
      return;
    }

    this.liberarVistaPrevia();
    this.vistaPrevia.set(URL.createObjectURL(archivo));
    this.nombreArchivo.set(archivo.name);
    this.resultado.set(null);
    this.estado.set('analizando');
    const kilobytes = Math.max(1, Math.round(archivo.size / 1024));
    this.lineas.set([
      { tipo: 'info', texto: `${archivo.name} cargada (${kilobytes} KB).` },
      { tipo: 'info', texto: 'Analizando la estructura…' },
    ]);

    this.prediccion.analizar(archivo).subscribe({
      next: (respuesta) => this.mostrar(respuesta),
      error: (error: HttpErrorResponse) => this.fallo(this.explicarError(error)),
    });
  }

  private mostrar(respuesta: Prediccion): void {
    const nombre = respuesta.nombre.toLowerCase();
    const confianza = formatearPorcentaje(respuesta.confianza);
    const conclusion: Linea[] =
      respuesta.confianza >= CONFIANZA_MINIMA
        ? [{ tipo: 'ok', texto: `Es ${nombre} con un ${confianza} de confianza.` }]
        : [
            { tipo: 'aviso', texto: `Resultado dudoso: se parece más a ${nombre}, pero solo con un ${confianza}.` },
            { tipo: 'aviso', texto: 'Prueba con un dibujo más limpio, sin sombras ni texto alrededor.' },
          ];

    this.lineas.update((lineas) => [...lineas, ...conclusion]);
    this.resultado.set(respuesta);
    this.estado.set('listo');
  }

  private fallo(mensaje: string): void {
    this.resultado.set(null);
    this.estado.set('error');
    this.lineas.update((lineas) => [...lineas, { tipo: 'error', texto: mensaje }]);
  }

  private explicarError(error: HttpErrorResponse): string {
    if (error.status === 0 || error.status >= 502) {
      return 'No hay conexión con el servidor. Arranca el backend con mvnw.cmd spring-boot:run y vuelve a subir la imagen.';
    }
    if (error.status === 413) {
      return 'La imagen es demasiado grande para el servidor. El máximo son 10 MB.';
    }
    if (error.status === 400) {
      // El backend explica el motivo en { "error": "..." }
      const motivo = error.error?.error ?? 'El servidor no ha podido leer la imagen.';
      return `${motivo} Prueba con otro archivo.`;
    }
    return `El servidor no ha podido analizar la imagen (error ${error.status}). Revisa la terminal del backend para ver el motivo.`;
  }

  private liberarVistaPrevia(): void {
    const url = this.vistaPrevia();
    if (url) {
      URL.revokeObjectURL(url);
    }
  }
}
