import { Chess } from "./vendor/chess.js";

const SAMPLE = "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25";
const apiPrefix = (document.querySelector('meta[name="app-prefix"]').content || "").replace(/\/$/, "");
const PIECES = {
  P: "♙", N: "♘", B: "♗", R: "♖", Q: "♕", K: "♔",
  p: "♟", n: "♞", b: "♝", r: "♜", q: "♛", k: "♚",
};

const fenInput = document.querySelector("#fen");
const pgnInput = document.querySelector("#pgn");
const statusEl = document.querySelector("#status");
const analyseBtn = document.querySelector("#analyse");
const downloadBtn = document.querySelector("#download-pgn");
const userInput = document.querySelector("#user");
const passInput = document.querySelector("#pass");
const movesEl = document.querySelector("#moves");
const gameList = document.querySelector("#game-list");
const similarPanel = document.querySelector("#similar-panel");
const similarTitle = document.querySelector("#similar-title");
const similarMeta = document.querySelector("#similar-meta");

const main = { fens: [SAMPLE], sans: [], marks: [null], index: 0, label: "Starting position" };
const similar = { fens: [], sans: [], marks: [], index: 0 };

// Credentials stay in localStorage as plain text. Game names and moves are set with textContent.
userInput.value = localStorage.getItem("ziga-chess-user") || "";
passInput.value = localStorage.getItem("ziga-chess-pass") || "";

function setStatus(text, isError) {
  statusEl.textContent = text;
  statusEl.classList.toggle("error", Boolean(isError));
}

function paint(el, fen, mark) {
  const board = fen.split(" ")[0];
  el.replaceChildren();
  let rank = 8;
  let file = 0;
  for (const symbol of board) {
    if (symbol === "/") {
      rank -= 1;
      file = 0;
      continue;
    }
    if (symbol >= "1" && symbol <= "8") {
      for (let i = 0; i < Number(symbol); i += 1) {
        el.append(square(file, rank, "", mark));
        file += 1;
      }
      continue;
    }
    el.append(square(file, rank, PIECES[symbol] || "", mark));
    file += 1;
  }
  el.setAttribute("aria-label", fen);
}

function square(file, rank, glyph, mark) {
  const name = "abcdefgh"[file] + rank;
  const node = document.createElement("div");
  const light = (file + rank) % 2 === 1;
  node.className = "sq " + (light ? "light" : "dark");
  if (mark && (mark.from === name || mark.to === name)) {
    node.classList.add("mark");
  }
  node.textContent = glyph;
  return node;
}

function showMain() {
  const fen = main.fens[main.index] || SAMPLE;
  paint(document.querySelector("#board"), fen, main.marks[main.index]);
  const move = main.sans[main.index - 1];
  document.querySelector("#board-caption").textContent = move
    ? main.label + " · " + move
    : main.label;
}

function showSimilar() {
  if (!similar.fens.length) {
    return;
  }
  paint(document.querySelector("#similar-board"), similar.fens[similar.index], similar.marks[similar.index]);
}

function lineFromPgn(pgn, fallbackFen) {
  const empty = { fens: [fallbackFen], sans: [], marks: [null] };
  try {
    const probe = new Chess();
    if (!probe.load_pgn(pgn, { sloppy: true })) {
      return empty;
    }
    const verbose = probe.history({ verbose: true });
    const start = probe.header().FEN || fallbackFen;
    const board = new Chess(start);
    const fens = [board.fen()];
    const sans = [];
    const marks = [null];
    for (const move of verbose) {
      const played = board.move(move.san, { sloppy: true });
      if (!played) {
        break;
      }
      fens.push(board.fen());
      sans.push(played.san);
      marks.push({ from: played.from, to: played.to });
    }
    return { fens, sans, marks };
  } catch (err) {
    return empty;
  }
}

function renderMoves() {
  movesEl.replaceChildren();
  main.sans.forEach((san, i) => {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = san;
    if (main.index === i + 1) {
      button.classList.add("active");
    }
    button.addEventListener("click", () => {
      main.index = i + 1;
      showMain();
      renderMoves();
    });
    item.append(button);
    movesEl.append(item);
  });
}

function step(line, delta, render) {
  const next = Math.min(Math.max(line.index + delta, 0), line.fens.length - 1);
  line.index = next;
  render();
}

function selectLine(which, pgn) {
  const line = lineFromPgn(pgn, fenInput.value.trim() || SAMPLE);
  main.fens = line.fens;
  main.sans = line.sans;
  main.marks = line.marks;
  main.index = line.fens.length > 1 ? 1 : 0;
  main.label = which;
  showMain();
  renderMoves();
  document.querySelector("#show-pv1").classList.toggle("active", which === "PV1");
  document.querySelector("#show-pv2").classList.toggle("active", which === "PV2");
}

function selectGame(game, button) {
  const fen = game.endgameFen || fenInput.value.trim();
  const line = lineFromPgn(game.pv1 || "", fen);
  similar.fens = line.fens.length ? line.fens : [fen];
  similar.sans = line.sans;
  similar.marks = line.marks.length ? line.marks : [null];
  similar.index = 0;
  similarTitle.textContent = game.white + " – " + game.black;
  const why = whyText(game);
  similarMeta.textContent = game.result + " · score " + game.score + (why ? " · " + why : "");
  similarPanel.classList.remove("hidden");
  showSimilar();
  for (const node of gameList.querySelectorAll("button")) {
    node.classList.toggle("active", node === button);
  }
}

