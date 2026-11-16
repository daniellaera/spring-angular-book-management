import { Injectable, signal } from '@angular/core';

/** Lets any page open the assistant, optionally with a question that is sent right away. */
@Injectable({ providedIn: 'root' })
export class ChatUiService {
  readonly isOpen = signal(false);
  /** A question waiting to be sent; the chat widget consumes it. */
  readonly pendingQuestion = signal<string | null>(null);

  open(question?: string): void {
    if (question?.trim()) this.pendingQuestion.set(question.trim());
    this.isOpen.set(true);
  }

  toggle(): void {
    this.isOpen.update(v => !v);
  }

  close(): void {
    this.isOpen.set(false);
  }
}
