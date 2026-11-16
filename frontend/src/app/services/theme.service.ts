import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'theme';
const DARK_CLASS = 'my-app-dark';

/** Dark-first theme switch. The choice is persisted in localStorage ('dark' | 'light'); index.html applies it before first paint. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly isDark = signal(this.read() !== 'light');

  constructor() {
    this.apply(this.isDark());
  }

  toggle(): void {
    this.set(!this.isDark());
  }

  set(dark: boolean): void {
    this.isDark.set(dark);
    this.apply(dark);
    try {
      localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light');
    } catch {
      /* private mode / blocked storage: the choice just is not persisted */
    }
  }

  private apply(dark: boolean): void {
    document.documentElement.classList.toggle(DARK_CLASS, dark);
  }

  private read(): string | null {
    try {
      return localStorage.getItem(STORAGE_KEY);
    } catch {
      return null;
    }
  }
}
