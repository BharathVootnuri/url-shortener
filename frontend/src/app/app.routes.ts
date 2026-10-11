import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    title: 'Shorten a URL',
    pathMatch: 'full',
    loadComponent: () => import('./features/create/create-page').then((m) => m.CreatePage),
  },
  {
    path: 'links/:code',
    title: 'Link details',
    loadComponent: () => import('./features/details/details-page').then((m) => m.DetailsPage),
  },
  {
    path: 'links/:code/analytics',
    title: 'Link analytics',
    loadComponent: () => import('./features/analytics/analytics-page').then((m) => m.AnalyticsPage),
  },
  {
    path: '**',
    title: 'Page not found',
    loadComponent: () => import('./features/not-found/not-found-page').then((m) => m.NotFoundPage),
  },
];
