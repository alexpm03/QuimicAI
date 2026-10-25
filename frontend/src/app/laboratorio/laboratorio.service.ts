import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Una capa de la red (InspectorRed.Capa en Java). salida = forma sin el lote, p. ej. [16, 48, 48]. */
export interface Capa {
  bloque: number;
  tipo: 'preentrenada' | 'convolucion' | 'normalizacion' | 'activacion' | 'pool' | 'aplanar' | 'densa' | 'dropout' | 'otra';
  nombre: string;
  detalle: string;
  salida: number[];
  pesos: number;
  explicacion: string;
}

export interface Arquitectura {
  /** "propia" (diseñada y entrenada desde cero) o "resnet18" (preentrenada con ImageNet) */
  red: string;
  entrada: number[];
  clases: number;
  pesosTotales: number;
  archivo: string;
  capas: Capa[];
}

@Injectable({ providedIn: 'root' })
export class LaboratorioService {
  private readonly http = inject(HttpClient);

  arquitectura(): Observable<Arquitectura> {
    return this.http.get<Arquitectura>('/api/laboratorio/arquitectura');
  }
}
