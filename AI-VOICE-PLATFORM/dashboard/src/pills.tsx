import { labelLanguage, labelStatus, statusTone } from "./format";

export function LanguagePill({ code }: { code: string }) {
  return (
    <span className={`pill lang-${code === "te" ? "te" : "en"}`} lang={code === "te" ? "te" : "en"}>
      {labelLanguage(code)}
    </span>
  );
}

export function StatusPill({ status }: { status: string }) {
  return <span className={`pill tone-${statusTone(status)}`}>{labelStatus(status)}</span>;
}
