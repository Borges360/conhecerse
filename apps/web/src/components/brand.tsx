import Link from "next/link";
export function Dandelion({
  className = "",
  size = 100,
}: {
  className?: string;
  size?: number;
}) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 100 120"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M14 112C47 98 52 69 53 43"
        stroke="currentColor"
        strokeWidth=".8"
      />
      {Array.from({ length: 17 }, (_, i) => {
        const angle = ((-172 + i * 10) * Math.PI) / 180;
        const x = 53 + Math.cos(angle) * 29,
          y = 43 + Math.sin(angle) * 34;
        return (
          <g key={i} stroke="currentColor" strokeWidth=".55">
            <path d={`M53 43L${x} ${y}`} />
            <path
              d={`M${x - 3} ${y - 3}L${x} ${y}L${x + 3} ${y - 3}M${x} ${y - 5}L${x} ${y}`}
            />
          </g>
        );
      })}
      <g stroke="currentColor" strokeWidth=".55">
        <path d="M75 19L89 9M86 6L89 9L93 8M90 4L89 9M81 40L95 33M92 30L95 33L99 32M95 28L95 33M68 8L74 1" />
      </g>
    </svg>
  );
}
export function Brand({
  compact = false,
  dark = false,
}: {
  compact?: boolean;
  dark?: boolean;
}) {
  return (
    <Link
      href="/"
      className={`brand ${dark ? "brand-light" : ""}`}
      aria-label="Paola Gularte, início"
    >
      <span className="monogram">
        P<span>G</span>
      </span>
      {!compact && (
        <span className="brand-wordmark">
          PAOLA GULARTE<small>PSICÓLOGA CLÍNICA</small>
        </span>
      )}
      <Dandelion size={40} />
    </Link>
  );
}
