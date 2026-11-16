import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ChatAiService } from '../../services/chat-ai.service';
import { AuthService } from '../../services/auth.service';
import { ChatUiService } from '../../services/chat-ui.service';
import { ChatMessage } from '../../models/chat-message.model';

@Component({
  selector: 'app-chat-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './chat-widget.html',
  styleUrl: './chat-widget.scss'
})
export class ChatWidget {
  private chatAiService = inject(ChatAiService);
  private authService = inject(AuthService);
  private chat = inject(ChatUiService);

  private messagesContainer = viewChild<ElementRef<HTMLElement>>('messagesContainer');
  private inputEl = viewChild<ElementRef<HTMLInputElement>>('chatInput');

  protected isOpen = this.chat.isOpen;
  protected isLoading = signal(false);
  protected userMessage = signal('');
  protected messages = signal<ChatMessage[]>([]);

  protected isLoggedIn = computed(() => this.authService.isLoggedIn());
  protected userName = computed(() => this.authService.getFullName());

  protected readonly suggestions = [
    'What can I borrow right now?',
    'Which books are by Robert C. Martin?',
    'Recommend something about AI'
  ];

  constructor() {
    // greeting + focus whenever the panel opens
    effect(() => {
      if (!this.isOpen()) return;
      untracked(() => {
        if (this.messages().length === 0 && this.isLoggedIn()) {
          this.messages.set([{
            role: 'assistant',
            content: `Hi ${this.userName()}! I'm your library assistant. Ask me anything about the collection.`,
            timestamp: new Date()
          }]);
        }
        setTimeout(() => { this.inputEl()?.nativeElement.focus(); this.scrollToBottom(); }, 80);
      });
    });

    // a page asked the assistant something (e.g. "Ask AI" on a book card)
    effect(() => {
      const question = this.chat.pendingQuestion();
      if (!question) return;
      untracked(() => {
        this.chat.pendingQuestion.set(null);
        if (this.isLoggedIn()) this.send(question);
      });
    });
  }

  protected toggleChat(): void {
    this.chat.toggle();
  }

  protected close(): void {
    this.chat.close();
  }

  protected onInput(event: Event): void {
    this.userMessage.set((event.target as HTMLInputElement).value);
  }

  protected sendMessage(): void {
    this.send(this.userMessage());
  }

  protected send(text: string): void {
    const question = text.trim();
    if (!question || this.isLoading()) return;

    this.messages.update(msgs => [...msgs, { role: 'user' as const, content: question, timestamp: new Date() }]);
    this.userMessage.set('');
    this.isLoading.set(true);

    // Empty assistant message that fills up while streaming
    const assistantMessage: ChatMessage = { role: 'assistant', content: '', timestamp: new Date(), isStreaming: true };
    this.messages.update(msgs => [...msgs, assistantMessage]);
    this.scrollToBottom();

    this.chatAiService.askStream(question).subscribe({
      next: (chunk) => {
        assistantMessage.content += chunk;
        this.messages.update(msgs => [...msgs]);
        this.scrollToBottom();
      },
      error: (err) => {
        console.error('Stream error:', err);
        assistantMessage.content = 'Sorry, something went wrong. Please try again.';
        assistantMessage.isStreaming = false;
        this.isLoading.set(false);
        this.messages.update(msgs => [...msgs]);
        this.scrollToBottom();
      },
      complete: () => {
        // Format the complete message - add newlines before numbered items
        assistantMessage.content = assistantMessage.content.replace(/(\d+)\.\s/g, '\n$1. ');
        assistantMessage.isStreaming = false;
        this.isLoading.set(false);
        this.messages.update(msgs => [...msgs]);
        this.scrollToBottom();
      }
    });
  }

  protected onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  protected onPanelKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      this.close();
    }
  }

  protected clearChat(): void {
    this.messages.set([{ role: 'assistant', content: 'Chat cleared! How can I help you?', timestamp: new Date() }]);
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const el = this.messagesContainer()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    }, 50);
  }
}
