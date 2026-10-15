import Link from "next/link";
import {
  ArrowUpRight,
  ArrowRight,
  Heart,
  ShieldCheck,
  Video,
  MoveUpRight,
} from "lucide-react";
import { Brand, Dandelion } from "@/components/brand";

export default function Home() {
  return (
    <div className="public-site">
      <header className="public-header">
        <Brand />
        <nav aria-label="Menu principal">
          <a href="#abordagem">O cuidado</a>
          <a href="#sobre">Sobre Paola</a>
          <Link href="/profissionais">Atendimento</Link>
          <Link className="button button-outline small" href="/login">
            Meu espaço <ArrowUpRight size={16} />
          </Link>
        </nav>
      </header>
      <main id="conteudo">
        <section className="public-hero">
          <div className="hero-copy">
            <span className="eyebrow">ACOLHER · COMPREENDER · CONSTRUIR</span>
            <h1>
              Um espaço para <em>ser.</em>
              <br />E se <em>conhecer.</em>
            </h1>
            <p>
              Entre o que você sente e a vida que deseja construir, existe um
              caminho. Você não precisa percorrê-lo sozinha.
            </p>
            <Link className="button" href="/profissionais">
              Encontre seu atendimento <ArrowUpRight size={18} />
            </Link>
            <div className="hero-footnote">
              <span className="line" />
              Psicologia clínica, com profundidade e leveza.
            </div>
          </div>
          <div className="hero-image">
            <div
              className="paola-photo"
              role="img"
              aria-label="Paola Gularte, psicóloga clínica"
            />
            <span className="photo-caption">
              EXISTÊNCIA. RELAÇÕES. ESCOLHAS.
            </span>
          </div>
          <Dandelion className="hero-dandelion" size={225} />
          <span className="hero-side-label">CADA HISTÓRIA TEM SEU TEMPO.</span>
        </section>
        <section className="care-intro" id="abordagem">
          <span className="eyebrow">O CUIDADO COMEÇA NA ESCUTA</span>
          <h2>
            Há sentido em olhar
            <br />
            com carinho <em>para si.</em>
          </h2>
          <p>
            Um encontro com a sua história, suas relações e suas possibilidades.
            Um lugar de escuta atenta, onde suas experiências encontram espaço.
          </p>
          <div className="care-pillars">
            <article>
              <span>01</span>
              <Heart strokeWidth={1} size={32} />
              <h3>Acolher sua história</h3>
              <p>
                Você pode chegar como está. Cada encontro respeita seu tempo e
                sua singularidade.
              </p>
            </article>
            <article>
              <span>02</span>
              <Dandelion size={37} />
              <h3>Compreender seus caminhos</h3>
              <p>
                Um olhar cuidadoso para o que se repete, o que transforma e o
                que faz sentido.
              </p>
            </article>
            <article>
              <span>03</span>
              <MoveUpRight strokeWidth={1} size={32} />
              <h3>Construir possibilidades</h3>
              <p>
                Escolhas mais conscientes e um espaço para os movimentos que a
                vida pede.
              </p>
            </article>
          </div>
        </section>
        <section className="about-section" id="sobre">
          <div className="about-art">
            <Dandelion size={220} />
            <span>PG</span>
            <p>
              Existência.
              <br />
              Relações.
              <br />
              Escolhas.
              <br />
              <em>Vida com sentido.</em>
            </p>
          </div>
          <div>
            <span className="eyebrow">PAOLA GULARTE · PSICÓLOGA CLÍNICA</span>
            <h2>
              Cuidado que abre espaço
              <br />
              para <em>possibilidades.</em>
            </h2>
            <p>
              A psicoterapia é um encontro de escuta e construção. Um espaço
              para compreender o que você vive, acolher suas questões e
              encontrar caminhos que façam sentido para você.
            </p>
            <p>
              Conhecer-se é também reconhecer seus limites, escolhas e desejos.
              Cada processo é único e construído em conjunto.
            </p>
            <Link className="text-link" href="/profissionais">
              Consultar perfis e disponibilidade <ArrowRight size={17} />
            </Link>
          </div>
        </section>
        <section className="private-cta">
          <div>
            <span className="eyebrow">SEU ESPAÇO DE CUIDADO, TAMBÉM AQUI</span>
            <h2>
              Mais tranquilidade.
              <br />
              <em>Mais presença.</em>
            </h2>
            <p>
              Agenda, documentos e materiais em um portal privado, organizado
              para acompanhar o seu processo.
            </p>
            <Link className="button button-light" href="/login">
              Acessar meu espaço <ArrowUpRight size={18} />
            </Link>
          </div>
          <div className="private-benefits">
            <p>
              <ShieldCheck size={23} /> Privacidade em cada etapa
            </p>
            <p>
              <Video size={23} /> Encontros online com acesso protegido
            </p>
            <p>
              <Heart size={23} /> Um cuidado que respeita seu tempo
            </p>
          </div>
          <Dandelion size={210} />
        </section>
      </main>
      <footer className="public-footer">
        <Brand />
        <p>Um espaço seguro para pensar a vida com profundidade e leveza.</p>
        <div>
          <Link href="/privacidade">Privacidade</Link>
          <Link href="/demonstracao">Conhecer a plataforma</Link>
          <span>© {new Date().getFullYear()} Conhecer-se</span>
        </div>
      </footer>
    </div>
  );
}
