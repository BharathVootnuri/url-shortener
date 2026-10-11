import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ClickBucket, Granularity } from '../core/models';

interface Bar {
  x: number;
  y: number;
  width: number;
  height: number;
  clicks: number;
  title: string;
}

interface Tick {
  x: number;
  text: string;
}

const WIDTH = 640;
const HEIGHT = 240;
const MARGIN = { top: 12, right: 8, bottom: 30, left: 44 };
const MAX_X_LABELS = 6;

/**
 * Dependency-free SVG bar chart of clicks per bucket (UTC). Expects a dense series (see fillBuckets).
 * It is decorative for assistive technology: the page also renders the same numbers as a table.
 */
@Component({
  selector: 'app-click-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      class="chart"
      [attr.viewBox]="'0 0 ' + width + ' ' + height"
      preserveAspectRatio="xMidYMid meet"
      role="img"
      [attr.aria-label]="ariaLabel()"
    >
      <!-- horizontal grid + y labels -->
      @for (line of gridLines(); track line.value) {
        <line [attr.x1]="margin.left" [attr.x2]="width - margin.right" [attr.y1]="line.y" [attr.y2]="line.y" class="grid" />
        <text [attr.x]="margin.left - 6" [attr.y]="line.y + 4" text-anchor="end" class="axis-label">{{ line.value }}</text>
      }
      @for (bar of bars(); track bar.x) {
        <rect [attr.x]="bar.x" [attr.y]="bar.y" [attr.width]="bar.width" [attr.height]="bar.height" class="bar">
          <title>{{ bar.title }}</title>
        </rect>
      }
      @for (tick of xTicks(); track tick.x) {
        <text [attr.x]="tick.x" [attr.y]="height - 10" text-anchor="middle" class="axis-label">{{ tick.text }}</text>
      }
    </svg>
  `,
})
export class ClickChart {
  readonly buckets = input.required<readonly ClickBucket[]>();
  readonly granularity = input.required<Granularity>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly margin = MARGIN;

  private readonly plotWidth = WIDTH - MARGIN.left - MARGIN.right;
  private readonly plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;

  private readonly maxClicks = computed(() => Math.max(1, ...this.buckets().map((b) => b.clicks)));

  protected readonly bars = computed<Bar[]>(() => {
    const buckets = this.buckets();
    const slot = this.plotWidth / Math.max(1, buckets.length);
    const barWidth = Math.max(1, slot * 0.7);
    const max = this.maxClicks();
    return buckets.map((bucket, i) => {
      const height = (bucket.clicks / max) * this.plotHeight;
      return {
        x: MARGIN.left + i * slot + (slot - barWidth) / 2,
        y: MARGIN.top + this.plotHeight - height,
        width: barWidth,
        height,
        clicks: bucket.clicks,
        title: `${formatBucket(bucket.bucketStart, this.granularity(), true)}: ${bucket.clicks} click${bucket.clicks === 1 ? '' : 's'}`,
      };
    });
  });

  protected readonly gridLines = computed(() => {
    const max = this.maxClicks();
    const values = max <= 4 ? Array.from({ length: max + 1 }, (_, i) => i) : [0, Math.round(max / 2), max];
    return [...new Set(values)].map((value) => ({
      value,
      y: MARGIN.top + this.plotHeight - (value / max) * this.plotHeight,
    }));
  });

  protected readonly xTicks = computed<Tick[]>(() => {
    const buckets = this.buckets();
    const slot = this.plotWidth / Math.max(1, buckets.length);
    const every = Math.max(1, Math.ceil(buckets.length / MAX_X_LABELS));
    const ticks: Tick[] = [];
    for (let i = 0; i < buckets.length; i += every) {
      ticks.push({
        x: MARGIN.left + i * slot + slot / 2,
        text: formatBucket(buckets[i].bucketStart, this.granularity(), false),
      });
    }
    return ticks;
  });

  protected readonly ariaLabel = computed(() => {
    const total = this.buckets().reduce((sum, b) => sum + b.clicks, 0);
    return `Bar chart of clicks per ${this.granularity() === 'DAY' ? 'day' : 'hour'}, ${total} in total. The same data is available in the table below.`;
  });
}

/** Formats a bucket start in UTC; `long` adds the date for hourly buckets and the year. */
export function formatBucket(iso: string, granularity: Granularity, long: boolean): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const options: Intl.DateTimeFormatOptions =
    granularity === 'DAY'
      ? { month: 'short', day: 'numeric', ...(long ? { year: 'numeric' } : {}), timeZone: 'UTC' }
      : long
        ? { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC' }
        : { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC' };
  return new Intl.DateTimeFormat('en-US', options).format(date) + (long && granularity === 'HOUR' ? ' UTC' : '');
}
