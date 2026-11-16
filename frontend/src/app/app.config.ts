import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';

import { providePrimeNG } from 'primeng/config';
import { AppPreset } from '../theme/app-preset';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { ConfirmationService, MessageService } from 'primeng/api';
import { environment } from '../environments/environment';
import { authInterceptor } from './interceptors/auth-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withXhr(), withInterceptors([authInterceptor])),
    providePrimeNG({
      license: environment.primeuiLicense || undefined,
      theme: {
        preset: AppPreset,
        options: {
          darkModeSelector: '.my-app-dark'
        }
      }
    }),
    ConfirmationService,
    MessageService
  ]
};