function whyText(game) {
  const tokens = game.explanation && game.explanation.tokens;
  if (!tokens || !tokens.length) {
    return "";
  }
  return tokens.map((token) => token.field + ":" + token.token).join("  ");
}

function renderGames(games) {
  gameList.replaceChildren();
  similarPanel.classList.add("hidden");
  if (!games.length) {
    const empty = document.createElement("li");
    empty.textContent = "No similar games in this index.";
    gameList.append(empty);
    return;
  }
  games.forEach((game) => {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "#" + game.rank + "  " + game.white + " – " + game.black + "  " + game.score;
    button.addEventListener("click", () => selectGame(game, button));
    item.append(button);
    const why = whyText(game);
    if (why) {
      const note = document.createElement("p");
      note.className = "why";
      note.textContent = why;
      item.append(note);
    }
    gameList.append(item);
  });
}

document.querySelector("#load-pgn").addEventListener("click", () => {
  const line = lineFromPgn(pgnInput.value, fenInput.value.trim() || SAMPLE);
  const header = line.fens[0];
  if (!pgnInput.value.trim() || header === (fenInput.value.trim() || SAMPLE) && !line.sans.length) {
    setStatus("Could not read a position from that PGN.", true);
    return;
  }
  fenInput.value = header;
  main.fens = [header];
  main.sans = [];
  main.marks = [null];
  main.index = 0;
  main.label = "Loaded from PGN";
  showMain();
  renderMoves();
  setStatus("FEN loaded from the PGN header, or from the position after the moves.", false);
});

document.querySelector("#show-pv1").addEventListener("click", () => {
  if (window.__lastAnalyse) selectLine("PV1", window.__lastAnalyse.principalVariations[0].pgn);
});
document.querySelector("#show-pv2").addEventListener("click", () => {
  if (window.__lastAnalyse) selectLine("PV2", window.__lastAnalyse.principalVariations[1].pgn);
});

for (const [id, delta] of [["main-start", -999], ["main-prev", -1], ["main-next", 1], ["main-end", 999]]) {
  document.querySelector("#" + id).addEventListener("click", () => {
    step(main, delta, () => { showMain(); renderMoves(); });
  });
}
for (const [id, delta] of [["sim-start", -999], ["sim-prev", -1], ["sim-next", 1], ["sim-end", 999]]) {
  document.querySelector("#" + id).addEventListener("click", () => {
    step(similar, delta, showSimilar);
  });
}

function placeFen(fen, label) {
  const chess = new Chess();
  if (!chess.validate_fen(fen).valid) {
    return false;
  }
  chess.load(fen);
  const placed = chess.fen();
  fenInput.value = placed;
  main.fens = [placed];
  main.sans = [];
  main.marks = [null];
  main.index = 0;
  main.label = label;
  showMain();
  renderMoves();
  return true;
}

fenInput.addEventListener("change", () => {
  if (!placeFen(fenInput.value.trim(), "Starting position")) {
    setStatus("That FEN could not be placed on the board.", true);
    return;
  }
  setStatus("", false);
});

document.querySelector("#copy-link").addEventListener("click", async () => {
  const fen = fenInput.value.trim();
  if (!new Chess().validate_fen(fen).valid) {
    setStatus("That FEN could not be placed on the board.", true);
    return;
  }
  const url = new URL(window.location.href);
  url.searchParams.set("fen", fen);
  history.replaceState(null, "", url);
  try {
    await navigator.clipboard.writeText(url.toString());
    setStatus("Link copied.", false);
  } catch (err) {
    setStatus(url.toString(), false);
  }
});

downloadBtn.addEventListener("click", () => {
  const text = (window.__lastAnalyse && window.__lastAnalyse.similarPgn) || "";
  if (!text.trim()) {
    setStatus("No similar games to download.", true);
    return;
  }
  const file = new Blob([text], { type: "application/x-chess-pgn" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(file);
  link.download = "similar-games.pgn";
  link.click();
  URL.revokeObjectURL(link.href);
});

analyseBtn.addEventListener("click", async () => {
  const fen = fenInput.value.trim();
  const user = userInput.value;
  const pass = passInput.value;
  localStorage.setItem("ziga-chess-user", user);
  localStorage.setItem("ziga-chess-pass", pass);
  analyseBtn.disabled = true;
  downloadBtn.disabled = true;
  setStatus("Analysing…", false);
  try {
    const response = await fetch(apiPrefix + "/analyse", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": basicAuthHeader(user, pass),
      },
      body: JSON.stringify({ FEN: fen }),
    });
    const payload = await response.json();
    if (!response.ok) {
      setStatus(payload.error || "Analyse failed.", true);
      return;
    }
    window.__lastAnalyse = payload;
    selectLine("PV1", payload.principalVariations[0].pgn);
    renderGames(payload.similarGames || []);
    downloadBtn.disabled = !(payload.similarPgn && payload.similarPgn.trim());
    const ms = payload.meta && payload.meta.tookMs;
    setStatus("Done in " + ms + " ms.", false);
  } catch (err) {
    setStatus("The analyse request failed.", true);
  } finally {
    analyseBtn.disabled = false;
  }
});

function basicAuthHeader(user, pass) {
  const bytes = new TextEncoder().encode(user + ":" + pass);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return "Basic " + btoa(binary);
}

const linked = new URLSearchParams(window.location.search).get("fen");
if (linked && !placeFen(linked, "Shared position")) {
  setStatus("The fen link is not a valid position.", true);
}
showMain();
