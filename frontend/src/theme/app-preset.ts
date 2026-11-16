/**
 * DESIGN TOKENS: the single place that defines the look of the app.
 *
 *  - Accent colour ........ ACCENT (amber) and its contrast text colour
 *  - Secondary colour ..... AI (teal), used only for the AI features
 *  - Neutral surfaces ..... SURFACE scale + the `app` tokens per colour scheme
 *  - Radius ............... RADIUS
 *  - Fonts ................ FONT (also swap the two @fontsource imports in src/styles.scss)
 *
 * PrimeNG turns this preset into CSS variables on :root (`--p-*`). Everything under `semantic.app`
 * becomes `--p-app-*` and is the only thing component styles may use for colour, e.g. `var(--p-app-card)`.
 * Dark mode = class `my-app-dark` on <html> (see ThemeService).
 */
import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/** Accent: change this scale to re-colour buttons, links, focus rings, highlights. */
const ACCENT = {
  50: '#fffbeb', 100: '#fef3c7', 200: '#fde68a', 300: '#fcd34d', 400: '#fbbf24',
  500: '#f59e0b', 600: '#d97706', 700: '#b45309', 800: '#92400e', 900: '#78350f', 950: '#451a03'
};
/** Text colour used ON the accent colour (dark ink: 8:1+ contrast on amber in both themes). */
const ON_ACCENT = '#1b1407';

/** Secondary: used for the AI features ("Ask your library", the assistant). */
const AI = { dark: '#2dd4bf', darkSoft: 'rgba(45, 212, 191, 0.12)', light: '#0d6b63', lightSoft: 'rgba(15, 118, 110, 0.09)' };

/** Warm-neutral surfaces. 0-300 light mode, 700-950 dark mode. */
const SURFACE = {
  0: '#ffffff', 50: '#f7f5f0', 100: '#efece4', 200: '#e1ddd2', 300: '#cbc6b9', 400: '#8c877b',
  500: '#75716a', 600: '#5a5751', 700: '#3a3d47', 800: '#262a33', 900: '#171a20', 950: '#0e1014'
};

export const RADIUS = { xs: '4px', sm: '8px', md: '12px', lg: '16px', xl: '24px' };

export const FONT = {
  display: "'Fraunces Variable', 'Iowan Old Style', Georgia, serif",
  sans: "'Inter Variable', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
};

