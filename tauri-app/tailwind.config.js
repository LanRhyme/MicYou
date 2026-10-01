const animate = require("tailwindcss-animate")

/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: ["class"],
  safelist: ["dark"],
  prefix: "",
  
  content: ['./index.html', './src/**/*.{ts,vue}'],
  
  theme: {
    extend: {
      colors: {
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
        "on-primary": "hsl(var(--on-primary))",
        "on-secondary": "hsl(var(--on-secondary))",
        error: "hsl(var(--error))",
        "on-error": "hsl(var(--on-error))",
        "primary-container": "hsl(var(--primary-container))",
        "on-primary-container": "hsl(var(--on-primary-container))",
        "secondary-container": "hsl(var(--secondary-container))",
        "on-secondary-container": "hsl(var(--on-secondary-container))",
        tertiary: "hsl(var(--tertiary))",
        "on-tertiary": "hsl(var(--on-tertiary))",
        surface: "hsl(var(--surface))",
        "on-surface": "hsl(var(--on-surface))",
        "surface-variant": "hsl(var(--surface-variant))",
        "on-surface-variant": "hsl(var(--on-surface-variant))",
        "surface-container": "hsl(var(--surface-container))",
        "surface-container-low": "hsl(var(--surface-container-low))",
        "surface-bright": "hsl(var(--surface-bright))",
      },
      borderRadius: {
        xl: "calc(var(--radius) + 4px)",
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
    },
  },
  plugins: [animate],
}
