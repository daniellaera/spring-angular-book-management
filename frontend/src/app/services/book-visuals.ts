import { Book } from '../models/book.model';

/**
 * Generated covers: books have no cover image in the API, so a cover is derived from the genre (hue) and the title (variation).
 * A real `book.image` always wins. Saturation/lightness live here, not in components.
 */
const GENRE_HUE: Record<string, number> = {
  technology: 212,
  programming: 262,
  fiction: 350,
  'science fiction': 188,
  cooking: 24,
  science: 160,
  business: 42,
  lifestyle: 96,
  history: 18,
  poetry: 316
};

function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

export interface CoverStyle {
  background: string;
  monogram: string;
  /** which decorative pattern to draw (0-3), stable per title */
  pattern: number;
}

export function genreHue(genre?: string | null, title = ''): number {
  const key = (genre ?? '').trim().toLowerCase();
  return GENRE_HUE[key] ?? hash(key || title) % 360;
}

export function coverStyle(book: Pick<Book, 'title' | 'genre'>, dark: boolean): CoverStyle {
  const hue = genreHue(book.genre, book.title);
  const h = hash(book.title);
  const shift = (h % 40) - 20; // slight per-title hue drift inside a genre
  const angle = 120 + (h % 90);
  const l1 = dark ? 34 : 40;
  const l2 = dark ? 16 : 22;
  const background =
    `linear-gradient(${angle}deg, hsl(${hue + shift} 62% ${l1}%) 0%, hsl(${hue + shift + 28} 58% ${l2}%) 100%)`;
  const words = book.title.replace(/^(the|a|an)\s+/i, '').split(/\s+/).filter(Boolean);
  const monogram = (words[0]?.[0] ?? '?') + (words.length > 1 ? words[1][0] : '');
  return { background, monogram: monogram.toUpperCase(), pattern: h % 4 };
}

/** Small coloured dot for a genre chip (information is never conveyed by colour alone: the chip also has the genre text). */
export function genreDot(genre?: string | null): string {
  return `hsl(${genreHue(genre)} 70% 60%)`;
}
