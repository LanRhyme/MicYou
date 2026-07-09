import type { VariantProps } from "class-variance-authority"
import { cva } from "class-variance-authority"

export { default as Button } from "./Button.vue"

export const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full text-sm font-medium ring-offset-background transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] active:scale-[0.96] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary/90 hover:shadow-md hover:-translate-y-0.5",
        destructive:
          "bg-destructive text-destructive-foreground hover:bg-destructive/90 hover:shadow-md hover:-translate-y-0.5",
        outline:
          "border-2 border-input bg-transparent hover:bg-accent hover:text-accent-foreground hover:border-accent",
        secondary:
          "bg-secondary/80 text-secondary-foreground hover:bg-secondary hover:shadow-sm",
        ghost: "hover:bg-accent hover:text-accent-foreground active:bg-accent/80",
        link: "text-primary underline-offset-4 hover:underline",
        elevated: "bg-background text-foreground shadow-sm hover:shadow-md hover:-translate-y-0.5 transition-all",
        tonal: "bg-secondary text-secondary-foreground hover:bg-secondary/80 hover:shadow-sm"
      },
      size: {
        "default": "h-10 px-6 py-2",
        "sm": "h-9 px-4 text-xs",
        "lg": "h-12 px-8 text-base",
        "icon": "h-12 w-12",
        "icon-sm": "size-10",
        "icon-lg": "size-14",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
)

export type ButtonVariants = VariantProps<typeof buttonVariants>
