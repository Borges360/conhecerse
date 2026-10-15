"use client";
import { useCallback, useEffect, useState } from "react";
import { checksum } from "@/lib/browser-crypto";
import { request } from "@/lib/api.generated";
import type { Row } from "./workspace";
import { Field, ErrorNotice, Notice, SaveButton, date } from "./ui";
export function ClinicalFiles({
  orgId,
  patientId,
}: {
  orgId: string;
  patientId: string;
}) {
  const [files, setFiles] = useState<Row[]>([]),
    [restricted, setRestricted] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const base = "/v1/orgs/" + orgId,
    path =
      base +
      "/patients/" +
      patientId +
      (restricted ? "/restricted-test-materials" : "/clinical-files");
  const load = useCallback(async () => {
    try {
      setFiles((await request<{ items: Row[] }>(path)).items);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Arquivos indisponíveis.");
    }
  }, [path]);
  useEffect(() => {
    void load();
  }, [load]);
  return (
    <section className="panel settings-panel">
      <h2>Anexos e materiais profissionais</h2>
      <Field label="Finalidade de acesso">
        <select
          value={restricted ? "restricted" : "clinical"}
          onChange={(e) => setRestricted(e.target.value === "restricted")}
        >
          <option value="clinical">Anexos clínicos do paciente</option>
          <option value="restricted">
            Materiais de teste — acesso profissional restrito
          </option>
        </select>
      </Field>
      {restricted && (
        <Notice>
          Materiais de testes ficam segregados do prontuário normal e dos
          documentos liberados no portal.
        </Notice>
      )}
      <form
        className="entity-form"
        onSubmit={async (e) => {
          e.preventDefault();
          const form = e.currentTarget,
            file = new FormData(form).get("file") as File;
          setBusy(true);
          setError("");
          try {
            if (
              file.size > 25 * 1024 * 1024 ||
              !["application/pdf", "image/png", "image/jpeg"].includes(
                file.type,
              )
            )
              throw new Error("Envie PDF, PNG ou JPEG com até 25 MB.");
            const bytes = await file.arrayBuffer(),
              sha256 = checksum(bytes);
            const upload = await request<{ id: string; uploadUrl: string }>(
              base + "/files/uploads",
              {
                method: "POST",
                body: JSON.stringify({
                  filename: file.name,
                  contentType: file.type,
                  sizeBytes: file.size,
                  sha256,
                  patientId,
                  classification: restricted
                    ? "RESTRICTED_TEST"
                    : "SENSITIVE_CLINICAL",
                }),
              },
            );
            const put = await fetch(upload.uploadUrl, {
              method: "PUT",
              headers: { "Content-Type": file.type },
              body: bytes,
            });
            if (!put.ok) throw new Error("Falha ao armazenar o arquivo.");
            const validation = await request<{ status: string }>(
              base + "/files/" + upload.id + "/complete",
              {
                method: "POST",
              },
            );
            if (validation.status !== "READY")
              throw new Error(
                "O arquivo foi rejeitado na validação e permanece em quarentena.",
              );
            await request(path, {
              method: "POST",
              body: JSON.stringify({ fileId: upload.id }),
            });
            form.reset();
            await load();
          } catch (e) {
            setError(e instanceof Error ? e.message : "Arquivo não validado.");
          } finally {
            setBusy(false);
          }
        }}
      >
        <Field label="Arquivo protegido">
          <input
            name="file"
            type="file"
            accept="application/pdf,image/png,image/jpeg"
            required
          />
        </Field>
        <SaveButton busy={busy}>Enviar e validar arquivo</SaveButton>
      </form>
      {error && <ErrorNotice message={error} />}{" "}
      {files.map((f) => (
        <div className="integration-row" key={f.id}>
          <span>
            <strong>
              {restricted ? "Material restrito" : "Anexo clínico"}
            </strong>
            <small>{date(String(f.createdAt))}</small>
          </span>
          <button
            className="text-button"
            onClick={async () => {
              try {
                const ticket = await request<{ downloadUrl: string }>(
                  base + "/files/" + f.fileAssetId + "/download",
                  { method: "POST" },
                );
                window.location.assign(ticket.downloadUrl);
              } catch (e) {
                setError(
                  e instanceof Error ? e.message : "Download indisponível.",
                );
              }
            }}
          >
            Baixar arquivo autorizado
          </button>
        </div>
      ))}
    </section>
  );
}
