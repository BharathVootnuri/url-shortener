import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <section class="card">
      <h1>Page not found</h1>
      <p>There is nothing at this address.</p>
      <a class="button" routerLink="/">Back to the start</a>
    </section>
  `,
})
export class NotFoundPage {}
