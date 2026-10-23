// Saneado de textos no confiables (comentario, adjuntos, metadatos).
// Redacta secretos, emails, IPs y rutas privadas. Nunca ejecuta el texto.
interface Pattern {
  kind: string;
  re: RegExp;
}

const PATTERNS: Pattern[] = [
  { kind: "email", re: /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g },
  { kind: "ipv4", re: /\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b/g },
  { kind: "ipv6-full", re: /\b(?=[0-9a-fA-F:]*[a-fA-F])(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:.]{2,}\b/g },
  { kind: "ipv6-local", re: /(?<![0-9A-Za-z:.])::[0-9A-Fa-f:.]{1,}\b/g },
  { kind: "bearer", re: /\b[Bb]earer\s+[A-Za-z0-9_.-]+/g },
  { kind: "basic-auth", re: /\b[Bb]asic\s+[A-Za-z0-9+/=]{8,}/g },
  { kind: "secret-assign", re: /\b(password|passwd|pwd|secret|token|api[_-]?key|auth[_-]?token|access[_-]?token|session[_-]?id|client[_-]?secret)\b\s*[:=]\s*\S+/gi },
  { kind: "aws-key", re: /\bAKIA[0-9A-Z]{16}\b/g },
  { kind: "private-key", re: /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g },
  { kind: "home-path", re: /(\/home\/|\/Users\/)[^\s:"']+/g },
  { kind: "win-path", re: /[A-Za-z]:\\[^\s"']+/g },
  { kind: "serial", re: /\b(serial(?:number)?|imei|mac[_-]?address)\b\s*[:=]\s*\S+/gi },
  { kind: "credential-url", re: /[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/\s:]+:[^/\s@]+@[^\s/]+/g },
];

export interface SanitizeResult {
  text: string;
  redactions: number;
}

export function sanitizeText(input: string, maxChars = 4000 + 256): SanitizeResult {
  // Normaliza sustituyendo sustitutos no emparejados y controles no blancos.
  let text = input.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "�");
  text = text.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "");
  let redactions = 0;
  for (const pattern of PATTERNS) {
    pattern.re.lastIndex = 0;
    text = text.replace(pattern.re, () => {
      redactions += 1;
      return `[redacted:${pattern.kind}]`;
    });
  }
  if (text.length > maxChars) {
    text = text.slice(0, maxChars);
    redactions += 1;
  }
  return { text, redactions };
}

/** Aplica sanitizeText a comentario + adjuntos y cuenta el total redactado. */
export function sanitizeReportTexts(comment: string, attachments: Map<string, string>): { comment: string; redactionCount: number } {
  let redactionCount = 0;
  const cleanComment = sanitizeText(comment);
  redactionCount += cleanComment.redactions;
  for (const [name, body] of attachments) {
    const clean = sanitizeText(body, 8 * 1024 * 1024 + 256);
    attachments.set(name, clean.text);
    redactionCount += clean.redactions;
  }
  return { comment: cleanComment.text, redactionCount };
}

/**
 * Sanea recursivamente TODAS las cadenas de un JSON allowlistado antes de
 * persistirlo. Los números/booleans/null pasan intactos, así que el hash de
 * idempotencia (calculado sobre el original canónico) no se ve afectado.
 */
export function sanitizeJsonStrings(value: unknown, maxChars = 8192): { value: unknown; redactions: number } {
  if (typeof value === "string") {
    const clean = sanitizeText(value, maxChars);
    return { value: clean.text, redactions: clean.redactions };
  }
  if (Array.isArray(value)) {
    let redactions = 0;
    const out = value.map((entry) => {
      const clean = sanitizeJsonStrings(entry, maxChars);
      redactions += clean.redactions;
      return clean.value;
    });
    return { value: out, redactions };
  }
  if (typeof value === "object" && value !== null) {
    let redactions = 0;
    const out: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      const clean = sanitizeJsonStrings(entry, maxChars);
      redactions += clean.redactions;
      out[key] = clean.value;
    }
    return { value: out, redactions };
  }
  return { value, redactions: 0 };
}