export const AppPreset = definePreset(Aura, {
  primitive: {
    borderRadius: { none: '0', xs: RADIUS.xs, sm: RADIUS.sm, md: RADIUS.sm, lg: RADIUS.md, xl: RADIUS.lg }
  },
  semantic: {
    primary: ACCENT,
    focusRing: { width: '2px', style: 'solid', offset: '2px', color: '{app.focus}' },
    colorScheme: {
      light: {
        surface: SURFACE,
        primary: { color: '{primary.500}', contrastColor: ON_ACCENT, hoverColor: '{primary.400}', activeColor: '{primary.300}' },
        highlight: { background: '{primary.100}', focusBackground: '{primary.200}', color: '{primary.900}', focusColor: '{primary.900}' },
        text: { color: '#1c1a17', hoverColor: '#000000', mutedColor: '#5c574f', hoverMutedColor: '#3f3b35' },
        content: { background: '#ffffff', hoverBackground: '{surface.100}', borderColor: '{surface.200}', color: '#1c1a17', hoverColor: '#000000' },
        formField: { background: '#ffffff', disabledBackground: '{surface.100}', filledBackground: '{surface.50}', borderColor: '{surface.400}', hoverBorderColor: '{surface.500}', color: '#1c1a17', placeholderColor: '#6b665d' },
      },
      dark: {
        surface: SURFACE,
        primary: { color: '{primary.400}', contrastColor: ON_ACCENT, hoverColor: '{primary.300}', activeColor: '{primary.200}' },
        highlight: { background: 'rgba(251, 191, 36, 0.16)', focusBackground: 'rgba(251, 191, 36, 0.24)', color: '{primary.200}', focusColor: '{primary.100}' },
        text: { color: '#ece8e0', hoverColor: '#ffffff', mutedColor: '#a7a39a', hoverMutedColor: '#c4c0b6' },
        content: { background: '{surface.900}', hoverBackground: '{surface.800}', borderColor: '{surface.800}', color: '#ece8e0', hoverColor: '#ffffff' },
        formField: { background: '{surface.950}', disabledBackground: '{surface.800}', filledBackground: '{surface.800}', borderColor: '#6b6f7b', hoverBorderColor: '#8b8f9b', color: '#ece8e0', placeholderColor: '#9a968d' },
      }
    }
  },
  /* Custom tokens (not part of Aura). Become --p-app-*; component styles use only these for colour. */
  extend: {
    app: {
      radiusSm: RADIUS.sm, radiusMd: RADIUS.md, radiusLg: RADIUS.lg, radiusXl: RADIUS.xl,
      fontDisplay: FONT.display, fontSans: FONT.sans
    },
    colorScheme: {
      light: {
        app: {
          bg: '{surface.50}', card: '#ffffff', elevated: '#ffffff', sunken: '{surface.100}',
          border: '{surface.200}', borderStrong: '{surface.300}',
          text: '#1c1a17', muted: '#5c574f',
          focus: '{primary.700}', accentText: '{primary.800}', accentSoft: 'rgba(217, 119, 6, 0.10)',
          ai: AI.light, aiSoft: AI.lightSoft,
          shadowSm: '0 1px 2px rgba(28, 26, 23, 0.06), 0 1px 1px rgba(28, 26, 23, 0.04)',
          shadowLg: '0 12px 32px -8px rgba(28, 26, 23, 0.18), 0 2px 6px rgba(28, 26, 23, 0.06)',
          glass: 'rgba(247, 245, 240, 0.82)', scrim: 'rgba(28, 26, 23, 0.45)'
        }
      },
      dark: {
        app: {
          bg: '{surface.950}', card: '{surface.900}', elevated: '{surface.800}', sunken: '#0a0b0e',
          border: '{surface.800}', borderStrong: '{surface.700}',
          text: '#ece8e0', muted: '#a7a39a',
          focus: '{primary.400}', accentText: '{primary.400}', accentSoft: 'rgba(251, 191, 36, 0.12)',
          ai: AI.dark, aiSoft: AI.darkSoft,
          shadowSm: '0 1px 2px rgba(0, 0, 0, 0.5)',
          shadowLg: '0 18px 40px -12px rgba(0, 0, 0, 0.75), 0 2px 8px rgba(0, 0, 0, 0.5)',
          glass: 'rgba(14, 16, 20, 0.78)', scrim: 'rgba(0, 0, 0, 0.62)'
        }
      }
    }
  },
  components: {
    button: {
      root: { borderRadius: RADIUS.sm, paddingX: '1.1rem', paddingY: '0.6rem', label: { fontWeight: '600' } },
      // AA contrast fixes found by axe: dark ink on the orange "warn" fill, readable outlined variants in light mode
      colorScheme: {
        light: {
          root: { warn: { background: '{orange.400}', hoverBackground: '{orange.300}', activeBackground: '{orange.300}', borderColor: '{orange.400}', hoverBorderColor: '{orange.300}', activeBorderColor: '{orange.300}', color: '#1b1407', hoverColor: '#1b1407', activeColor: '#1b1407' } },
          outlined: { primary: { color: '{app.accentText}' }, secondary: { color: '{app.text}' } }
        },
        dark: {
          root: { warn: { background: '{orange.400}', hoverBackground: '{orange.300}', activeBackground: '{orange.300}', borderColor: '{orange.400}', hoverBorderColor: '{orange.300}', activeBorderColor: '{orange.300}', color: '#1b1407', hoverColor: '#1b1407', activeColor: '#1b1407' } },
          outlined: { primary: { color: '{app.accentText}' }, secondary: { color: '{app.text}' } }
        }
      }
    },
    dialog: { root: { borderRadius: RADIUS.lg } },
    paginator: { root: { background: 'transparent' } },
    tag: { root: { fontWeight: '600', fontSize: '0.75rem' } }
  }
});
