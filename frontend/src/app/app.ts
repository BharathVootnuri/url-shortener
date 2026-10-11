import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { environment } from '../environments/environment';
import { extractCode } from './core/code-parser';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, ReactiveFormsModule],
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);

  protected readonly lookup = new FormControl('', { nonNullable: true });
  protected readonly lookupError = signal<string | null>(null);

  /** "Open" (also what Enter does): follows the short link in a new tab, i.e. the real redirect. */
  protected openShortLink(event: Event): void {
    event.preventDefault(); // never let the browser do a full-page form submit
    const code = this.codeOrError();
    if (code) {
      // Always go through our own backend so the click is recorded, whatever host was pasted.
      window.open(`${environment.apiBaseUrl}/${code}`, '_blank', 'noopener');
    }
  }

  /** "Details": shows the link's details page inside this app. */
  protected showDetails(): void {
    const code = this.codeOrError();
    if (code) {
      this.lookup.reset();
      void this.router.navigate(['/links', code]);
    }
  }

  private codeOrError(): string | null {
    const code = extractCode(this.lookup.value);
    this.lookupError.set(code ? null : 'Enter a short code or a short URL.');
    return code;
  }
}
