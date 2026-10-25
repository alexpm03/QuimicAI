import { Routes } from '@angular/router';

import { Portal } from './portal/portal';
import { Laboratorio } from './laboratorio/laboratorio';

export const routes: Routes = [
  { path: '', component: Portal, title: 'Moléculas · identifica fórmulas estructurales' },
  { path: 'laboratorio', component: Laboratorio, title: 'Laboratorio · Moléculas' },
  { path: '**', redirectTo: '' },
];
