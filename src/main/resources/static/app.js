// ===== STATIC MATCH SCHEDULE =====
// Decoupled from the backend API to ensure reliable chronological ordering.
// Keys are match IDs (A1–L6), values are ISO-8601 date strings.
// TODO: Dates are structurally correct for sorting, but temporally inaccurate. Do not render.
const STATIC_MATCH_SCHEDULE = {
    // Matchday 1
    "A1": "2026-06-11T19:00:00Z", "A2": "2026-06-11T22:00:00Z",
    "B1": "2026-06-12T19:00:00Z", "B2": "2026-06-13T19:00:00Z",
    "D1": "2026-06-12T22:00:00Z", "D2": "2026-06-13T22:00:00Z",
    "C1": "2026-06-13T19:30:00Z", "C2": "2026-06-13T22:30:00Z",
    "E1": "2026-06-14T19:00:00Z", "E2": "2026-06-14T22:00:00Z",
    "F1": "2026-06-14T19:30:00Z", "F2": "2026-06-14T22:30:00Z",
    "G1": "2026-06-15T19:00:00Z", "G2": "2026-06-15T22:00:00Z",
    "H1": "2026-06-15T19:30:00Z", "H2": "2026-06-15T22:30:00Z",
    "I1": "2026-06-16T19:00:00Z", "I2": "2026-06-16T22:00:00Z",
    "J1": "2026-06-16T19:30:00Z", "J2": "2026-06-16T22:30:00Z",
    "K1": "2026-06-17T19:00:00Z", "K2": "2026-06-17T22:00:00Z",
    "L1": "2026-06-17T19:30:00Z", "L2": "2026-06-17T22:30:00Z",

    // Matchday 2
    "A3": "2026-06-18T19:00:00Z", "A4": "2026-06-18T22:00:00Z",
    "B3": "2026-06-18T19:30:00Z", "B4": "2026-06-18T22:30:00Z",
    "C3": "2026-06-19T19:00:00Z", "C4": "2026-06-19T22:00:00Z",
    "D3": "2026-06-19T19:30:00Z", "D4": "2026-06-19T22:30:00Z",
    "E3": "2026-06-20T19:00:00Z", "E4": "2026-06-20T22:00:00Z",
    "F3": "2026-06-20T19:30:00Z", "F4": "2026-06-20T22:30:00Z",
    "G3": "2026-06-21T19:00:00Z", "G4": "2026-06-21T22:00:00Z",
    "H3": "2026-06-21T19:30:00Z", "H4": "2026-06-21T22:30:00Z",
    "I3": "2026-06-22T19:00:00Z", "I4": "2026-06-22T22:00:00Z",
    "J3": "2026-06-22T19:30:00Z", "J4": "2026-06-22T22:30:00Z",
    "K3": "2026-06-23T19:00:00Z", "K4": "2026-06-23T22:00:00Z",
    "L3": "2026-06-23T19:30:00Z", "L4": "2026-06-23T22:30:00Z",

    // Matchday 3 (Final group games played simultaneously)
    "A5": "2026-06-24T19:00:00Z", "A6": "2026-06-24T19:00:00Z",
    "B5": "2026-06-24T22:00:00Z", "B6": "2026-06-24T22:00:00Z",
    "C5": "2026-06-24T19:30:00Z", "C6": "2026-06-24T19:30:00Z",
    "D5": "2026-06-25T19:00:00Z", "D6": "2026-06-25T19:00:00Z",
    "E5": "2026-06-25T22:00:00Z", "E6": "2026-06-25T22:00:00Z",
    "F5": "2026-06-25T19:30:00Z", "F6": "2026-06-25T19:30:00Z",
    "G5": "2026-06-26T19:00:00Z", "G6": "2026-06-26T19:00:00Z",
    "H5": "2026-06-26T22:00:00Z", "H6": "2026-06-26T22:00:00Z",
    "I5": "2026-06-26T19:30:00Z", "I6": "2026-06-26T19:30:00Z",
    "J5": "2026-06-27T19:00:00Z", "J6": "2026-06-27T19:00:00Z",
    "K5": "2026-06-27T22:00:00Z", "K6": "2026-06-27T22:00:00Z",
    "L5": "2026-06-27T19:30:00Z", "L6": "2026-06-27T19:30:00Z"
};

