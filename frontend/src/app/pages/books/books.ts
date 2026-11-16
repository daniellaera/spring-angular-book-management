import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { expand, reduce, EMPTY } from 'rxjs';

import { ButtonModule } from 'primeng/button';
import { PaginatorModule, PaginatorState } from 'primeng/paginator';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';

import { Book } from '../../models/book.model';
import { BookService } from '../../services/book.service';
import { AiSearchService } from '../../services/ai-search.service';
import { ChatUiService } from '../../services/chat-ui.service';
import { genreDot } from '../../services/book-visuals';
import { HeroShelf } from '../../components/hero-shelf/hero-shelf';
import { BookCard } from '../../components/book-card/book-card';
import { BookCover } from '../../components/book-cover/book-cover';

type SortKey = 'recent' | 'title' | 'author' | 'rating';
type ViewMode = 'grid' | 'table';

const VIEW_KEY = 'books_view';
const PAGE_SIZE_FETCH = 100;

@Component({
  selector: 'app-books-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HeroShelf, FormsModule, RouterLink, DatePipe, ButtonModule, PaginatorModule, SelectModule, TableModule, BookCard, BookCover],
  templateUrl: './books.html',
  styleUrl: './books.scss'
})
export class Books implements OnInit {
  private bookService = inject(BookService);
  private aiSearch = inject(AiSearchService);
  private chat = inject(ChatUiService);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);

  // ---- catalogue (loaded once, filtered/sorted/paged on the client: instant feedback) ----
  protected catalog = signal<Book[]>([]);
  protected loading = signal(true);
  protected loadFailed = signal(false);

  protected query = signal('');
  protected genre = signal<string | null>(null);
  protected sort = signal<SortKey>('recent');
  protected view = signal<ViewMode>(this.readView());
  protected first = signal(0);
  protected rows = signal(12);

  protected readonly sortOptions: { label: string; value: SortKey }[] = [
    { label: 'Newest first', value: 'recent' },
    { label: 'Title A–Z', value: 'title' },
    { label: 'Author A–Z', value: 'author' },
    { label: 'Top rated', value: 'rating' }
  ];

  protected genres = computed(() => {
    const counts = new Map<string, number>();
    for (const b of this.catalog()) {
      const g = b.genre?.trim();
      if (g) counts.set(g, (counts.get(g) ?? 0) + 1);
    }
    return [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([name, count]) => ({ name, count }));
  });

  protected filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    const g = this.genre();
    const list = this.catalog().filter(b =>
      (!g || b.genre === g) &&
      (!q || `${b.title} ${b.author} ${b.genre ?? ''}`.toLowerCase().includes(q)));
    const by: Record<SortKey, (a: Book, b: Book) => number> = {
      recent: (a, b) => new Date(b.createdDate ?? 0).getTime() - new Date(a.createdDate ?? 0).getTime(),
      title: (a, b) => a.title.localeCompare(b.title),
      author: (a, b) => a.author.localeCompare(b.author),
      rating: (a, b) => (b.averageRating || 0) - (a.averageRating || 0)
    };
    return [...list].sort(by[this.sort()]);
  });

  /** three books shown as a decorative stack in the hero: best rated first */
  protected shelf = computed(() =>
    [...this.catalog()].sort((a, b) => (b.averageRating || 0) - (a.averageRating || 0)).slice(0, 3));

  protected pageItems = computed(() => this.filtered().slice(this.first(), this.first() + this.rows()));
  protected hasFilters = computed(() => !!this.query().trim() || !!this.genre());

  // ---- "Ask your library" (semantic search) ----
  protected askQuery = signal('');
  protected askLoading = signal(false);
  protected askError = signal(false);
  protected askedFor = signal<string | null>(null);
  protected askResults = signal<Book[]>([]);
  protected readonly examples = [
    'A book about keeping business rules independent of frameworks',
    'Something to learn about space and the universe',
    'A gripping story with a mystery'
  ];

  ngOnInit(): void {
    this.loadCatalog();
  }

  protected loadCatalog(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.bookService.getAllBooks(0, PAGE_SIZE_FETCH, 'createdDate', -1).pipe(
      expand(res => (res.last ? EMPTY : this.bookService.getAllBooks(res.pageNumber + 1, PAGE_SIZE_FETCH, 'createdDate', -1))),
      reduce((all, res) => all.concat(res.content), [] as Book[]),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: books => {
        this.catalog.set(books);
        this.loading.set(false);
      },
      error: err => {
        console.error('Error loading books:', err);
        this.loadFailed.set(true);
        this.loading.set(false);
      }
    });
  }

  // ---- filters ----
  protected setQuery(value: string): void {
    this.query.set(value);
    this.first.set(0);
  }

  protected setGenre(name: string | null): void {
    this.genre.set(this.genre() === name ? null : name);
    this.first.set(0);
  }

  protected setSort(value: SortKey): void {
    this.sort.set(value);
    this.first.set(0);
  }

  protected clearFilters(): void {
    this.query.set('');
    this.genre.set(null);
    this.first.set(0);
  }

  protected setView(mode: ViewMode): void {
    this.view.set(mode);
    try { localStorage.setItem(VIEW_KEY, mode); } catch { /* storage unavailable: not persisted */ }
  }

  protected onPage(event: PaginatorState): void {
    this.first.set(event.first ?? 0);
    this.rows.set(event.rows ?? this.rows());
    document.getElementById('catalogue')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  // ---- ask ----
  protected ask(question = this.askQuery()): void {
    const q = question.trim();
    if (!q || this.askLoading()) return;
    this.askQuery.set(q);
    this.askLoading.set(true);
    this.askError.set(false);
    this.askedFor.set(q);
    this.aiSearch.search(q).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: results => {
        this.askResults.set(results ?? []);
        this.askLoading.set(false);
      },
      error: err => {
        console.error('Semantic search failed:', err);
        this.askResults.set([]);
        this.askError.set(true);
        this.askLoading.set(false);
      }
    });
  }

  protected closeAsk(): void {
    this.askedFor.set(null);
    this.askResults.set([]);
    this.askError.set(false);
  }

  protected askAssistant(): void {
    this.chat.open(this.askedFor() ?? this.askQuery());
  }

  protected dot(genre?: string | null): string {
    return genreDot(genre);
  }

  protected open(book: Book): void {
    this.router.navigate(['/books', book.id]);
  }

  protected skeletons = Array.from({ length: 8 }, (_, i) => i);

  private readView(): ViewMode {
    try { return localStorage.getItem(VIEW_KEY) === 'table' ? 'table' : 'grid'; } catch { return 'grid'; }
  }
}
