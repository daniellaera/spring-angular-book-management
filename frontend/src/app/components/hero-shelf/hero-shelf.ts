import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Book } from '../../models/book.model';
import { BookCover } from '../book-cover/book-cover';

/** Decorative fanned stack of generated covers for the hero (hidden from assistive tech, desktop only). */
@Component({
  selector: 'app-hero-shelf',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BookCover],
  template: `
    <div class="shelf" aria-hidden="true">
      @for (b of books(); track b.id; let i = $index) {
        <div class="shelf__book" [style.--n]="i"><app-book-cover [book]="b" /></div>
      }
    </div>`,
  styleUrl: './hero-shelf.scss'
})
export class HeroShelf {
  books = input.required<Book[]>();
}