// ===== STATE =====
let TEAMS = {};
let GROUPS = {};
let bracketData = null;
let lastSeenEventTimestamp = null; // track which events we've already shown
let isAdmin = false;
let lockedMatches = {}; // matchId → [score1, score2]

// Utility: Delays function execution until X ms of inactivity
function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

// Collect all current scores from the DOM into a single state object
function collectCurrentState() {
    const groupScores = {};
    document.querySelectorAll('.group-match-row').forEach(row => {
        const inputs = row.querySelectorAll('.score-input');
        const onchangeAttr = inputs[0].getAttribute('onchange') || '';
        const match = onchangeAttr.match(/setGroupScore\('([^']+)'/);
        if (match) {
            groupScores[match[1]] = {
                score1: inputs[0].value !== '' ? parseInt(inputs[0].value) : null,
                score2: inputs[1].value !== '' ? parseInt(inputs[1].value) : null
            };
        }
    });

    const bracketScores = {};
    document.querySelectorAll('#bracket .match').forEach(matchEl => {
        const matchId = matchEl.getAttribute('data-match');
        const inputs = matchEl.querySelectorAll('.score-input');
        if (inputs.length === 2) {
            bracketScores[matchId] = {
                score1: inputs[0].value !== '' ? parseInt(inputs[0].value) : null,
                score2: inputs[1].value !== '' ? parseInt(inputs[1].value) : null
            };
        }
    });

    return { groups: groupScores, bracket: bracketScores };
}

// The actual network request to save the blob
const persistStateToBackend = async () => {
    const saveStatusEl = document.getElementById('save-status');
    try {
        const currentState = collectCurrentState();

        if (saveStatusEl) {
            saveStatusEl.textContent = 'Saving...';
            saveStatusEl.className = 'save-status saving';
        }

        const response = await fetch('/api/user/state', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(currentState)
        });

        if (!response.ok) throw new Error('Network response was not ok');
        
        console.log('Tournament state auto-saved successfully.');

        if (saveStatusEl) {
            saveStatusEl.textContent = 'Saved ✓';
            saveStatusEl.className = 'save-status saved';
            setTimeout(() => {
                saveStatusEl.textContent = '';
                saveStatusEl.className = 'save-status';
            }, 2500);
        }

    } catch (error) {
        console.error('Failed to auto-save state:', error);
        if (saveStatusEl) {
            saveStatusEl.textContent = '';
            saveStatusEl.className = 'save-status';
        }
    }
};

// Wrap it in a 500ms debounce
const debouncedSave = debounce(persistStateToBackend, 500);

// ===== API HELPERS =====
async function apiGet(url) {
    const res = await fetch(url);
    return res.json();
}

async function apiPost(url, body) {
    const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    return res.json();
}

// ===== INIT =====
async function init() {
    const data = await apiGet('/api/teams');
    for (const [code, info] of Object.entries(data)) {
        TEAMS[code] = info;
    }
    const groupsData = await apiGet('/api/groups');
    GROUPS = groupsData.groups;
    await loadGroupStage();
    await loadBracket();

    // Restore saved user state (needed after server restart)
    await restoreSavedState();

    // Load fallback odds snapshot timestamp for the simulate button
    loadOddsSnapshotTimestamp();
}

/**
 * Fetches the fallback-odds.json last-modified timestamp from the backend
 * and displays it inside the simulate button (e.g. "Odds snapshot: 19.6. 13:23:50").
 */
async function loadOddsSnapshotTimestamp() {
    try {
        const data = await apiGet('/api/fallback-odds-timestamp');
        if (data.timestamp) {
            const el = document.getElementById('odds-snapshot-time');
            if (el) el.textContent = ' (Odds snapshot: ' + data.timestamp + ')';
        }
    } catch (err) {
        // Non-critical — just skip showing the timestamp
    }
}

