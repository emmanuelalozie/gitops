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
