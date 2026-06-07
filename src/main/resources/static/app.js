// ===== STATE =====
let TEAMS = {};
let GROUPS = {};
let bracketData = null;

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
}

// ===== TAB SWITCHING =====
function showTab(tab) {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    event.target.classList.add('active');
    document.getElementById('tab-' + tab).classList.add('active');
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

    const finalWinner = getWinner(finalMatch);

    let html = '';

    // LEFT SIDE
    html += renderRound(leftR32, 'Round of 32', 'left');
    html += renderRound(leftR16, 'Round of 16', 'left');
    html += renderRound(leftQF, 'Quarter-Finals', 'left');
    html += renderRound(leftSF, 'Semi-Finals', 'left');

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
        ${renderMatch(finalMatch)}
    </div>`;

    // RIGHT CONNECTOR → FINAL
    html += `<div class="final-connector final-connector-right"></div>`;

    // RIGHT SIDE
    html += renderRound(rightSF, 'Semi-Finals', 'right');
    html += renderRound(rightQF, 'Quarter-Finals', 'right');
    html += renderRound(rightR16, 'Round of 16', 'right');
    html += renderRound(rightR32, 'Round of 32', 'right');

    bracket.innerHTML = html;
}

function getWinner(match) {
    if (!match || match.score1 === null || match.score2 === null) return null;
    if (match.score1 > match.score2) return match.team1;
    if (match.score2 > match.score1) return match.team2;
    return null;
}

function renderTeamRow(teamCode, score, matchId, slot, isWinner, isLoser) {
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

    const scoreDisplay = score !== null && score !== undefined ? score : '-';

    return `<div class="${classes}">
        <span class="team-flag" title="${TEAMS[teamCode]?.name || teamCode}">${flagImgLarge(teamCode)}</span>
        <span class="team-name">${teamCode}</span>
        <span class="team-score" onclick="editKnockoutScore(${matchId}, ${slot}, this)"
              data-match="${matchId}" data-slot="${slot}">${scoreDisplay}</span>
    </div>`;
}

function renderMatch(match) {
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
        isTeam1Winner, isTeam2Winner && !isTeam1Winner);
    html += renderTeamRow(match.team2, match.score2, match.id, 2,
        isTeam2Winner, isTeam1Winner && !isTeam2Winner);

    return `<div class="match-wrap"><div class="${cls}" data-match="${match.id}">${html}</div></div>`;
}

function renderRound(matchList, label, side) {
    const matchHtmls = matchList.map(m => renderMatch(m)).join('');
    return `<div class="round round-${side}">
        <div class="round-label">${label}</div>
        <div class="match-list">${matchHtmls}</div>
    </div>`;
}

// ===== KNOCKOUT SCORE EDITING =====
function editKnockoutScore(matchId, slot, el) {
    if (el.querySelector('input')) return;

    const match = bracketData.matches.find(m => m.id === matchId);
    if (!match) return;
    const team = slot === 1 ? match.team1 : match.team2;
    if (!team) return;

    const currentVal = slot === 1 ? match.score1 : match.score2;
    const displayVal = currentVal !== null && currentVal !== undefined ? currentVal : '';

    el.classList.add('editing');
    el.innerHTML = `<input type="number" min="0" max="99" value="${displayVal}" />`;
    const input = el.querySelector('input');
    input.focus();
    input.select();

    async function commit() {
        let val = input.value.trim();
        let numVal = val === '' ? null : parseInt(val, 10);
        if (numVal !== null && isNaN(numVal)) numVal = null;
        if (numVal !== null && numVal < 0) numVal = 0;

        if (numVal !== null) {
            const s1 = slot === 1 ? numVal : match.score1;
            const s2 = slot === 2 ? numVal : match.score2;
            if (s1 !== null && s2 !== null) {
                await apiPost(`/api/bracket/${matchId}/score`, { score1: s1, score2: s2 });
                await loadBracket();
            }
        }

        el.classList.remove('editing');
    }

    input.addEventListener('blur', commit);
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') input.blur();
        if (e.key === 'Escape') {
            el.classList.remove('editing');
            const scoreDisplay = (slot === 1 ? match.score1 : match.score2);
            el.textContent = scoreDisplay !== null && scoreDisplay !== undefined ? scoreDisplay : '-';
        }
    });
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
init();