// ===== STATE RESTORATION =====
async function restoreSavedState() {
    try {
        const response = await fetch('/api/user/state');
        if (!response.ok) return;

        const wrapper = await response.json();

        // Display user email from the wrapper
        if (wrapper.email) {
            const emailEl = document.getElementById('user-email');
            if (emailEl) emailEl.textContent = wrapper.email;
        }

        // Store admin status and locked matches
        isAdmin = wrapper.isAdmin === true;
        lockedMatches = wrapper.lockedMatches || {};

        const savedState = wrapper.state;
        if (!savedState || !(savedState.groups || savedState.bracket)) return;

        const hasGroups = savedState.groups && Object.keys(savedState.groups).length > 0;
        const hasBracket = savedState.bracket && Object.keys(savedState.bracket).length > 0;

        if (!hasGroups && !hasBracket) return;

        // Fetch current backend state to determine what needs restoring
        const matchesData = await apiGet('/api/group-matches');

        // Determine which group matches need restoring (backend score is null but saved state has a score)
        const backendScores = {};
        for (const m of matchesData.matches) {
            if (m.score1 !== null && m.score2 !== null) {
                backendScores[m.id] = true;
            }
        }

        const groupEntriesToRestore = hasGroups
            ? Object.entries(savedState.groups)
                .filter(([matchId, scores]) =>
                    scores.score1 !== null && scores.score2 !== null && !backendScores[matchId])
            : [];

        const bracketEntriesToRestore = hasBracket
            ? Object.entries(savedState.bracket)
                .filter(([_, scores]) => scores.score1 !== null && scores.score2 !== null)
                .sort(([a], [b]) => parseInt(a) - parseInt(b))
            : [];

        const hasAnythingToRestore = groupEntriesToRestore.length > 0 || bracketEntriesToRestore.length > 0;

        if (!hasAnythingToRestore) {
            console.log('Backend state matches saved state, nothing to restore.');
            return;
        }

        console.log(`Restoring ${groupEntriesToRestore.length} group scores and ${bracketEntriesToRestore.length} bracket scores...`);

        // 1. Restore group scores first (bracket seeding depends on group results)
        for (const [matchId, scores] of groupEntriesToRestore) {
            await apiPost(`/api/groups/${matchId}/score`, scores);
        }
        await loadGroupStage();

        // 2. Seed and restore bracket scores
        if (bracketEntriesToRestore.length > 0) {
            await apiPost('/api/bracket/seed');

            for (const [matchId, scores] of bracketEntriesToRestore) {
                await apiPost(`/api/bracket/${matchId}/score`, scores);
            }
            await loadBracket();
        }

        console.log('Saved state restored successfully.');
    } catch (error) {
        console.error('Failed to restore saved state:', error);
    }
}

// ===== TAB SWITCHING =====
async function showTab(tab) {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    event.target.classList.add('active');
    document.getElementById('tab-' + tab).classList.add('active');

    // When switching to bracket tab: seed if not yet seeded, otherwise reload
    if (tab === 'bracket') {
        await loadBracket();
        // Auto-seed if R32 matches have no teams assigned yet
        const needsSeeding = bracketData && bracketData.matches.some(
            m => m.round === 'R32' && m.team1 === null && m.team2 === null
        );
        if (needsSeeding) {
            await seedBracket();
        }
    }
}

// ===== ODDS TOOLTIP HELPER =====
function buildMatchTooltip(m) {
    if (m.isLocked) {
        return '⚽ Game played — result is final.';
    }
    const parts = [];
    if (m.matchDate) {
        try {
            const d = new Date(m.matchDate);
            const dateStr = d.toLocaleDateString('en-GB', {
                weekday: 'short', day: 'numeric', month: 'short', year: 'numeric'
            });
            const timeStr = d.toLocaleTimeString('en-GB', {
                hour: '2-digit', minute: '2-digit', timeZoneName: 'short'
            });
            parts.push('📅 ' + dateStr + ', ' + timeStr);
        } catch (e) {
            parts.push('📅 ' + m.matchDate);
        }
    }
    if (m.odds1 != null && m.oddsDraw != null && m.odds2 != null) {
        const pHome = Math.round((1 / m.odds1) * 100);
        const pDraw = Math.round((1 / m.oddsDraw) * 100);
        const pAway = Math.round((1 / m.odds2) * 100);
        parts.push('🏟️ ' + m.team1 + ' win: ' + pHome + '%');
        parts.push('🤝 Draw: ' + pDraw + '%');
        parts.push('🏟️ ' + m.team2 + ' win: ' + pAway + '%');
    }
    return parts.length > 0 ? parts.join('\n') : null;
}

// ===== FLAG HELPER =====
function flagImg(code) {
    const t = TEAMS[code];
    if (!t) return '<span>⚽</span>';
    return `<img src="https://flagcdn.com/w20/${t.flag}.png"
                 srcset="https://flagcdn.com/w40/${t.flag}.png 2x"
                 alt="${t.name}" title="${t.name}" width="18" height="12"
                 style="display:block;border-radius:1px;">`;
}

