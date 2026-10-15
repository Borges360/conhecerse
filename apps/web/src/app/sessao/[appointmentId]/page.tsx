import { session } from "@/lib/session";
import { redirect } from "next/navigation";
import { VideoRoom } from "@/components/video-room";
export const dynamic = "force-dynamic";
export default async function Session({
  params,
  searchParams,
}: {
  params: Promise<{ appointmentId: string }>;
  searchParams: Promise<{ org?: string }>;
}) {
  const { appointmentId } = await params,
    { org } = await searchParams;
  if (!(await session())) redirect("/login");
  if (!org) redirect("/portal");
  return <VideoRoom orgId={org} appointmentId={appointmentId} />;
}
