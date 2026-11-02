-- demo 프로필 시드. 내부 ID 는 api-football 체계(EPL 39, La Liga 140, Bundesliga 78, Ligue 1 61).
-- 팀 이름은 일부러 api-football 표기(Wolves, Brighton, Newcastle...)로 두어 ESPN 이름 매핑을 시험한다.
INSERT INTO country (id, code, name, flag) VALUES
 (1, 'GB', 'England', 'https://media.api-sports.io/flags/gb.svg'),
 (2, 'ES', 'Spain',   'https://media.api-sports.io/flags/es.svg'),
 (3, 'DE', 'Germany', 'https://media.api-sports.io/flags/de.svg'),
 (4, 'FR', 'France',  'https://media.api-sports.io/flags/fr.svg');

INSERT INTO league (id, name, type, logo, country_id) VALUES
 (39,  'Premier League', 'League', 'https://media.api-sports.io/football/leagues/39.png', 1),
 (140, 'La Liga',        'League', 'https://media.api-sports.io/football/leagues/140.png', 2),
 (78,  'Bundesliga',     'League', 'https://media.api-sports.io/football/leagues/78.png', 3),
 (61,  'Ligue 1',        'League', 'https://media.api-sports.io/football/leagues/61.png', 4);

INSERT INTO team (id, name, logo, league_ids) VALUES
 (33, 'Manchester United', 'https://a.espncdn.com/i/teamlogos/soccer/500/360.png', '39'),
 (50, 'Manchester City',   'https://a.espncdn.com/i/teamlogos/soccer/500/382.png', '39'),
 (40, 'Liverpool',         'https://a.espncdn.com/i/teamlogos/soccer/500/364.png', '39'),
 (42, 'Arsenal',           'https://a.espncdn.com/i/teamlogos/soccer/500/359.png', '39'),
 (49, 'Chelsea',           'https://a.espncdn.com/i/teamlogos/soccer/500/363.png', '39'),
 (47, 'Tottenham',         'https://a.espncdn.com/i/teamlogos/soccer/500/367.png', '39'),
 (34, 'Newcastle',         'https://a.espncdn.com/i/teamlogos/soccer/500/361.png', '39'),
 (66, 'Aston Villa',       'https://a.espncdn.com/i/teamlogos/soccer/500/362.png', '39'),
 (39, 'Wolves',            'https://a.espncdn.com/i/teamlogos/soccer/500/380.png', '39'),
 (51, 'Brighton',          'https://a.espncdn.com/i/teamlogos/soccer/500/331.png', '39'),
 (541, 'Real Madrid',      'https://a.espncdn.com/i/teamlogos/soccer/500/86.png',  '140'),
 (529, 'Barcelona',        'https://a.espncdn.com/i/teamlogos/soccer/500/83.png',  '140'),
 (530, 'Atletico Madrid',  'https://a.espncdn.com/i/teamlogos/soccer/500/1068.png','140'),
 (536, 'Sevilla',          'https://a.espncdn.com/i/teamlogos/soccer/500/243.png', '140'),
 (157, 'Bayern Munich',    'https://a.espncdn.com/i/teamlogos/soccer/500/132.png', '78'),
 (165, 'Borussia Dortmund','https://a.espncdn.com/i/teamlogos/soccer/500/124.png', '78'),
 (168, 'Bayer Leverkusen', 'https://a.espncdn.com/i/teamlogos/soccer/500/131.png', '78'),
 (173, 'RB Leipzig',       'https://a.espncdn.com/i/teamlogos/soccer/500/11420.png','78'),
 (85,  'Paris Saint Germain','https://a.espncdn.com/i/teamlogos/soccer/500/160.png','61'),
 (81,  'Marseille',        'https://a.espncdn.com/i/teamlogos/soccer/500/176.png', '61'),
 (91,  'Monaco',           'https://a.espncdn.com/i/teamlogos/soccer/500/174.png', '61'),
 (79,  'Lille',            'https://a.espncdn.com/i/teamlogos/soccer/500/166.png', '61');

-- 시즌 → 국가 → 리그 → 팀 드릴다운용 매핑 (현재 시즌: 7월 이후면 올해, 그 전이면 작년)
INSERT INTO league_team_map (id, league_season, country_id, country_name, country_flag, league_id, league_name, league_logo, team_id, team_name, team_logo)
SELECT 1000 + t.id, CAST(CASE WHEN MONTH(CURRENT_DATE) >= 7 THEN YEAR(CURRENT_DATE) ELSE YEAR(CURRENT_DATE) - 1 END AS VARCHAR), c.id, c.name, c.flag, l.id, l.name, l.logo, t.id, t.name, t.logo
FROM team t JOIN league l ON l.id = CAST(t.league_ids AS INT) JOIN country c ON c.id = l.country_id;

-- 감독/선수 샘플 (맨유만)
INSERT INTO coaches (id, name, age, nationality, photo) VALUES (1, 'Demo Manager', '48', 'Portugal', 'https://a.espncdn.com/i/teamlogos/soccer/500/360.png');
INSERT INTO player (id, name, age, number, position, photo, team_id) VALUES
 (101, 'Demo Keeper',   28, 1,  'Goalkeeper', 'https://a.espncdn.com/i/teamlogos/soccer/500/360.png', 33),
 (102, 'Demo Defender', 25, 4,  'Defender',   'https://a.espncdn.com/i/teamlogos/soccer/500/360.png', 33),
 (103, 'Demo Midfield', 23, 8,  'Midfielder', 'https://a.espncdn.com/i/teamlogos/soccer/500/360.png', 33),
 (104, 'Demo Striker',  21, 9,  'Attacker',   'https://a.espncdn.com/i/teamlogos/soccer/500/360.png', 33);