function flagImgLarge(code) {
    const t = TEAMS[code];
    if (!t) return '<span>⚽</span>';
    return `<img src="https://flagcdn.com/w20/${t.flag}.png"
                 srcset="https://flagcdn.com/w40/${t.flag}.png 2x"
                 alt="${t.name}" title="${t.name}" width="22" height="15"
                 style="display:block;border-radius:2px;">`;
}

// ===== GROUP STAGE RENDERING =====

/**
 * Processes raw group matches from the backend by:
 * 1. Injecting reliable static dates from STATIC_MATCH_SCHEDULE
 * 2. Merging locked match scores from admin-locked results
 * 3. Sorting chronologically by matchDate
 */
function processGroupMatches(rawMatches) {
    const processed = rawMatches.map(match => {
        // 1. Force the static date (fallback to far-future if unknown)
        const reliableDate = STATIC_MATCH_SCHEDULE[match.id] || "2026-12-31T00:00:00Z";

        // 2. Apply locked scores if they exist
        let s1 = match.score1;
        let s2 = match.score2;
        let locked = false;

        if (lockedMatches && lockedMatches[match.id]) {
            [s1, s2] = lockedMatches[match.id];
            locked = true;
        }

        return {
            ...match,
            matchDate: reliableDate,
            score1: s1,
            score2: s2,
            isLocked: locked
        };
    });

    // 3. Sort chronologically
    processed.sort((a, b) => new Date(a.matchDate) - new Date(b.matchDate));

    return processed;
}

async function loadGroupStage() {
    const data = await apiGet('/api/standings');
    const matchesData = await apiGet('/api/group-matches');

    // Process matches: inject static dates, merge locked scores, sort chronologically
    const allProcessedMatches = processGroupMatches(matchesData.matches);

    const container = document.getElementById('groups-container');
    let html = '';
    let tabIndex = 1;

    for (const group of ['A','B','C','D','E','F','G','H','I','J','K','L']) {
        const standings = data.standings[group];
        const groupMatches = allProcessedMatches.filter(m => m.group === group);

        html += `<div class="group-card">`;
        html += `<div class="group-card-header">Group ${group}</div>`;

        // Standings table
        html += `<table class="standings-table">
            <thead><tr>
                <th>Team</th><th>P</th><th>GF</th><th>GA</th><th>GD</th><th>Pts</th>
            </tr></thead><tbody>`;

        for (const s of standings) {
            const gd = s.goalDifference >= 0 ? `+${s.goalDifference}` : s.goalDifference;
            html += `<tr>
                <td style="display:flex;align-items:center;gap:4px;">
                    ${flagImg(s.teamCode)}
                    <span class="team-code">${s.teamCode}</span>
                </td>
                <td>${s.played}</td>
                <td>${s.goalsFor}</td>
                <td>${s.goalsAgainst}</td>
                <td>${gd}</td>
                <td style="font-weight:700;color:var(--text-primary)">${s.points}</td>
            </tr>`;
        }
        html += `</tbody></table>`;

        // Matches
        html += `<div class="group-matches">`;
        for (const m of groupMatches) {
            const isLocked = lockedMatches.hasOwnProperty(m.id);
            const disabledAttr = isLocked ? ' disabled' : '';
            const lockedClass = isLocked ? ' match-locked' : '';
            const lockBtn = isAdmin
                ? `<button class="lock-btn ${isLocked ? 'locked' : 'unlocked'}"
                       onclick="toggleLock('${m.id}', this)"
                       title="${isLocked ? 'Unlock match result' : 'Lock match result'}">
                       ${isLocked ? '🔒' : '🔓'}
                   </button>`
                : '';
            const tooltipText = buildMatchTooltip(m);
            const infoIcon = tooltipText
? `<span class="match-info-icon" title="${tooltipText.replace(/"/g, '"').replace(/\n/g, '&#10;')}">i</span>`
                : '';
            html += `<div class="group-match-row${lockedClass}">
                <div class="group-match-teams">
                    ${flagImg(m.team1)}
                    <span class="team-code">${m.team1}</span>
                </div>
                <div class="group-match-score">
                    <input type="number" class="score-input" min="0" max="99"
                           value="${m.score1 !== null ? m.score1 : ''}"
                           placeholder="-"
                           tabindex="${tabIndex++}"
                           onchange="setGroupScore('${m.id}', 1, this.value)"
                           onfocus="this.select()"${disabledAttr}>
                    <span class="match-separator">-</span>
                    <input type="number" class="score-input" min="0" max="99"
                           value="${m.score2 !== null ? m.score2 : ''}"
                           placeholder="-"
                           tabindex="${tabIndex++}"
                           onchange="setGroupScore('${m.id}', 2, this.value)"
                           onfocus="this.select()"${disabledAttr}>
                </div>
                <div class="group-match-teams" style="justify-content:flex-end;">
                    <span class="team-code">${m.team2}</span>
                    ${flagImg(m.team2)}
                </div>
                ${infoIcon}${lockBtn}
            </div>`;
        }
        html += `</div></div>`;
    }

    container.innerHTML = html;
}

