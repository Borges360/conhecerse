import { PaymentPage } from "@/components/payment";
import { session } from "@/lib/session";
import { redirect } from "next/navigation";
export const dynamic = "force-dynamic";
export default async function Payment({
  params,
}: {
  params: Promise<{ paymentId: string }>;
}) {
  if (!(await session())) redirect("/login");
  const { paymentId } = await params;
  return <PaymentPage paymentId={paymentId} />;
}
