import { branchFor, buildResource, fieldsOf, fromInput, inputsOf, problemsByField, toInput } from "./schemaForm";

const users = {
  type: "object",
  properties: {
    id: { type: "integer", minimum: 1 },
    username: { type: "string", pattern: "^[A-Za-z0-9._-]+$" },
    email: { type: "string" },
    topics: { type: "array", items: { type: "string" } },
    private: { type: "boolean" },
    homepage: { type: ["string", "null"] },
  },
  required: ["id", "username"],
};

it("reads fields from a schema in order", () => {
  const fields = fieldsOf(users);
  expect(fields.map((f) => `${f.name}:${f.type}:${f.required}`)).toEqual([
    "id:integer:true", "username:string:true", "email:string:false", "topics:list:false", "private:boolean:false", "homepage:string:false",
  ]);
  expect(fields[0]!.minimum).toBe(1);
});

it("round-trips a resource through the form", () => {
  const fields = fieldsOf(users);
  const resource = { id: 3, username: "Ann", topics: ["a", "b"], private: true };
  const inputs = inputsOf(fields, resource);
  expect(inputs.topics).toBe("a\nb");
  expect(buildResource(fields, inputs)).toEqual({ ...resource });
});

it("keeps bad numbers as text so the engine can point at them", () => {
  const id = fieldsOf(users)[0]!;
  expect(fromInput(id, "12")).toBe(12);
  expect(fromInput(id, "1.5")).toBe("1.5");
  expect(fromInput(id, "x")).toBe("x");
  expect(fromInput(id, " ")).toBeUndefined();
  expect(toInput(id, undefined)).toBe("");
});

it("groups problems by field", () => {
  const map = problemsByField([
    { file: "f", pointer: "/email", message: "/email: does not match the regex pattern" },
    { file: "f", pointer: "/topics/1", message: "bad" },
    { file: "f", pointer: "", message: "whole file" },
  ]);
  expect(map.get("email")).toEqual(["does not match the regex pattern"]);
  expect(map.get("topics")).toEqual(["bad"]);
  expect(map.get("")).toEqual(["whole file"]);
});

it("suggests a branch name from the title", () => {
  expect(branchFor("Add Ann to dev!")).toBe("gitops/add-ann-to-dev");
  expect(branchFor("???")).toBe("gitops/config-change");
});