async function setGroupScore(matchId, slot, value) {
    // Find the current scores for this match from the inputs
    const row = event.target.closest('.group-match-row');
    const inputs = row.querySelectorAll('.score-input');
    const s1 = inputs[0].value !== '' ? parseInt(inputs[0].value) : null;
    const s2 = inputs[1].value !== '' ? parseInt(inputs[1].value) : null;

    if (s1 === null || s2 === null) return; // Need both scores

    const currentTabIndex = parseInt(event.target.getAttribute('tabindex'));

    await apiPost(`/api/groups/${matchId}/score`, { score1: s1, score2: s2 });
    await loadGroupStage();
    debouncedSave();

    // Restore focus to the next input after the one that triggered the save
    const nextInput = document.querySelector('.score-input[tabindex="' + (currentTabIndex + 1) + '"]');
    if (nextInput) nextInput.focus();
}

// ===== BRACKET RENDERING =====
async function loadBracket() {
    const data = await apiGet('/api/bracket');
    bracketData = data;
    renderBracket();
}

function renderBracket() {
    if (!bracketData) return;

    const matches = {};
    bracketData.matches.forEach(m => { matches[m.id] = m; });

    const bracket = document.getElementById('bracket');

    // Separate by round and side
    const leftR32 = [], leftR16 = [], leftQF = [], leftSF = [];
    const rightR32 = [], rightR16 = [], rightQF = [], rightSF = [];
    let finalMatch = null;

    for (const m of bracketData.matches) {
        if (m.round === 'R32' && m.side === 'left') leftR32.push(m);
        if (m.round === 'R16' && m.side === 'left') leftR16.push(m);
        if (m.round === 'QF' && m.side === 'left') leftQF.push(m);
        if (m.round === 'SF' && m.side === 'left') leftSF.push(m);
        if (m.round === 'R32' && m.side === 'right') rightR32.push(m);
        if (m.round === 'R16' && m.side === 'right') rightR16.push(m);
        if (m.round === 'QF' && m.side === 'right') rightQF.push(m);
        if (m.round === 'SF' && m.side === 'right') rightSF.push(m);
        if (m.round === 'Final') finalMatch = m;
    }

    const sortFn = (a, b) => a.matchIndex - b.matchIndex;
    leftR32.sort(sortFn); leftR16.sort(sortFn); leftQF.sort(sortFn); leftSF.sort(sortFn);
    rightR32.sort(sortFn); rightR16.sort(sortFn); rightQF.sort(sortFn); rightSF.sort(sortFn);

    // Pre-compute tabindex: column-by-column (R32 left→right, R16 left→right, … Final)
    const tabIndexMap = {};
    let t = 1;
    const assignTabIndexes = (matchList) => {
        for (const m of matchList) {
            if (m.team1) tabIndexMap[`${m.id}-1`] = t++;
            if (m.team2) tabIndexMap[`${m.id}-2`] = t++;
        }
    };
    assignTabIndexes(leftR32);
    assignTabIndexes(rightR32);
    assignTabIndexes(leftR16);
    assignTabIndexes(rightR16);
    assignTabIndexes(leftQF);
    assignTabIndexes(rightQF);
    assignTabIndexes(leftSF);
    assignTabIndexes(rightSF);
    if (finalMatch) {
        if (finalMatch.team1) tabIndexMap[`${finalMatch.id}-1`] = t++;
        if (finalMatch.team2) tabIndexMap[`${finalMatch.id}-2`] = t++;
    }

    const finalWinner = getWinner(finalMatch);

    let html = '';

    // LEFT SIDE
    html += renderRound(leftR32, 'Round of 32', 'left', tabIndexMap);
    html += renderRound(leftR16, 'Round of 16', 'left', tabIndexMap);
    html += renderRound(leftQF, 'Quarter-Finals', 'left', tabIndexMap);
    html += renderRound(leftSF, 'Semi-Finals', 'left', tabIndexMap);

    // LEFT CONNECTOR → FINAL
    html += `<div class="final-connector final-connector-left"></div>`;

    // FINAL + CHAMPION
    html += `<div class="champion-section">
        <div class="champion-card">
            <div class="champion-trophy">🏆</div>
            <div class="champion-label">Champion</div>
            <div class="champion-name" title="${finalWinner ? TEAMS[finalWinner]?.name || '' : ''}">
                ${finalWinner ? flagImgLarge(finalWinner) + ' ' + finalWinner : '???'}
            </div>
        </div>
        ${renderMatch(finalMatch, tabIndexMap)}
    </div>`;

    // RIGHT CONNECTOR → FINAL
    html += `<div class="final-connector final-connector-right"></div>`;

    // RIGHT SIDE
    html += renderRound(rightSF, 'Semi-Finals', 'right', tabIndexMap);
    html += renderRound(rightQF, 'Quarter-Finals', 'right', tabIndexMap);
    html += renderRound(rightR16, 'Round of 16', 'right', tabIndexMap);
    html += renderRound(rightR32, 'Round of 32', 'right', tabIndexMap);

    bracket.innerHTML = html;
}

