"use client";
import { useEffect, useRef } from "react";
export function ReadingCounter({
  orgSlug,
  contentId,
}: {
  orgSlug: string;
  contentId: string;
}) {
  const marker = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    let sent = false;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting) && !sent) {
        timer = setTimeout(() => {
          sent = true;
          void fetch(
            "/api/bff/v1/public/blog/" +
              encodeURIComponent(orgSlug) +
              "/" +
              contentId +
              "/read",
            {
              method: "POST",
              credentials: "omit",
              headers: { "Content-Type": "application/json" },
              body: "{}",
              cache: "no-store",
            },
          );
        }, 5000);
      } else if (timer) clearTimeout(timer);
    });
    if (marker.current) observer.observe(marker.current);
    return () => {
      observer.disconnect();
      if (timer) clearTimeout(timer);
    };
  }, [orgSlug, contentId]);
  return <span ref={marker} aria-hidden="true" />;
}
