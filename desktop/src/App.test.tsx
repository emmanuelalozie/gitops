import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { App } from "./App";

// Runs on the demo backend (no desktop app in tests), through the same views and state as the real thing.
it("opens a repository, shows the overview and edits a resource", async () => {
  render(<App />);
  fireEvent.click(await screen.findByText("Open a repository…"));
  expect(await screen.findByRole("heading", { name: "Overview" })).toBeTruthy();
  await waitFor(() => expect(screen.getAllByText("Drift").length).toBeGreaterThan(0), { timeout: 3000 });

  const nav = screen.getByRole("navigation");
  fireEvent.click(within(nav).getByText("dev"));
  fireEvent.click(await screen.findByRole("tab", { name: "Git config" }));
  fireEvent.click(await screen.findByText("Antonette"));
  const email = (await screen.findByLabelText(/email/)) as HTMLInputElement;
  expect(email.value).toBe("Shanna@melissa.tv");
  fireEvent.change(email, { target: { value: "ann@example.com" } });
  const save = screen.getByRole("button", { name: "Save to working tree" }) as HTMLButtonElement;
  await waitFor(() => expect(save.disabled).toBe(false));
  fireEvent.click(save);
  expect(await screen.findByText(/Saved Antonette/)).toBeTruthy();
});

it("lists changes and proposes them", async () => {
  render(<App />);
  fireEvent.click(await screen.findByText("Open a repository…"));
  const nav = await screen.findByRole("navigation");
  fireEvent.click(within(nav).getByText("Changes"));
  const submit = await screen.findByRole("button", { name: /Commit \d+ files? and open pull request/ });
  fireEvent.click(submit);
  expect(await screen.findByText(/open pull request form/)).toBeTruthy();
});

it("applies the dev plan only after the target name is typed, then shows it verified", async () => {
  render(<App />);
  fireEvent.click(await screen.findByText("Open a repository…"));
  const nav = await screen.findByRole("navigation");
  fireEvent.click(await within(nav).findByText("dev"));
  const apply = await screen.findByText("Apply…", {}, { timeout: 3000 });
  fireEvent.click(apply);
  const confirm = screen.getByText(/^Apply \d+ changes?$/) as HTMLButtonElement;
  expect(confirm.disabled).toBe(true);
  fireEvent.change(screen.getByLabelText(/to confirm/), { target: { value: "dev" } });
  expect(confirm.disabled).toBe(false);
  fireEvent.click(confirm);
  expect(await screen.findByText(/Applied and verified/, {}, { timeout: 5000 })).toBeTruthy();
  expect(screen.getAllByText(/✓/).length).toBeGreaterThan(0);
});

it("offers apply only for targets that allow it", async () => {
  render(<App />);
  fireEvent.click(await screen.findByText("Open a repository…"));
  const nav = await screen.findByRole("navigation");
  fireEvent.click(await within(nav).findByText("prod"));
  await screen.findByRole("tab", { name: "Plan" });
  expect(screen.queryByText("Apply…")).toBeNull();
});

it("explains a folder that isn't a GitOps repository instead of failing", async () => {
  render(<App />);
  fireEvent.click(await screen.findByText("/demo/inventory-manager"));
  expect(await screen.findByText("inventory-manager isn't a GitOps repository")).toBeTruthy();
  expect(screen.getByText(/"repository": "emmanuelalozie\/inventory-manager"/)).toBeTruthy();
  expect(screen.getByText("Choose another folder…")).toBeTruthy();
});

it("starts a stopped mock and re-plans the targets that use it", async () => {
  render(<App />);
  fireEvent.click(await screen.findByText("Open a repository…"));
  const nav = await screen.findByRole("navigation");
  fireEvent.click(await within(nav).findByText("dev"));
  fireEvent.click(await screen.findByText("Stop", {}, { timeout: 3000 }));
  expect(await screen.findByText("Start mock", {}, { timeout: 3000 })).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Re-plan" }));
  expect(await screen.findByText(/Press Start mock/, {}, { timeout: 3000 })).toBeTruthy();
  fireEvent.click(screen.getByText("Start mock"));
  expect(await screen.findByText("mock running", {}, { timeout: 3000 })).toBeTruthy();
  await waitFor(() => expect(screen.queryByText(/Press Start mock/)).toBeNull(), { timeout: 3000 });
});