function getWinner(match) {
    if (!match || match.score1 === null || match.score2 === null) return null;
    if (match.score1 > match.score2) return match.team1;
    if (match.score2 > match.score1) return match.team2;
    return null;
}

function renderTeamRow(teamCode, score, matchId, slot, isWinner, isLoser, tabIndexMap) {
    if (!teamCode) {
        return `<div class="team-row empty">
            <span class="team-flag">⚽</span>
            <span class="team-name">TBD</span>
            <span class="team-score">-</span>
        </div>`;
    }

    let classes = 'team-row';
    if (isWinner) classes += ' winner';
    if (isLoser) classes += ' loser';

    const scoreVal = score !== null && score !== undefined ? score : '';
    const tabIdx = tabIndexMap && tabIndexMap[`${matchId}-${slot}`];
    const tabAttr = tabIdx ? `tabindex="${tabIdx}"` : '';

    return `<div class="${classes}">
        <span class="team-flag" title="${TEAMS[teamCode]?.name || teamCode}">${flagImgLarge(teamCode)}</span>
        <span class="team-name">${teamCode}</span>
        <input type="number" class="score-input" min="0" max="99"
               value="${scoreVal}" placeholder="-"
               ${tabAttr}
               onchange="setKnockoutScore(${matchId}, ${slot}, this)"
               onkeydown="handleKnockoutTab(event, ${matchId}, ${slot})"
               onfocus="this.select()">
    </div>`;
}

function renderMatch(match, tabIndexMap) {
    if (!match) return '';
    const winner = getWinner(match);
    const isTeam1Winner = winner && winner === match.team1;
    const isTeam2Winner = winner && winner === match.team2;

    let cls = 'match';
    if (match.round === 'Final') cls += ' match-final';

    let html = '';
    if (match.round === 'Final') {
        html += `<div class="match-final-label">🏆 Final</div>`;
    }

    html += renderTeamRow(match.team1, match.score1, match.id, 1,
        isTeam1Winner, isTeam2Winner && !isTeam1Winner, tabIndexMap);
    html += renderTeamRow(match.team2, match.score2, match.id, 2,
        isTeam2Winner, isTeam1Winner && !isTeam2Winner, tabIndexMap);

    return `<div class="match-wrap"><div class="${cls}" data-match="${match.id}">${html}</div></div>`;
}

function renderRound(matchList, label, side, tabIndexMap) {
    const matchHtmls = matchList.map(m => renderMatch(m, tabIndexMap)).join('');
    return `<div class="round round-${side}">
        <div class="round-label">${label}</div>
        <div class="match-list">${matchHtmls}</div>
    </div>`;
}

// ===== KNOCKOUT SCORE EDITING =====

