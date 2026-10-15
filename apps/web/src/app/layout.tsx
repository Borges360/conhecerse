import type { Metadata } from "next";
import { headers } from "next/headers";
import "@fontsource/playfair-display/latin-400.css";
import "@fontsource/playfair-display/latin-400-italic.css";
import "@fontsource/montserrat/latin-400.css";
import "@fontsource/montserrat/latin-500.css";
import "@fontsource/montserrat/latin-600.css";
import "./globals.css";
export const metadata: Metadata = {
  title: {
    default: "Conhecer-se · Paola Gularte",
    template: "%s · Conhecer-se",
  },
  description:
    "Um espaço de acolhimento, escolhas e vida com sentido. Psicologia clínica e cuidado com privacidade.",
  robots: { index: false, follow: false },
};
export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  await headers();
  return (
    <html lang="pt-BR">
      <body>
        <a className="skip-link" href="#conteudo">
          Pular para o conteúdo
        </a>
        {children}
      </body>
    </html>
  );
}
