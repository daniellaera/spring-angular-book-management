import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Book } from '../../models/book.model';
import { BookCover } from '../book-cover/book-cover';
import { ChatUiService } from '../../services/chat-ui.service';
import { genreDot } from '../../services/book-visuals';

/** One book in the grid: cover, title, author, genre, rating and availability. The whole card is one link (the title). */
@Component({
  selector: 'app-book-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, BookCover],
  templateUrl: './book-card.html',
  styleUrl: './book-card.scss'
})
export class BookCard {
  private chat = inject(ChatUiService);

  book = input.required<Book>();
  /** index inside the list, used to stagger the entrance animation */
  index = input(0);

  protected dot = computed(() => genreDot(this.book().genre));
  protected rating = computed(() => this.book().averageRating || 0);

  protected askAbout(): void {
    this.chat.open(`Tell me about "${this.book().title}" by ${this.book().author}`);
  }
}
