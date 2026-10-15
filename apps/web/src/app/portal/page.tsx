import { redirect } from "next/navigation";
import { session } from "@/lib/session";
import { Portal } from "@/components/portal";
export const dynamic = "force-dynamic";
export default async function PatientPortal() {
  if (!(await session())) redirect("/login?next=/portal");
  return <Portal />;
}
