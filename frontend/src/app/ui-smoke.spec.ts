import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ThemeService } from './services/theme.service';
import { BookCover } from './components/book-cover/book-cover';
import { BookCard } from './components/book-card/book-card';
import { NavbarComponent } from './components/navbar-component/navbar-component';
import { Books } from './pages/books/books';
import { Book } from './models/book.model';

const book = (over: Partial<Book> = {}): Book => ({
  id: 1, title: 'Clean Architecture', author: 'Robert C. Martin', isbn: '978-0134494166',
  genre: 'Programming', isAvailable: true, averageRating: 4, ...over
});

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('my-app-dark');
  });

  it('is dark by default and applies the class', () => {
    const theme = TestBed.inject(ThemeService);
    expect(theme.isDark()).toBe(true);
    expect(document.documentElement.classList.contains('my-app-dark')).toBe(true);
  });

  it('toggles, persists the choice and updates the root class', () => {
    const theme = TestBed.inject(ThemeService);
    theme.toggle();
    expect(theme.isDark()).toBe(false);
    expect(localStorage.getItem('theme')).toBe('light');
    expect(document.documentElement.classList.contains('my-app-dark')).toBe(false);
    theme.toggle();
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('restores a stored light preference', () => {
    localStorage.setItem('theme', 'light');
    expect(TestBed.inject(ThemeService).isDark()).toBe(false);
  });
});

describe('BookCover / BookCard', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  it('renders a generated cover with a monogram when there is no image', () => {
    const f = TestBed.createComponent(BookCover);
    f.componentRef.setInput('book', book());
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('CA');
  });

  it('renders the card with title link, genre and availability text (not colour only)', () => {
    const f = TestBed.createComponent(BookCard);
    f.componentRef.setInput('book', book());
    f.detectChanges();
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelector('a')?.getAttribute('href')).toBe('/books/1');
    expect(el.textContent).toContain('Programming');
    expect(el.textContent).toContain('Available');
  });

  it('shows Borrowed for unavailable books', () => {
    const f = TestBed.createComponent(BookCard);
    f.componentRef.setInput('book', book({ isAvailable: false }));
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Borrowed');
  });
});

describe('Navbar theme toggle', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [NavbarComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
  });

  it('flips aria-pressed and the theme when clicked', () => {
    const f = TestBed.createComponent(NavbarComponent);
    f.detectChanges();
    const btn: HTMLButtonElement = f.nativeElement.querySelector('button[aria-pressed]');
    expect(btn.getAttribute('aria-pressed')).toBe('true');
    btn.click();
    f.detectChanges();
    expect(btn.getAttribute('aria-pressed')).toBe('false');
    expect(TestBed.inject(ThemeService).isDark()).toBe(false);
  });
});

describe('Books page', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [Books],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
  });

  it('shows skeletons, then the catalogue grid after loading', () => {
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(Books);
    f.detectChanges();
    expect(f.nativeElement.querySelector('.skeleton')).toBeTruthy();

    const req = http.expectOne(r => r.url.endsWith('/book'));
    req.flush({ content: [book(), book({ id: 2, title: 'Clean Code' })], totalPages: 1, totalElements: 2, number: 0, size: 100, last: true });
    f.detectChanges();
    expect(f.nativeElement.querySelectorAll('app-book-card').length).toBe(2);
    expect(f.nativeElement.textContent).toContain('Clean Code');
  });

  it('shows an error state with retry when loading fails', () => {
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(Books);
    f.detectChanges();
    http.expectOne(r => r.url.endsWith('/book')).flush('x', { status: 500, statusText: 'err' });
    f.detectChanges();
    expect(f.nativeElement.querySelector('[role="alert"], .state')).toBeTruthy();
  });
});
