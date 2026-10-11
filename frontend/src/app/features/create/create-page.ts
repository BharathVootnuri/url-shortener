import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiProblem } from '../../core/api-problem';
import { ApiService } from '../../core/api.service';
import { LinkResponse } from '../../core/models';
import { expiryValidator, toIsoInstant, urlValidator } from '../../core/url-validation';
import { CopyButton } from '../../shared/copy-button';
import { ErrorPanel } from '../../shared/error-panel';

/** AC7.1 / AC7.2: validated create form, inline server errors, result with copy button and links. */
@Component({
  selector: 'app-create-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, DatePipe, CopyButton, ErrorPanel],
  templateUrl: './create-page.html',
})
export class CreatePage {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = new FormGroup({
    url: new FormControl('', { nonNullable: true, validators: [urlValidator] }),
    expiresAt: new FormControl('', { nonNullable: true, validators: [expiryValidator] }),
  });

  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly result = signal<LinkResponse | null>(null);

  /** Errors reported by the server for a specific field, shown inline until the user edits it. */
  protected readonly urlServerError = signal<string | null>(null);
  protected readonly expiryServerError = signal<string | null>(null);
  /** Errors that belong to no field (rate limit, outage, ...). */
  protected readonly generalProblem = signal<ApiProblem | null>(null);

  constructor() {
    this.form.controls.url.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.urlServerError.set(null));
    this.form.controls.expiresAt.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.expiryServerError.set(null));
  }

  protected urlMessage(): string | null {
    const control = this.form.controls.url;
    const showClient = control.touched || this.submitted();
    return this.urlServerError() ?? (showClient ? (control.errors?.['url'] as string | undefined) ?? null : null);
  }

  protected expiryMessage(): string | null {
    const control = this.form.controls.expiresAt;
    const showClient = control.touched || this.submitted();
    return this.expiryServerError() ?? (showClient ? (control.errors?.['expiry'] as string | undefined) ?? null : null);
  }

  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    this.submitted.set(true);
    this.generalProblem.set(null);
    if (this.form.invalid) {
      return;
    }

    const { url, expiresAt } = this.form.getRawValue();
    this.submitting.set(true);
    this.api
      .createLink({ url: url.trim(), expiresAt: expiresAt ? toIsoInstant(expiresAt) : null })
      .subscribe({
        next: (link) => {
          this.submitting.set(false);
          this.result.set(link);
        },
        error: (problem: ApiProblem) => {
          this.submitting.set(false);
          this.showProblem(problem);
        },
      });
  }

  private showProblem(problem: ApiProblem): void {
    switch (problem.code) {
      case 'INVALID_URL':
        this.urlServerError.set(problem.message);
        break;
      case 'INVALID_EXPIRY':
        this.expiryServerError.set(problem.message);
        break;
      default:
        this.generalProblem.set(problem);
    }
  }

  protected createAnother(): void {
    this.result.set(null);
    this.submitted.set(false);
    this.generalProblem.set(null);
    this.form.reset();
  }
}
