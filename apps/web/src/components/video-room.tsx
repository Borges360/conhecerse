"use client";
import { useEffect, useRef, useState } from "react";
import {
  ExternalE2EEKeyProvider,
  Room,
  isE2EESupported,
  RoomEvent,
} from "livekit-client";
import { LiveKitRoom, VideoConference } from "@livekit/components-react";
import "@livekit/components-styles";
import {
  Video,
  Mic,
  MicOff,
  VideoOff,
  ShieldCheck,
  ArrowLeft,
} from "lucide-react";
import Link from "next/link";
import { Brand } from "./brand";
import { ErrorNotice, Notice } from "./ui";
import {
  request,
  type VideoAccess,
  type RemoteContingency,
} from "@/lib/api.generated";
export function VideoRoom({
  orgId,
  appointmentId,
}: {
  orgId: string;
  appointmentId: string;
}) {
  const preview = useRef<HTMLVideoElement>(null),
    stream = useRef<MediaStream | null>(null),
    roomRef = useRef<Room | null>(null);
  const [audio, setAudio] = useState(true),
    [video, setVideo] = useState(true),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [room, setRoom] = useState<Room | null>(null),
    [connection, setConnection] = useState("Preparação"),
    [ended, setEnded] = useState(false);
  const [contingency, setContingency] = useState<RemoteContingency | null>(
    null,
  );
  useEffect(() => {
    request<RemoteContingency>(
      "/v1/orgs/" +
        orgId +
        "/appointments/" +
        appointmentId +
        "/remote-contingency",
    )
      .then(setContingency)
      .catch(() => setContingency(null));
  }, [orgId, appointmentId]);
  useEffect(
    () => () => {
      stream.current?.getTracks().forEach((t) => t.stop());
      void roomRef.current?.disconnect();
    },
    [],
  );
  async function devices() {
    try {
      stream.current?.getTracks().forEach((t) => t.stop());
      stream.current = await navigator.mediaDevices.getUserMedia({
        audio,
        video,
      });
      if (preview.current) preview.current.srcObject = stream.current;
      setError("");
    } catch {
      setError(
        "Não foi possível acessar câmera ou microfone. Verifique as permissões do navegador.",
      );
    }
  }
  async function join() {
    setBusy(true);
    setError("");
    try {
      if (!isE2EESupported())
        throw new Error(
          "Este navegador não oferece a criptografia exigida. Use um navegador compatível.",
        );
      const access = await request<VideoAccess>(
        "/v1/orgs/" +
          orgId +
          "/appointments/" +
          appointmentId +
          "/video/access",
        { method: "POST" },
      );
      const provider = new ExternalE2EEKeyProvider();
      const instance = new Room({
        adaptiveStream: true,
        dynacast: true,
        encryption: {
          keyProvider: provider,
          worker: new Worker(
            new URL("livekit-client/e2ee-worker", import.meta.url),
          ),
        },
      });
      roomRef.current = instance;
      await provider.setKey(access.e2eeKey);
      await instance.setE2EEEnabled(true);
      instance.on(RoomEvent.Reconnecting, () => setConnection("Reconectando…"));
      instance.on(RoomEvent.Reconnected, () =>
        setConnection("Conexão protegida"),
      );
      instance.on(RoomEvent.Disconnected, () => {
        setConnection("Encontro encerrado");
        setEnded(true);
      });
      stream.current?.getTracks().forEach((t) => t.stop());
      await instance.connect(access.serverUrl, access.accessToken);
      await instance.localParticipant.setMicrophoneEnabled(audio);
      await instance.localParticipant.setCameraEnabled(video);
      setRoom(instance);
      setConnection("Conexão protegida · E2EE");
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "O atendimento online está indisponível. Use o canal de contingência combinado com o profissional.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="video-page" id="conteudo">
      <Brand dark />
      {contingency && (
        <details className="panel settings-panel">
          <summary>Combinados para interrupção e privacidade</summary>
          <p>{contingency.interruptionPlan}</p>
          <p>{contingency.privacyGuidance}</p>
          {contingency.contingencyChannel && (
            <p>Canal de contingência: {contingency.contingencyChannel}</p>
          )}
          {contingency.acuteRiskPlan && (
            <p>
              Protocolo revisado para risco agudo: {contingency.acuteRiskPlan}
            </p>
          )}
          {contingency.currentLocation && (
            <p>Localização registrada: {contingency.currentLocation}</p>
          )}
          {contingency.emergencyContact && (
            <p>
              Contato de emergência: {contingency.emergencyContact.name} ·{" "}
              {contingency.emergencyContact.phone}
            </p>
          )}
        </details>
      )}
      {room && !ended ? (
        <>
          <p className="video-status" role="status">
            <ShieldCheck size={16} />
            {connection}
          </p>
          <div className="video-live">
            <LiveKitRoom
              room={room}
              serverUrl={undefined}
              token={undefined}
              connect={false}
              data-lk-theme="default"
            >
              <VideoConference chatMessageFormatter={() => ""} />
            </LiveKitRoom>
          </div>
        </>
      ) : (
        <>
          <h1>{ended ? "Respeite seu tempo." : "Um espaço de presença."}</h1>
          <Notice>
            Áudio e vídeo com criptografia de ponta a ponta. Sem gravação e sem
            transcrição. Este atendimento não é um serviço de emergência.
          </Notice>
          {!ended && (
            <section className="video-preflight">
              <video
                className="video-preview"
                ref={preview}
                autoPlay
                playsInline
                muted
                aria-label="Prévia da sua câmera"
              />
              <p>
                Encontre um lugar reservado e confira seus dispositivos antes de
                entrar. A entrada fica disponível na janela do atendimento.
              </p>
              <div className="button-row">
                <button
                  className="button button-outline button-light"
                  onClick={devices}
                >
                  Testar câmera e áudio
                </button>
                <button
                  className="button button-light"
                  aria-pressed={audio}
                  aria-label="Ativar ou desativar microfone"
                  onClick={() => {
                    setAudio(!audio);
                    stream.current
                      ?.getAudioTracks()
                      .forEach((t) => (t.enabled = !audio));
                  }}
                >
                  {audio ? <Mic size={18} /> : <MicOff size={18} />}
                </button>
                <button
                  className="button button-light"
                  aria-pressed={video}
                  aria-label="Ativar ou desativar câmera"
                  onClick={() => {
                    setVideo(!video);
                    stream.current
                      ?.getVideoTracks()
                      .forEach((t) => (t.enabled = !video));
                  }}
                >
                  {video ? <Video size={18} /> : <VideoOff size={18} />}
                </button>
                <button
                  className="button button-light"
                  disabled={busy}
                  onClick={join}
                >
                  {busy ? "Preparando conexão…" : "Entrar no encontro"}
                </button>
              </div>
            </section>
          )}
          {error && <ErrorNotice message={error} />}
          <Link className="text-link" href="/portal">
            <ArrowLeft size={16} />
            Voltar ao meu espaço
          </Link>
        </>
      )}
    </main>
  );
}
