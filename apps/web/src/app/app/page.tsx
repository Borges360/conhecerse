import { session } from "@/lib/session";
import { redirect } from "next/navigation";
import { Onboarding } from "@/components/onboarding";
export const dynamic = "force-dynamic";
export default async function App() {
  if (!(await session())) redirect("/login");
  return <Onboarding />;
}
