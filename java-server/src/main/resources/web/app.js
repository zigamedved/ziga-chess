import { Chess } from "./vendor/chess.js";

const SAMPLE = "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25";
const apiPrefix = (document.querySelector('meta[name="app-prefix"]').content || "").replace(/\/$/, "");
const PIECES = {
  P: "♙", N: "♘", B: "♗", R: "♖", Q: "♕", K: "♔",
  p: "♟", n: "♞", b: "♝", r: "♜", q: "♛", k: "♚",
};

const fenInput = document.querySelector("#fen");
const statusEl = document.querySelector("#status");
const analyseBtn = document.querySelector("#analyse");
const downloadBtn = document.querySelector("#download-pgn");
const userInput = document.querySelector("#user");
const passInput = document.querySelector("#pass");
const movesEl = document.querySelector("#moves");
const gameList = document.querySelector("#game-list");
const reviewEl = document.querySelector("#review");
const reviewName = document.querySelector("#review-name");

const main = { fens: [SAMPLE], sans: [], marks: [null], index: 0, label: "Starting position" };
let bestLine = null;

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
    el.append(square(file, rank, symbol, mark));
    file += 1;
  }
  el.setAttribute("aria-label", fen);
}

function square(file, rank, symbol, mark) {
  const name = "abcdefgh"[file] + rank;
  const node = document.createElement("div");
  const light = (file + rank) % 2 === 1;
  node.className = "sq " + (light ? "light" : "dark");
  if (symbol) {
    node.classList.add(symbol === symbol.toUpperCase() ? "w" : "b");
    node.textContent = PIECES[symbol] || "";
  }
  if (mark && (mark.from === name || mark.to === name)) {
    node.classList.add("mark");
  }
  return node;
}

function showArrow(mark) {
  const svg = document.querySelector("#arrow");
  const line = document.querySelector("#arrow-line");
  if (!mark) {
    svg.classList.add("hidden");
    return;
  }
  const point = (squareName) => [
    "abcdefgh".indexOf(squareName[0]) + 0.5,
    8.5 - Number(squareName[1]),
  ];
  const [x1, y1] = point(mark.from);
  const [x2, y2] = point(mark.to);
  line.setAttribute("x1", x1);
  line.setAttribute("y1", y1);
  line.setAttribute("x2", x2);
  line.setAttribute("y2", y2);
  svg.classList.remove("hidden");
}

function showMain() {
  const fen = main.fens[main.index] || SAMPLE;
  const mark = main.marks[main.index];
  paint(document.querySelector("#board"), fen, mark);
  showArrow(mark);
  const move = main.sans[main.index - 1];
  document.querySelector("#board-caption").textContent = move
    ? main.label + " · " + move
    : main.label;
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
  bestLine = null;
  reviewEl.classList.add("hidden");
  const line = lineFromPgn(pgn, currentFen());
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

function rememberBest() {
  if (bestLine) {
    return;
  }
  bestLine = {
    fens: main.fens.slice(),
    sans: main.sans.slice(),
    marks: main.marks.slice(),
    index: main.index,
    label: main.label,
  };
}

function selectGame(game, button) {
  rememberBest();
  const fen = game.endgameFen || currentFen();
  const line = lineFromPgn(game.pv1 || "", fen);
  main.fens = line.fens.length ? line.fens : [fen];
  main.sans = line.sans;
  main.marks = line.marks.length ? line.marks : [null];
  main.index = line.sans.length ? 1 : 0;
  main.label = game.white + " – " + game.black;
  reviewName.textContent = main.label;
  reviewEl.classList.remove("hidden");
  showMain();
  renderMoves();
  for (const node of gameList.querySelectorAll("button")) {
    node.classList.toggle("active", node === button);
  }
}

function restoreBest() {
  if (!bestLine) {
    return;
  }
  main.fens = bestLine.fens;
  main.sans = bestLine.sans;
  main.marks = bestLine.marks;
  main.index = bestLine.index;
  main.label = bestLine.label;
  bestLine = null;
  reviewEl.classList.add("hidden");
  showMain();
  renderMoves();
  for (const node of gameList.querySelectorAll("button")) {
    node.classList.remove("active");
  }
}

function whyText(game) {
  const tokens = game.explanation && game.explanation.tokens;
  if (!tokens || !tokens.length) {
    return "";
  }
  return tokens.map((token) => token.field + ":" + token.token).join("  ");
}

function matchPercent(game, games) {
  const top = Math.max(...games.map((row) => Number(row.score) || 0), 0);
  if (top <= 0) {
    return 0;
  }
  return Math.round(((Number(game.score) || 0) / top) * 100);
}

function renderMeter(games) {
  let white = 0;
  let draw = 0;
  let black = 0;
  for (const game of games) {
    if (game.result === "1-0") white += 1;
    else if (game.result === "0-1") black += 1;
    else if (game.result === "1/2-1/2") draw += 1;
  }
  const total = white + draw + black;
  const caption = document.querySelector("#meter-caption");
  const set = (id, count) => {
    document.querySelector(id).style.flex = String(total ? count : 0);
  };
  set("#meter-white", white);
  set("#meter-draw", draw);
  set("#meter-black", black);
  caption.textContent = total
    ? white + " · " + draw + " · " + black
    : "Results";
}

function renderGames(games) {
  gameList.replaceChildren();
  reviewEl.classList.add("hidden");
  bestLine = null;
  renderMeter(games);
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
    const thumb = document.createElement("div");
    thumb.className = "board thumb";
    thumb.setAttribute("aria-hidden", "true");
    const copy = document.createElement("span");
    copy.className = "game-copy";
    const title = document.createElement("strong");
    title.textContent = game.white + " – " + game.black;
    const meta = document.createElement("span");
    const pct = matchPercent(game, games);
    meta.textContent = (game.result || "—") + " · " + pct + "% match";
    const bar = document.createElement("span");
    bar.className = "match";
    const fill = document.createElement("span");
    fill.style.width = pct + "%";
    bar.append(fill);
    copy.append(title, meta, bar);
    const why = whyText(game);
    if (why) {
      const note = document.createElement("span");
      note.className = "why";
      note.textContent = why;
      copy.append(note);
    }
    button.append(thumb, copy);
    button.addEventListener("click", () => selectGame(game, button));
    item.append(button);
    gameList.append(item);
    paint(thumb, game.endgameFen || currentFen(), null);
  });
}

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
document.querySelector("#back-line").addEventListener("click", restoreBest);

function currentFen() {
  return positionFromInput(fenInput.value) || SAMPLE;
}

function positionFromInput(raw) {
  const text = (raw || "").trim();
  if (!text) {
    return null;
  }
  const chess = new Chess();
  if (chess.validate_fen(text).valid) {
    chess.load(text);
    return chess.fen();
  }
  try {
    const probe = new Chess();
    const loaded = probe.load_pgn(text, { sloppy: true });
    const fen = probe.fen();
    const start = new Chess().fen();
    if (loaded || (probe.validate_fen(fen).valid && fen !== start)) {
      return fen;
    }
  } catch (err) {
    return null;
  }
  return null;
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
  const fen = positionFromInput(fenInput.value);
  if (!fen) {
    setStatus("That text is not a FEN or a PGN.", true);
    return;
  }
  placeFen(fen, "Position");
  setStatus("", false);
});

document.querySelector("#copy-link").addEventListener("click", async () => {
  const fen = currentFen();
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
  const fen = positionFromInput(fenInput.value);
  if (!fen) {
    setStatus("Enter a FEN or a PGN first.", true);
    return;
  }
  fenInput.value = fen;
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
