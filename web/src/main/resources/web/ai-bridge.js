/**
 * Registers the AI tab's commands (window.QB_AI_COMMANDS, from ai/commands.js) in the command
 * registry, so they can go on the Quick Access Toolbar, a custom tab, a shortcut or command search
 * like every other command. Loaded after commands.js and qat.js.
 */
(function () {
  if (!window.Commands || !Array.isArray(window.QB_AI_COMMANDS)) return;
  Commands.def(window.QB_AI_COMMANDS.map((c) => {
    const base = { id: c.id, label: c.label, icon: c.icon || "sparkle", tab: "AI", group: c.group || "AI", tip: c.tip, origin: c.origin };
    if (c.type === "menu" && typeof c.items === "function") {
      return { ...base, kind: "menu", menu: () => c.items().map((it) => ({ label: it.label, checked: !!it.checked, onClick: () => it.run() })) };
    }
    if (c.type === "toggle") return { ...base, kind: "toggle", isActive: () => !!(c.checked && c.checked()), run: (arg) => c.run(arg) };
    return { ...base, run: (arg) => c.run(arg) };
  }));
})();
