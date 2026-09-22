// Person nodes become Employee nodes. Old FRIEND_OF relationships and the age
// property are left in place (no longer used by the application), so nothing is lost.
MATCH (p:Person)
SET p:Employee,
    p.status = coalesce(p.status, 'ACTIVE'),
    p.searchName = toLower(p.name)
REMOVE p:Person;

// Merge duplicate skills ("Java", "java", " Java ") into one node so a unique
// constraint can be created on Skill.name in the next migration.
MATCH (s:Skill)
WITH toLower(trim(s.name)) AS key, collect(s) AS skills
WHERE size(skills) > 1
WITH head(skills) AS keep, tail(skills) AS duplicates
UNWIND duplicates AS duplicate
CALL (keep, duplicate) {
  MATCH (e)-[r:HAS_SKILL]->(duplicate)
  MERGE (e)-[:HAS_SKILL]->(keep)
  DELETE r
}
DETACH DELETE duplicate;
