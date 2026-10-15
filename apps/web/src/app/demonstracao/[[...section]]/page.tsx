import { Workspace } from "@/components/workspace";
export default async function Demo({
  params,
}: {
  params: Promise<{ section?: string[] }>;
}) {
  const { section } = await params;
  return <Workspace orgId="demo" section={section?.[0] || "dashboard"} demo />;
}
