import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
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
import { Analytics, Granularity } from '../../core/models';
import { fillBuckets } from '../../core/time-buckets';
import { ClickChart } from '../../shared/click-chart';
import { ErrorPanel } from '../../shared/error-panel';

export interface RangePreset {
  readonly id: string;
  readonly label: string;
  readonly hours: number;
  readonly granularity: Granularity;
}

export const PRESETS: readonly RangePreset[] = [
  { id: '24h', label: 'Last 24 hours', hours: 24, granularity: 'HOUR' },
  { id: '7d', label: 'Last 7 days', hours: 7 * 24, granularity: 'DAY' },
  { id: '30d', label: 'Last 30 days', hours: 30 * 24, granularity: 'DAY' },
];

/** AC7.3: total clicks plus a chart and table, with loading, empty and error states. */
@Component({
  selector: 'app-analytics-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink, ClickChart, ErrorPanel],
  templateUrl: './analytics-page.html',
})
export class AnalyticsPage {
  /** Bound from the route parameter `:code`. */
  readonly code = input.required<string>();

  protected readonly presets = PRESETS;
  protected readonly preset = signal<RangePreset>(PRESETS[2]);
  protected readonly state = signal<LoadState<Analytics>>(LOADING);

  /** The sparse API series expanded to one bar per bucket (zeros included) for the chart and table. */
  protected readonly dense = computed(() => {
    const s = this.state();
    return s.status === 'loaded'
      ? fillBuckets(s.data.series, s.data.from, s.data.to, s.data.granularity)
      : [];
  });

  private readonly api = inject(ApiService);
  private inFlight?: Subscription;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.inFlight?.unsubscribe());
    effect(() => {
      const code = this.code();
      const preset = this.preset();
      untracked(() => this.load(code, preset));
    });
  }

  protected select(preset: RangePreset): void {
    this.preset.set(preset);
  }

  protected reload(): void {
    this.load(this.code(), this.preset());
  }

  private load(code: string, preset: RangePreset): void {
    this.inFlight?.unsubscribe();
    this.state.set(LOADING);
    // `to` is omitted so the server uses its own clock for "now" (no client clock skew for recent clicks).
    const from = new Date(Date.now() - preset.hours * 3_600_000).toISOString();
    this.inFlight = this.api.getAnalytics(code, { from, granularity: preset.granularity }).subscribe({
      next: (data) => this.state.set({ status: 'loaded', data }),
      error: (problem: ApiProblem) => this.state.set({ status: 'error', problem }),
    });
  }

  protected isGone(problem: ApiProblem): boolean {
    return problem.status === 404 || problem.status === 410;
  }
}
