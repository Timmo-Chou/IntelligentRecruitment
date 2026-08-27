import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{js,ts,jsx,tsx,mdx}"],
  theme: {
    extend: {
      colors: {
        brand: "rgb(var(--color-brand) / <alpha-value>)",
        ai: "rgb(var(--color-ai) / <alpha-value>)",
        surface: "rgb(var(--color-surface) / <alpha-value>)",
      },
      boxShadow: {
        card: "0 4px 16px rgba(15, 23, 42, 0.04)",
      },
    },
  },
  plugins: [],
};

export default config;