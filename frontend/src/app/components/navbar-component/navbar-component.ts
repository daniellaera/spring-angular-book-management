import { ChangeDetectionStrategy, Component, HostListener, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MenuModule } from 'primeng/menu';
import { MenuItem } from 'primeng/api';

import { AuthService } from '../../services/auth.service';
import { ThemeService } from '../../services/theme.service';
import { ChatUiService } from '../../services/chat-ui.service';

@Component({
  selector: 'app-navbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, MenuModule],
  templateUrl: './navbar-component.html',
  styleUrl: './navbar-component.scss'
})
export class NavbarComponent {
  protected auth = inject(AuthService);
  protected theme = inject(ThemeService);
  private chat = inject(ChatUiService);

  protected scrolled = signal(false);

  protected isLoggedIn = this.auth.isLoggedIn;
  protected timeLeft = this.auth.timeLeft;
  protected hasGitHub = computed(() => !!this.auth.userDetails().githubId);
  protected fullName = computed(() => {
    this.auth.userDetails(); // re-evaluate when the profile loads
    return this.auth.getFullName();
  });
  protected initials = computed(() =>
    this.fullName().split(/\s+/).filter(Boolean).slice(0, 2).map(p => p[0]).join('').toUpperCase() || 'G');

  protected timeLabel = computed(() => {
    const time = this.timeLeft();
    const hours = Math.floor(time / 3600);
    const minutes = Math.floor((time % 3600) / 60);
    const seconds = time % 60;
    return hours > 0
      ? `${hours}h ${minutes.toString().padStart(2, '0')}m`
      : `${minutes}:${seconds.toString().padStart(2, '0')}`;
  });
  protected sessionLow = computed(() => this.timeLeft() < 60);

  protected menuItems: MenuItem[] = [
    { label: 'Profile', icon: 'pi pi-user', routerLink: ['/profile'] },
    { separator: true },
    { label: 'Sign out', icon: 'pi pi-sign-out', command: () => this.auth.logout() }
  ];

  @HostListener('window:scroll')
  protected onScroll(): void {
    this.scrolled.set(window.scrollY > 4);
  }

  protected openAssistant(): void {
    this.chat.toggle();
  }
}
