import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ApiProblem } from '../core/api-problem';

/** Shows an ApiProblem: friendly message, optional retry, and the trace id for support. */
@Component({
  selector: 'app-error-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="error-panel" role="alert">
      <p class="error-message">{{ problem().message }}</p>
      @if (problem().retryAfterSeconds; as seconds) {
        <p class="muted">Please try again in about {{ seconds }} second{{ seconds === 1 ? '' : 's' }}.</p>
      }
      @if (problem().traceId; as traceId) {
        <p class="muted">Reference: <code>{{ traceId }}</code></p>
      }
      @if (showRetry() && problem().retryable) {
        <button type="button" class="secondary" (click)="retry.emit()">Try again</button>
      }
    </div>
  `,
})
export class ErrorPanel {
  readonly problem = input.required<ApiProblem>();
  readonly showRetry = input(true);
  readonly retry = output<void>();
}
