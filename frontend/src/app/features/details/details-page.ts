import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiProblem } from '../../core/api-problem';
import { ApiService } from '../../core/api.service';
import { LOADING, LoadState } from '../../core/load-state';
import { LinkDetails } from '../../core/models';
import { CopyButton } from '../../shared/copy-button';
import { ErrorPanel } from '../../shared/error-panel';

/** Link details. AC7.4: unknown (404) and expired (410) links get a human-readable message. */
@Component({
  selector: 'app-details-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink, CopyButton, ErrorPanel],
  templateUrl: './details-page.html',
})
export class DetailsPage {
  /** Bound from the route parameter `:code`. */
  readonly code = input.required<string>();

  private readonly api = inject(ApiService);
  protected readonly state = signal<LoadState<LinkDetails>>(LOADING);
  private inFlight?: Subscription;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.inFlight?.unsubscribe());
    effect(() => {
      const code = this.code();
      untracked(() => this.load(code));
    });
  }

  protected reload(): void {
    this.load(this.code());
  }

  private load(code: string): void {
    this.inFlight?.unsubscribe();
    this.state.set(LOADING);
    this.inFlight = this.api.getDetails(code).subscribe({
      next: (data) => this.state.set({ status: 'loaded', data }),
      error: (problem: ApiProblem) => this.state.set({ status: 'error', problem }),
    });
  }

  /** 404 and 410 are "this link is not usable", not a failure worth retrying. */
  protected isGone(problem: ApiProblem): boolean {
    return problem.status === 404 || problem.status === 410;
  }
}
