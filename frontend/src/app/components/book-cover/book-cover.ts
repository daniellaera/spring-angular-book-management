import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { Book } from '../../models/book.model';
import { ThemeService } from '../../services/theme.service';
import { coverStyle } from '../../services/book-visuals';

/** Real cover when the book has an image, otherwise a generated one (genre colour + title monogram). */
@Component({
  selector: 'app-book-cover',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="cover"
      [class.cover--lg]="size() === 'lg'"
      [class.cover--sm]="size() === 'sm'"
      [style.background]="book().image ? null : style().background"
      [attr.data-pattern]="style().pattern"
      [attr.role]="decorative() ? null : 'img'"
      [attr.aria-label]="decorative() ? null : 'Cover of ' + book().title"
      [attr.aria-hidden]="decorative() ? 'true' : null">
      @if (book().image) {
        <img [src]="book().image" alt="" loading="lazy" />
      } @else {
        <span class="cover__genre">{{ book().genre || 'Book' }}</span>
        <span class="cover__title">{{ book().title }}</span>
        <span class="cover__mono">{{ style().monogram }}</span>
      }
    </div>
  `,
  styleUrl: './book-cover.scss'
})
export class BookCover {
  private theme = inject(ThemeService);

  book = input.required<Pick<Book, 'title' | 'genre' | 'image'>>();
  size = input<'sm' | 'md' | 'lg'>('md');
  /** Decorative when the title is already shown next to it (cards, table rows). */
  decorative = input(true);

  protected style = computed(() => coverStyle(this.book(), this.theme.isDark()));
}
