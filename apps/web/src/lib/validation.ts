import { z } from "zod";
import definitions from "./api.schemas.generated.json";
type Schema = {
  type?: string | string[];
  enum?: string[];
  $ref?: string;
  properties?: Record<string, Schema>;
  required?: string[];
  items?: Schema;
  additionalProperties?: boolean;
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  minItems?: number;
  maxItems?: number;
  pattern?: string;
  format?: string;
  anyOf?: Schema[];
  uniqueItems?: boolean;
};
const schemas = definitions as Record<string, Schema>,
  cache = new Map<string, z.ZodType>();
function convert(s: Schema): z.ZodType {
  if (s.anyOf)
    return z.union(
      s.anyOf.map(convert) as [z.ZodType, z.ZodType, ...z.ZodType[]],
    );
  if (s.$ref) return named(s.$ref.split("/").at(-1)!);
  if (Array.isArray(s.type))
    return z.union(
      s.type.map((type) => convert({ ...s, type })) as [
        z.ZodType,
        z.ZodType,
        ...z.ZodType[],
      ],
    );
  if (s.enum) return z.enum(s.enum as [string, ...string[]]);
  if (s.type === "object") {
    const fields: Record<string, z.ZodType> = {};
    for (const [name, child] of Object.entries(s.properties || {})) {
      const schema = convert(child);
      fields[name] = s.required?.includes(name) ? schema : schema.optional();
    }
    return s.additionalProperties === false
      ? z.strictObject(fields)
      : z.looseObject(fields);
  }
  if (s.type === "array") {
    let array = z.array(convert(s.items || {}));
    if (s.minItems !== undefined) array = array.min(s.minItems);
    if (s.maxItems !== undefined) array = array.max(s.maxItems);
    return s.uniqueItems
      ? array.refine(
          (values) =>
            new Set(values.map((v) => JSON.stringify(v))).size ===
            values.length,
        )
      : array;
  }
  if (s.type === "integer" || s.type === "number") {
    let value = z.number();
    if (s.type === "integer") value = value.int();
    if (s.minimum !== undefined) value = value.min(s.minimum);
    if (s.maximum !== undefined) value = value.max(s.maximum);
    return value;
  }
  if (s.type === "boolean") return z.boolean();
  if (s.type === "null") return z.null();
  if (s.type === "string") {
    let value = z.string();
    if (s.minLength !== undefined) value = value.min(s.minLength);
    if (s.maxLength !== undefined) value = value.max(s.maxLength);
    if (s.pattern) value = value.regex(new RegExp(s.pattern));
    switch (s.format) {
      case "uuid":
        return value.and(z.uuid());
      case "email":
        return value.and(z.email());
      case "date":
        return value.and(z.iso.date());
      case "date-time":
        return value.and(z.iso.datetime({ offset: true }));
      case "uri":
        return value.and(z.url());
      default:
        return value;
    }
  }
  return z.unknown();
}
function named(name: string) {
  let schema = cache.get(name);
  if (!schema) {
    schema = convert(schemas[name] || {});
    cache.set(name, schema);
  }
  return schema;
}
export function validateRequest(name: string, body: unknown) {
  if (!name) return;
  const result = named(name).safeParse(body);
  if (!result.success)
    throw new Error(
      "Confira os campos obrigatórios e os formatos informados antes de salvar.",
    );
}
