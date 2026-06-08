// ===== STATE =====
let TEAMS = {};
let GROUPS = {};
let bracketData = null;

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

        const savedState = wrapper.state;
        if (!savedState || !(savedState.groups || savedState.bracket)) return;

        const hasGroups = savedState.groups && Object.keys(savedState.groups).length > 0;
        const hasBracket = savedState.bracket && Object.keys(savedState.bracket).length > 0;

        if (!hasGroups && !hasBracket) return;

        // Check if backend is in a fresh state (no scores at all)
        const matchesData = await apiGet('/api/group-matches');
        const needsRestore = !matchesData.matches.some(m => m.score1 !== null || m.score2 !== null);

        if (!needsRestore) {
            console.log('Backend state intact, skipping restore.');
            return;
        }

        console.log('Backend state empty, restoring from saved state...');

        // 1. Restore group scores first (bracket seeding depends on group results)
        if (hasGroups) {
            for (const [matchId, scores] of Object.entries(savedState.groups)) {
                if (scores.score1 !== null && scores.score2 !== null) {
                    await apiPost(`/api/groups/${matchId}/score`, scores);
                }
            }
        }
        await loadGroupStage();

        // 2. Seed and restore bracket scores
        if (hasBracket) {
            await apiPost('/api/bracket/seed');

            // Sort by matchId numeric to replay in round order (R32 → R16 → QF → SF → Final)
            const bracketEntries = Object.entries(savedState.bracket)
                .filter(([_, scores]) => scores.score1 !== null && scores.score2 !== null)
                .sort(([a], [b]) => parseInt(a) - parseInt(b));

            for (const [matchId, scores] of bracketEntries) {
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
async function loadGroupStage() {
    const data = await apiGet('/api/standings');
    const matchesData = await apiGet('/api/group-matches');

    const container = document.getElementById('groups-container');
    let html = '';
    let tabIndex = 1;

    for (const group of ['A','B','C','D','E','F','G','H','I','J','K','L']) {
        const standings = data.standings[group];
        const groupMatches = matchesData.matches.filter(m => m.group === group);

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
            html += `<div class="group-match-row">
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
                           onfocus="this.select()">
                    <span class="match-separator">-</span>
                    <input type="number" class="score-input" min="0" max="99"
                           value="${m.score2 !== null ? m.score2 : ''}"
                           placeholder="-"
                           tabindex="${tabIndex++}"
                           onchange="setGroupScore('${m.id}', 2, this.value)"
                           onfocus="this.select()">
                </div>
                <div class="group-match-teams" style="justify-content:flex-end;">
                    <span class="team-code">${m.team2}</span>
                    ${flagImg(m.team2)}
                </div>
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
    const originalText = btn.innerHTML;
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
        btn.innerHTML = originalText;
        btn.disabled = false;
        btn.classList.remove('btn-success');
    }, 3000);
}

// ===== STARTUP =====

// Event delegation: trigger auto-save on any score input change
document.addEventListener('input', (event) => {
    if (event.target.classList.contains('score-input')) {
        debouncedSave();
    }
});

init();
