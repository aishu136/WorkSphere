// Offices: (:Employee)-[:LOCATED_AT]->(:Office)
CREATE CONSTRAINT office_id IF NOT EXISTS FOR (o:Office) REQUIRE o.id IS UNIQUE;
CREATE CONSTRAINT office_code IF NOT EXISTS FOR (o:Office) REQUIRE o.code IS UNIQUE;
CREATE INDEX office_name IF NOT EXISTS FOR (o:Office) ON (o.name);

// Projects: (:Employee)-[:WORKS_ON {role, allocationPercent, since}]->(:Project),
// (:Project)-[:LED_BY]->(:Employee), (:Project)-[:OWNED_BY]->(:Department)
CREATE CONSTRAINT project_id IF NOT EXISTS FOR (p:Project) REQUIRE p.id IS UNIQUE;
CREATE CONSTRAINT project_code IF NOT EXISTS FOR (p:Project) REQUIRE p.code IS UNIQUE;
CREATE INDEX project_name IF NOT EXISTS FOR (p:Project) ON (p.name);
CREATE INDEX project_status IF NOT EXISTS FOR (p:Project) ON (p.status);
