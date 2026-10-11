import { ChangeDetectionStrategy, Component, OnDestroy, input, signal } from '@angular/core';

/** Copies text to the clipboard and confirms (or reports failure) in an aria-live region. */
@Component({
  selector: 'app-copy-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="secondary" (click)="copy()">{{ label() }}</button>
    <span class="copy-status" role="status" aria-live="polite">{{ status() }}</span>
  `,
})
export class CopyButton implements OnDestroy {
  readonly text = input.required<string>();
  readonly label = input('Copy');
  protected readonly status = signal('');
  private timer: ReturnType<typeof setTimeout> | undefined;

  async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.text());
      this.show('Copied!');
    } catch {
      this.show('Copy failed. Select the text and copy it manually.');
    }
  }

  private show(message: string): void {
    this.status.set(message);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.status.set(''), 3000);
  }

  ngOnDestroy(): void {
    clearTimeout(this.timer);
  }
}
