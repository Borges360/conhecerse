import { Brand } from "@/components/brand";
import { NewsletterForm } from "@/components/newsletter-form";
import { Empty } from "@/components/ui";
import { ReadingCounter } from "@/components/reading-counter";
import type { Row } from "@/components/workspace";
export async function generateMetadata({
  params,
}: {
  params: Promise<{ orgSlug: string }>;
}) {
  const { orgSlug } = await params;
  return {
    title: "Reflexões · Conhecer-se",
    alternates: { canonical: "/blog/" + encodeURIComponent(orgSlug) },
  };
}
export default async function Blog({
  params,
}: {
  params: Promise<{ orgSlug: string }>;
}) {
  const { orgSlug } = await params;
  let items: Row[] = [];
  let unavailable = false;
  if (/^[a-z0-9-]{3,40}$/.test(orgSlug))
    try {
      const response = await fetch(
        (process.env.API_BASE_URL || "http://127.0.0.1:8080") +
          "/v1/public/blog/" +
          orgSlug +
          "?limit=100",
        { cache: "no-store", signal: AbortSignal.timeout(5000) },
      );
      if (response.ok) items = (await response.json()).items;
      else unavailable = true;
    } catch {
      unavailable = true;
    }
  else unavailable = true;
  return (
    <main className="directory-page" id="conteudo">
      <div className="portal-top">
        <Brand />
        <a href="/profissionais" className="text-link">
          Encontre um espaço de cuidado
        </a>
      </div>
      <span className="eyebrow">REFLEXÕES PARA O SEU CAMINHO</span>
      <h1>
        Uma pausa.
        <br />
        <em>Um novo olhar.</em>
      </h1>
      <p>
        Conteúdos públicos preparados e revisados pelo consultório. Cada cuidado
        tem seu tempo e sua história.
      </p>
      {items.length ? (
        <div className="document-grid">
          {items.map((c) => (
            <article className="document-card" key={c.id} id={c.slug || c.id}>
              <ReadingCounter orgSlug={orgSlug} contentId={c.id} />
              <h2>{c.title}</h2>
              <div
                className="rich-content"
                dangerouslySetInnerHTML={{ __html: c.bodySanitizedHtml || "" }}
              />
            </article>
          ))}
        </div>
      ) : (
        <Empty
          title={
            unavailable
              ? "Conteúdos indisponíveis"
              : "Novas reflexões estão a caminho"
          }
          description="Os conteúdos públicos do consultório aparecerão neste espaço após revisão e publicação."
        />
      )}
      {!unavailable && (
        <section className="panel settings-panel">
          <h2>Um convite para refletir</h2>
          <NewsletterForm orgSlug={orgSlug} />
        </section>
      )}
    </main>
  );
}
