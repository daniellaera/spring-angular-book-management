import { Component, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';

import { GithubService } from '../../services/github.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-github-callback',
  imports: [],
  templateUrl: './github-callback.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './github-callback.scss'
})
export class GithubCallback implements OnInit {
  constructor(
    private githubService: GithubService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {

    const urlParams = new URLSearchParams(window.location.search);
    const code = urlParams.get('code');

    if (!code) {
      console.error('❌ No authorization code found in URL');
      this.router.navigate(['/login']);
      return;
    }
    this.githubService.handleGithubCallback(code).subscribe({
      next: (response) => {
        const token = response.token;

        if (!token) {
          console.error('❌ Token is missing in the response');
          this.router.navigate(['/login']);
          return;
        }
        localStorage.setItem('auth_token', token);
        localStorage.setItem('username', response.username);
        this.authService.checkSession();
        this.router.navigate(['/']);
      },
      error: (error) => {
        console.error('❌ GitHub OAuth processing failed:', error);
        alert('Login failed. Please try again.');
        this.router.navigate(['/login']);
      }
    });
  }
}
