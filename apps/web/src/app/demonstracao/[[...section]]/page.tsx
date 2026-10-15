import { Workspace } from "@/components/workspace";
import { demoAppointments, demoInvoices } from "@/lib/demo";
export default async function Demo({
  params,
}: {
  params: Promise<{ section?: string[] }>;
}) {
  const { section } = await params;
  return (
    <Workspace
      orgId="demo"
      section={section?.[0] || "dashboard"}
      demo
      seededDemoAppointments={demoAppointments()}
      seededDemoInvoices={demoInvoices}
    />
  );
}