// Get all bracket score inputs sorted by tabindex
function getBracketInputsInOrder() {
    const bracketEl = document.getElementById('tab-bracket');
    return Array.from(bracketEl.querySelectorAll('.score-input[tabindex]'))
        .filter(el => el.closest('.match'))
        .sort((a, b) => parseInt(a.getAttribute('tabindex')) - parseInt(b.getAttribute('tabindex')));
}

// Focus the bracket input that follows the one with the given tabindex
function focusNextBracketInput(afterTabindex) {
    const ordered = getBracketInputsInOrder();
    const currentIdx = ordered.findIndex(el => parseInt(el.getAttribute('tabindex')) === afterTabindex);
    if (currentIdx >= 0 && currentIdx + 1 < ordered.length) {
        ordered[currentIdx + 1].focus();
    }
}

function handleKnockoutTab(e, matchId, slot) {
    if (e.key !== 'Tab' || e.shiftKey) return;

    const matchEl = e.target.closest('.match');
    const inputs = matchEl.querySelectorAll('.score-input');
    const s1 = inputs[0].value !== '' ? parseInt(inputs[0].value) : null;
    const s2 = inputs[1].value !== '' ? parseInt(inputs[1].value) : null;

    // Only intercept Tab if both scores are filled (would trigger a save)
    if (s1 === null || s2 === null) return;

    e.preventDefault(); // Prevent browser's default tab - we'll handle it after re-render
    const currentTabIndex = parseInt(e.target.getAttribute('tabindex'));

    (async () => {
        await apiPost(`/api/bracket/${matchId}/score`, { score1: s1, score2: s2 });
        await loadBracket();
        debouncedSave();

        // Focus the next input in tab order after re-render
        focusNextBracketInput(currentTabIndex);
    })();
}

async function setKnockoutScore(matchId, slot, inputEl) {
    const matchEl = inputEl.closest('.match');
    if (!matchEl) return;
    const inputs = matchEl.querySelectorAll('.score-input');
    const s1 = inputs[0].value !== '' ? parseInt(inputs[0].value) : null;
    const s2 = inputs[1].value !== '' ? parseInt(inputs[1].value) : null;

    if (s1 === null || s2 === null) return; // Need both scores

    const currentTabIndex = parseInt(inputEl.getAttribute('tabindex'));

    await apiPost(`/api/bracket/${matchId}/score`, { score1: s1, score2: s2 });
    await loadBracket();
    debouncedSave();

    // Restore focus to the next input after the one that triggered the save
    focusNextBracketInput(currentTabIndex);
}

// ===== SEED & RESET =====
async function seedBracket() {
    await apiPost('/api/bracket/seed');
    await loadBracket();
}

async function resetAll() {
    await apiPost('/api/reset');
    await loadGroupStage();
    await loadBracket();
}

// ===== BETFAIR SIMULATION =====
async function simulateBetfairGroups() {
    const btn = document.getElementById('btn-simulate-betfair');
    const snapshotSpan = document.getElementById('odds-snapshot-time');
    const snapshotText = snapshotSpan ? snapshotSpan.textContent : '';
    btn.disabled = true;
    btn.innerHTML = '⏳ Simulating via Betfair Odds...';

    try {
        const result = await apiPost('/api/betfair/simulate-groups');
        if (result.success) {
            btn.innerHTML = '✅ ' + result.message;
            btn.classList.add('btn-success');
            await loadGroupStage();
            await loadBracket();
        } else {
            btn.innerHTML = '❌ Simulation failed';
        }
    } catch (err) {
        btn.innerHTML = '❌ Error: ' + err.message;
    }

    setTimeout(() => {
        btn.disabled = false;
        btn.classList.remove('btn-success');
        // Restore button text with the snapshot timestamp preserved
        btn.innerHTML = '🎲 Simulate Group Stage via Betfair Odds\n<span id="odds-snapshot-time" class="odds-snapshot-time">' + snapshotText + '</span>';
    }, 3000);
}

// ===== NOTIFICATIONS =====

const NOTIFICATION_ICONS = {
    INFO: 'ℹ️',
    WARNING: '⚠️',
    ERROR: '❌'
};

const NOTIFICATION_AUTO_DISMISS_MS = 8000;
const NOTIFICATION_MAX_VISIBLE = 5;

/**
 * Shows a notification toast in the top-right corner.
 * @param {string} type - INFO, WARNING, or ERROR
 * @param {string} category - e.g. "Betfair", "System"
 * @param {string} message - user-friendly message
 */
