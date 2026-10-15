import { redirect } from "next/navigation";
import { session } from "@/lib/session";
import { Workspace } from "@/components/workspace";
export const dynamic = "force-dynamic";
export default async function Clinic({
  params,
}: {
  params: Promise<{ orgId: string; section?: string[] }>;
}) {
  const { orgId, section } = await params;
  const current = await session();
  if (!current) redirect("/login");
  return (
    <Workspace
      orgId={orgId}
      section={section?.[0] || "dashboard"}
      name={current.tokens.name}
      patientId={section?.[1]}
    />
  );
}
