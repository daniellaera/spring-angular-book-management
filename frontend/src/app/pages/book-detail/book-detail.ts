import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DatePickerModule } from 'primeng/datepicker';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ToastModule } from 'primeng/toast';
import { DialogModule } from 'primeng/dialog';

import { Book } from '../../models/book.model';
import { BookService } from '../../services/book.service';
import { AuthService } from '../../services/auth.service';
import { ChatUiService } from '../../services/chat-ui.service';
import { genreDot } from '../../services/book-visuals';
import { BookCover } from '../../components/book-cover/book-cover';
import { CommentForm } from '../../components/comment-form/comment-form';
import { RatingForm } from '../../components/rating-form/rating-form';

@Component({
  selector: 'app-book-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe, FormsModule, RouterLink,
    ButtonModule, DatePickerModule, ConfirmDialogModule, ToastModule, DialogModule,
    BookCover, CommentForm, RatingForm
  ],
  templateUrl: './book-detail.html',
  styleUrl: './book-detail.scss'
})
export class BookDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private bookService = inject(BookService);
  protected authService = inject(AuthService);
  private confirmationService = inject(ConfirmationService);
  private messageService = inject(MessageService);
  private chat = inject(ChatUiService);

  book = signal<Book | null>(null);
  loading = signal(true);
  loadFailed = signal(false);
  showBorrowDialog = signal(false);
  selectedBorrowDates = signal<Date[] | null>(null);
  minDate = new Date(); // Can't borrow in the past
  maxDate = new Date(new Date().setMonth(new Date().getMonth() + 3)); // Max 3 months

  isOwner = computed(() => {
    const currentBook = this.book();
    const currentUserId = this.authService.userDetails().userId;
    return currentBook?.userDTO?.id === currentUserId;
  });

  isAvailable = computed(() => this.book()?.isAvailable ?? false);

  hasBorrow = computed(() => {
    const currentBook = this.book();
    return !!(currentBook?.borrow && !currentBook.borrow.isReturned);
  });

  protected isBorrower = computed(() => {
    const currentBook = this.book();
    const currentUserId = this.authService.userDetails().userId;
    return !!(currentBook?.borrow
      && !currentBook.borrow.isReturned
      && currentBook.borrow.userId === currentUserId);
  });

  protected stars = computed(() => {
    const rounded = Math.round(this.book()?.averageRating ?? 0);
    return [1, 2, 3, 4, 5].map(n => n <= rounded);
  });
  protected dot = computed(() => genreDot(this.book()?.genre));

  ngOnInit(): void {
    const bookId = this.route.snapshot.params['id'];
    if (bookId) {
      this.loadBookDetails(+bookId);
    }
  }

  openBorrowDialog(): void {
    // Check if logged in first
    if (!this.authService.isLoggedIn()) {
      this.messageService.add({
        severity: 'warn',
        summary: 'Login Required',
        detail: 'Please log in to borrow books'
      });
      this.router.navigate(['/login']);
      return;
    }

    this.showBorrowDialog.set(true);
  }

  confirmBorrow(): void {
    const dates = this.selectedBorrowDates();
    const currentBook = this.book();

    if (!dates || dates.length !== 2 || !currentBook) {
      this.messageService.add({
        severity: 'warn',
        summary: 'Invalid Dates',
        detail: 'Please select a valid date range'
      });
      return;
    }

    this.bookService.borrowBook(currentBook.id, dates[0], dates[1]).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: 'Success',
          detail: 'Book borrowed successfully!'
        });
        this.showBorrowDialog.set(false);
        this.loadBookDetails(currentBook.id); // Reload book data
      },
      error: (error) => {
        console.error('Error borrowing book:', error);
        this.messageService.add({
          severity: 'error',
          summary: 'Error',
          detail: 'Failed to borrow book'
        });
      }
    });
  }

  loadBookDetails(bookId: number): void {
    this.loading.set(true);
    this.loadFailed.set(false);

    this.bookService.getBookById(bookId).subscribe({
      next: (book) => {
        this.book.set(book);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Error loading book:', error);
        this.messageService.add({
          severity: 'error',
          summary: 'Error',
          detail: 'Failed to load book details'
        });
        this.loadFailed.set(true);
        this.loading.set(false);
      }
    });
  }

  confirmDelete(): void {
    this.confirmationService.confirm({
      message: 'Do you want to delete this book? This cannot be undone.',
      header: 'Delete this book?',
      icon: 'pi pi-trash',
      acceptLabel: 'Delete',
      rejectLabel: 'Keep it',
      acceptButtonProps: { severity: 'danger' },
      rejectButtonProps: { severity: 'secondary', outlined: true },
      accept: () => {
        this.deleteBook();
      }
    });
  }

  deleteBook(): void {
    const currentBook = this.book();
    if (!currentBook?.id) return;

    this.bookService.deleteBook(currentBook.id).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: 'Success',
          detail: 'Book deleted successfully'
        });
        setTimeout(() => this.router.navigate(['/']), 1500);
      },
      error: (error) => {
        console.error('Error deleting book:', error);
        this.messageService.add({
          severity: 'error',
          summary: 'Error',
          detail: 'Failed to delete book'
        });
      }
    });
  }

  askAssistant(): void {
    const b = this.book();
    if (b) this.chat.open(`Tell me about "${b.title}" by ${b.author}`);
  }

  protected initials(name: string | undefined): string {
    return (name ?? '?').split(/\s+/).filter(Boolean).slice(0, 2).map(p => p[0]).join('').toUpperCase();
  }

  protected reloadBook(): void {
    const currentBookId = this.book()?.id;
    if (currentBookId) {
      this.loadBookDetails(currentBookId);
    }
  }

  protected confirmReturn(): void {
    this.confirmationService.confirm({
      message: 'Do you want to return this book?',
      header: 'Return this book?',
      icon: 'pi pi-undo',
      acceptLabel: 'Return',
      rejectLabel: 'Cancel',
      rejectButtonProps: { severity: 'secondary', outlined: true },
      accept: () => {
        this.returnBook();
      }
    });
  }

  private returnBook(): void {
    const currentBook = this.book();
    if (!currentBook?.id) return;

    this.bookService.returnBook(currentBook.id).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: 'Success',
          detail: 'Book returned successfully!'
        });
        this.loadBookDetails(currentBook.id);
      },
      error: (error) => {
        console.error('Error returning book:', error);
        this.messageService.add({
          severity: 'error',
          summary: 'Error',
          detail: 'Failed to return book'
        });
      }
    });
  }
}