function showNotification(type, category, message) {
    const container = document.getElementById('notifications-container');
    if (!container) return;

    // Limit visible notifications
    const existing = container.querySelectorAll('.notification:not(.hiding)');
    if (existing.length >= NOTIFICATION_MAX_VISIBLE) {
        dismissNotification(existing[0]);
    }

    const icon = NOTIFICATION_ICONS[type] || 'ℹ️';
    const typeLower = type.toLowerCase();

    const el = document.createElement('div');
    el.className = `notification notification-${typeLower}`;
    el.innerHTML = `
        <span class="notification-icon">${icon}</span>
        <div class="notification-body">
            <div class="notification-category">${category}</div>
            <div class="notification-message">${message}</div>
        </div>
        <button class="notification-close" onclick="dismissNotification(this.parentElement)" title="Dismiss">&times;</button>
    `;

    container.appendChild(el);

    // Auto-dismiss after timeout
    el._autoDismissTimer = setTimeout(() => dismissNotification(el), NOTIFICATION_AUTO_DISMISS_MS);
}

function dismissNotification(el) {
    if (!el || el.classList.contains('hiding')) return;
    clearTimeout(el._autoDismissTimer);
    el.classList.add('hiding');
    setTimeout(() => el.remove(), 300);
}

/**
 * Fetches new events from the backend and displays them as notifications.
 * Keeps track of the last-seen timestamp to avoid showing duplicates.
 */
async function pollAppEvents() {
    try {
        const data = await apiGet('/api/events');
        if (!data || !data.events) return;

        const events = data.events;
        if (events.length === 0) return;

        const lastTimestamp = events[events.length - 1].timestamp;

        // First load: just set the marker, don't show old events
        if (lastSeenEventTimestamp === null) {
            lastSeenEventTimestamp = lastTimestamp;
            return;
        }

        // Filter to only new events
        const newEvents = events.filter(e => e.timestamp > lastSeenEventTimestamp);

        if (newEvents.length > 0) {
            lastSeenEventTimestamp = lastTimestamp;
            // Show new events (stagger slightly so they don't all appear at once)
            newEvents.forEach((event, i) => {
                setTimeout(() => {
                    showNotification(event.type, event.category, event.message);
                }, i * 200);
            });
        }
    } catch (err) {
        console.error('Failed to poll app events:', err);
    }
}

// ===== ADMIN LOCK/UNLOCK =====

async function toggleLock(matchId, btnEl) {
    const row = btnEl.closest('.group-match-row');
    const inputs = row.querySelectorAll('.score-input');
    const isCurrentlyLocked = lockedMatches.hasOwnProperty(matchId);

    if (isCurrentlyLocked) {
        // Unlock: call DELETE endpoint
        const res = await fetch(`/api/admin/lock-score/${matchId}`, { method: 'DELETE' });
        if (res.ok) {
            delete lockedMatches[matchId];
            // Re-enable inputs
            inputs.forEach(inp => inp.disabled = false);
            // Update button
            btnEl.className = 'lock-btn unlocked';
            btnEl.innerHTML = '🔓';
            btnEl.title = 'Lock match result';
            row.classList.remove('match-locked');
        }
    } else {
        // Lock: need both scores filled
        const s1 = inputs[0].value !== '' ? parseInt(inputs[0].value) : null;
        const s2 = inputs[1].value !== '' ? parseInt(inputs[1].value) : null;
        if (s1 === null || s2 === null) {
            showNotification('WARNING', 'Admin', 'Both scores must be filled before locking.');
            return;
        }
        const res = await fetch(`/api/admin/lock-score/${matchId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ score1: s1, score2: s2 })
        });
        if (res.ok) {
            lockedMatches[matchId] = [s1, s2];
            // Disable inputs
            inputs.forEach(inp => inp.disabled = true);
            // Update button
            btnEl.className = 'lock-btn locked';
            btnEl.innerHTML = '🔒';
            btnEl.title = 'Unlock match result';
            row.classList.add('match-locked');
        }
    }
}

// ===== STARTUP =====

// Event delegation: trigger auto-save on any score input change
document.addEventListener('input', (event) => {
    if (event.target.classList.contains('score-input')) {
        debouncedSave();
    }
});

init();

// Poll for app events every 10 seconds
setInterval(pollAppEvents, 10000);
