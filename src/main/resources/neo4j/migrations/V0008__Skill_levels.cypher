// Skill levels: (:Employee)-[:HAS_SKILL {level}]->(:Skill) and (:Project)-[:REQUIRES {level}]->(:Skill),
// level 1 (beginner) to 5 (expert). Skills recorded before levels existed become 3 (intermediate).
MATCH ()-[r:HAS_SKILL]->() WHERE r.level IS NULL SET r.level = 3;
